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

package org.dinky.data.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FlinkHistoryArchiveDirectoryTest {
    @Test
    void keepsTheExistingResourceArchiveByDefault() {
        String previous = System.getProperty("dinky.flink.history.archive-directory");
        try {
            System.clearProperty("dinky.flink.history.archive-directory");
            assertEquals(
                    SystemConfiguration.FLINK_JOB_ARCHIVE,
                    SystemConfiguration.getInstances().getFlinkHistoryArchiveDirectory());
        } finally {
            restore(previous);
        }
    }

    @Test
    void usesTheSameSharedDirectoryForSubmissionAndHistoryServer() {
        String previous = System.getProperty("dinky.flink.history.archive-directory");
        SystemConfiguration config = SystemConfiguration.getInstances();
        Boolean enabled = config.getUseFlinkHistoryServer().getValue();
        Integer port = config.getFlinkHistoryServerPort().getValue();
        Integer interval = config.getFlinkHistoryServerArchiveRefreshInterval().getValue();
        try {
            System.setProperty("dinky.flink.history.archive-directory", "file:///shared/job-archive");
            config.getUseFlinkHistoryServer().setValue(true);
            config.getFlinkHistoryServerPort().setValue(8082);
            config.getFlinkHistoryServerArchiveRefreshInterval().setValue(5000);
            assertEquals("file:///shared/job-archive", config.getFlinkHistoryArchiveDirectory());
            assertEquals(
                    "file:///shared/job-archive",
                    config.getFlinkHistoryServerConfiguration().get("historyserver.archive.fs.dir"));
        } finally {
            config.getUseFlinkHistoryServer().setValue(enabled);
            config.getFlinkHistoryServerPort().setValue(port);
            config.getFlinkHistoryServerArchiveRefreshInterval().setValue(interval);
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty("dinky.flink.history.archive-directory");
        } else {
            System.setProperty("dinky.flink.history.archive-directory", previous);
        }
    }
}
