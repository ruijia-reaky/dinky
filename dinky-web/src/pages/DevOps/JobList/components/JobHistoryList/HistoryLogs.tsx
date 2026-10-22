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
import { Alert, Button, Drawer, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';

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
  const [pods, setPods] = useState<LogPod[]>([]);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  const load = async () => {
    setLoading(true);
    setFailed(false);
    try {
      const response = await getData(API_CONSTANTS.JOB_LOG_FILES, { jobInstanceId: instanceId });
      setPods(response.data ?? []);
    } catch {
      setFailed(true);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [instanceId]);

  const rows = pods.flatMap((pod) =>
    (pod.files?.length ? pod.files : [undefined]).map((file) => ({ pod, file }))
  );

  return (
    <Drawer
      open
      width='90%'
      title={`${l('devops.historyLogs.title')} #${instanceId}`}
      onClose={onClose}
      extra={
        <Button onClick={load} loading={loading}>
          {l('devops.historyLogs.refresh')}
        </Button>
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
          scroll={{ x: 1500 }}
          locale={{ emptyText: l('devops.historyLogs.empty') }}
          columns={[
            {
              title: '运行批次（Pod 内）',
              width: 245,
              sorter: (a, b) => (a.file?.runStarted ?? 0) - (b.file?.runStarted ?? 0),
              defaultSortOrder: 'descend',
              render: (_, { pod, file }) =>
                file?.runId ? (
                  <Space direction='vertical'>
                    <Typography.Text>
                      第 {runNumber(pod, file)} 次启动 ·{' '}
                      {new Date(file.runStarted!).toLocaleString()}
                    </Typography.Text>
                    <Typography.Text copyable type='secondary'>
                      {file.runId}
                    </Typography.Text>
                  </Space>
                ) : (
                  '旧日志，批次未知'
                )
            },
            {
              title: '批次状态',
              width: 210,
              filters: ['本次运行', '异常退出', '正常退出', '历史运行', '批次未知'].map((text) => ({
                text,
                value: text
              })),
              onFilter: (value, row) => runState(row.pod, row.file).includes(String(value)),
              render: (_, { pod, file }) => <Tag>{runState(pod, file)}</Tag>
            },
            {
              title: 'Pod 运行状态',
              width: 210,
              filters: ['当前运行', '异常退出', '正常结束', '历史 Pod', '未知'].map((text) => ({
                text,
                value: text
              })),
              onFilter: (value, row) => podState(row.pod).includes(String(value)),
              render: (_, { pod }) => {
                const container = lifecycle(pod).containers?.find(
                  (c) => c.name === 'flink-main-container'
                );
                const previous = container?.lastState?.terminated;
                return (
                  <Space direction='vertical'>
                    <Tag>{podState(pod)}</Tag>
                    <Typography.Text>重启次数：{container?.restartCount ?? '未知'}</Typography.Text>
                    {previous && (
                      <Typography.Text>
                        上一轮：{previous.exitCode === 0 ? '正常退出' : '异常退出'} (
                        {previous.exitCode} / {previous.reason}) {previous.finishedAt}
                      </Typography.Text>
                    )}
                    <Typography.Text type='secondary'>采集：{pod.lastSeen ?? '-'}</Typography.Text>
                  </Space>
                );
              }
            },
            {
              title: '日志类型',
              width: 150,
              filters: ['主日志', '批次内轮转文件', '标准输出'].map((text) => ({
                text,
                value: text
              })),
              onFilter: (value, row) => fileState(row.pod, row.file) === value,
              render: (_, { pod, file }) => <Tag>{fileState(pod, file)}</Tag>
            },
            {
              title: '文件更新时间',
              width: 190,
              render: (_, { file }) => (file ? new Date(file.modified).toLocaleString() : '-')
            },
            { title: l('devops.historyLogs.role'), width: 70, render: (_, row) => row.pod.role },
            {
              title: l('devops.historyLogs.node'),
              width: 160,
              render: (_, { pod }) => (
                <Space direction='vertical'>
                  <Typography.Text copyable>{pod.nodeName}</Typography.Text>
                  <Typography.Text>{pod.hostIp}</Typography.Text>
                </Space>
              )
            },
            {
              title: 'Pod / UID',
              width: 250,
              render: (_, { pod }) => (
                <Space direction='vertical'>
                  <Typography.Text copyable>{pod.podName}</Typography.Text>
                  <Typography.Text copyable type='secondary'>
                    {pod.podUid}
                  </Typography.Text>
                </Space>
              )
            },
            {
              title: l('devops.historyLogs.directory'),
              width: 350,
              render: (_, { pod }) => (
                <Typography.Paragraph copyable style={{ wordBreak: 'break-all' }}>
                  {pod.hostLogDir}
                </Typography.Paragraph>
              )
            },
            {
              title: l('devops.historyLogs.file'),
              width: 300,
              render: (_, { file }) =>
                file ? <Typography.Text copyable>{file.name}</Typography.Text> : '-'
            },
            {
              title: l('devops.historyLogs.size'),
              width: 100,
              render: (_, { file }) => (file ? `${(file.size / 1024 / 1024).toFixed(2)} MB` : '-')
            },
            {
              title: l('devops.historyLogs.status'),
              width: 130,
              render: (_, { pod }) => (
                <Tag color={pod.accessStatus === 'AVAILABLE' ? 'green' : 'default'}>
                  {l(`devops.historyLogs.status.${pod.accessStatus}`)}
                </Tag>
              )
            },
            {
              title: l('global.table.operate'),
              width: 100,
              fixed: 'right',
              render: (_, { pod, file }) =>
                file && pod.accessStatus === 'AVAILABLE' ? (
                  <Typography.Link
                    href={`${API_CONSTANTS.JOB_LOG_FILES}/${pod.id}/download?fileName=${encodeURIComponent(file.name)}`}
                  >
                    {l('devops.historyLogs.download')}
                  </Typography.Link>
                ) : (
                  '-'
                )
            }
          ]}
        />
      </Space>
    </Drawer>
  );
};

export default HistoryLogs;
