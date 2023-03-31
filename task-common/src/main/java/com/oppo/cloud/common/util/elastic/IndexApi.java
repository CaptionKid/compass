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

package com.oppo.cloud.common.util.elastic;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 创建索引API
 */
public class IndexApi {

    public IndexResponse indexSync(RestHighLevelClient client, final String index,
                                   final Map<String, Object> document) throws IOException {
        final IndexRequest request = new IndexRequest(index).source(document);
        return client.index(request, RequestOptions.DEFAULT);
    }

    public void indexAysnc(RestHighLevelClient client, final String index,
                           final Map<String, Object> document) throws InterruptedException {
        final IndexRequest request = new IndexRequest(index).source(document);
        client.indexAsync(request, RequestOptions.DEFAULT, new ActionListener<IndexResponse>() {

            @Override
            public void onResponse(IndexResponse indexResponse) {
                // do something
            }
            @Override
            public void onFailure(Exception e) {
                throw new RuntimeException(e);
            }
        });
        TimeUnit.SECONDS.sleep(1);
    }
}
