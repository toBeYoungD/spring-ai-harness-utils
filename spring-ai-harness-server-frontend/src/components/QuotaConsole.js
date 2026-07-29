import React, { useState, useMemo, useEffect } from 'react';
import {
  Button, Input, Radio, Select, Tag, Progress, Modal, Drawer, InputNumber,
  Switch, Slider, Space, Card, Statistic, Table, Row, Col, Divider, Tooltip, message
} from 'antd';
import {
  SettingOutlined, ReloadOutlined, EditOutlined, EyeOutlined, FolderOutlined,
  WarningOutlined, CloseCircleOutlined, DatabaseOutlined, InfoCircleOutlined
} from '@ant-design/icons';

// TODO: 后端就绪后改用 services/api.js（listQuotas / setQuota / recalc），当前为 mock 数据。
const GB = 1024 * 1024 * 1024;
const MB = 1024 * 1024;
const GLOBAL_DEFAULT = 1 * GB;
const WARN_PCT = 80;

const WORKSPACES = [
  { key:'openclaw-alice',   system:'openclaw', agent:'code-assistant', user:'alice',   used:412*MB,   limit:0,      custom:false, recalc:'2026-07-28 03:00' },
  { key:'openclaw-bob',     system:'openclaw', agent:'code-assistant', user:'bob',     used:980*MB,   limit:0,      custom:false, recalc:'2026-07-28 03:00' },
  { key:'hermes-charlie',   system:'hermes',   agent:'research',       user:'charlie', used:1.2*GB,   limit:2*GB,   custom:true,  recalc:'2026-07-28 02:30' },
  { key:'qwenpaw-dave',     system:'qwenpaw',  agent:'coder',          user:'dave',    used:1.07*GB,  limit:0,      custom:false, recalc:'2026-07-28 01:12' },
  { key:'hermes-eve',       system:'hermes',   agent:'research',       user:'eve',     used:300*MB,   limit:0,      custom:false, recalc:'2026-07-28 03:00' },
  { key:'openclaw-frank',   system:'openclaw', agent:'code-assistant', user:'frank',   used:850*MB,   limit:0,      custom:false, recalc:'2026-07-28 03:00' },
  { key:'qwenpaw-grace',    system:'qwenpaw',  agent:'coder',          user:'grace',   used:2.1*GB,   limit:3*GB,   custom:true,  recalc:'2026-07-28 02:45' },
  { key:'hermes-heidi',     system:'hermes',   agent:'analyst',        user:'heidi',   used:120*MB,   limit:0,      custom:false, recalc:'2026-07-28 03:00' },
];

const effLimit = ws => (ws.custom && ws.limit > 0) ? ws.limit : GLOBAL_DEFAULT;
const usagePct = ws => Math.round(ws.used / effLimit(ws) * 100);
const statusOf = ws => { const p = usagePct(ws); if (p >= 100) return 'over'; if (p >= WARN_PCT) return 'warn'; return 'normal'; };
function fmtBytes(b){ if(b>=GB) return (b/GB).toFixed(2)+' GB'; if(b>=MB) return (b/MB).toFixed(0)+' MB'; return (b/1024).toFixed(0)+' KB'; }
function trend(used){ const r=[0.45,0.53,0.6,0.68,0.76,0.88,1.0]; return r.map((v,i)=>Math.round(used*v*(0.94+0.02*i))); }

function StatusPill({ ws }){
  const s = statusOf(ws);
  const cls = s==='over'?'is-over':s==='warn'?'is-warn':'is-normal';
  const text = s==='over'?'超限':s==='warn'?'告警':'正常';
  return <span className={'status-pill '+cls}><span className="dot"/>{text}</span>;
}

