import React, { useState, useEffect } from 'react';
import { Drawer, Table, Button, Space, message, Tag, Popconfirm, Spin } from 'antd';
import { HistoryOutlined, UndoOutlined, ReloadOutlined } from '@ant-design/icons';
import { api } from '../services/api';

export const SnapshotDrawer = ({ visible, filePath, authHeader, onClose, onRewindSuccess }) => {
  const [snapshots, setSnapshots] = useState([]);
  const [loading, setLoading] = useState(false);
  const [rewindingId, setRewindingId] = useState(null);

  const fetchSnapshots = async () => {
    setLoading(true);
    try {
      const data = await api.listSnapshots(authHeader, filePath || '');
      setSnapshots(data || []);
    } catch (err) {
      message.error(`Failed to fetch snapshots: ${err.response?.data?.error || err.message}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (visible) {
      fetchSnapshots();
    }
  }, [visible, filePath]);

  const handleRewind = async (snapshotId) => {
    setRewindingId(snapshotId);
    try {
      const res = await api.rewind(authHeader, snapshotId);
      message.success(res.message || 'Successfully rewound file state!');
      fetchSnapshots();
      if (onRewindSuccess) onRewindSuccess();
    } catch (err) {
      message.error(`Rewind failed: ${err.response?.data?.error || err.message}`);
    } finally {
      setRewindingId(null);
    }
  };

  const columns = [
    {
      title: 'Snapshot ID',
      dataIndex: 'snapshotId',
      key: 'snapshotId',
      render: (id) => <code style={{ color: '#38bdf8' }}>{id}</code>
    },
    {
      title: 'Action',
      dataIndex: 'action',
      key: 'action',
      render: (action) => {
        const colorMap = {
          WRITE: 'blue',
          EDIT: 'cyan',
          TRASH: 'volcano',
          MOVE: 'purple',
          REWIND: 'gold'
        };
        return <Tag color={colorMap[action] || 'default'}>{action}</Tag>;
      }
    },
    {
      title: 'File Path',
      dataIndex: 'filePath',
      key: 'filePath',
      render: (path) => <span style={{ color: '#e2e8f0' }}>{path}</span>
    },
    {
      title: 'Created At',
      dataIndex: 'timestamp',
      key: 'timestamp',
      render: (ts) => new Date(ts).toLocaleString()
    },
    {
      title: 'Operation',
      key: 'action',
      render: (_, record) => (
        <Popconfirm
          title="Rewind file to this snapshot?"
          description="A safety snapshot of current state will be created."
          onConfirm={() => handleRewind(record.snapshotId)}
          okText="Rewind"
          cancelText="Cancel"
        >
          <Button
            type="primary"
            size="small"
            icon={<UndoOutlined />}
            loading={rewindingId === record.snapshotId}
          >
            Rewind
          </Button>
        </Popconfirm>
      )
    }
  ];

  return (
    <Drawer
      title={
        <Space>
          <HistoryOutlined />
          <span>File Snapshots & Rollback History {filePath ? `(${filePath})` : ''}</span>
        </Space>
      }
      placement="right"
      width={780}
      onClose={onClose}
      visible={visible}
      extra={
        <Button icon={<ReloadOutlined />} onClick={fetchSnapshots} loading={loading}>
          Refresh
        </Button>
      }
    >
      {loading ? (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Spin size="large" />
        </div>
      ) : (
        <Table
          dataSource={snapshots}
          columns={columns}
          rowKey="snapshotId"
          pagination={{ pageSize: 10 }}
        />
      )}
    </Drawer>
  );
};
