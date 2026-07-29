---
name: harness-project-guide
description: Spring AI Harness 项目开发规范与工作流导航。何时用 - 新会话或存量会话首次接手本项目任何开发任务前；需要了解前后端如何联动；需要知道改代码后要同步更新哪些文档；需要了解 internal-dev 复刻流程 / dev-spec 规范；或需要确认当前生效约束（antd 4.7 锁定、亮色控制台主题隔离、Phase2 审计范围、配额模型、file permission 用户自服务、tools/call 拦截）时。调用后即可开始正确工作，无需先通读全部文档。
---

# Spring AI Harness 项目开发规范导航

本 skill 是项目规范的**主动入口**：浓缩核心约束 + 指向权威文档。新会话或存量会话调用后即可开始正确工作。详情仍以权威文档为准（见第六节导航表），本 skill 不重复其全文，避免漂移。

## 一、何时用本 skill

- 新会话 / 存量会话**首次接手本项目任何开发任务**前，先读本 skill。
- 需要了解**前后端如何联动**（加能力时 REST 与 MCP 两条路的关系）。
- 改完代码，需要核对**要同步更新哪些文档**。
- 需要了解 **internal-dev 复刻流程 / dev-spec 规范 / commit 与 push 规则**。
- 需要确认**当前生效的新约束**（见第四节，这些散落在 dev-spec 与 memory 里，本 skill 固化）。

> 本 skill 是导航 + 速览，不是权威出处。代码与 AGENTS.md 才是 ground truth。改了约束记得回填本 skill 第四节。

## 二、项目速览

多模块 Maven 项目 `io.github.spring-ai-harness:spring-ai-harness-utils-parent`（`${revision}` = `0.0.1-SNAPSHOT`，flatten-maven-plugin）。

| 模块 | 定位 |
|---|---|
| `spring-ai-harness-mcp-server` | 无状态 MCP server（Spring Boot + Spring AI MCP），workspace 隔离的文件工具，后端 Aliyun OSS，含快照/回滚、relay 代理、多媒体流处理、配额、审计 |
| `spring-ai-harness-utils` | Spring AI agent loop advisor 库（上下文压缩、技能注入、自动记忆、HITL、工具结果预算）+ 共享 `StorageProvider` 接口 |
| `spring-ai-harness-server-frontend` | React 18 + Ant Design 4.7 + Vite 管理台（文件管理器、MCP 调试器、配额管理、日志审计） |
| `spring-ai-harness-utils-bom` | 下游消费 BOM |
| `examples/qwenpaw-demo` | 完整 agent workspace demo（沙箱、定时任务、MCP client 接入） |

## 三、前后端联动（核心）

### 链路

```
前端 src/services/api.js (axios)
   │  Vite dev proxy: /api/** 、/mcp  ->  http://localhost:8080   （生产直连）
   ▼
后端 :8080
   ├─ /api/v1/workspace/**   Authorization: {system}-{agent}-{user}   ->  WorkspaceApiController
   ├─ /api/v1/admin/**       X-Admin-Token                            ->  Admin controller（跨工作区管理）
   ├─ /api/v1/...attachment  （UUID 路径隔离）                          ->  AttachmentController
   └─ /mcp                   Authorization（JSON-RPC 2.0）             ->  MCP tools（FileSystemTools / SkillTools / RelayTools）
```

### 核心抽象：REST controller 与 MCP tools 共用同一套存储栈

REST controller 与 MCP tools **不各自实现文件操作**，都走：

```
请求 -> HeaderAuthenticationProvider -> WorkspaceIdentity({system}-{agent}-{user})
      -> StorageProviderFactory.getStorageProvider(ctx)
      -> AliyunOssStorage（OSS key 前缀 mcp/workspaces/{system}-{agent}-{user}/）
         + 装饰器：ObservedStorageProvider（可观测，可关）/ QuotaEnforcedStorageProvider（配额，默认开）
      -> FileContentProcessor（唯一内容解析路径：文本行 / 图片缩放+Base64 / PDF / Office）
      -> Aliyun OSS
```

**身份、路径隔离、配额、快照只在一处实现**（StorageProvider 栈）。加后端能力时，REST 与 MCP 两条路通常都要考虑（共用存储栈），前端 `api.js` 加对应方法。

### 端点表（api.js ↔ 后端 ↔ 鉴权）

