import React, { useState, useMemo } from 'react';
import {
  Button, Input, Select, Tag, Tabs, Drawer, InputNumber, Switch, Space,
  Card, Statistic, Table, Row, Col, Divider, Tooltip, message
} from 'antd';
import {
  SettingOutlined, ReloadOutlined, FileTextOutlined, InfoCircleOutlined, EyeOutlined,
  AuditOutlined, SafetyOutlined, ToolOutlined, DatabaseOutlined, ThunderboltOutlined,
  StopOutlined, ClockCircleOutlined, CloseCircleOutlined
} from '@ant-design/icons';

// TODO: 后端就绪后改用 services/api.js（listBehaviorLogs / listOperationLogs / 导出），当前为 mock 数据。
// ===== 行为日志 mock =====
const BEHAVIOR_LOGS = [
  { traceId:'tr-001', time:'2026-07-28 14:23:05', system:'openclaw', agent:'code-assistant', user:'alice', tool:'read_file', category:'file', args:{path:'src/Main.java'}, duration:42, status:'success', result:{lines:128, truncated:false} },
  { traceId:'tr-002', time:'2026-07-28 14:23:18', system:'openclaw', agent:'code-assistant', user:'alice', tool:'write_file', category:'file', args:{path:'src/Main.java', size:2048}, duration:88, status:'success', snapshot:'snap-20260728-142318' },
  { traceId:'tr-003', time:'2026-07-28 14:24:02', system:'openclaw', agent:'code-assistant', user:'bob', tool:'run_shell_command', category:'relay', args:{command:'npm install'}, duration:12450, status:'success', result:{exitCode:0} },
  { traceId:'tr-004', time:'2026-07-28 14:24:30', system:'openclaw', agent:'code-assistant', user:'bob', tool:'run_shell_command', category:'relay', args:{command:'rm -rf /'}, duration:3, status:'denied', error:'PERMISSION_DENIED: 命令命中黑名单' },
  { traceId:'tr-005', time:'2026-07-28 14:25:11', system:'hermes', agent:'research', user:'charlie', tool:'browser_navigate', category:'relay', args:{url:'https://example.com'}, duration:320, status:'success', result:{title:'Example'} },
  { traceId:'tr-006', time:'2026-07-28 14:25:44', system:'qwenpaw', agent:'coder', user:'dave', tool:'edit_file', category:'file', args:{path:'config.yml', oldStr:'timeout: 30', newStr:'timeout: 60'}, duration:55, status:'success', snapshot:'snap-20260728-142544' },
  { traceId:'tr-007', time:'2026-07-28 14:26:20', system:'qwenpaw', agent:'coder', user:'dave', tool:'read_file', category:'file', args:{path:'../../../etc/passwd'}, duration:2, status:'denied', error:'PERMISSION_DENIED: 路径越界' },
  { traceId:'tr-008', time:'2026-07-28 14:26:58', system:'hermes', agent:'research', user:'eve', tool:'list_directory', category:'file', args:{path:'docs/'}, duration:18, status:'success', result:{entries:12} },
  { traceId:'tr-009', time:'2026-07-28 14:27:30', system:'openclaw', agent:'code-assistant', user:'frank', tool:'write_file', category:'file', args:{path:'build/output.jar', size:5242880}, duration:210, status:'fail', error:'QUOTA_EXCEEDED: 工作区配额不足' },
  { traceId:'tr-010', time:'2026-07-28 14:28:12', system:'qwenpaw', agent:'coder', user:'grace', tool:'list_skills', category:'skill', args:{}, duration:12, status:'success', result:{skills:5} },
  { traceId:'tr-011', time:'2026-07-28 14:28:45', system:'hermes', agent:'analyst', user:'heidi', tool:'read_file', category:'file', args:{path:'report.pdf', pageRange:'1-5'}, duration:340, status:'success', result:{pages:5} },
  { traceId:'tr-012', time:'2026-07-28 14:29:05', system:'openclaw', agent:'code-assistant', user:'alice', tool:'run_ipython_cell', category:'relay', args:{code:'import pandas as pd'}, duration:890, status:'success', result:{stdout:''} },
  { traceId:'tr-013', time:'2026-07-28 14:29:40', system:'hermes', agent:'research', user:'charlie', tool:'browser_file_upload', category:'relay', args:{size:12000000}, duration:5, status:'denied', error:'PERMISSION_DENIED: 上传文件超限' },
  { traceId:'tr-014', time:'2026-07-28 14:30:15', system:'qwenpaw', agent:'coder', user:'dave', tool:'trash_file', category:'file', args:{path:'old/scratch.txt'}, duration:30, status:'success', snapshot:'snap-20260728-143015' },
  { traceId:'tr-015', time:'2026-07-28 14:30:48', system:'openclaw', agent:'code-assistant', user:'frank', tool:'grep', category:'file', args:{pattern:'TODO', path:'src/'}, duration:120, status:'success', result:{matches:7} },
];

