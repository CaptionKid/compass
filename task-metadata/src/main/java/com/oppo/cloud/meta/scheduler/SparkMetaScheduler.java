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

package com.oppo.cloud.meta.scheduler;

import com.oppo.cloud.meta.service.ITaskSyncerMetaService;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * SPARK任务app列表数据同步
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "scheduler.sparkMeta", name = "enable", havingValue = "true")
public class SparkMetaScheduler {

    /**
     * spark分布式锁，在 {@link com.oppo.cloud.meta.config.ZookeeperLockConfig} 中注入生成
     */
    @Resource(name = "sparkMetaLock")
    private InterProcessMutex lock;
    /**
     * Spark history任务元数据获取service，在 {@link com.oppo.cloud.meta.service.impl.SparkMetaServiceImpl} 中注入生成
     */
    @Resource(name = "SparkMetaServiceImpl")
    private ITaskSyncerMetaService spark;

    // @Scheduled Springboot定时任务注解，配置信息在application-hadoop.yml文件中
    @Scheduled(cron = "${scheduler.sparkMeta.cron}")
    private void run() {
        try {
            lock();
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }
    /**
     * zk锁，防止多实例同时同步数据
     */
    private void lock() throws Exception {
        // 获取锁失败就返回
        if (!lock.acquire(1, TimeUnit.SECONDS)) {
            log.warn("cannot get {}", lock.getParticipantNodes());
            return;
        }
        try {
            log.info("get {}", lock.getParticipantNodes());
            spark.syncer();
        } finally {
            log.info("release {}", lock.getParticipantNodes());
            lock.release();
        }
    }

}