| api.js 方法 | 后端端点 | 鉴权头 |
|---|---|---|
| `listFiles` / `getFileContent` / `uploadFile` / `deleteFile` / `moveFile` | `GET/POST/DELETE /api/v1/workspace/files`、`/files/content`、`/files/upload`、`/files/move` | `Authorization: {system}-{agent}-{user}` |
| `listSnapshots` / `rewind` | `GET /api/v1/workspace/snapshots`、`POST /api/v1/workspace/rewind/{id}` | `Authorization` |
| `listWorkspaces` / `listAdminWorkspaceFiles` / `deleteAdminWorkspaceFile` / `moveAdminWorkspaceFile` | `/api/v1/admin/workspaces[/{key}/files[/move]]` | `X-Admin-Token` |
| `callMcp` | `POST /mcp`（JSON-RPC: tools/list、tools/call、resources/list、resources/read） | `Authorization` |

> 配额管理 / 日志审计控制台目前为 **mock 数据**，`api.js` 尚无对应方法（后端待对接）。新增时遵循上表模式。

### 改动含义速记

- 加文件/存储能力 → 改 `StorageProvider` + `FileContentProcessor`，REST 与 MCP 两路自动复用。
- 加 REST 端点 → `WorkspaceApiController` / `AttachmentController` / admin controller，前端 `api.js` 加方法。
- 加 MCP 工具 → `@McpTool`（first arg 是 `McpTransportContext`），见 AGENTS.md「Adding a New MCP Tool」。

## 四、当前生效约束速览（本次及近期新增 + 既有硬约束）

### 前端

- **antd 全工程锁 4.7**（自 v5 降级，含 `@ant-design/icons` 4.7 + `moment`）。React 18 + antd 4.7 有 `defaultProps`/`findDOMNode`/`ReactDOM.render` legacy 等**非阻断**警告；若运行异常，回退 antd 4.24.x（最新 v4，支持 React 18，API 仍 v4）。
- 既有组件用 **v4 API**：`Tabs.TabPane`（非 `items`）、`Modal/Drawer visible`（非 `open`）、`Breadcrumb.Item` 子项（非 `items`）、`ConfigProvider` 不带 `theme`（v4 无 algorithm）。**勿用 v5-only**：`Flex`、`Segmented`、`Tour`、`QRCode`、`Watermark`、`FloatButton`。
- **新增亮色控制台** `QuotaConsole` / `AuditConsole`：CSS 作用域隔离（`.console-light` / `.console-modal` / `.console-drawer` + `!important`），不污染既有暗色组件。**主题统一待定**，勿擅自全局化亮/暗；既有暗色组件（FileExplorer/McpDebugger）样式不动。

### 后端 / 安全 / 权限

- **workspace 隔离**：身份来自 `Authorization: {system}-{agent}-{user}`，成为 OSS 前缀 `mcp/workspaces/{system}-{agent}-{user}/`。**绝对路径（leading `/`）抛 `SecurityException`**；所有路径走 `AliyunOssStorage.getFullKey()`，不绕过。
- **配额**：per `{system-agent-user}` 组合，默认 1GB，`QuotaEnforcedStorageProvider` 装饰器（默认开），`.storage` meta 增量 + 24h 全量重算。领域二计费暂不考虑，领域一配额存在。
- **file permission**：用户自服务（非管理员代办），`{system-agent-user}` 三段身份；管理员需跨工作区管理（`X-Admin-Token`）。
- **Phase2 审计** = 智能体行为日志 + 管理员操作日志两类。行为日志由 `@McpTool` 切面记录，是 **tools/call 拦截**（非 tools/list 裁剪）。
- **快照自动且可逆**：Write(existing)/Edit/Trash 前置快照；Rewind 自身先做安全快照（undo-of-undo）。`.snapshots/`、`.trash/`、`.shadow/` 隐藏于目录列表。
- **装饰器零开销可关**：factory 在禁用时返回裸 `AliyunOssStorage`，无 per-call `if`。
- **MCP gateway 内容暂忽略**（不在当前工作范围）。

### 文档 / 协作惯例

- **drawio 图**生成后必须做**坐标几何校验 + XML 转义校验**（XML 合法 ≠ 布局正确）。
- **中文回答**；技术方案讲抽象（为什么做、做什么），不堆函数签名 / 行号（评审用）。
- **中文注释，英文代码**；`@McpTool` description 是英文（LLM-facing）；无 `System.out.println`（用 SLF4J `@Slf4j`）；DTO 用 `record`；无硬编码 FQCN。
- **常量同步**：`MAX_LINES` / `MAX_LINE_LENGTH` / `DEFAULT_HEAD_LIMIT` / `MAX_IMAGE_EDGE` 改了，须同步 `@McpTool` description 英文文本。

## 五、文档更新清单（改代码后按影响范围同步）

改代码 / 架构后**必须**同步：

