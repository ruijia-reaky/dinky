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

type LogFile = { name: string; size: number; modified: number };
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
        {failed && <Alert type='error' showIcon message={l('devops.historyLogs.failed')} />}
        <Table
          loading={loading}
          dataSource={rows}
          rowKey={({ pod, file }) => `${pod.id}/${file?.name ?? ''}`}
          pagination={{ pageSize: 10 }}
          scroll={{ x: 1500 }}
          locale={{ emptyText: l('devops.historyLogs.empty') }}
          columns={[
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
