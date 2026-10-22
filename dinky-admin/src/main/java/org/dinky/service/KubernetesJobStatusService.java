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

import org.dinky.data.enums.GatewayType;
import org.dinky.data.enums.JobStatus;
import org.dinky.data.model.ext.JobInfoDetail;
import org.dinky.gateway.config.GatewayConfig;
import org.dinky.gateway.config.K8sConfig;
import org.dinky.gateway.kubernetes.operator.api.FlinkDeployment;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;

/** Uses Operator evidence when the live Flink REST endpoint is no longer available. */
@Slf4j
@Service
public class KubernetesJobStatusService {
    public Optional<JobStatus> getJobStatus(JobInfoDetail info) {
        if (info.getClusterInstance() == null
                || GatewayType.get(info.getClusterInstance().getType())
                        != GatewayType.KUBERNETES_APPLICATION_OPERATOR) {
            return Optional.empty();
        }
        try {
            GatewayConfig gateway =
                    info.getHistory() == null || info.getHistory().getConfigJson() == null
                            ? null
                            : info.getHistory().getConfigJson().getGatewayConfig();
            if (gateway == null && info.getClusterConfiguration() != null) {
                gateway = GatewayConfig.build(info.getClusterConfiguration().getConfig());
            }
            if (gateway == null || gateway.getKubernetesConfig() == null) {
                return Optional.empty();
            }
            K8sConfig k8s = gateway.getKubernetesConfig();
            Map<String, String> options = new HashMap<>(k8s.getConfiguration());
            if (gateway.getFlinkConfig() != null) {
                options.putAll(gateway.getFlinkConfig().getConfiguration());
            }
            Config config =
                    k8s.getKubeConfig() == null || k8s.getKubeConfig().trim().isEmpty()
                            ? Config.autoConfigure(null)
                            : Config.fromKubeconfig(k8s.getKubeConfig());
            config.setConnectionTimeout(2000);
            config.setRequestTimeout(5000);
            try (KubernetesClient client = new DefaultKubernetesClient(config)) {
                String namespace = options.getOrDefault("kubernetes.namespace", "default");
                String application = options.getOrDefault(
                        "kubernetes.cluster-id", info.getClusterInstance().getName());
                FlinkDeployment deployment = client.resources(FlinkDeployment.class)
                        .inNamespace(namespace)
                        .withName(application)
                        .get();
                Optional<JobStatus> status =
                        observedStatus(deployment, info.getInstance().getJid());
                if (status.isPresent()) {
                    try {
                        String endpoint = restEndpoint(
                                client.services()
                                        .inNamespace(namespace)
                                        .withName(application + "-rest")
                                        .get(),
                                info.getClusterInstance().getJobManagerHost());
                        if (endpoint != null) {
                            info.getClusterInstance().setJobManagerHost(endpoint);
                        }
                    } catch (RuntimeException ignored) {
                        // A missing/unreadable REST service does not invalidate matching Operator evidence.
                    }
                }
                return status;
            }
        } catch (Exception e) {
            log.debug(
                    "Could not read Operator status for instance {}: {}",
                    info.getId(),
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static String restEndpoint(io.fabric8.kubernetes.api.model.Service service, String previous) {
        if (service == null || service.getSpec() == null || service.getSpec().getPorts() == null) {
            return null;
        }
        for (ServicePort port : service.getSpec().getPorts()) {
            if (!"rest".equals(port.getName()) && !Integer.valueOf(8081).equals(port.getPort())) {
                continue;
            }
            String address = service.getSpec().getClusterIP();
            Integer number = port.getPort();
            if ("NodePort".equals(service.getSpec().getType())) {
                if (previous == null || port.getNodePort() == null) {
                    return null;
                }
                URI uri = URI.create(previous.contains("://") ? previous : "http://" + previous);
                address = uri.getHost();
                number = port.getNodePort();
            } else if (service.getSpec().getType() != null
                    && !"ClusterIP".equals(service.getSpec().getType())) {
                return null;
            }
            if (address == null || address.isEmpty() || "None".equals(address) || number == null) {
                return null;
            }
            return (address.contains(":") && !address.startsWith("[") ? "[" + address + "]" : address) + ":" + number;
        }
        return null;
    }

    static Optional<JobStatus> observedStatus(FlinkDeployment deployment, String jobId) {
        if (deployment == null
                || deployment.getStatus() == null
                || deployment.getStatus().getJobStatus() == null
                || jobId == null
                || jobId.isEmpty()
                || !jobId.equals(deployment.getStatus().getJobStatus().getJobId())) {
            return Optional.empty();
        }
        JobStatus status = JobStatus.get(deployment.getStatus().getJobStatus().getState());
        return status == JobStatus.UNKNOWN || status == JobStatus.SUSPENDED || status == JobStatus.RECONNECTING
                ? Optional.empty()
                : Optional.of(status);
    }
}