function TrendChart({ points, limitBytes }){
  const w=540,h=170,padL=48,padR=18,padT=16,padB=28;
  const max = Math.max(limitBytes, ...points)*1.15;
  const x = i => padL + i*(w-padL-padR)/(points.length-1);
  const y = v => h-padB - (v/max)*(h-padT-padB);
  const line = points.map((p,i)=>(i?'L':'M')+x(i).toFixed(1)+' '+y(p).toFixed(1)).join(' ');
  const area = line + ` L ${x(points.length-1).toFixed(1)} ${h-padB} L ${x(0).toFixed(1)} ${h-padB} Z`;
  const days = ['07-22','07-23','07-24','07-25','07-26','07-27','07-28'];
  const ticks = 4;
  return (
    <svg width={w} height={h} style={{display:'block'}}>
      {Array.from({length:ticks+1}).map((_,i)=>{
        const val = max*i/ticks; const yy = y(val);
        return <g key={i}>
          <line x1={padL} y1={yy} x2={w-padR} y2={yy} stroke="rgba(0,0,0,0.04)"/>
          <text x={padL-6} y={yy+3} textAnchor="end" fill="#8c8c8c" fontSize="10">{fmtBytes(val)}</text>
        </g>;
      })}
      <line x1={padL} y1={y(limitBytes)} x2={w-padR} y2={y(limitBytes)} stroke="#ff4d4f" strokeDasharray="4 3" strokeWidth="1.5"/>
      <text x={w-padR} y={y(limitBytes)-5} textAnchor="end" fill="#ff7875" fontSize="10">上限 {fmtBytes(limitBytes)}</text>
      <path d={area} fill="rgba(24,144,255,0.10)"/>
      <path d={line} fill="none" stroke="#1890ff" strokeWidth="2"/>
      {points.map((p,i)=><circle key={i} cx={x(i)} cy={y(p)} r="3" fill="#1890ff" stroke="#ffffff" strokeWidth="1"/>)}
      {days.map((d,i)=><text key={i} x={x(i)} y={h-8} textAnchor="middle" fill="#8c8c8c" fontSize="10">{d}</text>)}
    </svg>
  );
}

function SetLimitModal({ visible, ws, onCancel, onConfirm }){
  const [mode,setMode]=useState('custom');
  const [gb,setGb]=useState(2);
  useEffect(()=>{
    if(ws){
      if(ws.custom){ setMode('custom'); setGb(+( (ws.limit/GB).toFixed(2) )); }
      else { setMode('default'); setGb(2); }
    }
  },[ws]);
  const bytes = Math.round(gb*GB);
  return (
    <Modal className="console-modal" visible={visible} title={ws?`设置配额上限 · ${ws.key}`:''} onCancel={onCancel}
      onOk={()=>onConfirm(mode==='default'?0:bytes, mode!=='default')} okText="保存" cancelText="取消" width={460}>
      {ws && <>
        <div style={{display:'flex',alignItems:'center',justifyContent:'space-between',marginBottom:16,
            padding:'10px 14px',background:'#fafbfc',borderRadius:8,border:'1px solid var(--border)'}}>
          <div>
            <div style={{color:'var(--text-3)',fontSize:12,marginBottom:4}}>当前用量</div>
            <div style={{fontSize:14,color:'var(--text)'}}>{fmtBytes(ws.used)} / {fmtBytes(effLimit(ws))}</div>
          </div>
          <StatusPill ws={ws}/>
        </div>
        <div className="section-label">上限策略</div>
        <Radio.Group value={mode} onChange={e=>setMode(e.target.value)} style={{marginBottom:18}}>
          <Space direction="vertical">
            <Radio value="custom">自定义上限</Radio>
            <Radio value="default">回退全局默认 ({fmtBytes(GLOBAL_DEFAULT)})</Radio>
          </Space>
        </Radio.Group>
        {mode==='custom' && <>
          <div style={{display:'flex',alignItems:'center',gap:10,marginBottom:12}}>
            <InputNumber value={gb} min={0.1} step={0.1} onChange={v=>setGb(v||0)} style={{width:130}}/>
            <span>GB</span>
            <span style={{color:'#8c8c8c'}}>= {fmtBytes(bytes)}</span>
          </div>
          <Slider min={0} max={10} step={0.1} value={gb} onChange={setGb} tipFormatter={v=>v+' GB'}/>
        </>}
        <div className="help-text"><InfoCircleOutlined /> 保存后即时生效于下一次写操作校验</div>
      </>}
    </Modal>
  );
}