// ===== 管理员操作日志 mock =====
const OPERATION_LOGS = [
  { traceId:'op-001', time:'2026-07-28 10:05:22', admin:'admin-7f3a', ip:'10.0.1.5', action:'SET_TOOL_PERMISSION', actionLabel:'权限变更', target:'qwenpaw-coder-dave', summary:'run_shell_command: 允许->拒绝', before:{run_shell_command:'allow'}, after:{run_shell_command:'deny'}, result:'success' },
  { traceId:'op-002', time:'2026-07-28 10:12:08', admin:'admin-7f3a', ip:'10.0.1.5', action:'SET_QUOTA', actionLabel:'配额调整', target:'hermes-research-charlie', summary:'上限 2GB->3GB', before:{limitBytes:2147483648, custom:true}, after:{limitBytes:3221225472, custom:true}, result:'success' },
  { traceId:'op-003', time:'2026-07-28 10:30:45', admin:'admin-2b9c', ip:'10.0.1.8', action:'CROSS_WORKSPACE_COPY', actionLabel:'跨空间文件', target:'openclaw-alice -> hermes-eve', summary:'docs/spec.md 跨空间复制', before:{}, after:{copied:true, bytes:4096}, result:'success' },
  { traceId:'op-004', time:'2026-07-28 11:02:13', admin:'admin-7f3a', ip:'10.0.1.5', action:'REWIND', actionLabel:'快照回滚', target:'openclaw-alice', summary:'src/Main.java 回滚至 snap-20260728-091200', before:{}, after:{rewound:true}, result:'success' },
  { traceId:'op-005', time:'2026-07-28 11:15:30', admin:'admin-5e1d', ip:'10.0.2.3', action:'SET_TOOL_PERMISSION', actionLabel:'权限变更', target:'openclaw-code-assistant-bob', summary:'browser_navigate: 拒绝->允许', before:{browser_navigate:'deny'}, after:{browser_navigate:'allow'}, result:'success' },
  { traceId:'op-006', time:'2026-07-28 11:40:02', admin:'admin-2b9c', ip:'10.0.1.8', action:'SET_QUOTA', actionLabel:'配额调整', target:'qwenpaw-coder-grace', summary:'上限回退全局默认', before:{limitBytes:3221225472, custom:true}, after:{custom:false}, result:'success' },
  { traceId:'op-007', time:'2026-07-28 12:05:18', admin:'admin-7f3a', ip:'10.0.1.5', action:'CROSS_WORKSPACE_DELETE', actionLabel:'跨空间文件', target:'openclaw-frank', summary:'.trash/ 清理 14 项', before:{}, after:{purged:14}, result:'success' },
  { traceId:'op-008', time:'2026-07-28 12:22:44', admin:'admin-5e1d', ip:'10.0.2.3', action:'SET_TOOL_PERMISSION', actionLabel:'权限变更', target:'hermes-analyst-heidi', summary:'computer: 允许->拒绝', before:{computer:'allow'}, after:{computer:'deny'}, result:'success' },
];

function StatusPill({ status }){
  const map = {
    success: { cls:'is-normal', text:'成功' },
    fail:    { cls:'is-over',   text:'失败' },
    denied:  { cls:'is-warn',   text:'权限拒绝' },
  };
  const c = map[status] || { cls:'is-info', text:status };
  return <span className={'status-pill '+c.cls}><span className="dot"/>{c.text}</span>;
}
function OpResultPill({ r }){
  return r==='success'
    ? <span className="status-pill is-normal"><span className="dot"/>成功</span>
    : <span className="status-pill is-over"><span className="dot"/>失败</span>;
}
function CategoryTag({ cat }){
  const label = { file:'文件', skill:'技能', relay:'中继' };
  return <span className={'cat-tag '+cat}>{label[cat] || cat}</span>;
}
function JsonBlock({ obj, title }){
  return <div style={{marginBottom:12}}>
    {title && <div className="section-label" style={{marginBottom:6}}>{title}</div>}
    <pre className="json-block">{JSON.stringify(obj,null,2)}</pre>
  </div>;
}

