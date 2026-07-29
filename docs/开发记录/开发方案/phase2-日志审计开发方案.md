# Phase 2 日志审计 · 开发方案

## 1. 目标与范围

按此前定调，Phase 2 只覆盖两类日志：

| 日志类型 | 记录对象 | 关键字段 |
|---|---|---|
| **智能体行为日志** | 每一次 MCP 工具调用（FileSystemTools / SkillTools / RelayTools） | identity、工具名、入参、出参/错误、耗时、状态、时间戳、traceId、关联快照 |
| **管理员操作日志** | 管理员在管理台的所有配置与跨空间文件操作 | 管理员身份、操作类型、目标、变更前、变更后、结果、来源 IP、时间戳 |

不记录：异常告警、工作区生命周期。

## 2. 总体设计

- **行为日志**由 `@McpTool` Around 切面统一拦截记录，与后续 Phase 3 的工具权限判定**共用同一个切面**：一次拦截既做审计也做权限判定，不重复埋点。
- **操作日志**由管理台 admin REST 端点执行完动作后写入。
- 日志存储**按天分区**，**异步批量写入**，不阻塞 MCP 热路径。
- 管理端提供"智能体行为日志 / 管理员操作日志"两个视图，支持筛选、详情、导出。

## 3. 工具应用端（MCP Server）

### 3.1 新增 `audit/` 模块

| 组件 | 职责 |
|---|---|
| `AuditAspect` | `@McpTool` 环绕切面：提取身份、计时、捕获结果/异常、构造 `BehaviorAuditRecord`。Phase 3 在此处接入权限判定，无论允许/拒绝都记录。 |
| `BehaviorAuditStore` | 行为日志的写入 + 查询抽象；默认本地文件按天分区，可替换为 DB/ES。 |
| `AdminAuditStore` | 管理员操作日志的写入 + 查询。 |
| `AuditConfig` | 开关、留存周期、脱敏规则、批量大小等配置。 |

### 3.2 数据模型（抽象）

**BehaviorAuditRecord**

```
traceId
timestamp
system / agent / user          # 三段式身份
workspaceKey                    # {system}-{agent}-{user}
ossPrefix
toolName                        # read_file / run_shell_command / ...
toolCategory                    # file / skill / relay
args                            # 入参 JSON（脱敏后）
result                          # 出参 JSON
status                          # SUCCESS / FAIL / PERMISSION_DENIED
durationMs
errorCode                       # 失败/拒绝原因
snapshotId                      # 破坏性操作前置快照 ID
```

**AdminAuditRecord**

```
traceId
timestamp
adminTokenHash                  # X-Admin-Token 哈希，不存明文
adminAction                     # SET_TOOL_PERMISSION / SET_QUOTA / CROSS_WORKSPACE_COPY / REWIND / ...
targetIdentity                  # 被操作的工作区或资源
targetResource
beforeJson                      # 变更前
afterJson                       # 变更后
result                          # SUCCESS / FAIL
clientIp
```

### 3.3 关键流程

#### 流程 A：工具调用热路径

```
Agent 请求 -> @McpTool 切面进入
  -> 提取 {system}-{agent}-{user}
  -> [Phase 3 接入点] 权限判定
       ├─ 拒绝: status=PERMISSION_DENIED, 构造 record, 异步写入, 返回 403
       └─ 允许: 继续执行工具
  -> 工具返回 / 抛异常
  -> 构造 record (status=SUCCESS/FAIL)
  -> 异步批量写入 BehaviorAuditStore
  -> 返回结果
```

#### 流程 B：管理员操作

```
Admin 请求 (X-Admin-Token)
  -> admin REST 端点
  -> 执行配置变更 / 跨空间文件操作
  -> 捕获 before / after
  -> 写入 AdminAuditStore
  -> 返回结果
```

#### 流程 C：管理台查询

```
Admin 打开 日志审计 页面
  -> GET /api/audit/behavior?identity=...&status=...&from=...&to=...
  -> BehaviorAuditStore.query(filters) 返回分页结果
  -> 渲染表格
  -> 点击详情 -> GET /api/audit/behavior/{traceId}
```

### 3.4 管理台消费接口（REST）

- `GET /api/audit/behavior` — 行为日志列表（按身份/工具/分类/状态/时间范围分页）
- `GET /api/audit/behavior/{traceId}` — 单条详情
- `GET /api/audit/operation` — 管理员操作日志列表
- `GET /api/audit/operation/{traceId}` — 单条详情
- `GET /api/audit/stats` — 统计卡片数据

所有接口需要 `X-Admin-Token`。

### 3.5 非功能性设计

- **零开销可插拔**：`audit.enabled=false` 时 `AuditAspect` 不装配，热路径无开销。
- **非阻塞保证**：日志写入走异步队列 + 批量落盘；落盘失败只通过 SLF4J 告警，**绝不中断工具调用**。
- **敏感数据脱敏**：`Authorization`、文件内容等按配置脱敏或截断。
- **留存与清理**：按天分区，后台定时清理超过 `audit.retention-days` 的分区。

## 4. 管理端（Frontend）

