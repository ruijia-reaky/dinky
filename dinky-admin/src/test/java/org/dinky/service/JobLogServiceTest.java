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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.dinky.configure.JobLogProperties;
import org.dinky.context.TenantContextHolder;
import org.dinky.data.model.job.JobInstance;
import org.dinky.data.model.job.JobLogPod;
import org.dinky.mapper.JobInstanceMapper;
import org.dinky.mapper.JobLogPodMapper;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.sun.net.httpserver.HttpServer;

class JobLogServiceTest {
    private JobLogService service;
    private JobLogProperties properties;
    private JobLogPodMapper pods;
    private JobInstanceMapper instances;
    private JobLogPod row;
    private HttpServer server;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.set(1);
        properties = new JobLogProperties();
        pods = mock(JobLogPodMapper.class);
        instances = mock(JobInstanceMapper.class);
        service = new JobLogService(properties, pods, instances, Runnable::run);
        JobInstance instance = new JobInstance();
        instance.setId(7);
        instance.setTenantId(1);
        when(instances.selectOne(any())).thenReturn(instance);
        row = new JobLogPod();
        row.setId(11);
        row.setTenantId(1);
        row.setJobInstanceId(7);
        row.setNodeName("worker-a");
        row.setRelativeDir("app/uid_app-tm");
        row.setFilesJson("[{\"name\":\"old.log\",\"size\":3,\"modified\":1}]");
        when(pods.selectList(any())).thenReturn(Collections.singletonList(row));
        when(pods.selectOne(any())).thenReturn(row);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        JobLogProperties.Agent agent = new JobLogProperties.Agent();
        agent.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        agent.setToken("test-token");
        properties.getAgents().put("worker-a", agent);
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
        TenantContextHolder.clear();
    }

    @Test
    void resolvesSystemdUidDirectoryAndDownloadsUsingItsRealName() throws Exception {
        String uid = "12345678-abcd-1234-abcd-123456789abc";
        row.setPodUid(uid);
        row.setRelativeDir("app/" + uid + "_taskmanager-1-1");
        String actual = "app/" + uid.replace('-', '_') + "_taskmanager-1-1";
        server.createContext("/logs", exchange -> {
            if (!exchange.getRequestURI().getQuery().equals("directory=" + actual)) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                byte[] data = "[{\"name\":\"old.log\",\"size\":3,\"modified\":1}]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            }
            exchange.close();
        });
        server.createContext("/download", exchange -> {
            assertEquals(
                    "directory=" + actual + "&file=old.log",
                    exchange.getRequestURI().getQuery());
            exchange.sendResponseHeaders(200, 3);
            exchange.getResponseBody().write("old".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        JobLogPod result = service.list(7).get(0);
        assertEquals("AVAILABLE", result.getAccessStatus());
        assertEquals(actual, result.getRelativeDir());
        assertEquals("/data/flink-logs/" + actual, result.getHostLogDir());
        HttpURLConnection connection = service.download(11, "old.log");
        try {
            assertEquals('o', connection.getInputStream().read());
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void downloadsOldFileWithoutLiveFlinkOrKubernetes() throws Exception {
        // Explicit node configuration must win over the DaemonSet fallback.
        properties.getNodeAgent().setEnabled(true);
        properties.getNodeAgent().setPort(1);
        properties.getNodeAgent().setToken("different-token");
        server.createContext("/download", exchange -> {
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] data = "old".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        HttpURLConnection connection = service.download(11, "old.log");
        try {
            assertEquals(3, connection.getContentLengthLong());
            assertEquals('o', connection.getInputStream().read());
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void retainsSnapshotWhenNodeDirectoryHasBeenCleaned() {
        server.createContext("/logs", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        JobLogPod result = service.list(7).get(0);
        assertEquals("MISSING", result.getAccessStatus());
        assertEquals("old.log", result.getFiles().get(0).getName());
    }

    @Test
    void allowsPathOnlyConfiguration() {
        properties.getAgents().clear();
        assertEquals("PATH_ONLY", service.list(7).get(0).getAccessStatus());
    }

    @Test
    void daemonSetRoutingUsesCapturedNodeIpAndAuthenticatesDownload() throws Exception {
        properties.getAgents().clear();
        row.setHostIp("127.0.0.1");
        properties.getNodeAgent().setEnabled(true);
        properties.getNodeAgent().setPort(server.getAddress().getPort());
        properties.getNodeAgent().setToken("daemonset-token");
        server.createContext("/download", exchange -> {
            assertEquals("Bearer daemonset-token", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, 3);
            exchange.getResponseBody().write("old".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        HttpURLConnection connection = service.download(11, "old.log");
        try {
            assertEquals('o', connection.getInputStream().read());
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void daemonSetRoutingRequiresOptInAndNumericNodeAddress() {
        properties.getAgents().clear();
        row.setHostIp("127.0.0.1");
        assertEquals("PATH_ONLY", service.list(7).get(0).getAccessStatus());
        properties.getNodeAgent().setEnabled(true);
        for (String ip : new String[] {null, "", "node.example.org", "127.0.0.1/other", "127.0.0.1:18090"}) {
            row.setHostIp(ip);
            assertEquals("PATH_ONLY", service.list(7).get(0).getAccessStatus());
        }
    }

    @Test
    void rejectsUnknownFilesAndTraversalBeforeContactingNode() {
        for (String name : new String[] {"../passwd", "folder\\old.log", "old.log\r\n", "other.log"}) {
            assertThrows(ResponseStatusException.class, () -> service.download(11, name));
        }
    }

    @Test
    void rejectsMissingTenantAndForeignExecution() {
        TenantContextHolder.clear();
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> service.list(7))
                        .getStatus());
        TenantContextHolder.set(2);
        when(instances.selectOne(any())).thenReturn(null);
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.list(7))
                        .getStatus());
    }

    @Test
    void reportsGoneForADeletedPreviouslyIndexedFile() throws Exception {
        server.createContext("/download", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        assertEquals(
                HttpStatus.GONE,
                assertThrows(ResponseStatusException.class, () -> service.download(11, "old.log"))
                        .getStatus());
    }
}