function BehaviorDetailDrawer({ visible, rec, onClose }){
  if(!rec) return <Drawer className="console-drawer" visible={false} onClose={onClose} width={620}/>;
  const identity = `${rec.system}-${rec.agent}-${rec.user}`;
  return (
    <Drawer className="console-drawer" visible={visible} onClose={onClose} title={`行为日志详情 · ${rec.traceId}`} width={620}>
      <div className="drawer-section" style={{display:'flex',alignItems:'center',justifyContent:'space-between'}}>
        <div>
          <div className="section-label" style={{marginBottom:6}}>调用身份</div>
          <div className="kv">system: <b>{rec.system}</b>　agent: <b>{rec.agent}</b>　user: <b>{rec.user}</b></div>
        </div>
        <StatusPill status={rec.status}/>
      </div>
      <div className="section-label">OSS prefix</div>
      <span className="prefix-chip"><code>mcp/workspaces/{identity}/</code></span>

      <Row gutter={16} style={{marginTop:18,marginBottom:8}}>
        <Col span={8}><Statistic title="工具" value={rec.tool}/></Col>
        <Col span={8}><Statistic title="分类" value={rec.category==='file'?'文件':rec.category==='skill'?'技能':'中继'}/></Col>
        <Col span={8}><Statistic title="耗时" value={rec.duration} suffix="ms"/></Col>
      </Row>
      <div style={{color:'var(--text-3)',fontSize:12,margin:'10px 0 16px'}}>调用时间: {rec.time}</div>
      <Divider style={{margin:'12px 0'}}/>
      <JsonBlock obj={rec.args} title="入参"/>
      {rec.status==='success'
        ? <JsonBlock obj={rec.result || {}} title="出参"/>
        : <JsonBlock obj={{error:rec.error}} title="错误"/>}
      {rec.snapshot && <div style={{marginTop:8}}>
        <span className="section-label" style={{marginRight:6}}>关联快照:</span>
        <span className="prefix-chip"><code>{rec.snapshot}</code></span>
        <span style={{color:'var(--text-3)',fontSize:12,marginLeft:8}}>破坏性操作前置快照</span>
      </div>}
      <div className="help-text" style={{marginTop:16}}><InfoCircleOutlined /> traceId: <code>{rec.traceId}</code></div>
    </Drawer>
  );
}

function OperationDetailDrawer({ visible, rec, onClose }){
  if(!rec) return <Drawer className="console-drawer" visible={false} onClose={onClose} width={620}/>;
  return (
    <Drawer className="console-drawer" visible={visible} onClose={onClose} title={`操作日志详情 · ${rec.traceId}`} width={620}>
      <div className="drawer-section" style={{display:'flex',alignItems:'center',justifyContent:'space-between'}}>
        <div>
          <div className="section-label" style={{marginBottom:6}}>管理员</div>
          <div className="kv"><b>{rec.admin}</b>　来源 IP: <b>{rec.ip}</b></div>
        </div>
        <OpResultPill r={rec.result}/>
      </div>
      <div className="section-label">目标</div>
      <span className="prefix-chip"><code>{rec.target}</code></span>

      <Row gutter={16} style={{marginTop:18,marginBottom:8}}>
        <Col span={12}><Statistic title="操作类型" value={rec.actionLabel}/></Col>
        <Col span={12}><Statistic title="操作时间" value={rec.time}/></Col>
      </Row>
      <div style={{color:'var(--text-3)',fontSize:12,margin:'10px 0 16px'}}>变更摘要: {rec.summary}</div>
      <Divider style={{margin:'12px 0'}}/>
      <JsonBlock obj={rec.before} title="变更前"/>
      <JsonBlock obj={rec.after} title="变更后"/>
      <div className="help-text" style={{marginTop:8}}><InfoCircleOutlined /> traceId: <code>{rec.traceId}</code></div>
    </Drawer>
  );
}

