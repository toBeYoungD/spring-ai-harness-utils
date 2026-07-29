# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read AGENTS.md first

[AGENTS.md](AGENTS.md) is the **living, authoritative** design document for this project. It covers the MCP server architecture, workspace isolation model, security standards, coding conventions, and module structure in detail. It is kept in sync with code — read it before planning non-trivial work, and update it after changes. This file covers what AGENTS.md does not: the big-picture module relationships, build commands, and the utils module internals.

## 内部二次开发复刻（跨会话硬约束）

本项目正从公司外搬入公司内做二次开发：内外网络隔离、开发规范/组件/依赖版本可能不同，两端代码会分叉。当前工作聚焦**应用层/业务逻辑改动**（依赖需求低）。完整流程见 [`docs/开发记录/内部二次开发复刻流程.md`](docs/开发记录/内部二次开发复刻流程.md)。

任何应用层改动，**必须**遵守：

1. **分支**：在 `internal-dev` 分支（从 `main` 拉出的单一长期工作分支）上进行；不在 `main` 直接改应用层代码。
2. **交付物**：真实代码改动 + 配套 dev-spec 文档（参照 [`docs/开发记录/dev-specs/_模板.md`](docs/开发记录/dev-specs/_模板.md)）。代码=参考实现，dev-spec=复刻说明。
3. **dev-spec 精度**：散文 + 关键代码片段；单列「依赖敏感点」标注公司内可能差异。
4. **新会话首次涉及应用层工作前**：先读 `docs/开发记录/内部二次开发复刻流程.md` + 本节 + dev-spec 模板，再开始。
5. **提交**：每个 dev-spec 对应一次提交，commit 信息含序号与 dev-spec 路径；仅按用户要求 push。

复刻由开发者在公司内按 dev-spec 手动执行，本节保证跨会话一致。

## Project overview

