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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dinky.data.model.job.JobLogPod;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;

class JobLogExecutionServiceTest {
    private JobLogExecutionService.LiveJob job(String id) {
        JobLogExecutionService.LiveJob job = new JobLogExecutionService.LiveJob();
        job.setJid(id);
        return job;
    }

    @Test
    void distinguishesOriginalNewAndAmbiguousExecutions() {
        assertEquals("NO_JOBS", JobLogExecutionService.classify("old", Collections.emptyList()));
        assertEquals("MATCHED", JobLogExecutionService.classify("old", Arrays.asList(job("new"), job("old"))));
        assertEquals("JOB_ID_CHANGED", JobLogExecutionService.classify("old", Collections.singletonList(job("new"))));
        assertEquals("MULTIPLE_JOBS", JobLogExecutionService.classify("old", Arrays.asList(job("new1"), job("new2"))));
    }

    @Test
    void applicationNameReuseDoesNotAssociateAnUnrelatedDeployment() {
        JobLogPod row = new JobLogPod();
        row.setRole("JM");
        row.setPodUid("original");
        Pod replacement = new PodBuilder()
                .withNewMetadata()
                .withUid("replacement")
                .endMetadata()
                .build();
        assertFalse(JobLogExecutionService.sameApplication(
                Collections.singletonList(row), Collections.singletonList(replacement)));
        replacement.getMetadata().setUid("original");
        assertTrue(JobLogExecutionService.sameApplication(
                Collections.singletonList(row), Collections.singletonList(replacement)));
        row.setRole("TM");
        assertFalse(JobLogExecutionService.sameApplication(
                Collections.singletonList(row), Collections.singletonList(replacement)));
    }
}
