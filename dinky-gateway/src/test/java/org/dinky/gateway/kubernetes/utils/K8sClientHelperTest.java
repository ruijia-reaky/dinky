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

package org.dinky.gateway.kubernetes.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;

class K8sClientHelperTest {
    @Test
    void operatorConfigurationSurvivesDeploymentReplacement() {
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("app")
                .withUid("old-deployment")
                .addToOwnerReferences(new OwnerReferenceBuilder()
                        .withApiVersion("flink.apache.org/v1beta1")
                        .withKind("FlinkDeployment")
                        .withName("app")
                        .withUid("stable-operator-resource")
                        .withController(false)
                        .build())
                .endMetadata()
                .build();
        assertEquals(
                "stable-operator-resource",
                K8sClientHelper.resourceOwner(deployment).getUid());
        deployment.getMetadata().setUid("new-deployment");
        assertEquals(
                "stable-operator-resource",
                K8sClientHelper.resourceOwner(deployment).getUid());
    }

    @Test
    void nativeApplicationStillUsesItsDeploymentAsOwner() {
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("app")
                .withUid("native-deployment")
                .endMetadata()
                .build();
        assertEquals(
                "native-deployment", K8sClientHelper.resourceOwner(deployment).getUid());
        assertEquals("Deployment", K8sClientHelper.resourceOwner(deployment).getKind());
    }
}
