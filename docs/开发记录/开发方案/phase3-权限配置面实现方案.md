# phase3-权限配置面实现方案

> 实现 Phase 3：两套独立的权限配置面——①管理员工具权限管理台（Phase 3a）、②用户自服务文件 ACL 管理台（Phase 3b）。各有管理台页面 + REST 接口 + yaml 持久化。运行时拦截由 Phase 4 的统一 `PermissionEnforcedStorageProvider` 装饰器完成。

---

## 一、需求背景

- 智能体经 `/mcp` 调用文件工具、技能工具、中继工具，当前无权限管控。
- 需要两套配置面：
  - **工具权限**：管理员按用户（`{system}-{agent}-{user}`）配允许使用哪些 MCP 工具。
  - **文件 ACL**：用户自服务，配本工作区内哪些路径可读/可写/不可访问。
- 两者是 **AND 关系**，但**配置者不同**（管理员 vs 用户）、**管理台不同**（两个独立 tab）。
- Phase 3 **只做配置面**——管理台 + REST + yaml 持久化，不开发任何拦截器/装饰器（Phase 4 职责）。

## 二、范围

| 维度 | 本期 | 不做 |
|---|---|---|
| Phase 3a 工具权限 | 管理员管理台 UI + REST CRUD + yaml 持久化 | 不开发拦截器 |
| Phase 3b 文件 ACL | 用户自服务管理台 UI + REST CRUD + `.acl/rules.json` 持久化 | 不开发装饰器 |
| 执行面 | —（Phase 4 职责：统一 PermissionEnforcedStorageProvider） | — |
| UI 风格 | 亮色 `.console-light`，同配额/审计控制台 | 主题统一待后续讨论 |

---

## Phase 3a — 工具权限配置面

### 3a.1 管理台 UI

App.js 新增 tab「工具权限」，管理员 `X-Admin-Token` 鉴权。亮色作用域。

#### 布局

```
┌──────────────────────────────────────────────────────────┐
│  工具权限管控                          [刷新]           │
│  配置用户可使用的 MCP 工具。拒绝在 tools/call 时生效。     │
├──────────────────────────────────────────────────────────┤
│  ┌──────┬──────┬──────┬──────┐                          │
│  │ 已配用户 │ 涉及系统 │ 涉及角色 │ 最近变更  │           │
│  │   12    │   3    │   4    │ 2 分钟前 │              │
│  └──────┴──────┴──────┴──────┘                          │
│                                                          │
│  [搜索用户...]  [按 system ▼]  [+ 新增权限]              │
│                                                          │
│  ┌──────────────────────────────────────────────────────┐│
│  │ 用户身份              │ tools          │ 操作        ││
│  ├──────────────────────────────────────────────────────┤│
│  │ openclaw-code-..-alice│ 7 个工具 (reader)│ [编辑][删除] ││
│  │ openclaw-code-..-bob  │ 12 个工具 (editor)│ [编辑][删除] ││
│  └──────────────────────────────────────────────────────┘│
│                                    共 12 条  < 1 2 >    │
└──────────────────────────────────────────────────────────┘
```

#### 设权限 Modal（`.console-modal`）

```
┌─────────────────────────────────────┐
│  配置工具权限                    [X] │
├─────────────────────────────────────┤
│  用户身份                            │
│  [openclaw ▼] [code-assistant ▼]    │
│  [alice ▼]                          │
│                                     │
│  可用工具                            │
│  ┌ 文件工具 ──────────────────────┐ │
│  │ ☑ read  ☑ glob  ☑ grep       │ │
│  │ ☑ list_directory              │ │
│  │ ☐ write ☐ edit ☐ trash       │ │
│  └───────────────────────────────┘ │
│  ┌ 技能工具 ──────────────────────┐ │
│  │ ☑ read_skill ☑ list_skills   │ │
│  └───────────────────────────────┘ │
│  ┌ 中继工具 ──────────────────────┐ │
│  │ ☐ browser_navigate            │ │
│  │ ☐ run_shell_command           │ │
│  └───────────────────────────────┘ │
│  [全选] [全不选]  工具总数: 26      │
│                                     │
│           [取消]        [保存]      │
└─────────────────────────────────────┘
```

#### 详情 Drawer（`.console-drawer`）

展示权限统计 + 已允许的工具列表（按文件/技能/中继分组陈列）。

### 3a.2 REST 接口

