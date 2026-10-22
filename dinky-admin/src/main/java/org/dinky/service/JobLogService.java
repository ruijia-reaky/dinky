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
import org.dinky.context.TenantContextHolder;
import org.dinky.data.enums.GatewayType;
import org.dinky.data.model.ext.JobInfoDetail;
import org.dinky.data.model.job.JobInstance;
import org.dinky.data.model.job.JobLogPod;
import org.dinky.gateway.config.GatewayConfig;
import org.dinky.gateway.config.K8sConfig;
import org.dinky.mapper.JobInstanceMapper;
import org.dinky.mapper.JobLogPodMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.net.InetAddresses;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class JobLogService {
    private final JobLogProperties properties;
    private final JobLogPodMapper mapper;
    private final JobInstanceMapper instanceMapper;
    private final Executor executor;
    private final Set<Integer> pending = ConcurrentHashMap.newKeySet();

    public JobLogService(
            JobLogProperties properties,
            JobLogPodMapper mapper,
            JobInstanceMapper instanceMapper,
            @Qualifier("jobLogExecutor") Executor executor) {
        this.properties = properties;
        this.mapper = mapper;
        this.instanceMapper = instanceMapper;
        this.executor = executor;
    }

    /** Called before terminal-state handling; failure must not change job monitoring. */
    public void collect(JobInfoDetail info) {
        if (!properties.isEnabled() || !pending.add(info.getId())) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    collectNow(info);
                } finally {
                    pending.remove(info.getId());
                }
            });
        } catch (RejectedExecutionException e) {
            pending.remove(info.getId());
            log.debug("Log collector is busy; defer instance {} to its next refresh", info.getId());
        }
    }

    private void collectNow(JobInfoDetail info) {
        try {
            TenantContextHolder.set(info.getInstance().getTenantId());
            if (info.getClusterInstance() == null
                    || !GatewayType.get(info.getClusterInstance().getType()).isKubernetesApplicationMode()) {
                return;
            }
            GatewayConfig gateway =
                    info.getHistory() == null || info.getHistory().getConfigJson() == null
                            ? null
                            : info.getHistory().getConfigJson().getGatewayConfig();
            if (gateway == null && info.getClusterConfiguration() != null) {
                gateway = GatewayConfig.build(info.getClusterConfiguration().getConfig());
            }
            if (gateway == null || gateway.getKubernetesConfig() == null) {
                return;
            }
            K8sConfig k8s = gateway.getKubernetesConfig();
            Map<String, String> options = new HashMap<>(k8s.getConfiguration());
            if (gateway.getFlinkConfig() != null) {
                options.putAll(gateway.getFlinkConfig().getConfiguration());
            }
            String namespace = options.getOrDefault("kubernetes.namespace", "default");
            String application = options.getOrDefault(
                    "kubernetes.cluster-id", info.getClusterInstance().getName());
            Config config =
                    k8s.getKubeConfig() == null || k8s.getKubeConfig().trim().isEmpty()
                            ? Config.autoConfigure(null)
                            : Config.fromKubeconfig(k8s.getKubeConfig());
            config.setConnectionTimeout(properties.getConnectTimeoutMs());
            config.setRequestTimeout(properties.getReadTimeoutMs());
            Set<String> observed = new HashSet<>();
            try (KubernetesClient client = new DefaultKubernetesClient(config)) {
                for (Pod pod : client.pods()
                        .inNamespace(namespace)
                        .withLabel("app", application)
                        .list()
                        .getItems()) {
                    // Reusing an application name must not attach Pods from an earlier submission.
                    if (info.getHistory() != null
                            && info.getHistory().getStartTime() != null
                            && pod.getMetadata().getCreationTimestamp() != null) {
                        Instant submitted = info.getHistory()
                                .getStartTime()
                                .atZone(ZoneId.systemDefault())
                                .toInstant();
                        if (Instant.parse(pod.getMetadata().getCreationTimestamp())
                                .isBefore(submitted.minusSeconds(5))) {
                            continue;
                        }
                    }
                    LocalDateTime finished = info.getInstance().getFinishTime();
                    if (finished != null
                            && finished.getYear() > 1970
                            && pod.getMetadata().getCreationTimestamp() != null
                            && Instant.parse(pod.getMetadata().getCreationTimestamp())
                                    .isAfter(finished.atZone(ZoneId.systemDefault())
                                            .toInstant())) {
                        continue;
                    }
                    observed.add(pod.getMetadata().getUid());
                    recordPod(info.getInstance(), application, namespace, pod);
                }
            }
            for (JobLogPod old : mapper.selectList(new LambdaQueryWrapper<JobLogPod>()
                    .eq(JobLogPod::getTenantId, info.getInstance().getTenantId())
                    .eq(JobLogPod::getJobInstanceId, info.getId()))) {
                if (!observed.contains(old.getPodUid()) && old.getLifecycleJson() != null) {
                    com.alibaba.fastjson2.JSONObject evidence = JSON.parseObject(old.getLifecycleJson());
                    evidence.put("present", false);
                    old.setLifecycleJson(evidence.toJSONString());
                    mapper.updateById(old);
                }
            }
            list(info.getInstance().getId());
        } catch (Exception e) {
            log.warn(
                    "Could not snapshot log locations for job instance {}: {}",
                    info.getId(),
                    e.getClass().getSimpleName());
        } finally {
            TenantContextHolder.clear();
        }
    }

    private synchronized void recordPod(JobInstance instance, String application, String namespace, Pod pod) {
        if (pod.getSpec() == null || pod.getSpec().getNodeName() == null) {
            return; // Wait until scheduling has assigned a real node.
        }
        Map<String, String> labels = pod.getMetadata().getLabels();
        String component = labels == null ? "" : labels.get("component");
        if (!"jobmanager".equals(component) && !"taskmanager".equals(component)) {
            return;
        }
        JobLogPod row = mapper.selectOne(new LambdaQueryWrapper<JobLogPod>()
                .eq(JobLogPod::getTenantId, instance.getTenantId())
                .eq(JobLogPod::getJobInstanceId, instance.getId())
                .eq(JobLogPod::getPodUid, pod.getMetadata().getUid()));
        boolean insert = row == null;
        if (insert) {
            row = new JobLogPod();
            row.setTenantId(instance.getTenantId());
            row.setJobInstanceId(instance.getId());
            row.setFlinkJobId(instance.getJid());
            row.setApplicationId(application);
            row.setNamespace(namespace);
            row.setRole("jobmanager".equals(component) ? "JM" : "TM");
            row.setPodUid(pod.getMetadata().getUid());
            row.setPodName(pod.getMetadata().getName());
            row.setNodeName(pod.getSpec().getNodeName());
            row.setHostIp(pod.getStatus() == null ? null : pod.getStatus().getHostIP());
            String directory = properties
                    .getDirectoryTemplate()
                    .replace("{app}", application)
                    .replace("{namespace}", namespace)
                    .replace("{uid}", row.getPodUid())
                    .replace("{pod}", row.getPodName());
            row.setRelativeDir(directory);
            row.setHostLogDir(properties.getRootPath() + "/" + directory);
            row.setFilesJson("[]");
            row.setFirstSeen(LocalDateTime.now());
        }
        Map<String, Object> evidence = new HashMap<>();
        Map<String, Object> runHistory = new HashMap<>();
        if (row.getLifecycleJson() != null) {
            com.alibaba.fastjson2.JSONObject previous = JSON.parseObject(row.getLifecycleJson());
            if (previous.getJSONObject("runHistory") != null) {
                runHistory.putAll(previous.getJSONObject("runHistory"));
            }
        }
        evidence.put("runHistory", runHistory);
        evidence.put("present", true);
        evidence.put("deleting", pod.getMetadata().getDeletionTimestamp() != null);
        if (pod.getStatus() != null) {
            evidence.put("phase", pod.getStatus().getPhase());
            evidence.put("reason", pod.getStatus().getReason());
            evidence.put("containers", pod.getStatus().getContainerStatuses());
            if (pod.getStatus().getContainerStatuses() != null) {
                for (io.fabric8.kubernetes.api.model.ContainerStatus container :
                        pod.getStatus().getContainerStatuses()) {
                    if ("flink-main-container".equals(container.getName())) {
                        rememberRun(runHistory, container.getLastState());
                        rememberRun(runHistory, container.getState());
                    }
                }
            }
        }
        row.setLifecycleJson(JSON.toJSONString(evidence));
        row.setLastSeen(LocalDateTime.now());
        if (insert) {
            mapper.insert(row); // Persist identity even if the file service is temporarily unavailable.
        } else {
            mapper.updateById(row);
        }
    }

    private static void rememberRun(Map<String, Object> history, io.fabric8.kubernetes.api.model.ContainerState state) {
        if (state == null) return;
        Map<String, Object> run = new HashMap<>();
        String started;
        if (state.getTerminated() != null) {
            started = state.getTerminated().getStartedAt();
            run.put("status", "TERMINATED");
            run.put("exitCode", state.getTerminated().getExitCode());
            run.put("reason", state.getTerminated().getReason());
            run.put("finishedAt", state.getTerminated().getFinishedAt());
        } else if (state.getRunning() != null) {
            started = state.getRunning().getStartedAt();
            run.put("status", "RUNNING");
        } else return;
        if (started != null) history.put(started, run);
    }

    private JobInstance authorizedInstance(Integer id) {
        Object tenant = TenantContextHolder.get();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        JobInstance instance = instanceMapper.selectOne(
                new LambdaQueryWrapper<JobInstance>().eq(JobInstance::getId, id).eq(JobInstance::getTenantId, tenant));
        if (instance == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return instance;
    }

    public List<JobLogPod> list(Integer instanceId) {
        JobInstance instance = authorizedInstance(instanceId);
        List<JobLogPod> rows = mapper.selectList(new LambdaQueryWrapper<JobLogPod>()
                .eq(JobLogPod::getTenantId, instance.getTenantId())
                .eq(JobLogPod::getJobInstanceId, instanceId)
                .orderByAsc(JobLogPod::getId));
        for (JobLogPod row : rows) {
            row.setFiles(
                    row.getFilesJson() == null
                            ? Collections.emptyList()
                            : JSON.parseArray(row.getFilesJson(), JobLogPod.LogFile.class));
            if (resolveAgent(row) == null) {
                row.setAccessStatus("PATH_ONLY");
                continue;
            }
            HttpURLConnection connection = null;
            try {
                connection = connect(row, "/logs?directory=" + encode(row.getRelativeDir()));
                int status = connection.getResponseCode();
                String originalDirectory = row.getRelativeDir();
                String uid = row.getPodUid();
                if (status == 404 && uid != null && uid.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) {
                    String alternateDirectory = originalDirectory.contains(uid)
                            ? originalDirectory.replace(uid, uid.replace('-', '_'))
                            : originalDirectory.replace(uid.replace('-', '_'), uid);
                    if (!originalDirectory.equals(alternateDirectory)) {
                        connection.disconnect();
                        connection = connect(row, "/logs?directory=" + encode(alternateDirectory));
                        status = connection.getResponseCode();
                        if (status == 200) {
                            row.setRelativeDir(alternateDirectory);
                            row.setHostLogDir(properties.getRootPath() + "/" + alternateDirectory);
                        }
                    }
                }
                if (status == 404) {
                    row.setAccessStatus("MISSING");
                } else if (status != 200) {
                    row.setAccessStatus("UNAVAILABLE");
                } else {
                    try (InputStream input = connection.getInputStream()) {
                        List<JobLogPod.LogFile> files = org.dinky.utils.JsonUtils.objectMapper.readValue(
                                input, new TypeReference<List<JobLogPod.LogFile>>() {});
                        row.setFiles(files);
                        row.setFilesJson(JSON.toJSONString(files));
                        mapper.updateById(row);
                        row.setAccessStatus(files.isEmpty() ? "EMPTY" : "AVAILABLE");
                    }
                }
            } catch (Exception e) {
                row.setAccessStatus("UNAVAILABLE"); // Keep the last successful file snapshot.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return rows;
    }

    public HttpURLConnection download(Integer id, String fileName) throws IOException {
        Object tenant = TenantContextHolder.get();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        JobLogPod row = mapper.selectOne(
                new LambdaQueryWrapper<JobLogPod>().eq(JobLogPod::getId, id).eq(JobLogPod::getTenantId, tenant));
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        authorizedInstance(row.getJobInstanceId());
        if (fileName == null
                || fileName.isEmpty()
                || fileName.contains("/")
                || fileName.contains("\\")
                || fileName.contains("\r")
                || fileName.contains("\n")
                || fileName.contains("\0")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        List<JobLogPod.LogFile> knownFiles = row.getFilesJson() == null
                ? Collections.emptyList()
                : JSON.parseArray(row.getFilesJson(), JobLogPod.LogFile.class);
        if (knownFiles.stream().noneMatch(file -> Objects.equals(fileName, file.getName()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        HttpURLConnection connection =
                connect(row, "/download?directory=" + encode(row.getRelativeDir()) + "&file=" + encode(fileName));
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new ResponseStatusException(status == 404 ? HttpStatus.GONE : HttpStatus.BAD_GATEWAY);
        }
        return connection;
    }

    private JobLogProperties.Agent resolveAgent(JobLogPod row) {
        JobLogProperties.Agent explicit = properties.getAgents().get(row.getNodeName());
        if (explicit != null) {
            return explicit;
        }
        JobLogProperties.NodeAgent config = properties.getNodeAgent();
        String ip = row.getHostIp();
        if (config == null
                || !config.isEnabled()
                || ip == null
                || !InetAddresses.isInetAddress(ip)
                || config.getPort() < 1
                || config.getPort() > 65535
                || !("http".equals(config.getScheme()) || "https".equals(config.getScheme()))) {
            return null;
        }
        JobLogProperties.Agent agent = new JobLogProperties.Agent();
        agent.setUrl(config.getScheme() + "://" + (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + config.getPort());
        agent.setToken(config.getToken());
        return agent;
    }

    private HttpURLConnection connect(JobLogPod row, String path) throws IOException {
        JobLogProperties.Agent agent = resolveAgent(row);
        if (agent == null || agent.getToken() == null || agent.getToken().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        URI base = URI.create(agent.getUrl());
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null) {
            throw new IOException("Invalid configured log agent URL");
        }
        HttpURLConnection connection = (HttpURLConnection)
                URI.create(agent.getUrl().replaceAll("/$", "") + path).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(properties.getConnectTimeoutMs());
        connection.setReadTimeout(properties.getReadTimeoutMs());
        connection.setRequestProperty("Authorization", "Bearer " + agent.getToken());
        return connection;
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }
}
