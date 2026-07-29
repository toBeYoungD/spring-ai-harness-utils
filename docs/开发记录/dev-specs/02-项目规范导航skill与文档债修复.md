# dev-spec 02：项目规范导航 skill 与 AGENTS.md 文档债修复

> **元信息**
> - 任务简述：将散落的项目规范集成为项目级 skill `harness-project-guide`（前后端联动 + 约束速览 + 文档更新清单 + 复刻流程），并修复本次 antd 降级遗留的 AGENTS.md 文档债。
> - 创建会话 / 日期：2026-07-29
> - 分支：`internal-dev`
> - 对应 commit：`docs/spec(02): 创建项目规范导航 skill 并修复 AGENTS.md 文档债`
> - 依赖敏感点摘要：无外部依赖；skill 为 Claude Code 项目级配置（`.claude/skills/`），需放行 .gitignore（原忽略整个 .claude/），公司内 Claude Code 环境需支持 skills 目录。

---

## 一、需求背景

- 本次会话完成前端配额/审计控制台移植（dev-spec 01，commit `3767f4e`）后，沉淀的约束与规范散落在多处：根 `CLAUDE.md`（自动加载硬约束）、4 份 `AGENTS.md`（权威设计）、`docs/开发记录/`（复刻流程 + dev-specs + 开发方案）、`并行会话启动提示词.md`（会话自举入口）。
- 三个痛点：
  1. **存量会话难以快速了解新约束**：新会话要读 5+ 文档才能开始正确工作；dev-spec 01 新增的约束（antd 锁 4.7、亮色控制台主题隔离、Phase2 审计范围、配额模型、file permission 用户自服务、tools/call 拦截）散落在 dev-spec 与 memory 里，无单一入口。
  2. **前后端联动无文档**：`并行会话启动提示词.md` 未讲前后端如何联动（`api.js` ↔ controller ↔ MCP tools ↔ Vite proxy），而这恰是开发中最常需要的。
  3. **文档更新清单无固化**：AGENTS.md 强调"改代码须同步更新所有 AGENTS.md"，但无清单列出"到底要同步哪几处"，导致遗漏--活例证：dev-spec 01 antd 降级后 root/frontend `AGENTS.md` 第 2 节仍写 "Ant Design v5 / dark glassmorphism"，已过时。
- 触发来源：用户需求--"将以上所有的开发规范集成为一个 skill，让其他会话（含存量会话）能快速了解项目前后端联动、文档更新、公司内同步方法"。
- 目标：创建项目级 skill 作为规范主动入口，自包含核心约束 + 指向权威文档，补齐前后端联动与文档更新清单两块缺口；顺带修复 AGENTS.md 文档债；提示词文档加指向。

---

## 二、现状分析

- **`.claude/` 无 skills 目录**：仅有 `preview/`（前端原型）、`launch.json`、`settings.local.json`。本 skill 为项目首个。
- **前后端联动链路**（基于代码探查）：
  - 前端 `spring-ai-harness-server-frontend/src/services/api.js`（axios）经 Vite dev proxy `/api`、`/mcp` -> :8080。
  - 用户工作区 `/api/v1/workspace/**`（`Authorization: {system}-{agent}-{user}`）-> `WorkspaceApiController`；管理员 `/api/v1/admin/**`（`X-Admin-Token`）；附件 `/api/v1/...attachment`（UUID 隔离）-> `AttachmentController`；MCP `POST /mcp`（JSON-RPC）-> `FileSystemTools`/`SkillTools`/`RelayTools`。
  - **核心抽象**：REST controller 与 MCP tools 共用同一套存储栈（`StorageProviderFactory.getStorageProvider(ctx)` -> `HeaderAuthenticationProvider` -> `WorkspaceIdentity` -> `AliyunOssStorage` + 装饰器 `Observed`/`QuotaEnforced` -> `FileContentProcessor` -> OSS）。身份/隔离/配额/快照只在一处实现。
