/*
 * Copyright 2023 OPPO.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oppo.cloud.meta.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.oppo.cloud.common.constant.Constant;
import com.oppo.cloud.common.domain.cluster.spark.SparkApp;
import com.oppo.cloud.common.domain.cluster.spark.SparkApplication;
import com.oppo.cloud.common.domain.cluster.yarn.Attempt;
import com.oppo.cloud.common.service.RedisService;
import com.oppo.cloud.common.util.DateUtil;
import com.oppo.cloud.common.util.elastic.BulkApi;
import com.oppo.cloud.meta.config.HadoopConfig;
import com.oppo.cloud.meta.service.IClusterConfigService;
import com.oppo.cloud.meta.service.ITaskSyncerMetaService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 同步spark app元数据
 */
@Slf4j
@Service("SparkMetaServiceImpl")
public class SparkMetaServiceImpl implements ITaskSyncerMetaService {

    @Value("${scheduler.sparkMeta.limitCount}")
    private long limitCount;

    @Value("${spring.elasticsearch.spark-app-prefix}")
    private String sparkAppPrefix;
    @Resource
    private HadoopConfig config;

    /**
     * 集群配置信息Service，实现类 {@link ClusterConfigServiceImpl}
     */
    @Resource
    private IClusterConfigService iClusterConfigService;

    @Resource(name = "restTemplate")
    private RestTemplate restTemplate;

    /**
     * redis service类，通过{@link com.oppo.cloud.common.config.RedisTemplateConfig}注入
     */
    @Resource
    private RedisService redisService;

    @Resource
    private Executor sparkMetaExecutor;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * Elasticsearch的client类，通过{@link com.oppo.cloud.meta.config.ElasticsearchConfig}注入
     */
    @Resource
    private RestHighLevelClient client;

    private final Pattern hdfsPattern = Pattern.compile(".*?(?<hdfs>hdfs://.*)</li>.*", Pattern.DOTALL);

    private static final String SPARK_HOME_URL = "http://%s/";

    private static final String SPARK_APPS_URL = "http://%s/api/v1/applications?limit=%d&minDate=%s";

