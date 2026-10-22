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

import { getData } from '@/services/api';
import { API_CONSTANTS } from '@/services/endpoints';
import { l } from '@/utils/intl';
import { Alert, Button, Checkbox, Descriptions, Drawer, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';

type LogFile = {
  name: string;
  size: number;
  modified: number;
  runId?: string;
  runStarted?: number;
};
type LogPod = {
  id: number;
  role: string;
  nodeName: string;
  hostIp?: string;
  podName: string;
  podUid: string;
  hostLogDir: string;
  files: LogFile[];
  accessStatus: string;
  lifecycleJson?: string;
  lastSeen?: string;
};

type Lifecycle = {
  present?: boolean;
  deleting?: boolean;
  phase?: string;
  runHistory?: Record<
    string,
    { status: string; exitCode?: number; reason?: string; finishedAt?: string }
  >;
  containers?: {
    name: string;
    restartCount?: number;
    state?: {
      running?: { startedAt?: string };
      terminated?: { exitCode: number; reason?: string };
    };
    lastState?: { terminated?: { exitCode: number; reason?: string; finishedAt?: string } };
  }[];
};
const lifecycle = (pod: LogPod): Lifecycle => {
  try {
    return JSON.parse(pod.lifecycleJson ?? '{}');
  } catch {
    return {};
  }
};
const podState = (pod: LogPod) => {
  const evidence = lifecycle(pod);
  const container = evidence.containers?.find((c) => c.name === 'flink-main-container');
  const ended = container?.state?.terminated;
  if (ended)
    return ended.exitCode === 0
      ? '正常退出'
      : `异常退出 (${ended.exitCode} / ${ended.reason ?? ''})`;
  if (evidence.phase === 'Failed') return '异常退出';
  if (evidence.phase === 'Succeeded') return '正常结束';
  if (evidence.present === false) return '历史 Pod（已移除）';
  if (evidence.deleting) return '正在退出';
  if (container?.state?.running) return '当前运行';
  return evidence.phase ?? '历史状态未知';
};
const runState = (pod: LogPod, file?: LogFile) => {
  if (!file?.runStarted) return '旧日志，批次未知';
  const evidence = lifecycle(pod);
  const candidates = Object.entries(evidence.runHistory ?? {})
    .filter(
      ([start, run]) =>
        Date.parse(start) <= file.runStarted! &&
        (!run.finishedAt || file.runStarted! <= Date.parse(run.finishedAt))
    )
    .sort(([a], [b]) => Date.parse(b) - Date.parse(a));
  const run = candidates[0]?.[1];
  if (run?.status === 'TERMINATED')
    return run.exitCode === 0 ? '正常退出' : `异常退出 (${run.exitCode} / ${run.reason ?? ''})`;
  const container = evidence.containers?.find((c) => c.name === 'flink-main-container');
  const currentStart = Date.parse(container?.state?.running?.startedAt ?? '');
  if (evidence.present && !evidence.deleting && file.runStarted >= currentStart) return '本次运行';
  return '历史运行（退出未知）';
};
const runNumber = (pod: LogPod, file?: LogFile) => {
  const runs = [
    ...new Set(pod.files.map((f) => f.runStarted).filter((time): time is number => !!time))
  ].sort((a, b) => a - b);
  return file?.runStarted ? runs.indexOf(file.runStarted) + 1 : undefined;
};
const fileState = (_pod: LogPod, file?: LogFile) => {
  if (!file) return '-';
  if (/\.(log|out)[.-].+$/.test(file.name)) return '批次内轮转文件';
  return file.name.endsWith('.out') ? '标准输出' : '主日志';
};

const HistoryLogs = ({ instanceId, onClose }: { instanceId: number; onClose: () => void }) => {
  const [detail, setDetail] = useState<{ pod: LogPod; file?: LogFile }>();
  const [pods, setPods] = useState<LogPod[]>([]);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [refreshedAt, setRefreshedAt] = useState<number>();
  const inFlight = useRef(false);

  const load = useCallback(
    async (silent = false) => {
      if (inFlight.current) return;
      inFlight.current = true;
      if (!silent) setLoading(true);
      setFailed(false);
      try {
        const response = await getData(API_CONSTANTS.JOB_LOG_FILES, { jobInstanceId: instanceId });
        setPods(response.data ?? []);
        setRefreshedAt(Date.now());
      } catch {
        setFailed(true);
      } finally {
        if (!silent) setLoading(false);
        inFlight.current = false;
      }
    },
    [instanceId]
  );

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (!autoRefresh) return;
    const timer = window.setInterval(() => {
      void load(true);
    }, 5000);
    return () => window.clearInterval(timer);
  }, [autoRefresh, load]);

  const rows = pods.flatMap((pod) =>
    (pod.files?.length ? pod.files : [undefined]).map((file) => ({ pod, file }))
  );

  return (
    <Drawer
      open
      width='min(1120px, 100vw)'
      title={`${l('devops.historyLogs.title')} #${instanceId}`}
      onClose={onClose}
      extra={
        <Space wrap>
          {refreshedAt && (
            <Typography.Text type='secondary'>
              更新于 {new Date(refreshedAt).toLocaleTimeString()}
            </Typography.Text>
          )}
          <Checkbox
            checked={autoRefresh}
            onChange={(event) => setAutoRefresh(event.target.checked)}
          >
            每 5 秒刷新
          </Checkbox>
          <Button onClick={() => void load()} loading={loading}>
            {l('devops.historyLogs.refresh')}
          </Button>
        </Space>
      }
    >
      <Space direction='vertical' size='middle' style={{ width: '100%' }}>
        <Alert type='info' showIcon message={l('devops.historyLogs.help')} />
        <Alert
          type='info'
          showIcon
          message='按 Pod UID + UTC 启动时间 + 随机 ID 区分运行批次，轮转文件归属原批次。Pod 内编号按仍保留的批次排序；旧日志不推测重启顺序。退出状态来自已采集的 Kubernetes 证据。'
        />
        {failed && <Alert type='error' showIcon message={l('devops.historyLogs.failed')} />}
        <Table
          loading={loading}
          dataSource={rows}
          rowKey={({ pod, file }) => `${pod.id}/${file?.name ?? ''}`}
          pagination={{ pageSize: 10 }}
          scroll={{ x: 1070 }}
          locale={{ emptyText: l('devops.historyLogs.empty') }}
          columns={[
            {
              title: '运行批次（Pod 内）',
              width: 210,
              sorter: (a, b) => (a.file?.runStarted ?? 0) - (b.file?.runStarted ?? 0),
              defaultSortOrder: 'descend',
              render: (_, { pod, file }) =>
                file?.runId ? (
                  <Space direction='vertical'>
                    <Typography.Text>
                      第 {runNumber(pod, file)} 次启动 ·{' '}
                      {new Date(file.runStarted!).toLocaleString()}
                    </Typography.Text>
                  </Space>
                ) : (
                  '旧日志，批次未知'
                )
            },
            {
              title: '批次状态',
              width: 190,
              filters: ['本次运行', '异常退出', '正常退出', '历史运行', '批次未知'].map((text) => ({
                text,
                value: text
              })),
              onFilter: (value, row) => runState(row.pod, row.file).includes(String(value)),
              render: (_, { pod, file }) => <Tag>{runState(pod, file)}</Tag>
            },
            {
              title: '日志类型',
              width: 120,
              filters: ['主日志', '批次内轮转文件', '标准输出'].map((text) => ({
                text,
                value: text
              })),
              onFilter: (value, row) => fileState(row.pod, row.file) === value,
              render: (_, { pod, file }) => <Tag>{fileState(pod, file)}</Tag>
            },
            {
              title: '文件更新时间',
              width: 170,
              render: (_, { file }) => (file ? new Date(file.modified).toLocaleString() : '-')
            },
            { title: l('devops.historyLogs.role'), width: 60, render: (_, row) => row.pod.role },
            {
              title: l('devops.historyLogs.size'),
              width: 90,
              render: (_, { file }) => (file ? `${(file.size / 1024 / 1024).toFixed(2)} MB` : '-')
            },
            {
              title: l('devops.historyLogs.status'),
              width: 110,
              render: (_, { pod }) => (
                <Tag color={pod.accessStatus === 'AVAILABLE' ? 'green' : 'default'}>
                  {l(`devops.historyLogs.status.${pod.accessStatus}`)}
                </Tag>
              )
            },
            {
              title: l('global.table.operate'),
              width: 120,
              fixed: 'right',
              render: (_, { pod, file }) => (
                <Space>
                  {file && pod.accessStatus === 'AVAILABLE' && (
                    <Typography.Link
                      href={`${API_CONSTANTS.JOB_LOG_FILES}/${pod.id}/download?fileName=${encodeURIComponent(file.name)}`}
                    >
                      {l('devops.historyLogs.download')}
                    </Typography.Link>
                  )}
                  <Button
                    type='link'
                    style={{ padding: 0 }}
                    onClick={() => setDetail({ pod, file })}
                  >
                    详情
                  </Button>
                </Space>
              )
            }
          ]}
        />
      </Space>
      <Drawer
        open={!!detail}
        title='日志详情'
        width='min(640px, 100vw)'
        onClose={() => setDetail(undefined)}
      >
        {detail &&
          (() => {
            const pod = pods.find((item) => item.id === detail.pod.id) ?? detail.pod;
            const file = pod.files.find((item) => item.name === detail.file?.name) ?? detail.file;
            const container = lifecycle(pod).containers?.find(
              (c) => c.name === 'flink-main-container'
            );
            const previous = container?.lastState?.terminated;
            const text = (value?: string) =>
              value ? (
                <Typography.Text copyable style={{ wordBreak: 'break-all' }}>
                  {value}
                </Typography.Text>
              ) : (
                '-'
              );
            return (
              <Descriptions
                bordered
                labelStyle={{ width: 128, minWidth: 128, whiteSpace: 'nowrap' }}
                contentStyle={{ wordBreak: 'break-all' }}
                column={1}
                size='small'
                items={[
                  { key: 'role', label: '进程类型', children: pod.role },
                  { key: 'run', label: '运行批次 ID', children: text(file?.runId) },
                  {
                    key: 'start',
                    label: '批次启动时间',
                    children: file?.runStarted
                      ? new Date(file.runStarted).toLocaleString()
                      : '旧日志，批次未知'
                  },
                  { key: 'state', label: '批次状态', children: runState(pod, file) },
                  {
                    key: 'node',
                    label: '服务器',
                    children: (
                      <Space direction='vertical'>
                        {text(pod.nodeName)}
                        {text(pod.hostIp)}
                      </Space>
                    )
                  },
                  { key: 'pod', label: 'Pod 名称', children: text(pod.podName) },
                  { key: 'uid', label: 'Pod UID', children: text(pod.podUid) },
                  { key: 'directory', label: '宿主机日志目录', children: text(pod.hostLogDir) },
                  { key: 'file', label: '日志文件', children: text(file?.name) },
                  {
                    key: 'path',
                    label: '完整日志路径',
                    children: text(file ? `${pod.hostLogDir}/${file.name}` : undefined)
                  },
                  { key: 'podState', label: 'Pod 当前状态', children: podState(pod) },
                  {
                    key: 'restarts',
                    label: '容器重启次数',
                    children: container?.restartCount ?? '未知'
                  },
                  {
                    key: 'previous',
                    label: '最近一次退出',
                    children: previous
                      ? `${previous.exitCode === 0 ? '正常退出' : '异常退出'} (${previous.exitCode} / ${previous.reason ?? ''}) ${previous.finishedAt ?? ''}`
                      : '无已采集证据'
                  },
                  { key: 'seen', label: '最近采集时间', children: pod.lastSeen ?? '-' },
                  {
                    key: 'modified',
                    label: '文件更新时间',
                    children: file ? new Date(file.modified).toLocaleString() : '-'
                  },
                  {
                    key: 'size',
                    label: '文件大小',
                    children: file ? `${file.size.toLocaleString()} 字节` : '-'
                  },
                  {
                    key: 'access',
                    label: '访问状态',
                    children: l(`devops.historyLogs.status.${pod.accessStatus}`)
                  }
                ]}
              />
            );
          })()}
      </Drawer>
    </Drawer>
  );
};

export default HistoryLogs;
