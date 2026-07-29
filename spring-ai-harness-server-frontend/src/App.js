import React, { useState, useEffect } from 'react';
import { ConfigProvider, Layout, Button } from 'antd';
import { FileExplorer } from './components/FileExplorer';
import { McpDebugger } from './components/McpDebugger';
import QuotaConsole from './components/QuotaConsole';
import AuditConsole from './components/AuditConsole';
import {
  FolderOpenOutlined,
  BugOutlined,
  DatabaseOutlined,
  AuditOutlined,
  SunOutlined,
  MoonOutlined
} from '@ant-design/icons';
import './styles/console-light.css';

const { Content } = Layout;

function App() {
  const [activeTab, setActiveTab] = useState('files');
  const [themeMode, setThemeMode] = useState(() => {
    return localStorage.getItem('theme-mode') || 'dark';
  });

  const toggleTheme = () => {
    const nextTheme = themeMode === 'dark' ? 'light' : 'dark';
    setThemeMode(nextTheme);
    localStorage.setItem('theme-mode', nextTheme);
  };

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', themeMode);
  }, [themeMode]);

  const renderContent = () => {
    switch (activeTab) {
      case 'mcp':
        return <McpDebugger />;
      case 'quota':
        return <QuotaConsole />;
      case 'audit':
        return <AuditConsole />;
      case 'files':
      default:
        return <FileExplorer />;
    }
  };

  return (
    <ConfigProvider>
      <Layout style={{ minHeight: '100vh', background: 'var(--bg-primary)' }}>
        {/* Sticky Global Navigation Bar */}
        <div style={{
          background: 'var(--bg-navbar)',
          backdropFilter: 'blur(12px)',
          borderBottom: '1px solid var(--border-color)',
          padding: '12px 24px',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          position: 'sticky',
          top: 0,
          zIndex: 1000
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 32 }}>
            <span style={{ fontSize: 16, fontWeight: 700, color: 'var(--text-navbar)', letterSpacing: '0.5px' }}>
              Spring AI Harness Console
            </span>
            <div style={{ display: 'flex', gap: 8 }}>
              <Button
                type={activeTab === 'files' ? 'primary' : 'text'}
                icon={<FolderOpenOutlined />}
                onClick={() => setActiveTab('files')}
                style={{
                  fontWeight: 600,
                  borderRadius: 6,
                  color: activeTab === 'files' ? undefined : 'var(--text-secondary)'
                }}
              >
                File Manager
              </Button>
              <Button
                type={activeTab === 'mcp' ? 'primary' : 'text'}
                icon={<BugOutlined />}
                onClick={() => setActiveTab('mcp')}
                style={{
                  fontWeight: 600,
                  borderRadius: 6,
                  color: activeTab === 'mcp' ? undefined : 'var(--text-secondary)'
                }}
              >
                MCP Client Debugger
              </Button>
              <Button
                type={activeTab === 'quota' ? 'primary' : 'text'}
                icon={<DatabaseOutlined />}
                onClick={() => setActiveTab('quota')}
                style={{
                  fontWeight: 600,
                  borderRadius: 6,
                  color: activeTab === 'quota' ? undefined : 'var(--text-secondary)'
                }}
              >
                配额管理
              </Button>
              <Button
                type={activeTab === 'audit' ? 'primary' : 'text'}
                icon={<AuditOutlined />}
                onClick={() => setActiveTab('audit')}
                style={{
                  fontWeight: 600,
                  borderRadius: 6,
                  color: activeTab === 'audit' ? undefined : 'var(--text-secondary)'
                }}
              >
                日志审计
              </Button>
            </div>
          </div>
          {/* Theme Toggle Button */}
          <div>
            <Button
              type="text"
              icon={themeMode === 'dark' ? <SunOutlined style={{ color: '#eab308' }} /> : <MoonOutlined style={{ color: '#4f46e5' }} />}
              onClick={toggleTheme}
              style={{ fontSize: 16 }}
            />
          </div>
        </div>

        {/* Active Workspace/Debugger View */}
        <Content style={{ minHeight: 'calc(100vh - 57px)' }}>
          {renderContent()}
        </Content>
      </Layout>
    </ConfigProvider>
  );
}

export default App;