function AuditConfigDrawer({ visible, onClose }){
  const [enabled,setEnabled]=useState(true);
  const [retain,setRetain]=useState(30);
  const [mask,setMask]=useState(true);
  const [batch,setBatch]=useState(100);
  return (
    <Drawer className="console-drawer" visible={visible} onClose={onClose} title="审计配置" width={440}
      extra={<Button type="primary" onClick={()=>{message.success('审计配置已保存');onClose();}}>保存</Button>}>
      <div style={{marginBottom:20}}>
        <div className="section-label">审计开关（零开销可插拔）</div>
        <span><Switch size="small" checked={enabled} onChange={setEnabled}/> enabled</span>
        <div className="help-text" style={{marginTop:6}}><InfoCircleOutlined /> 关闭后 @McpTool 切面不记录，热路径无运行时开销</div>
      </div>
      <Divider style={{margin:'12px 0'}}/>
      <div style={{marginBottom:20}}>
        <div className="section-label">留存周期</div>
        <Space>每 <InputNumber value={retain} min={1} onChange={setRetain} style={{width:90}}/> 天</Space>
        <div className="help-text" style={{marginTop:6}}><InfoCircleOutlined /> 按天分区存储，过期分区自动清理</div>
      </div>
      <Divider style={{margin:'12px 0'}}/>
      <div style={{marginBottom:20}}>
        <div className="section-label">敏感参数脱敏</div>
        <span><Switch size="small" checked={mask} onChange={setMask}/> mask sensitive args</span>
        <div className="help-text" style={{marginTop:6}}><InfoCircleOutlined /> 对 Authorization、文件内容等做脱敏/截断</div>
      </div>
      <Divider style={{margin:'12px 0'}}/>
      <div style={{marginBottom:14}}>
        <div className="section-label">异步批量写入</div>
        <Space>每 <InputNumber value={batch} min={10} step={10} onChange={setBatch} style={{width:90}}/> 条一批落盘</Space>
        <div className="help-text" style={{marginTop:6}}><InfoCircleOutlined /> 非阻塞热路径，落盘失败仅告警不影响工具调用</div>
      </div>
    </Drawer>
  );
}

