import React, { useState, useEffect } from 'react';
import { Drawer, Input, Button, Space, message, Spin, Tag } from 'antd';
import { SaveOutlined, ReloadOutlined, HistoryOutlined } from '@ant-design/icons';
import { api } from '../services/api';

export const FileViewerModal = ({ visible, filePath, authHeader, onClose, onOpenSnapshots }) => {
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [modified, setModified] = useState(false);

  const fetchContent = async () => {
    if (!filePath) return;
    setLoading(true);
    try {
      const text = await api.getFileContent(authHeader, filePath);
      setContent(typeof text === 'string' ? text : JSON.stringify(text, null, 2));
      setModified(false);
    } catch (err) {
      message.error(`Failed to read file: ${err.response?.data?.error || err.message}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (visible && filePath) {
      fetchContent();
    }
  }, [visible, filePath]);

  const handleSave = async () => {
    setSaving(true);
    try {
      await api.uploadFile(authHeader, filePath, content);
      message.success(`File ${filePath} saved successfully! Snapshot created.`);
      setModified(false);
    } catch (err) {
      message.error(`Save failed: ${err.response?.data?.error || err.message}`);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={
        <Space>
          <span>📄 {filePath}</span>
          {modified && <Tag color="warning">Unsaved Changes</Tag>}
        </Space>
      }
      placement="right"
      width={720}
      onClose={onClose}
      visible={visible}
      extra={
        <Space>
          <Button icon={<HistoryOutlined />} onClick={() => onOpenSnapshots(filePath)}>
            Snapshots
          </Button>
          <Button icon={<ReloadOutlined />} onClick={fetchContent} loading={loading}>
            Refresh
          </Button>
          <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>
            Save
          </Button>
        </Space>
      }
    >
      {loading ? (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Spin size="large" />
        </div>
      ) : (
        <Input.TextArea
          value={content}
          onChange={(e) => {
            setContent(e.target.value);
            setModified(true);
          }}
          rows={28}
          className="code-editor-textarea"
          style={{ height: 'calc(100vh - 150px)', resize: 'none' }}
        />
      )}
    </Drawer>
  );
};
