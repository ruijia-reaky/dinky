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

package org.dinky.service;

import org.dinky.configure.JobLogProperties;
import org.dinky.data.enums.GatewayType;
import org.dinky.data.model.ext.JobInfoDetail;
import org.dinky.data.model.job.JobLogPod;
import org.dinky.gateway.config.GatewayConfig;
import org.dinky.gateway.config.K8sConfig;
import org.dinky.mapper.JobLogPodMapper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/** Live application evidence is separate from the immutable execution Job ID. */
@Service
@RequiredArgsConstructor
public class JobLogExecutionService {
    private final JobLogProperties properties;
    private final JobLogPodMapper mapper;
    private final JobLogService logs;

    @Data
    public static class Observation {
        private String savedJobId;
        private String savedStatus;
        private String applicationId;
        private String status = "UNAVAILABLE";
        private String checkedAt = Instant.now().toString();
        private List<LiveJob> jobs = new ArrayList<>();
    }

    @Data
    public static class LiveJob {
        private String jid;
        private String name;
        private String state;
        private long startedAt;
    }

    public Observation observe(JobInfoDetail info) {
        Observation result = new Observation();
        result.setSavedJobId(info.getInstance().getJid());
        result.setSavedStatus(info.getInstance().getStatus());
        if (!properties.isEnabled()
                || info.getClusterInstance() == null
                || !GatewayType.get(info.getClusterInstance().getType()).isKubernetesApplicationMode()) {
            result.setStatus("UNSUPPORTED");
            return result;
        }
        try {
            GatewayConfig gateway =
                    info.getHistory() == null || info.getHistory().getConfigJson() == null
                            ? null
                            : info.getHistory().getConfigJson().getGatewayConfig();
            if (gateway == null && info.getClusterConfiguration() != null) {
                gateway = GatewayConfig.build(info.getClusterConfiguration().getConfig());
            }
            if (gateway == null || gateway.getKubernetesConfig() == null) return result;
            K8sConfig k8s = gateway.getKubernetesConfig();
            Map<String, String> options = new HashMap<>(k8s.getConfiguration());
            if (gateway.getFlinkConfig() != null)
                options.putAll(gateway.getFlinkConfig().getConfiguration());
            String namespace = options.getOrDefault("kubernetes.namespace", "default");
            String application = options.getOrDefault(
                    "kubernetes.cluster-id", info.getClusterInstance().getName());
            result.setApplicationId(application);
            Config config =
                    k8s.getKubeConfig() == null || k8s.getKubeConfig().trim().isEmpty()
                            ? Config.autoConfigure(null)
                            : Config.fromKubeconfig(k8s.getKubeConfig());
            config.setConnectionTimeout(properties.getConnectTimeoutMs());
            config.setRequestTimeout(properties.getReadTimeoutMs());
            try (KubernetesClient client = new DefaultKubernetesClient(config)) {
                try {
                    logs.repairNodeAddresses(info.getId(), client);
                } catch (RuntimeException ignored) {
                    // Missing Node read permission must not hide the live execution observation.
                }
                List<JobLogPod> known = mapper.selectList(new LambdaQueryWrapper<JobLogPod>()
                        .eq(JobLogPod::getTenantId, info.getInstance().getTenantId())
                        .eq(JobLogPod::getJobInstanceId, info.getId()));
                List<Pod> current = client.pods()
                        .inNamespace(namespace)
                        .withLabel("app", application)
                        .withLabel("component", "jobmanager")
                        .list()
                        .getItems();
                if (!sameApplication(known, current)) {
                    result.setStatus("APPLICATION_UNVERIFIED");
                    return result;
                }
                String endpoint = KubernetesJobStatusService.restEndpoint(
                        client.services()
                                .inNamespace(namespace)
                                .withName(application + "-rest")
                                .get(),
                        info.getClusterInstance().getJobManagerHost());
                if (endpoint == null) return result;
                HttpURLConnection connection = (HttpURLConnection) URI.create("http://" + endpoint + "/jobs/overview")
                        .toURL()
                        .openConnection();
                connection.setConnectTimeout(properties.getConnectTimeoutMs());
                connection.setReadTimeout(properties.getReadTimeoutMs());
                connection.setInstanceFollowRedirects(false);
                try {
                    if (connection.getResponseCode() != 200) return result;
                    try (InputStream input = connection.getInputStream()) {
                        JsonNode response = org.dinky.utils.JsonUtils.objectMapper.readTree(input);
                        if (!response.path("jobs").isArray()) return result;
                        for (JsonNode job : response.path("jobs")) {
                            LiveJob live = new LiveJob();
                            live.setJid(job.path("jid").asText());
                            live.setName(job.path("name").asText());
                            live.setState(job.path("state").asText());
                            live.setStartedAt(job.path("start-time").asLong());
                            result.getJobs().add(live);
                        }
                        result.setStatus(classify(result.getSavedJobId(), result.getJobs()));
                    }
                } finally {
                    connection.disconnect();
                }
            }
        } catch (Exception ignored) {
            // Connectivity failure is not evidence of a terminal job state.
        }
        return result;
    }

    static boolean sameApplication(List<JobLogPod> known, List<Pod> current) {
        return current.stream()
                .anyMatch(pod -> pod.getMetadata() != null
                        && known.stream()
                                .anyMatch(row -> "JM".equals(row.getRole())
                                        && row.getPodUid() != null
                                        && row.getPodUid()
                                                .equals(pod.getMetadata().getUid())));
    }

    static String classify(String savedJobId, List<LiveJob> jobs) {
        if (jobs.isEmpty()) return "NO_JOBS";
        if (jobs.stream().anyMatch(job -> job.getJid().equals(savedJobId))) return "MATCHED";
        return jobs.size() == 1 ? "JOB_ID_CHANGED" : "MULTIPLE_JOBS";
    }
}