- **4 份 AGENTS.md**（按架构同步）：root `AGENTS.md`、`spring-ai-harness-mcp-server/AGENTS.md`、`spring-ai-harness-server-frontend/AGENTS.md`、`spring-ai-harness-utils/src/main/resources/prompt/AGENTS.md`。
- **文档债**：root `AGENTS.md` 第 2 节（line 14、104、目录、134、135）与 `server-frontend/AGENTS.md`（line 36、37、目录、25、46、47）仍写 "Ant Design v5 / dark glassmorphism"，dev-spec 01 降级到 4.7 + 新增亮色控制台后未同步。`mcp-server/AGENTS.md` 与 `utils/.../prompt/AGENTS.md` 无前端 antd 描述，不受影响。
- **既有自举入口**：`docs/开发记录/并行会话启动提示词.md` 已承担"让新会话了解约束"职责，但需手动粘贴触发，且缺前后端联动与文档更新清单。

---

## 三、方案设计

### A. 新建项目级 skill：`.claude/skills/harness-project-guide/SKILL.md`

项目级（随仓库走，公司内复刻可带上；本项目所有会话自动发现）。frontmatter `description` 决定自动触发时机（中文，覆盖"首次接手开发任务/了解前后端联动/文档更新/复刻流程/确认新约束"等场景）。**入库需放行 .gitignore**：原 `.gitignore` 忽略整个 `.claude/`，改为 `.claude/*` + `!.claude/skills/`，使 skills 入库而 `settings.local.json`/`preview/` 等本地配置仍忽略。

body 七节，定位为**导航 + 速览**（不照搬权威文档全文，避免漂移）：
1. 何时用本 skill + 定位。
2. 项目速览（四模块表）。
3. 前后端联动（链路图 + 共用存储栈核心抽象 + 端点表 + 改动含义）--补缺口一。
4. 当前生效约束速览（antd 4.7 锁定 / 亮色控制台作用域隔离 / Phase2 审计范围 / 配额模型 / file permission 用户自服务 / tools/call 拦截 / drawio 校验 / 中文与抽象惯例 / 安全）--补缺口二，固化散落约束。
5. 文档更新清单（4 份 AGENTS.md / 常量同步 / dev-spec / CLAUDE.md / README / 图集 / 本 skill 第四节）--补缺口三。
6. internal-dev 复刻流程（浓缩 + 指向权威文档）。
7. 权威文档导航表 + 开始任务检查清单（7 步）。

### B. 修复 AGENTS.md 第 2 节文档债

- root `AGENTS.md`：line 14 `Ant Design 5` -> `4.7`；line 104 `antd v5 with flat dark glassmorphism` -> `antd v4.7，既有暗色 + 新增亮色作用域隔离，主题统一待定`；2.1 目录补 `QuotaConsole.js`/`AuditConsole.js`/`console-light.css`/`FileViewerModal.js`/`NewItemModal.js`/`index.js`；line 122 `api.js` 描述补"配额/审计仍 mock"；line 134 `Ant Design v5 layout components` -> `v4`（移除 v5-only 的 `Flex`）；line 135 `global dark glassmorphism theme` -> 补亮色并存。
- `server-frontend/AGENTS.md`：line 36 `antd v5` -> `v4.7`（+ 降级说明 + v4 API 要点）；line 37 `Modern dark glassmorphism` -> 补亮色并存；目录补 `QuotaConsole.js`/`AuditConsole.js`/`console-light.css`；line 25 `styles/` 补 `console-light.css`；line 46 `v5 layout` -> `v4`（移除 `Flex`）；line 47 `global dark glassmorphism` -> 补亮色并存。

### C. 提示词文档加指向

`docs/开发记录/并行会话启动提示词.md` 第一节前加一段 blockquote，指向 `/harness-project-guide` skill，声明本文件聚焦并行会话协作（worktree/编号协调），与 skill 互补。保留既有全部内容。

---

## 四、改动清单