### 4.1 页面结构

基于现有预览（`.claude/preview/index.html`）的设计语言扩展：

- 统一 navbar：文件存储管理 / 配额管理 / **日志审计（active）**
- 页面标题栏：日志审计 + ⚙ 审计配置 / ⤓ 导出 / ↻ 刷新
- Ant Design `Tabs` 切换：
  - **智能体行为日志**
  - **管理员操作日志**

### 4.2 行为日志视图

**统计卡片（4 张）**
- 今日调用
- 失败 / 失败率
- 权限拒绝
- 平均耗时

**筛选器**
- 身份/工具名搜索
- 工具分类：全部 / 文件 / 技能 / 中继
- 状态：全部 / 成功 / 失败 / 权限拒绝
- 时间窗口：近 1 小时 / 今日 / 近 7 天 / 近 30 天

**表格列**
时间 | 身份 | 工具（带分类 Tag）| 入参摘要 | 耗时 | 状态 | 操作

**详情抽屉**
- identity + OSS prefix
- 工具 + 分类
- 时间 + 耗时
- 入参 / 出参 / 错误 JSON 展示
- 关联快照 ID（破坏性操作可跳转回滚）

### 4.3 管理员操作日志视图

**统计卡片（4 张）**
- 今日操作
- 涉及管理员
- 权限变更
- 配额调整

**筛选器**
- 管理员 / 目标搜索
- 操作类型：全部 / 权限变更 / 配额调整 / 跨空间复制 / 跨空间删除 / 快照回滚
- 时间窗口

**表格列**
时间 | 管理员 | 操作类型 | 目标 | 变更摘要 | 结果 | 操作

**详情抽屉**
- 管理员 + 来源 IP
- 操作类型 + 目标
- 变更前 / 变更后 JSON 对比
- 结果 + traceId

### 4.4 审计配置抽屉

- 审计开关（零开销可插拔说明）
- 留存周期（天）
- 敏感参数脱敏开关
- 异步批量写入条数

### 4.5 组件拆分建议

- `AuditConsole`：页面壳、tabs、配置抽屉
- `BehaviorLogView` / `BehaviorDetail`：行为日志列表与详情
- `OperationLogView` / `OperationDetail`：操作日志列表与详情
- `AuditConfigDrawer`：审计配置面板
- `JsonBlock`：统一 JSON 展示
- `AuditStats`：统计卡片
- `AuditFilterBar`：筛选器组合

### 4.6 与后端对接

在 `services/api.js` 中新增 audit 查询方法，调用 3.4 的接口。筛选条件与 URL query 同步，便于分享。导出功能将当前筛选结果生成 CSV/JSON 下载。

## 5. 与 Phase 3 的关系

Phase 2 先把 `@McpTool` 切面框架搭好，负责"记录每一次调用"。
Phase 3 在同一位置接入权限判定，复用同一条记录流，只增加 `PERMISSION_DENIED` 状态。
这样避免重复埋点，也保证 Phase 3 的权限拒绝事件一定被审计。

## 6. 推荐开发顺序

1. **Backend 基础**：`AuditRecord` 模型 + `AuditAspect` + `BehaviorAuditStore` 写入路径。
2. **Backend 管理操作**：admin REST 端点写 `AdminAuditStore` 的 hook。
3. **Backend 查询**：REST query API + stats endpoint。
4. **Frontend 行为日志**：列表、筛选、详情抽屉。
5. **Frontend 操作日志**：列表、筛选、详情抽屉。
6. **Frontend 配置与导出**：审计配置抽屉 + 导出功能。
7. **联调与测试**：重点验证"权限拒绝仍被记录"和"日志写入不阻塞工具调用"。

## 7. 产物与验证

| 产物 | 路径 |
|---|---|
| Phase 2 UI 设计稿预览 | `.claude/preview/audit-preview.html` |
| Phase 1 链接更新 | `.claude/preview/index.html` navbar 的"日志审计" tab 已链接到 `audit-preview.html` |
| 预览服务器 | `http://localhost:4173/audit-preview.html`（已通过 `quota-preview` 配置启动） |

验证结果：
- 页面无控制台错误。
- 行为日志 / 操作日志两个 tab 切换正常。
- 审计配置抽屉可正常打开，渲染全部配置项。
- 表格、统计卡片、筛选器、状态 Tag 均按设计稿渲染。

## 8. 配套 UI 设计稿说明

设计稿使用与 Phase 1 配额页面完全一致的前端栈：React 18 + Ant Design 5（UMD CDN）+ Babel standalone 内联 JSX，浅色主题、`#1677ff` 主色、glass 卡片、统一 navbar。

预览方式：

```bash
# 方式一：使用 launch.json 中已配置的 quota-preview
# 已在端口 4173 启动，访问：
# http://localhost:4173/audit-preview.html

# 方式二：手动启动
python -m http.server 4173 --directory .claude/preview
```

设计稿包含：
- 行为日志列表 + 详情抽屉
- 操作日志列表 + 详情抽屉
- 审计配置抽屉
- navbar 在 配额管理 与 日志审计 之间可互相跳转
