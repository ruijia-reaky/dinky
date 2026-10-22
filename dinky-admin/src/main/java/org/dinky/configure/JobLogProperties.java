/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.dinky.configure;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/** Historical log access is opt-in and independent of the live Flink REST endpoint. */
@Data
@Component
@ConfigurationProperties(prefix = "dinky.job-logs")
public class JobLogProperties {
    private boolean enabled;
    private String rootPath = "/data/flink-logs";
    private String directoryTemplate = "{app}/{uid}_{pod}";
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 5000;
    private Map<String, Agent> agents = new HashMap<>();

    @Data
    public static class Agent {
        private String url;
        private String token;
    }
}
