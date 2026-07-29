import React, { useState, useEffect, useRef } from 'react';
import {
  Layout,
  Card,
  Input,
  Button,
  Tabs,
  Space,
  Select,
  Typography,
  Table,
  Badge,
  Alert,
  message,
  Tooltip,
  Divider,
  Tag
} from 'antd';
import {
  BugOutlined,
  CloudSyncOutlined,
  PlayCircleOutlined,
  CodeOutlined,
  FileTextOutlined,
  CopyOutlined,
  ClearOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined
} from '@ant-design/icons';
import { Client } from '@modelcontextprotocol/client';
import { api } from '../services/api';

const { Content } = Layout;
const { Title, Text } = Typography;

export const McpDebugger = () => {
  // Config & State
  const [authHeader, setAuthHeader] = useState('sys1-agent1-user1');
  const [tools, setTools] = useState([]);
  const [resources, setResources] = useState([]);
  const [loadingTools, setLoadingTools] = useState(false);
  const [loadingResources, setLoadingResources] = useState(false);
  
  // Selected items & payloads
  const [selectedToolName, setSelectedToolName] = useState(null);
  const [toolArgsString, setToolArgsString] = useState('{}');
  const [loadingCall, setLoadingCall] = useState(false);
  
  // Inspector Data
  const [rpcRequest, setRpcRequest] = useState(null);
  const [rpcResponse, setRpcResponse] = useState(null);
  const [callTime, setCallTime] = useState(null);
  const [callStatus, setCallStatus] = useState(null); // 'success' | 'error' | null

  // Reference to the active MCP Client instance
  const clientRef = useRef(null);
  const [isInitialized, setIsInitialized] = useState(false);

  // Recreate and connect client when authHeader changes
  useEffect(() => {
    setIsInitialized(false);
    clientRef.current = null;
    
    // Custom transport wrapping stateless HTTP POST API
    const transport = {
      onclose: null,
      onerror: null,
      onmessage: null,
      start: async () => {},
      send: async (messagePayload) => {
        // Log request wire data
        setRpcRequest(messagePayload);
        setRpcResponse(null);
        setCallStatus(null);
        setCallTime(null);
        
        const startTime = Date.now();
        try {
          const responseData = await api.callMcp(authHeader, messagePayload);
          setCallTime(Date.now() - startTime);
          setRpcResponse(responseData);
          
          if (responseData.error) {
            setCallStatus('error');
          } else {
            setCallStatus('success');
          }
          
          if (transport.onmessage) {
            transport.onmessage(responseData);
          }
        } catch (error) {
          setCallTime(Date.now() - startTime);
          setCallStatus('error');
          const errResponse = error.response?.data || error.message;
          setRpcResponse(errResponse);
          if (transport.onerror) {
            transport.onerror(error);
          }
          throw error;
        }
      },
      close: async () => {}
    };

    const client = new Client(
      {
        name: 'spring-ai-harness-console',
        version: '1.0.0'
      },
      {
        capabilities: {}
      }
    );

    clientRef.current = client;

    const connectAndFetch = async () => {
      setLoadingTools(true);
      setLoadingResources(true);
      try {
        await client.connect(transport);
        setIsInitialized(true);
        await fetchTools(client);
        await fetchResources(client);
      } catch (err) {
        console.error('Failed to connect client or fetch lists:', err);
        message.error(`Client connection failed: ${err.message}`);
      } finally {
        setLoadingTools(false);
        setLoadingResources(false);
      }
    };

    connectAndFetch();
  }, [authHeader]);

  // Sync tool arguments default when selected tool changes
  useEffect(() => {
    if (!selectedToolName) return;
    const selectedTool = tools.find(t => t.name === selectedToolName);
    if (selectedTool && selectedTool.inputSchema) {
      const template = {};
      const props = selectedTool.inputSchema.properties || {};
      Object.keys(props).forEach(key => {
        template[key] = props[key].type === 'boolean' ? false : props[key].type === 'number' || props[key].type === 'integer' ? 0 : '';
      });
      setToolArgsString(JSON.stringify(template, null, 2));
    }
  }, [selectedToolName, tools]);

  const fetchTools = async (clientInstance) => {
    const client = clientInstance || clientRef.current;
    if (!client) {
      message.error('Client is not connected');
      return;
    }
    
    setLoadingTools(true);
    try {
      const response = await client.listTools();
      const toolsList = response.tools || [];
      setTools(toolsList);
      if (toolsList.length > 0) {
        setSelectedToolName(toolsList[0].name);
      } else {
        setSelectedToolName(null);
      }
    } catch (err) {
      console.error('Failed to list tools:', err);
      message.error(`Failed to list tools: ${err.message}`);
    } finally {
      setLoadingTools(false);
    }
  };

  const fetchResources = async (clientInstance) => {
    const client = clientInstance || clientRef.current;
    if (!client) return;
    
    setLoadingResources(true);
    try {
      const response = await client.listResources();
      setResources(response.resources || []);
    } catch (err) {
      console.error('Failed to list resources:', err);
    } finally {
      setLoadingResources(false);
    }
  };

  const handleCallTool = async () => {
    if (!selectedToolName) {
      message.warning('No tool selected');
      return;
    }

    let parsedArgs = {};
    try {
      parsedArgs = JSON.parse(toolArgsString);
    } catch (e) {
      message.error('Invalid JSON arguments format');
      return;
    }

    const client = clientRef.current;
    if (!client) {
      message.error('Client is not connected');
      return;
    }

    setLoadingCall(true);
    try {
      const result = await client.callTool({
        name: selectedToolName,
        arguments: parsedArgs
      });
      
      if (result.isError) {
        setCallStatus('error');
        message.error('Tool execution returned an error');
      } else {
        setCallStatus('success');
        message.success('Tool executed successfully');
      }
    } catch (err) {
      setCallStatus('error');
      message.error(`Tool execution failed: ${err.message}`);
    } finally {
      setLoadingCall(false);
    }
  };

  const handleReadResource = async (uri) => {
    const client = clientRef.current;
    if (!client) {
      message.error('Client is not connected');
      return;
    }

    setLoadingResources(true);
    try {
      await client.readResource({ uri });
      setCallStatus('success');
      message.success('Resource read successfully');
    } catch (err) {
      setCallStatus('error');
      message.error(`Failed to read resource: ${err.message}`);
    } finally {
      setLoadingResources(false);
    }
  };

  const handleFormatJson = () => {
    try {
      const parsed = JSON.parse(toolArgsString);
      setToolArgsString(JSON.stringify(parsed, null, 2));
    } catch (e) {
      message.error('Cannot format invalid JSON arguments');
    }
  };

  const copyToClipboard = (text) => {
    if (!text) return;
    navigator.clipboard.writeText(JSON.stringify(text, null, 2));
    message.success('Copied to clipboard!');
  };

  // Selected tool details
  const selectedTool = tools.find(t => t.name === selectedToolName);

  // Resources columns
  const resourceColumns = [
    {
      title: 'Name',
      dataIndex: 'name',
      key: 'name',
      render: (text) => <Text style={{ color: 'var(--text-primary)', fontWeight: 600 }}>{text}</Text>
    },
    {
      title: 'URI',
      dataIndex: 'uri',
      key: 'uri',
      render: (text) => <Text style={{ color: 'var(--text-code)', fontSize: '12px' }}>{text}</Text>
    },
    {
      title: 'Description',
      dataIndex: 'description',
      key: 'description',
      render: (text) => <Text style={{ color: 'var(--text-secondary)' }}>{text || '-'}</Text>
    },
    {
      title: 'Action',
      key: 'action',
      render: (_, record) => (
        <Button
          type="link"
          icon={<FileTextOutlined />}
          onClick={() => handleReadResource(record.uri)}
        >
          Read Content
        </Button>
      )
    }
  ];

  const tabItems = [
    {
      key: 'tools',
      label: (
        <span>
          <CodeOutlined /> Tools Call
        </span>
      ),
      children: (
        <Space direction="vertical" style={{ width: '100%' }} size="large">
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <Text style={{ color: 'var(--text-primary)', fontSize: '15px', fontWeight: 600 }}>Select MCP Tool</Text>
              <Button
                type="link"
                icon={<CloudSyncOutlined />}
                onClick={fetchTools}
                loading={loadingTools}
              >
                Sync Tools
              </Button>
            </div>
            
            <Select
              placeholder="No tools loaded. Click sync."
              value={selectedToolName}
              onChange={setSelectedToolName}
              style={{ width: '100%', marginBottom: 16 }}
              loading={loadingTools}
              options={tools.map(t => ({
                label: `${t.name} - ${t.description || ''}`,
                value: t.name
              }))}
            />

            {selectedTool && (
              <Card
                size="small"
                style={{
                  background: 'var(--bg-secondary)',
                  borderColor: 'var(--border-color)',
                  marginBottom: 16
                }}
              >
                <div style={{ marginBottom: 8 }}>
                  <Text type="secondary">Description: </Text>
                  <Text style={{ color: 'var(--text-primary)' }}>{selectedTool.description}</Text>
                </div>
                <div>
                  <Text type="secondary">Input Properties: </Text>
                  <ul>
                    {Object.entries(selectedTool.inputSchema?.properties || {}).map(([key, val]) => (
                      <li key={key}>
                        <Text style={{ color: 'var(--text-primary)', fontWeight: 600 }}>{key}</Text> 
                        <Text style={{ color: 'var(--text-code)', fontSize: '12px' }}> ({val.type})</Text>
                        {selectedTool.inputSchema?.required?.includes(key) && (
                          <Tag color="error" style={{ marginLeft: 6, transform: 'scale(0.85)' }}>Required</Tag>
                        )}
                        {val.description && (
                          <div style={{ color: 'var(--text-secondary)', fontSize: '12px' }}>{val.description}</div>
                        )}
                      </li>
                    ))}
                  </ul>
                </div>
              </Card>
            )}
          </div>

          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <Text style={{ color: 'var(--text-primary)', fontSize: '15px', fontWeight: 600 }}>Arguments (JSON-RPC params)</Text>
              <Space>
                <Button size="small" icon={<ClearOutlined />} onClick={() => setToolArgsString('{}')}>Clear</Button>
                <Button size="small" type="dashed" onClick={handleFormatJson}>Format JSON</Button>
              </Space>
            </div>
            
            <Input.TextArea
              value={toolArgsString}
              onChange={(e) => setToolArgsString(e.target.value)}
              rows={8}
              placeholder='e.g. {"path": "docs/README.md"}'
              style={{
                fontFamily: 'monospace',
                background: 'var(--bg-code)',
                color: 'var(--text-code)',
                borderColor: 'var(--border-color)',
                borderRadius: 6
              }}
            />
            
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              onClick={handleCallTool}
              loading={loadingCall}
              disabled={!selectedToolName}
              style={{ marginTop: 16, width: '100%' }}
            >
              Call Tool {selectedToolName ? `"${selectedToolName}"` : ''}
            </Button>
          </div>
        </Space>
      )
    },
    {
      key: 'resources',
      label: (
        <span>
          <FileTextOutlined /> Resources Browser
        </span>
      ),
      children: (
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Text style={{ color: 'var(--text-primary)', fontSize: '15px', fontWeight: 600 }}>Exposed Resources</Text>
            <Button
              type="link"
              icon={<CloudSyncOutlined />}
              onClick={fetchResources}
              loading={loadingResources}
            >
              Sync Resources
            </Button>
          </div>
          
          <Table
            dataSource={resources}
            columns={resourceColumns}
            loading={loadingResources}
            rowKey="uri"
            size="small"
            pagination={false}
            style={{ background: 'transparent' }}
            className="dark-table"
          />
        </Space>
      )
    }
  ];

  return (
    <Layout style={{ minHeight: 'calc(100vh - 64px)', background: 'var(--bg-primary)' }}>
      <Content style={{ padding: 24 }}>
        <div style={{ maxWidth: 1400, margin: '0 auto' }}>
          
          {/* Top Authentication Card */}
          <Card
            className="glass-container"
            style={{ marginBottom: 20, borderColor: 'var(--border-color)' }}
          >
            <Space size="large" wrap>
              <Space>
                <BugOutlined style={{ fontSize: 24, color: '#3b82f6' }} />
                <Title level={4} style={{ margin: 0, color: 'var(--text-primary)' }}>
                  MCP Client Debugger
                </Title>
              </Space>
              <Divider type="vertical" style={{ background: 'var(--border-color)', height: 24 }} />
              <Input
                addonBefore="Workspace Authorization"
                value={authHeader}
                onChange={(e) => setAuthHeader(e.target.value)}
                placeholder="Bearer system-agent-user"
                style={{ width: 340 }}
              />
              <Tooltip title="Tools and Resources list auto-refreshes when changing auth context">
                <Tag color="blue">HTTP Stateless Transport</Tag>
              </Tooltip>
            </Space>
          </Card>

          {/* Core Debugger Panel (2-column layout) */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
            
            {/* Left Card: Controllers & Interactive Inputs */}
            <Card
              className="glass-container"
              style={{ minHeight: 650, borderColor: 'var(--border-color)' }}
            >
              <Tabs defaultActiveKey="tools">
                {tabItems.map(t => (
                  <Tabs.TabPane key={t.key} tab={t.label}>{t.children}</Tabs.TabPane>
                ))}
              </Tabs>
            </Card>

            {/* Right Card: JSON-RPC Wire Inspector */}
            <Card
              className="glass-container"
              title={
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' }}>
                  <span style={{ color: 'var(--text-primary)', fontWeight: 600 }}>JSON-RPC Wire Inspector</span>
                  <Space>
                    {callStatus === 'success' && (
                      <Badge status="success" text={<Text style={{ color: '#4ade80' }}>Success ({callTime}ms)</Text>} />
                    )}
                    {callStatus === 'error' && (
                      <Badge status="error" text={<Text style={{ color: '#f87171' }}>Failed ({callTime}ms)</Text>} />
                    )}
                  </Space>
                </div>
              }
              style={{ minHeight: 650, borderColor: 'var(--border-color)' }}
            >
              <Space direction="vertical" style={{ width: '100%' }} size="middle">
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                    <Text style={{ color: 'var(--text-primary)', fontWeight: 500 }}>Sent Request Payload</Text>
                    <Button
                      size="small"
                      type="text"
                      icon={<CopyOutlined />}
                      disabled={!rpcRequest}
                      onClick={() => copyToClipboard(rpcRequest)}
                      style={{ color: 'var(--text-secondary)' }}
                    >
                      Copy
                    </Button>
                  </div>
                  <pre
                    style={{
                      padding: 12,
                      background: 'var(--bg-code)',
                      color: 'var(--text-secondary)',
                      border: '1px solid var(--border-color)',
                      borderRadius: 6,
                      maxHeight: 220,
                      overflowY: 'auto',
                      fontSize: '13px',
                      fontFamily: 'monospace'
                    }}
                  >
                    {rpcRequest ? (
                      <code style={{ color: '#10b981' }}>{JSON.stringify(rpcRequest, null, 2)}</code>
                    ) : (
                      <span style={{ color: 'var(--text-secondary)', fontStyle: 'italic' }}>No request sent yet. Sync tools or make a tool call.</span>
                    )}
                  </pre>
                </div>

                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                    <Text style={{ color: 'var(--text-primary)', fontWeight: 500 }}>Received Response Payload</Text>
                    <Button
                      size="small"
                      type="text"
                      icon={<CopyOutlined />}
                      disabled={!rpcResponse}
                      onClick={() => copyToClipboard(rpcResponse)}
                      style={{ color: 'var(--text-secondary)' }}
                    >
                      Copy
                    </Button>
                  </div>
                  <pre
                    style={{
                      padding: 12,
                      background: 'var(--bg-code)',
                      color: 'var(--text-secondary)',
                      border: '1px solid var(--border-color)',
                      borderRadius: 6,
                      maxHeight: 320,
                      overflowY: 'auto',
                      fontSize: '13px',
                      fontFamily: 'monospace'
                    }}
                  >
                    {rpcResponse ? (
                      <code style={{ color: callStatus === 'error' ? '#ef4444' : '#60a5fa' }}>
                        {JSON.stringify(rpcResponse, null, 2)}
                      </code>
                    ) : (
                      <span style={{ color: 'var(--text-secondary)', fontStyle: 'italic' }}>Waiting for response...</span>
                    )}
                  </pre>
                </div>

                {rpcResponse && rpcResponse.result && rpcResponse.result.content && (
                  <div>
                    <Text style={{ color: 'var(--text-primary)', fontWeight: 500, display: 'block', marginBottom: 6 }}>
                      Execution Result Output (Text)
                    </Text>
                    <Alert
                      message={
                        <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: '13px' }}>
                          {rpcResponse.result.content.map((c, i) => c.text).join('\n')}
                        </pre>
                      }
                      type={callStatus === 'error' ? 'error' : 'info'}
                      showIcon
                      icon={callStatus === 'error' ? <CloseCircleOutlined /> : <CheckCircleOutlined />}
                      style={{ background: 'var(--bg-secondary)', border: 'none' }}
                    />
                  </div>
                )}
              </Space>
            </Card>

          </div>
        </div>
      </Content>
    </Layout>
  );
};
