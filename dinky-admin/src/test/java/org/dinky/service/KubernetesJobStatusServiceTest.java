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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dinky.data.enums.JobStatus;
import org.dinky.gateway.kubernetes.operator.api.FlinkDeployment;
import org.dinky.gateway.kubernetes.operator.api.FlinkDeploymentStatus;

import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.ServiceBuilder;

class KubernetesJobStatusServiceTest {
    @Test
    void refreshesOperatorRestServiceAfterItsAddressChanges() {
        io.fabric8.kubernetes.api.model.Service service = new ServiceBuilder()
                .withNewSpec()
                .withType("ClusterIP")
                .withClusterIP("10.0.0.22")
                .addNewPort()
                .withName("rest")
                .withPort(8081)
                .endPort()
                .endSpec()
                .build();
        assertEquals("10.0.0.22:8081", KubernetesJobStatusService.restEndpoint(service, "10.0.0.11:8081"));
        service.getSpec().setType("NodePort");
        service.getSpec().getPorts().get(0).setNodePort(31234);
        assertEquals("192.0.2.10:31234", KubernetesJobStatusService.restEndpoint(service, "192.0.2.10:30001"));
        service.getSpec().setType("LoadBalancer");
        assertEquals(null, KubernetesJobStatusService.restEndpoint(service, "192.0.2.10:30001"));
    }

    @Test
    void anObservedUpgradeKeepsTheSameExecutionUnderMonitoring() {
        FlinkDeployment deployment = new FlinkDeployment();
        FlinkDeploymentStatus status = new FlinkDeploymentStatus();
        deployment.setStatus(status);
        status.getJobStatus().setJobId("execution-a");
        for (JobStatus expected : new JobStatus[] {JobStatus.RUNNING, JobStatus.RESTARTING, JobStatus.RECONCILING}) {
            status.getJobStatus().setState(expected.getValue());
            assertEquals(
                    expected,
                    KubernetesJobStatusService.observedStatus(deployment, "execution-a")
                            .get());
            assertTrue(KubernetesJobStatusService.observedStatus(deployment, "new-execution")
                    .isEmpty());
        }
    }

    @Test
    void acceptsOnlyExplicitTerminalEvidenceForTheSameJob() {
        FlinkDeployment deployment = new FlinkDeployment();
        FlinkDeploymentStatus status = new FlinkDeploymentStatus();
        deployment.setStatus(status);
        status.getJobStatus().setJobId("execution-a");
        for (JobStatus expected : new JobStatus[] {JobStatus.FAILED, JobStatus.CANCELED, JobStatus.FINISHED}) {
            status.getJobStatus().setState(expected.getValue());
            assertEquals(
                    expected,
                    KubernetesJobStatusService.observedStatus(deployment, "execution-a")
                            .get());
            assertTrue(KubernetesJobStatusService.observedStatus(deployment, "execution-b")
                    .isEmpty());
        }
    }

    @Test
    void missingDeletedAndUnknownResourcesDoNotInventAStatus() {
        assertTrue(
                KubernetesJobStatusService.observedStatus(null, "execution-a").isEmpty());
        FlinkDeployment deployment = new FlinkDeployment();
        FlinkDeploymentStatus status = new FlinkDeploymentStatus();
        deployment.setStatus(status);
        status.getJobStatus().setJobId("execution-a");
        for (String state : new String[] {null, "SUSPENDED", "UNKNOWN", "unrecognized"}) {
            status.getJobStatus().setState(state);
            assertTrue(KubernetesJobStatusService.observedStatus(deployment, "execution-a")
                    .isEmpty());
        }
        assertTrue(KubernetesJobStatusService.observedStatus(deployment, null).isEmpty());
    }
}