function BehaviorLogView({ onOpenDetail }){
  const [search,setSearch]=useState('');
  const [cat,setCat]=useState('all');
  const [statusF,setStatusF]=useState('all');
  const [win,setWin]=useState('today');

  const filtered = useMemo(()=> BEHAVIOR_LOGS.filter(r=>{
    const id = `${r.system}-${r.agent}-${r.user}`;
    if(search && !id.toLowerCase().includes(search.toLowerCase()) && !r.tool.toLowerCase().includes(search.toLowerCase())) return false;
    if(cat!=='all' && r.category!==cat) return false;
    if(statusF!=='all' && r.status!==statusF) return false;
    return true;
  }),[search,cat,statusF]);

  const columns = [
    { title:'时间', dataIndex:'time', width:160 },
    { title:'身份', width:230,
      render:(_,r)=><div className="ws-cell"><code className="ws-id">{r.system}-{r.agent}-{r.user}</code><div className="ws-sub">{r.system} · {r.agent} · {r.user}</div></div> },
    { title:'工具', width:180,
      render:(_,r)=><Space size={8}><code>{r.tool}</code><CategoryTag cat={r.category}/></Space> },
    { title:'入参摘要',
      render:(_,r)=>{ const s=JSON.stringify(r.args); return <code style={{color:'var(--text-2)'}}>{s.length>46?s.slice(0,46)+'…':s}</code>; } },
    { title:'耗时', width:90, render:(_,r)=><span>{r.duration}ms</span> },
    { title:'状态', width:120, render:(_,r)=><StatusPill status={r.status}/> },
    { title:'操作', width:90, align:'right',
      render:(_,r)=><div className="row-actions" onClick={e=>e.stopPropagation()}>
        <Tooltip title="查看详情"><Button type="text" size="small" icon={<EyeOutlined />} onClick={()=>onOpenDetail(r)}/></Tooltip>
      </div> },
  ];

  const statCards = [
    { label:'今日调用', value:'1,284', sub:'全部工具调用', tone:'blue', Icon:ThunderboltOutlined, vc:'' },
    { label:'失败', value:'41', sub:'失败率 3.2%', tone:'over', Icon:CloseCircleOutlined, vc:'tone-over' },
    { label:'权限拒绝', value:'17', sub:'工具调用被拦截', tone:'warn', Icon:StopOutlined, vc:'tone-warn' },
    { label:'平均耗时', value:'86', sub:'ms / 调用', tone:'green', Icon:ClockCircleOutlined, vc:'tone-green' },
  ];

  return <div>
    <div className="stat-grid">
      {statCards.map(c=>(
        <div key={c.label} className="stat-card">
          <div className="stat-top"><span className="stat-label">{c.label}</span><span className="stat-icon" data-tone={c.tone}><c.Icon /></span></div>
          <div className={'stat-value '+c.vc}>{c.value}</div>
          <div className="stat-sub">{c.sub}</div>
        </div>
      ))}
    </div>

    <div className="toolbar">
      <Input.Search placeholder="搜索身份 / 工具名" value={search} onChange={e=>setSearch(e.target.value)} style={{width:240}} size="small" allowClear/>
      <Select value={cat} onChange={setCat} size="small" style={{width:120}}
        options={[{value:'all',label:'全部分类'},{value:'file',label:'文件'},{value:'skill',label:'技能'},{value:'relay',label:'中继'}]}/>
      <Select value={statusF} onChange={setStatusF} size="small" style={{width:130}}
        options={[{value:'all',label:'全部状态'},{value:'success',label:'成功'},{value:'fail',label:'失败'},{value:'denied',label:'权限拒绝'}]}/>
      <Select value={win} onChange={setWin} size="small" style={{width:120}}
        options={[{value:'1h',label:'近 1 小时'},{value:'today',label:'今日'},{value:'7d',label:'近 7 天'},{value:'30d',label:'近 30 天'}]}/>
      <span style={{color:'var(--text-3)',fontSize:12}}><InfoCircleOutlined /> 行为日志由 @McpTool 切面记录，覆盖文件/技能/中继全部工具</span>
    </div>

    <Card className="glass harness-table" bodyStyle={{padding:0}} bordered={false}>
      <Table rowKey="traceId" dataSource={filtered} columns={columns} pagination={{pageSize:6,size:'small',showTotal:t=>`共 ${t} 条`}}
        size="middle" scroll={{x:'max-content'}}
        onRow={r=>({onClick:()=>onOpenDetail(r)})} style={{cursor:'pointer',background:'transparent'}}/>
    </Card>
  </div>;
}