1. **AGENTS.md（4 份，按影响范围）**：root `AGENTS.md`、`spring-ai-harness-mcp-server/AGENTS.md`、`spring-ai-harness-server-frontend/AGENTS.md`、`spring-ai-harness-utils/src/main/resources/prompt/AGENTS.md`。改哪层更新哪个；架构级改动同步 root。
2. **常量同步**：见上节，改常量同步 `@McpTool` description。
3. **dev-spec**：每个应用层改动一份 `docs/开发记录/dev-specs/NN-短名.md`（`NN` 两位递增，模板 `_模板.md`），必含「依赖敏感点」。
4. **CLAUDE.md**：跨会话硬约束有变时，更新根 `CLAUDE.md`（自动加载）+ 子模块 `CLAUDE.md`。
5. **`docs/README.md`**：新增文档时更新总索引。
6. **图集 `docs/图集/*.drawio`**：改了须坐标 + 转义校验。
7. **本 skill 第四节**：生效约束有变时回填。

> 活例证：dev-spec 02 随本 skill 修复了 root / frontend `AGENTS.md` 第 2 节过时的 "Ant Design v5 / dark glassmorphism" 描述（antd 降级 4.7 + 亮色控制台并存后遗留的文档债）。

## 六、internal-dev 复刻流程（浓缩）

- **分支**：`internal-dev`（从 `main` 拉的单一长期工作分支），不在 `main` 直接改应用层代码。并行会话各拉 task 分支，不合用同一分支。
- **交付物**：真实代码 + 配套 dev-spec（可同一 commit）。代码是 ground truth，dev-spec 是导航。
- **dev-spec 精度**：散文 + 关键代码片段，单列「依赖敏感点」（外部依赖/版本/组件 + 公司内差异 + 适配建议），无则填「无」。`NN` 递增，不重写历史 spec。
- **commit**：`feat/app-layer(NN): 简述` 或 `docs/spec(NN): 简述`，正文引用 dev-spec 路径 + 依赖敏感点摘要，末尾 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`。
- **push**：**仅按用户明确要求**；默认本地提交。
- **远端**：`origin` = ichaobuster（上游，只读）；`fork` = toBeYoungD（可推）。`internal-dev` 跟踪 `fork/internal-dev`。
- **代理**：全局 `http.proxy`/`https.proxy` = `http://127.0.0.1:7877`（VPN）。一次性：`git -c http.proxy=http://127.0.0.1:7877 -c https.proxy=http://127.0.0.1:7877 push ...`
- **公司内复刻**：按 `NN` 顺序逐份读 dev-spec → 定位文件 → 应用改动（片段对照，`git diff main..internal-dev -- <path>` 兜底）→ 命中依赖敏感点按说明适配 → 按「验证方式」验证 → 提交（commit 信息与外部一致）。

> 详情：[`docs/开发记录/内部二次开发复刻流程.md`](docs/开发记录/内部二次开发复刻流程.md)。并行会话协作（worktree / 编号协调）：[`docs/开发记录/并行会话启动提示词.md`](docs/开发记录/并行会话启动提示词.md)。

## 七、权威文档导航表 + 开始任务检查清单

### 何时读哪个

| 要了解 | 读 |
|---|---|
| 跨会话硬约束（自动加载） | 根 `CLAUDE.md` |
| 前后端架构 / 安全 / 编码标准（权威） | `AGENTS.md`（root + 子模块） |
| 复刻工作流总纲 | `docs/开发记录/内部二次开发复刻流程.md` |
| 并行会话协作 | `docs/开发记录/并行会话启动提示词.md` |
| dev-spec 模板与既有记录 | `docs/开发记录/dev-specs/` |
| 专题开发方案 | `docs/开发记录/开发方案/`（MCP 工具权限隔离 / 文件权限应用端 / phase2 日志审计） |
| 评审用安全体系 | `docs/技术方案/spring-ai-harness-安全体系技术方案(正式版).md` |
| 图集 | `docs/图集/*.drawio` |
| 总索引 | `docs/README.md` |

### 开始任务检查清单

1. 确认在 `internal-dev` 分支（或其 task 分支）：`git branch --show-current`。
2. 浏览 `docs/开发记录/dev-specs/`，领取下一个 `NN`。
3. 探查现状 → 写方案 → 改代码 → 填 dev-spec 全章节（含依赖敏感点）。
4. 按**第五节文档更新清单**同步相关文档（含本 skill 第四节若有新约束）。
5. 验证：后端 `./mvnw test -pl <module>`（全 mock，不连真 OSS）；前端 `npm run build` + `npm run dev`(:3000) 预览。
6. 提交：`feat/app-layer(NN): ...` / `docs/spec(NN): ...` + `Co-Authored-By` trailer。
7. **仅当用户明确要求时** push 到 `fork`。