function DetailDrawer({ visible, ws, onClose, onSet, onRecalc }){
  return (
    <Drawer className="console-drawer" visible={visible} onClose={onClose} title={ws?ws.key:''} width={600}
      extra={ws && <Space>
        <Button size="small" icon={<EditOutlined />} onClick={()=>onSet(ws)}>设置上限</Button>
        <Button size="small" icon={<ReloadOutlined />} onClick={()=>onRecalc(ws)}>手动重算</Button>
      </Space>}>
      {ws && <>
        <div className="drawer-section" style={{display:'flex',alignItems:'center',justifyContent:'space-between'}}>
          <div>
            <div className="section-label" style={{marginBottom:6}}>工作区详情</div>
            <div className="kv">system: <b>{ws.system}</b>　agent: <b>{ws.agent}</b>　user: <b>{ws.user}</b></div>
          </div>
          <StatusPill ws={ws}/>
        </div>
        <div className="section-label">OSS prefix</div>
        <span className="prefix-chip"><code>mcp/workspaces/{ws.key}/</code></span>

        <Row gutter={16} style={{marginTop:18,marginBottom:8}}>
          <Col span={8}><Statistic title="已用" value={fmtBytes(ws.used)}/></Col>
          <Col span={8}><Statistic title="上限" value={fmtBytes(effLimit(ws))} suffix={ws.custom?'':' (默认)'}/></Col>
          <Col span={8}><Statistic title="使用率" value={usagePct(ws)} suffix="%"/></Col>
        </Row>
        <div style={{color:'#8c8c8c',fontSize:12,margin:'10px 0 16px'}}>上次全量重算: {ws.recalc}</div>
        <Divider style={{margin:'12px 0'}}/>
        <div style={{marginTop:8,marginBottom:10,fontWeight:500,color:'var(--text)'}}>近 7 天用量趋势</div>
        <div className="glass" style={{padding:'8px 4px 0'}}>
          <TrendChart points={trend(ws.used)} limitBytes={effLimit(ws)}/>
        </div>
        {!ws.custom && <div style={{marginTop:16}}><Button onClick={()=>onSet(ws)}>设置自定义上限</Button></div>}
      </>}
    </Drawer>
  );
}

function ConfigDrawer({ visible, onClose, message }){
  const [snap,setSnap]=useState(true), [trash,setTrash]=useState(true), [shadow,setShadow]=useState(false);
  const [warn,setWarn]=useState(80), [crit,setCrit]=useState(90);
  const [recalcH,setRecalcH]=useState(24), [sampleH,setSampleH]=useState(1), [retain,setRetain]=useState(30);
  return (
    <Drawer className="console-drawer" visible={visible} onClose={onClose} title="全局配额配置" width={460}
      extra={<Button type="primary" onClick={()=>{message.success('全局配置已保存');onClose();}}>保存</Button>}>
      <div style={{marginBottom:20}}>
        <div className="section-label">默认上限（未自定义工作区回退此值）</div>
        <Space>
          <InputNumber value={1} min={0} style={{width:120}}/>
          <Select defaultValue="GB" style={{width:90}} options={[{value:'GB'},{value:'MB'}]}/>
        </Space>
        <div className="help-text" style={{marginTop:6}}><InfoCircleOutlined /> 源自 application.properties，仅展示</div>
      </div>
      <Divider style={{margin:'12px 0'}}/>
      <div style={{marginBottom:20}}>
        <div className="section-label">计入配额的目录</div>
        <Space size="large">
          <span><Switch size="small" checked={snap} onChange={setSnap}/> .snapshots/</span>
          <span><Switch size="small" checked={trash} onChange={setTrash}/> .trash/</span>
          <span><Switch size="small" checked={shadow} onChange={setShadow}/> .shadow/</span>
        </Space>
      </div>
      <Divider style={{margin:'12px 0'}}/>
      <div style={{marginBottom:20}}>
        <div className="section-label">告警阈值</div>
        <Space>
          警告 <InputNumber value={warn} min={0} max={100} onChange={setWarn} style={{width:80}}/> %
          严重 <InputNumber value={crit} min={0} max={100} onChange={setCrit} style={{width:80}}/> %
        </Space>
      </div>
      <Divider style={{margin:'12px 0'}}/>
      <div style={{marginBottom:14}}>
        <div className="section-label">全量重算间隔</div>
        <Space>每 <InputNumber value={recalcH} min={1} onChange={setRecalcH} style={{width:90}}/> 小时</Space>
      </div>
      <div style={{marginBottom:14}}>
        <div className="section-label">趋势采样</div>
        <Space>每 <InputNumber value={sampleH} min={1} onChange={setSampleH} style={{width:90}}/> 小时　保留 <InputNumber value={retain} min={1} onChange={setRetain} style={{width:90}}/> 天</Space>
      </div>
    </Drawer>
  );
}