function OperationLogView({ onOpenDetail }){
  const [search,setSearch]=useState('');
  const [actionF,setActionF]=useState('all');
  const [win,setWin]=useState('today');

  const filtered = useMemo(()=> OPERATION_LOGS.filter(r=>{
    if(search && !r.admin.toLowerCase().includes(search.toLowerCase()) && !r.target.toLowerCase().includes(search.toLowerCase())) return false;
    if(actionF!=='all' && r.action!==actionF) return false;
    return true;
  }),[search,actionF]);

  const columns = [
    { title:'时间', dataIndex:'time', width:160 },
    { title:'管理员', width:120, render:(_,r)=><code className="ws-id">{r.admin}</code> },
    { title:'操作类型', width:110, render:(_,r)=><Tag color={r.action.startsWith('CROSS')?'geekblue':r.action==='SET_QUOTA'?'gold':'volcano'} style={{borderRadius:4}}>{r.actionLabel}</Tag> },
    { title:'目标', render:(_,r)=><code style={{color:'var(--text-2)'}}>{r.target}</code> },
    { title:'变更摘要', render:(_,r)=><span style={{color:'var(--text-2)'}}>{r.summary}</span> },
    { title:'结果', width:100, render:(_,r)=><OpResultPill r={r.result}/> },
    { title:'操作', width:90, align:'right',
      render:(_,r)=><div className="row-actions" onClick={e=>e.stopPropagation()}>
        <Tooltip title="查看详情"><Button type="text" size="small" icon={<EyeOutlined />} onClick={()=>onOpenDetail(r)}/></Tooltip>
      </div> },
  ];

  const statCards = [
    { label:'今日操作', value:'56', sub:'全部管理操作', tone:'blue', Icon:AuditOutlined, vc:'' },
    { label:'涉及管理员', value:'4', sub:'不同管理员账号', tone:'purple', Icon:SafetyOutlined, vc:'tone-purple' },
    { label:'权限变更', value:'12', sub:'工具权限调整', tone:'warn', Icon:ToolOutlined, vc:'tone-warn' },
    { label:'配额调整', value:'8', sub:'空间上限调整', tone:'green', Icon:DatabaseOutlined, vc:'tone-green' },
  ];

  return <div>
    <div className="stat-grid">
      {statCards.map(c=>(
        <div key={c.label} className="stat-card">
          <div className="stat-top"><span className="stat-label">{c.label}</span><span className="stat-icon" data-tone={c.tone}><c.Icon /></span></div>
          <div className={'stat-value '+c.vc}>{c.value}</div>
          <div className="stat-sub">{c.sub}</div>
        </div>
      ))}
    </div>

    <div className="toolbar">
      <Input.Search placeholder="搜索管理员 / 目标" value={search} onChange={e=>setSearch(e.target.value)} style={{width:240}} size="small" allowClear/>
      <Select value={actionF} onChange={setActionF} size="small" style={{width:140}}
        options={[{value:'all',label:'全部类型'},{value:'SET_TOOL_PERMISSION',label:'权限变更'},{value:'SET_QUOTA',label:'配额调整'},{value:'CROSS_WORKSPACE_COPY',label:'跨空间复制'},{value:'CROSS_WORKSPACE_DELETE',label:'跨空间删除'},{value:'REWIND',label:'快照回滚'}]}/>
      <Select value={win} onChange={setWin} size="small" style={{width:120}}
        options={[{value:'today',label:'今日'},{value:'7d',label:'近 7 天'},{value:'30d',label:'近 30 天'}]}/>
      <span style={{color:'var(--text-3)',fontSize:12}}><InfoCircleOutlined /> 记录管理员的所有管理操作，与执行日志分离</span>
    </div>

    <Card className="glass harness-table" bodyStyle={{padding:0}} bordered={false}>
      <Table rowKey="traceId" dataSource={filtered} columns={columns} pagination={{pageSize:6,size:'small',showTotal:t=>`共 ${t} 条`}}
        size="middle" scroll={{x:'max-content'}}
        onRow={r=>({onClick:()=>onOpenDetail(r)})} style={{cursor:'pointer',background:'transparent'}}/>
    </Card>
  </div>;
}

export default function AuditConsole(){
  const [cfgOpen,setCfgOpen]=useState(false);
  const [refreshing,setRefreshing]=useState(false);
  const [behaviorDetail,setBehaviorDetail]=useState(null);
  const [operationDetail,setOperationDetail]=useState(null);

  const doRefresh = ()=>{ setRefreshing(true); setTimeout(()=>{ setRefreshing(false); message.success('已刷新'); }, 600); };

  return (
    <div className="console-light">
      <div className="page-head">
        <div>
          <h2>日志审计</h2>
          <div className="sub">记录智能体工具调用与管理员操作，支持事后排查与合规审计</div>
        </div>
        <Space>
          <Button icon={<SettingOutlined />} onClick={()=>setCfgOpen(true)}>审计配置</Button>
          <Button icon={<FileTextOutlined />} onClick={()=>message.success('已导出当前筛选结果')}>导出</Button>
          <Button icon={<ReloadOutlined />} loading={refreshing} onClick={doRefresh}>刷新</Button>
        </Space>
      </div>

      <Tabs defaultActiveKey="behavior" type="card">
        <Tabs.TabPane tab="智能体行为日志" key="behavior">
          <BehaviorLogView onOpenDetail={setBehaviorDetail}/>
        </Tabs.TabPane>
        <Tabs.TabPane tab="管理员操作日志" key="operation">
          <OperationLogView onOpenDetail={setOperationDetail}/>
        </Tabs.TabPane>
      </Tabs>

      <BehaviorDetailDrawer visible={!!behaviorDetail} rec={behaviorDetail} onClose={()=>setBehaviorDetail(null)}/>
      <OperationDetailDrawer visible={!!operationDetail} rec={operationDetail} onClose={()=>setOperationDetail(null)}/>
      <AuditConfigDrawer visible={cfgOpen} onClose={()=>setCfgOpen(false)}/>
    </div>
  );
}