    @Override
    public void syncer() {
        // 获取spark history地址列表
        List<String> clusters = iClusterConfigService.getSparkHistoryServers();
        log.info("sparkClusters:{}", clusters);
        if (clusters == null) {
            return;
        }
        //针对每个spark history地址创建异步回调线程
        CompletableFuture[] array = new CompletableFuture[clusters.size()];
        for (int i = 0; i < clusters.size(); i++) {
            int finalI = i;
            array[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    //拉取spark history服务中的元数据
                    pull(clusters.get(finalI));
                } catch (Exception e) {
                    log.error(e.getMessage());
                }
                return null;
            }, sparkMetaExecutor);
        }
        try {
            CompletableFuture.allOf(array).get();
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    /**
     * 任务数据同步
     */
    public void pull(String shs) {
        log.info("start to pull spark tasks:{}", shs);
        String eventLogDirectory;
        try {
            /**
             * 通过传入spark history的 localhost:port 字符串
             * 返回该spark history存储eventlog的hdfs目录地址
             */
            eventLogDirectory = getEventLogDirectory(shs);
        } catch (Exception e) {
            log.error("sparkMetaErr:eventLogDirectory:{},{}", shs, e.getMessage());
            return;
        }
        if (StringUtils.isBlank(eventLogDirectory)) {
            log.info("sparkMetaErr:eventLogDirectory:{}", shs);
            return;
        }
        /**
         * 通过spark monitor地址api/v1/applications 获取每个spark任务对元数据信息
         * 封装进SparkApplication对象中
         */
        List<SparkApplication> apps = sparkRequest(shs);
        if (apps == null || apps.size() == 0) {
            log.error("sparkMetaErr:appsNull:{}", shs);
            return;
        }
        log.info("sparkApps:{},{}", shs, apps.size());
        Map<String, Map<String, Object>> sparkAppMap = new HashMap<>();

        /**
         * 这里主要是将SparkApplication封装到sparkAppMap中
         * 方便写入Elasticsearch
         */
        for (SparkApplication info : apps) {
            if (info.getAttempts() == null || info.getAttempts().size() == 0) {
                log.error("sparkHistoryInfoAttemptSizeZero {},{}", shs, info);
                continue;
            }
            Attempt attempt = info.getAttempts().get(0);
            try {
                SparkApp sparkApp = new SparkApp(info.getId(), eventLogDirectory, attempt, shs);
                log.info("sparkApp:{}", sparkApp);
                String id = sparkApp.getSparkHistoryServer() + "_" + sparkApp.getAppId();
                sparkAppMap.put(id, sparkApp.getSparkAppMap());
            } catch (Exception e) {
                log.error("saveSparkAppsErr:{},{},{}", shs, e.getMessage(), e);
            }
        }

        /**
         * 将Spark信息写入Elasticsearch中
         * es index: compass-spark-app-拼接yyyy-MM-dd 格式
         * es document id: spark history地址_spark applicationId
         * es doucment source: appId、eventLogDirectory等基础信息
         */
        BulkResponse response;
        try {
            response = BulkApi.bulkByIds(client, sparkAppPrefix + DateUtil.getDay(0), sparkAppMap);
        } catch (IOException e) {
            log.error("bulkSparkAppsErr:{}", e.getMessage());
            return;
        }
        BulkItemResponse[] responses = response.getItems();

        for (BulkItemResponse r : responses) {
            if (r.isFailed()) {
                log.info("failedInsertApp:{},{}", r.getId(), r.status());
            }
        }

        log.info("saveSparkAppCount:{},{}", shs, sparkAppMap.size());
    }

    /**
     * spark 任务获取
     */
    public List<SparkApplication> sparkRequest(String shs) {
        // 获取固定limitCount个数对SparkApplication
        String url = String.format(SPARK_APPS_URL, shs, limitCount, DateUtil.getDay(-1));
        log.info("sparkUrl:{}", url);
        ResponseEntity<String> responseEntity = null;
        try {
            responseEntity = restTemplate.getForEntity(url, String.class);
        } catch (RestClientException e) {
            log.error("sparkRequestErr:{},{}", shs, e.getMessage());
            return null;
        }
        if (responseEntity.getBody() == null) {
            log.error("sparkRequestErr:{}", shs);
            return null;
        }
        List<SparkApplication> value;
        try {
            //　返回的是json字符串，直接解析到对象中
            value = objectMapper.readValue(responseEntity.getBody(),
                    TypeFactory.defaultInstance().constructCollectionType(List.class, SparkApplication.class));
        } catch (JsonProcessingException e) {
            log.error("sparkRequestErr:{},{}", shs, e.getMessage());
            return null;
        }
        return value;
    }

    /**
     * 获取SparkHistoryServer Event log directory: hdfs://ip:port/spark/
     */
    public String getEventLogDirectory(String ip) throws Exception {
        /**
         * 先从redis缓存中获取，获取到直接返回
         * redis key为 spark:event:log:directory:{spark histroy的ip:port}
         */
        String key = Constant.SPARK_EVENT_LOG_DIRECTORY + ip;
        String cacheResult = (String) redisService.get(key);
        if (StringUtils.isNotBlank(cacheResult)) {
            log.info("getEventLogDirectoryFromCache:{},{}", key, cacheResult);
            return cacheResult;
        }
        ResponseEntity<String> responseEntity;
        try {
            // 访问history url地址，响应的是一个html文本
            responseEntity = restTemplate.getForEntity(String.format(SPARK_HOME_URL, ip),
                    String.class);
        } catch (Exception e) {
            log.error("getEventLogDirectoryErr:{},{}", ip, e.getMessage());
            return "";
        }
        if (responseEntity.getBody() != null) {
            //对返回对html进行正则表达式匹配
            Matcher m = hdfsPattern.matcher(responseEntity.getBody());
            if (m.matches()) {
                // 获取spark event log存放对hdfs目录
                String path = m.group("hdfs");
                if (StringUtils.isNotBlank(path)) {
                    log.info("cacheEventLogDirectory:{},{}", key, path);
                    //　event log目录放入redis缓存
                    redisService.set(key, path, Constant.DAY_SECONDS);
                }
                return path;
            }
        }
        return "";
    }

}