| 方法 | 端点 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/v1/admin/tool-permissions/{system}-{agent}-{user}` | X-Admin-Token | 查询某用户的 allowed-tools |
| PUT | `/api/v1/admin/tool-permissions/{system}-{agent}-{user}` | X-Admin-Token | 设置 allowed-tools（替换全部） |
| DELETE | `/api/v1/admin/tool-permissions/{system}-{agent}-{user}` | X-Admin-Token | 删除权限配置（回退 default-policy） |
| GET | `/api/v1/admin/tool-permissions/tool-names` | X-Admin-Token | 列出所有可配 MCP 工具名 |

数据模型：

```json
{
  "identity": "openclaw-code-assistant-alice",
  "allowedTools": ["read", "glob", "grep", "list_directory", "list_snapshots", "read_skill", "list_skills"],
  "updatedAt": "2026-07-29T14:30:00"
}
```

### 3a.3 持久化

REST 直接读/写 yaml（`PermissionProperties.user-roles`）。权限变更写回 yaml，由 Phase 4 的策略通道在启动时加载。

### 3a.4 前端新增文件

| 文件 | 说明 |
|---|---|
| `src/components/ToolPermissionConsole.js` | 工具权限管理台（`.console-light` 根，含 Modal + Drawer） |

App.js：新增 tab「工具权限」+ 图标 `ToolOutlined`。

---

## Phase 3b — 文件 ACL 配置面

### 3b.1 管理台 UI

App.js 新增 tab「文件 ACL」，用户自服务。以 `Authorization: {system}-{agent}-{user}` 鉴权，**只能配本工作区**的路径级 ACL。亮色作用域。

#### 布局

```
┌──────────────────────────────────────────────────────────────┐
│  文件 ACL · 用户自服务                        + 新增规则     │
│  配置本工作区内智能体的文件访问限制。与工具权限是 AND 关系。   │
├──────────────────────────────────────────────────────────────┤
│  ┌──────┬──────┬──────┬──────┐                              │
│  │ 规则数 │ DENY │ READ │ WRITE│                              │
│  │   5   │  1   │  2   │  2   │                              │
│  └──────┴──────┴──────┴──────┘                              │
│                                                              │
│  [搜索 pattern...]  [全部 ▼]                      [刷新]     │
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ 路径 Pattern      │ 权限    │ 优先级 │ 更新时间    │ 操作 ││
│  ├─────────────────────────────────────────────────────────┤│
│  │ secrets/**        │ 🔴 不可访问│ 10     │ 15:00  │[编辑][删除]│
│  │ **/*.env          │ 🔴 不可访问│ 100    │ 14:50  │[编辑][删除]│
│  │ secrets/public/** │ 🟢 可读    │ 20     │ 15:10  │[编辑][删除]│
│  │ src/**            │ 🔵 可读写   │ 10     │ 14:00  │[编辑][删除]│
│  │ docs/**           │ 🟢 可读    │ 10     │ 14:30  │[编辑][删除]│
│  └─────────────────────────────────────────────────────────┘│
│                                        共 5 条  < 1 >       │
└──────────────────────────────────────────────────────────────┘
```

#### 新增/编辑规则 Modal（`.console-modal`）

```
┌───────────────────────────────────┐
│  新增 ACL 规则                  [X]│
├───────────────────────────────────┤
│  路径 Pattern                      │
│  [secrets/**                  ]   │
│  支持 glob: * 单段, ** 跨段       │
│                                   │
│  访问权限                          │
│  (●) 不可访问 DENY                │
│  ( ) 可读     READ                │
│  ( ) 可读写   WRITE               │
│                                   │
│  优先级                            │
│  [10              ]  (1-1000)      │
│  更大 = 更具体，覆盖更低优先级的规则   │
│                                   │
│           [取消]        [保存]    │
└───────────────────────────────────┘
```

### 3b.2 REST 接口

| 方法 | 端点 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/v1/workspace/file-acl` | Authorization | 查询本工作区全部 ACL 规则 |
| POST | `/api/v1/workspace/file-acl` | Authorization | 新增一条 ACL 规则 |
| PUT | `/api/v1/workspace/file-acl/{ruleId}` | Authorization | 修改规则 |
| DELETE | `/api/v1/workspace/file-acl/{ruleId}` | Authorization | 删除规则 |

数据模型：

```json
{
  "ruleId": "acl-3f7a",
  "pattern": "secrets/**",
  "access": "DENY",
  "priority": 10,
  "createdAt": "2026-07-29T15:00:00"
}
```

鉴权：从 `Authorization` 头解析 `WorkspaceIdentity`，用户只能配本工作区。

### 3b.3 持久化

直接写 `.acl/rules.json`（在 workspace 前缀下），`.acl/` 加入 `INTERNAL_PATH_PATTERN` 隐藏。由 Phase 4 策略通道启动加载。

### 3b.4 前端新增文件

| 文件 | 说明 |
|---|---|
| `src/components/FileAclConsole.js` | 文件 ACL 管理台（`.console-light` 根，含 Modal + Drawer） |

App.js：新增 tab「文件 ACL」+ 图标 `LockOutlined`。

---

## 实现步骤

| 步骤 | Phase | 内容 | 产出 |
|---|---|---|---|
| 1 | 3a | `ToolPermissionConsole.js` 管理台 UI（mock 起步） | 前端页面 |
| 2 | 3a | `ToolPermissionController.java` REST CRUD + 工具名枚举 | 后端接口 |
| 3 | 3a | yaml 持久化读写 | 规则存储 |
| 4 | 3b | `FileAclConsole.js` 管理台 UI（mock 起步） | 前端页面 |
| 5 | 3b | `FileAclController.java` REST CRUD | 后端接口 |
| 6 | 3b | `.acl/rules.json` 持久化 | 规则存储 |
| 7 | — | App.js + 两个 tab 导航 | 导航接入 |
| 8 | — | dev-spec + 文档同步 | 文档交付 |

## 交付物

- `ToolPermissionConsole.js`（工具权限管理台 + Modal + Drawer）
- `ToolPermissionController.java`（REST CRUD + 工具名枚举）
- `FileAclConsole.js`（文件 ACL 管理台 + Modal + Drawer）
- `FileAclController.java`（REST CRUD）
- 两项 yaml/JSON 持久化
- App.js + 两个 tab
- dev-spec（NN=03）

## 依赖

- 前端：antd 4.7 + `console-light.css`（亮色作用域）
- 后端：Spring Boot + Spring Web + yaml 读写
- 无外部新依赖
