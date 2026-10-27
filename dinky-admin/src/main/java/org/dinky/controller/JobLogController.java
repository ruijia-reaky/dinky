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

package org.dinky.controller;

import org.dinky.data.model.job.JobLogPod;
import org.dinky.data.result.Result;
import org.dinky.service.JobInstanceService;
import org.dinky.service.JobLogExecutionService;
import org.dinky.service.JobLogService;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;

@RestController
@SaCheckLogin
@RequiredArgsConstructor
@RequestMapping("/api/jobInstance/logFiles")
public class JobLogController {
    private final JobLogService service;
    private final JobInstanceService instances;
    private final JobLogExecutionService executions;

    @GetMapping("/execution")
    public Result<JobLogExecutionService.Observation> execution(@RequestParam Integer jobInstanceId) {
        return Result.succeed(
                executions.observe(instances.getJobInfoDetailInfo(service.authorizedInstance(jobInstanceId))));
    }

    @GetMapping
    public Result<List<JobLogPod>> list(@RequestParam Integer jobInstanceId) {
        return Result.succeed(service.list(jobInstanceId));
    }

    @GetMapping("/{id}/download")
    public void download(@PathVariable Integer id, @RequestParam String fileName, HttpServletResponse response)
            throws IOException {
        HttpURLConnection connection = service.download(id, fileName);
        try (InputStream input = connection.getInputStream()) {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment()
                            .filename(fileName, StandardCharsets.UTF_8)
                            .build()
                            .toString());
            long length = connection.getContentLengthLong();
            if (length >= 0) {
                response.setContentLengthLong(length);
            }
            StreamUtils.copy(input, response.getOutputStream());
        } finally {
            connection.disconnect();
        }
    }
}