A multi-module Maven project (`io.github.spring-ai.harness:spring-ai-harness-utils-parent`) that provides [Claude Code](https://code.claude.com/docs/en/settings#tools-available-to-claude)-inspired harness tools and agent skills to Spring AI applications.

Four modules:

| Module | Purpose |
|--------|---------|
| `spring-ai-harness-mcp-server` | Stateless MCP server (Spring Boot + Spring AI MCP) — workspace-isolated file tools backed by Aliyun OSS, plus snapshots/rewind, relay proxy, and multimedia stream processing |
| `spring-ai-harness-utils` | Advisor library for Spring AI agent loops — context compaction, skills injection, auto-memory, human-in-the-loop, tool-result budgeting. Also contains shared `StorageProvider` interfaces |
| `spring-ai-harness-server-frontend` | React 18 + Ant Design 5 web console — Explorer-style file manager and MCP JSON-RPC debugger |
| `spring-ai-harness-utils-bom` | Bill of Materials POM for downstream consumers |

Plus `examples/qwenpaw-demo` — a full agent workspace demo with sandbox provisioning, scheduled/cron tasks, and MCP client wiring to the harness server.

## Build & test

Requires **Java 17+** and **Maven 3.6+**. The project uses `${revision}` for the version via the `flatten-maven-plugin` — the effective version is `0.0.1-SNAPSHOT`. Use the `mvnw` wrapper at the repo root; parent POM properties and BOM imports are resolvable from there.

```bash
# Build everything (skip tests)
./mvnw clean package -DskipTests

# Build only the MCP server
./mvnw compile -pl spring-ai-harness-mcp-server

# Run all tests (all OSS interaction is mocked — no live cloud connection)
./mvnw test

# Run tests for one module
./mvnw test -pl spring-ai-harness-mcp-server

# Run a single test class or method
./mvnw test -pl spring-ai-harness-mcp-server -Dtest=FileSystemToolsTest
./mvnw test -pl spring-ai-harness-mcp-server -Dtest=AliyunOssStorageTest#getFullKeyRejectsAbsolutePath

# Start the MCP server (needs OSS credentials configured)
./mvnw spring-boot:run -pl spring-ai-harness-mcp-server

# Frontend
cd spring-ai-harness-server-frontend
npm install
npm run dev        # :3000, proxies /api and /mcp to :8080
npm run build      # outputs to dist/
```

## Key architectural layers (MCP server)

```
Request (Authorization header) → HeaderAuthenticationProvider → WorkspaceIdentity
                                       ↓
              StorageProviderFactory.getStorageProvider(context)
                                       ↓  builds AliyunOssStorage scoped to prefix
                                       ↓
     ┌── FileSystemTools (@McpTool)
     │      Read / Write / Edit / Glob / Grep / ListDirectory / Trash / ListSnapshots / Rewind / SendFileToUser
     ├── SkillTools (@McpTool + @McpResource)
     │      ListSkills / ReadSkill  +  skill://list / skill://{name}
     └── RelayTools (@McpTool)
            ~40 relayed browser/shell/computer tools to downstream MCP server
                                       ↓
┌────────── StorageProvider (interface) ──────────┐
│  AliyunOssStorage                                │
│  + ObservedStorageProvider (decorator, if OTel)  │
│  + QuotaEnforcedStorageProvider (decorator)      │
└──────────────────┬──────────────────────────────┘
                   ↓
         FileContentProcessor  (stateless, InputStream-only)
                   ↓
              Aliyun OSS
```

Critical design invariants:
- **Tools never touch OSS/auth directly.** They inject `StorageProviderFactory` and call `getStorageProvider(ctx)` per request.
- **`FileContentProcessor` is the sole content parsing path.** Storage impls supply an `InputStream`, processor handles text lines, image resize+Base64, PDF page ranges, Office doc extraction. No duplicate parsing in storage impls.
- **Snapshots are automatic and reversible.** Pre-snapshot before `Write` (existing files), `Edit`, and `Trash`. `Rewind` creates a safety snapshot first (undo-of-undo). `.snapshots/`, `.trash/`, `.shadow/` are hidden from directory listings.
- **Decorators make observability/quota zero-cost when off.** `StorageProviderFactory` returns raw `AliyunOssStorage` when disabled — no per-call `if` branches.

## The utils module (`spring-ai-harness-utils`)

Agent-loop advisors and shared tools. Package: `io.github.springai.harness`.

**Advisors** (`advisor/`):
- `AutoCompactAdvisor` — automatically compacts chat history when it nears context limits
- `ToolResultBudgetAdvisor` / `ToolArgsCompactAdvisor` / `MicroCompactAdvisor` — finer-grained context budget controls
- `AutoMemoryToolsAdvisor` — injects persistent memory tools into the agent loop
- `SkillsAdvisor` — injects skill discovery/reading tools
- `ClearThinkingAdvisor` — inserts thinking prompts/instructions
- `HumanInTheLoopBeforeAdvisor` / `HumanInTheLoopAfterAdvisor` — HITL approval gates with configurable specs

**Shared Storage** (`storage/`):
- `StorageProvider` interface — the same contract the MCP server implements
- `AliyunOssStorage` / `LocalFileStorage` — OSS and local filesystem implementations
- `ImageStorageUtil` — image processing utilities

**Tools** (`tool/`):
- `FileEditTool` — the Edit operation (exact string replacement)
- `SkillsTool` — skill discovery and reading
- `StorageProviderTools` — wraps StorageProvider as Spring AI tools
- `DateTimeTools`, `AutoMemoryTools`, `ToolResultBudgetTool`

**Utilities** (`util/`): `MarkdownParser`, `SkillUtil`, `ChatMemoryUtil`, `MemoryUtil`.

## Shared conventions

- **Chinese comments, English code.** `@McpTool` `description` is English (LLM-facing); internal logic comments are Chinese.
- **No `System.out.println`.** Use SLF4J via Lombok `@Slf4j`.
- **DTOs use Java `record`.** Config uses `@ConfigurationProperties` with `@ConditionalOnMissingBean`.
- **No hardcoded FQCNs** — always `import`.
- **Constants with sync notes** (`MAX_LINES`, `MAX_LINE_LENGTH`, `MAX_IMAGE_EDGE`) must stay in sync with their matching `@McpTool` description text.
- Tests: JUnit 5 + Mockito + AssertJ. Never hit real OSS — fully mock the `OSS` client. Security cases (absolute-path rejection, `..` traversal) must be covered.