- **新增 `.claude/skills/harness-project-guide/SKILL.md`**：skill 主体，frontmatter + 七节。
- **修改 `AGENTS.md`**（root）：第 2 节 antd v5->4.7、补亮色控制台并存、目录补新组件与 `console-light.css`、`api.js` 描述补 mock 说明、移除 v5-only `Flex`。
- **修改 `spring-ai-harness-server-frontend/AGENTS.md`**：同上（line 36/37/46/47 + 目录 + styles）。
- **修改 `docs/开发记录/并行会话启动提示词.md`**：第一节前加 skill 指向 blockquote。
- **修改 `.gitignore`**：`.claude/` -> `.claude/*` + `!.claude/skills/`，放行 skills 入库，本地配置（`settings.local.json`/`preview/`）仍忽略。
- **新增 `docs/开发记录/dev-specs/02-项目规范导航skill与文档债修复.md`**：本文件。

---

## 五、依赖敏感点

- **无外部依赖**：skill 与文档纯文本，不涉及 Maven/npm 依赖。
- **skill 为 Claude Code 项目级配置**：依赖 `.claude/skills/` 目录约定。公司内 Claude Code 环境需支持 skills 目录（标准 Claude Code 支持）；若环境不支持，skill 文件仍可作为普通文档阅读，其内容（前后端联动、约束速览、文档更新清单）不依赖运行时生效。
- **.gitignore 放行**：原 `.gitignore` 忽略整个 `.claude/`，本次改为 `.claude/*` + `!.claude/skills/`。公司内若 `.gitignore` 策略不同（如完全不入库 `.claude/`），需同步放行 `.claude/skills/`，否则 skill 不随仓库走、存量会话无法自动发现。
- **内容漂移风险**：skill 第四节约束速览与权威文档会随项目演进漂移。靠第五节"文档更新清单"纪律约束--改约束时同步回填 skill 第四节。skill 定位为"导航 + 速览"，详情仍查权威文档，降低漂移影响。

---

## 六、验证方式

- **skill 可被发现**：`.claude/skills/harness-project-guide/SKILL.md` 存在，frontmatter 合法（`name` + `description`）。
- **内容自洽**：七节齐备；前后端联动端点表与 `api.js` / `WorkspaceApiController` 实际一致；约束速览与 dev-spec 01 / `CLAUDE.md` / `AGENTS.md` 不冲突。
- **文档债修复**：root + frontend `AGENTS.md` 第 2 节不再出现 "antd v5" / 纯 "dark glassmorphism"；目录含 `QuotaConsole.js`/`AuditConsole.js`/`console-light.css`。
- **提示词文档**：顶部 blockquote 指向 `/harness-project-guide`。
- 预期结果：调用 `/harness-project-guide` 即可获得项目规范全貌；AGENTS.md 与实际技术栈一致。

---

## 七、复刻步骤

1. 定位：`.claude/skills/`、`AGENTS.md`、`spring-ai-harness-server-frontend/AGENTS.md`、`docs/开发记录/并行会话启动提示词.md`、`docs/开发记录/dev-specs/`。
2. 放行 `.gitignore` 的 `.claude/skills/`（原忽略整个 `.claude/`，改为 `.claude/*` + `!.claude/skills/`），新建 `.claude/skills/harness-project-guide/SKILL.md`：照 frontmatter + 七节结构，内容以本 dev-spec 与既有权威文档为准。
3. 修复 root `AGENTS.md` 与 `server-frontend/AGENTS.md` 第 2 节：antd v5->4.7、补亮色控制台并存、目录补新组件与 `console-light.css`、移除 v5-only `Flex`。
4. `并行会话启动提示词.md` 第一节前加 skill 指向 blockquote。
5. 适配依赖敏感点：确认公司内 Claude Code 支持 `.claude/skills/`（不支持则 skill 作文档用，内容仍有效）。
6. 验证：按六逐项核对；可实际调用 `/harness-project-guide` 确认 skill 被识别。
7. 兜底对照：`git diff main..internal-dev -- .claude/skills/ AGENTS.md spring-ai-harness-server-frontend/AGENTS.md docs/开发记录/`。