export default function QuotaConsole(){
  const [data,setData]=useState(WORKSPACES);
  const [agg,setAgg]=useState('workspace');
  const [search,setSearch]=useState('');
  const [statusF,setStatusF]=useState('all');
  const [setWs,setSetWs]=useState(null);
  const [detailWs,setDetailWs]=useState(null);
  const [cfgOpen,setCfgOpen]=useState(false);
  const [refreshing,setRefreshing]=useState(false);

  const filtered = useMemo(()=> data.filter(w=>{
    if(search && !w.key.toLowerCase().includes(search.toLowerCase())) return false;
    if(statusF!=='all' && statusOf(w)!==statusF) return false;
    return true;
  }),[data,search,statusF]);

  const stats = useMemo(()=>({
    count:data.length,
    warn:data.filter(w=>statusOf(w)==='warn').length,
    over:data.filter(w=>statusOf(w)==='over').length,
    total:data.reduce((s,w)=>s+w.used,0),
    systems:new Set(data.map(w=>w.system)).size,
  }),[data]);

  const recalc = w => message.success(`已触发 ${w.key} 全量重算`);
  const doRefresh = ()=>{ setRefreshing(true); setTimeout(()=>{ setRefreshing(false); message.success('已刷新'); }, 600); };

  const confirmLimit = (bytes,custom)=>{
    setData(d=>d.map(w=> w.key===setWs.key ? {...w, limit:bytes, custom} : w));
    message.success(`${setWs.key} 上限已${custom?'设置为 '+fmtBytes(bytes)+'，即时生效':'回退全局默认'}`);
    setSetWs(null);
  };

  const aggData = useMemo(()=>{
    if(agg==='workspace') return null;
    const map={};
    filtered.forEach(w=>{
      const k=w[agg];
      if(!map[k]) map[k]={name:k,count:0,totalUsed:0,totalLimit:0,pctSum:0};
      map[k].count++; map[k].totalUsed+=w.used; map[k].totalLimit+=effLimit(w); map[k].pctSum+=usagePct(w);
    });
    return Object.values(map).map(g=>({...g, avgPct:Math.round(g.pctSum/g.count)}));
  },[filtered,agg]);

  const strokeColor = ws => { const s=statusOf(ws); if(s==='over') return '#ff4d4f'; if(s==='warn') return '#faad14'; return '#52c41a'; };

  const wsColumns = [
    { title:'工作区', dataIndex:'key', width:230,
      render:(_,r)=>(<div className="ws-cell"><code className="ws-id">{r.key}</code><div className="ws-sub">{r.system} · {r.agent} · {r.user}</div></div>) },
    { title:'已用', width:100, render:(_,r)=>fmtBytes(r.used) },
    { title:'上限', width:150, render:(_,r)=> r.custom
        ? <span>{fmtBytes(r.limit)} <Tag color="blue" style={{marginLeft:2,borderRadius:4}}>自定义</Tag></span>
        : <span style={{color:'#8c8c8c'}}>{fmtBytes(GLOBAL_DEFAULT)} <span style={{fontSize:12}}>(默认)</span></span> },
    { title:'使用率', width:180, render:(_,r)=><Progress percent={usagePct(r)} size="small" strokeColor={strokeColor(r)} /> },
    { title:'状态', width:100, render:(_,r)=><StatusPill ws={r}/> },
    { title:'操作', width:140, align:'right', render:(_,r)=>(
      <div className="row-actions" onClick={e=>e.stopPropagation()}>
        <Button type="text" size="small" icon={<EditOutlined />} onClick={e=>{e.stopPropagation();setSetWs(r);}}>设置</Button>
        <Tooltip title="手动重算"><Button type="text" size="small" icon={<ReloadOutlined />} onClick={e=>{e.stopPropagation();recalc(r);}}/></Tooltip>
        <Tooltip title="查看详情"><Button type="text" size="small" icon={<EyeOutlined />} onClick={e=>{e.stopPropagation();setDetailWs(r);}}/></Tooltip>
      </div>
    )},
  ];

  const aggColumns = [
    { title: agg==='system'?'system':'agent', dataIndex:'name', render:t=><code className="ws-id">{t}</code> },
    { title:'工作区数', dataIndex:'count', width:100 },
    { title:'总已用', width:120, render:(_,r)=>fmtBytes(r.totalUsed) },
    { title:'总上限', width:120, render:(_,r)=>fmtBytes(r.totalLimit) },
    { title:'平均使用率', width:200, render:(_,r)=><Progress percent={r.avgPct} size="small" strokeColor={r.avgPct>=100?'#ff4d4f':r.avgPct>=WARN_PCT?'#faad14':'#52c41a'} /> },
  ];

  const statCards = [
    { label:'工作区数', value:stats.count, sub:`${stats.systems} 个 system`, tone:'blue', Icon:FolderOutlined, vc:'' },
    { label:'接近告警', value:stats.warn, sub:'用量 ≥ 80%', tone:'warn', Icon:WarningOutlined, vc:stats.warn?'tone-warn':'' },
    { label:'已超限', value:stats.over, sub:'需立即处理', tone:'over', Icon:CloseCircleOutlined, vc:stats.over?'tone-over':'' },
    { label:'总用量', value:fmtBytes(stats.total), sub:'全部工作区汇总', tone:'blue', Icon:DatabaseOutlined, vc:'' },
  ];

  return (
    <div className="console-light">
      <div className="page-head">
        <div>
          <h2>配额空间管理</h2>
          <div className="sub">管理各工作区的存储配额上限、使用率与告警状态</div>
        </div>
        <Space>
          <Button icon={<SettingOutlined />} onClick={()=>setCfgOpen(true)}>全局配置</Button>
          <Button icon={<ReloadOutlined />} loading={refreshing} onClick={doRefresh}>刷新</Button>
        </Space>
      </div>

      <div className="stat-grid">
        {statCards.map(c=>(
          <div key={c.label} className="stat-card">
            <div className="stat-top">
              <span className="stat-label">{c.label}</span>
              <span className="stat-icon" data-tone={c.tone}><c.Icon /></span>
            </div>
            <div className={'stat-value '+c.vc}>{c.value}</div>
            <div className="stat-sub">{c.sub}</div>
          </div>
        ))}
      </div>

      <div className="toolbar">
        <Radio.Group value={agg} onChange={e=>setAgg(e.target.value)} buttonStyle="solid" size="small">
          <Radio.Button value="workspace">按工作区</Radio.Button>
          <Radio.Button value="system">按 system</Radio.Button>
          <Radio.Button value="agent">按 agent</Radio.Button>
        </Radio.Group>
        <Input.Search placeholder="搜索 workspaceId" value={search} onChange={e=>setSearch(e.target.value)} style={{width:240}} size="small" allowClear/>
        <Select value={statusF} onChange={setStatusF} size="small" style={{width:130}}
          options={[{value:'all',label:'全部状态'},{value:'normal',label:'正常'},{value:'warn',label:'告警'},{value:'over',label:'超限'}]}/>
      </div>

      <Card className="glass harness-table" bodyStyle={{padding:0}} bordered={false}>
        <Table
          rowKey={agg==='workspace'?'key':'name'}
          dataSource={agg==='workspace'?filtered:aggData}
          columns={agg==='workspace'?wsColumns:aggColumns}
          pagination={{pageSize:6,size:'small',showTotal:t=>`共 ${t} 条`}}
          size="middle"
          scroll={{x:'max-content'}}
          onRow={r=>({onClick: agg==='workspace'? ()=>setDetailWs(r): ()=>{}})}
          style={{cursor: agg==='workspace'?'pointer':'default',background:'transparent'}}
        />
      </Card>

      <SetLimitModal visible={!!setWs} ws={setWs} onCancel={()=>setSetWs(null)} onConfirm={confirmLimit}/>
      <DetailDrawer visible={!!detailWs} ws={detailWs} onClose={()=>setDetailWs(null)}
        onSet={w=>{setDetailWs(null);setSetWs(w);}} onRecalc={w=>recalc(w)}/>
      <ConfigDrawer visible={cfgOpen} onClose={()=>setCfgOpen(false)} message={message}/>
    </div>
  );
}
