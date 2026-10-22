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

package org.dinky.data.model.job;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

/** One immutable Pod identity associated with one execution, including replaced Pods. */
@Data
@TableName("dinky_job_log_pod")
public class JobLogPod {
    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer tenantId;
    private Integer jobInstanceId;
    private String flinkJobId;
    private String namespace;
    private String applicationId;
    private String role;
    private String podName;
    private String podUid;
    private String nodeName;
    private String hostIp;
    private String relativeDir;
    private String hostLogDir;

    @JsonIgnore
    private String filesJson;

    /** Persisted Kubernetes lifecycle evidence; missing evidence must not imply failure. */
    private String lifecycleJson;

    private LocalDateTime firstSeen;
    private LocalDateTime lastSeen;

    @TableField(exist = false)
    private List<LogFile> files = new ArrayList<>();

    @TableField(exist = false)
    private String accessStatus;

    @Data
    public static class LogFile {
        private String name;
        private long size;
        private long modified;
        private String runId;
        private Long runStarted;
    }
}
