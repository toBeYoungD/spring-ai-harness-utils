import React, { useState, useEffect, useRef } from 'react';
import {
  Layout,
  Input,
  Button,
  Space,
  Radio,
  Breadcrumb,
  Modal,
  Form,
  message,
  Tooltip,
  Badge
} from 'antd';
import {
  FolderOutlined,
  FileAddOutlined,
  ReloadOutlined,
  HistoryOutlined,
  AppstoreOutlined,
  UnorderedListOutlined,
  ArrowUpOutlined,
  SearchOutlined
} from '@ant-design/icons';
import { api } from '../services/api';
import { FileListTable } from './FileListTable';
import { FileGridCards } from './FileGridCards';
import { NewItemModal } from './NewItemModal';
import { FileViewerModal } from './FileViewerModal';
import { SnapshotDrawer } from './SnapshotDrawer';

const { Header, Content } = Layout;

export const FileExplorer = () => {
  // Mode & Auth State
  const [userAuth, setUserAuth] = useState('sys1-agent1-user1');

  // File Manager Navigation State
  const [currentPath, setCurrentPath] = useState('');
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [viewMode, setViewMode] = useState('list'); // 'list' | 'grid'

  // Modals & Drawers
  const [newModalVisible, setNewModalVisible] = useState(false);
  const [viewerVisible, setViewerVisible] = useState(false);
  const [selectedFilePath, setSelectedFilePath] = useState(null);
  const [snapshotDrawerVisible, setSnapshotDrawerVisible] = useState(false);
  const [snapshotTargetFile, setSnapshotTargetFile] = useState('');

  // Rename Modal State
  const [renameModalVisible, setRenameModalVisible] = useState(false);
  const [renameTarget, setRenameTarget] = useState(null);
  const [renameForm] = Form.useForm();

  // Track last fetched parameters to prevent duplicate mount/render calls
  const lastFetchRef = useRef({ path: null, auth: null });

  // Fetch File Items
  const fetchFiles = async (force = false) => {
    if (!force && lastFetchRef.current.path === currentPath && lastFetchRef.current.auth === userAuth) {
      return;
    }
    lastFetchRef.current = { path: currentPath, auth: userAuth };

    setLoading(true);
    try {
      const data = await api.listFiles(userAuth, currentPath);
      setItems(data || []);
    } catch (err) {
      message.error(`Failed to load files: ${err.response?.data?.error || err.message}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchFiles();
  }, [currentPath, userAuth]);

  // Navigation handlers
  const handleItemClick = (item) => {
    if (item.isDirectory) {
      setCurrentPath(item.path);
    } else {
      setSelectedFilePath(item.path);
      setViewerVisible(true);
    }
  };

  const handleNavigateUp = () => {
    if (!currentPath) return;
    const parts = currentPath.replace(/\/$/, '').split('/');
    parts.pop();
    setCurrentPath(parts.join('/'));
  };

  const handleBreadcrumbClick = (path) => {
    setCurrentPath(path);
  };

  // Move / Rename handler
  const handleMoveItem = async (fromPath, toPath) => {
    try {
      await api.moveFile(userAuth, fromPath, toPath);
      message.success(`Successfully moved '${fromPath}' to '${toPath}'`);
      fetchFiles(true);
    } catch (err) {
      message.error(`Move failed: ${err.response?.data?.error || err.message}`);
    }
  };

  // Delete handler
  const handleDelete = async (path, trash = true) => {
    try {
      await api.deleteFile(userAuth, path, trash);
      message.success(`Successfully deleted '${path}'`);
      fetchFiles(true);
    } catch (err) {
      message.error(`Delete failed: ${err.response?.data?.error || err.message}`);
    }
  };

  // Create New Item handler
  const handleCreateItem = async (path, content) => {
    await api.uploadFile(userAuth, path, content);
    fetchFiles(true);
  };

  // Rename modal submit
  const handleRenameSubmit = async () => {
    try {
      const values = await renameForm.validateFields();
      await handleMoveItem(renameTarget.path, values.newPath);
      setRenameModalVisible(false);
    } catch (err) {
      // Form validation error
    }
  };

  const openRenameModal = (item) => {
    setRenameTarget(item);
    renameForm.setFieldsValue({ newPath: item.path });
    setRenameModalVisible(true);
  };

  // Filtered items by search keyword
  const filteredItems = items.filter((item) =>
    item.path.toLowerCase().includes(searchKeyword.toLowerCase())
  );

  // Breadcrumb path builder
  const pathSegments = currentPath ? currentPath.split('/') : [];
  const breadcrumbItems = [
    {
      title: (
        <span
          className="explorer-breadcrumb-item"
          onClick={() => handleBreadcrumbClick('')}
          onDragOver={(e) => e.preventDefault()}
          onDrop={(e) => {
            e.preventDefault();
            const dragged = e.dataTransfer.getData('text/plain');
            if (dragged) {
              const fileName = dragged.split('/').pop();
              handleMoveItem(dragged, fileName);
            }
          }}
        >
          📁 root
        </span>
      )
    },
    ...pathSegments.map((seg, idx) => {
      const segmentPath = pathSegments.slice(0, idx + 1).join('/');
      return {
        title: (
          <span
            className={`explorer-breadcrumb-item ${idx === pathSegments.length - 1 ? 'active' : ''}`}
            onClick={() => handleBreadcrumbClick(segmentPath)}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              const dragged = e.dataTransfer.getData('text/plain');
              if (dragged && dragged !== segmentPath) {
                const fileName = dragged.split('/').pop();
                handleMoveItem(dragged, `${segmentPath}/${fileName}`);
              }
            }}
          >
            {seg}
          </span>
        )
      };
    })
  ];

  return (
    <Layout style={{ minHeight: '100vh', background: 'var(--bg-primary)' }}>
      {/* Top Header */}
      <Header
        style={{
          background: 'var(--bg-header-bar)',
          backdropFilter: 'blur(16px)',
          borderBottom: '1px solid var(--border-color)',
          padding: '0 24px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          lineHeight: 'normal'
        }}
      >
        <Space size="middle" align="center">
          <FolderOutlined style={{ fontSize: 24, color: '#38bdf8' }} />
          <span style={{ fontSize: 18, fontWeight: 700, color: 'var(--text-primary)' }}>
            Spring AI Harness OSS File Manager
          </span>
        </Space>

        <Space size="large" align="center">
          <Input
            addonBefore="Auth Header"
            value={userAuth}
            onChange={(e) => setUserAuth(e.target.value)}
            style={{ width: 280 }}
          />
        </Space>
      </Header>

      {/* Main Content */}
      <Content style={{ padding: 24 }}>
        <div className="glass-container" style={{ padding: 20 }}>
          {/* Toolbar */}
          <div className="explorer-toolbar" style={{ borderRadius: 8, marginBottom: 16 }}>
            <Space size="middle">
              <Button
                icon={<ArrowUpOutlined />}
                disabled={!currentPath}
                onClick={handleNavigateUp}
              >
                Up
              </Button>
              <Breadcrumb>
                {breadcrumbItems.map((it, i) => (
                  <Breadcrumb.Item key={i}>{it.title}</Breadcrumb.Item>
                ))}
              </Breadcrumb>
            </Space>

            <Space size="middle">
              <Input
                placeholder="Search files..."
                prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
                value={searchKeyword}
                onChange={(e) => setSearchKeyword(e.target.value)}
                allowClear
                style={{ width: 220 }}
              />

              <Radio.Group
                value={viewMode}
                onChange={(e) => setViewMode(e.target.value)}
                buttonStyle="solid"
              >
                <Radio.Button value="list">
                  <UnorderedListOutlined /> List
                </Radio.Button>
                <Radio.Button value="grid">
                  <AppstoreOutlined /> Grid
                </Radio.Button>
              </Radio.Group>

              <Button
                type="primary"
                icon={<FileAddOutlined />}
                onClick={() => setNewModalVisible(true)}
              >
                New Item
              </Button>

              <Badge count={0}>
                <Button
                  icon={<HistoryOutlined />}
                  onClick={() => {
                    setSnapshotTargetFile('');
                    setSnapshotDrawerVisible(true);
                  }}
                >
                  Snapshots
                </Button>
              </Badge>

              <Tooltip title="Refresh Directory">
                <Button icon={<ReloadOutlined />} onClick={fetchFiles} loading={loading} />
              </Tooltip>
            </Space>
          </div>

          {/* File View (List vs Grid) */}
          {viewMode === 'list' ? (
            <FileListTable
              items={filteredItems}
              loading={loading}
              onItemClick={handleItemClick}
              onRename={openRenameModal}
              onDelete={handleDelete}
              onOpenSnapshots={(path) => {
                setSnapshotTargetFile(path);
                setSnapshotDrawerVisible(true);
              }}
              onMoveItem={handleMoveItem}
            />
          ) : (
            <FileGridCards
              items={filteredItems}
              onItemClick={handleItemClick}
              onRename={openRenameModal}
              onDelete={handleDelete}
              onOpenSnapshots={(path) => {
                setSnapshotTargetFile(path);
                setSnapshotDrawerVisible(true);
              }}
              onMoveItem={handleMoveItem}
            />
          )}
        </div>
      </Content>

      {/* New Item Modal */}
      <NewItemModal
        visible={newModalVisible}
        currentPath={currentPath}
        onCancel={() => setNewModalVisible(false)}
        onCreate={handleCreateItem}
      />

      {/* File Editor / Viewer Modal */}
      <FileViewerModal
        visible={viewerVisible}
        filePath={selectedFilePath}
        authHeader={userAuth}
        onClose={() => setViewerVisible(false)}
        onOpenSnapshots={(path) => {
          setSnapshotTargetFile(path);
          setSnapshotDrawerVisible(true);
        }}
      />

      {/* Snapshot Drawer */}
      <SnapshotDrawer
        visible={snapshotDrawerVisible}
        filePath={snapshotTargetFile}
        authHeader={userAuth}
        onClose={() => setSnapshotDrawerVisible(false)}
        onRewindSuccess={() => fetchFiles(true)}
      />

      {/* Rename Modal */}
      <Modal
        title="Rename / Move Item"
        visible={renameModalVisible}
        onOk={handleRenameSubmit}
        onCancel={() => setRenameModalVisible(false)}
        okText="Save"
      >
        <Form form={renameForm} layout="vertical">
          <Form.Item
            name="newPath"
            label="Target Relative Path"
            rules={[{ required: true, message: 'Please enter target path' }]}
          >
            <Input placeholder="e.g. docs/README.md or new-folder/" />
          </Form.Item>
        </Form>
      </Modal>
    </Layout>
  );
};
