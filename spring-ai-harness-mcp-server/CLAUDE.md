# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read AGENTS.md first

`AGENTS.md` is the **living, authoritative** design document for this project. It is kept in sync with the code and you are expected to update it whenever you change the things it documents (tool methods, `StorageProvider` contracts, `FileContentProcessor`, security logic, config properties, module structure, tech-stack versions). Before planning non-trivial work, read it; after changes, reconcile it. This file only captures what AGENTS.md does not — the big-picture mental model, build reality, and known gaps.

## What this is

An MCP (Model Context Protocol) server (Spring AI, stateless Streamable HTTP at `POST /mcp`) that gives AI agents workspace-isolated file operations backed by Aliyun OSS. The premise: many agents (openclaw, hermes, qwenpaw, …) disable their own file tools and route through this one server, so filesystem security is solved once for all of them.

Identity and isolation come entirely from the per-request `Authorization: {system}-{agent}-{user}` header. The header is parsed into a `WorkspaceIdentity`, which becomes the OSS key prefix `mcp/workspaces/{system}-{agent}-{user}/`. To the agent, `pwd` is `./` and all paths are relative; **absolute paths (leading `/`) throw `SecurityException`** and every path is funneled through `AliyunOssStorage.getFullKey()` — never bypass it.

## Build & test

This is a **child module** of `spring-ai-harness-utils-parent` (parent POM `${revision}` + Spring AI BOM). The parent reactor and `mvnw` wrapper are **not** present in this checkout, so:

- Standalone `mvn` builds only work if the parent POM and `spring-ai-bom` are resolvable from the local repo / settings — otherwise dependency resolution fails. Prefer building from the parent reactor root with `./mvnw … -pl spring-ai-harness-mcp-server`.
- Requires **Java 17+** and **Maven 3.6+**.

```bash
# Build (skip tests)
mvn clean package -DskipTests
# Run the server (defaults to :8080, MCP endpoint /mcp)
mvn spring-boot:run
# All unit tests (no live OSS — everything is Mockito-mocked)
mvn test
# Single test class / single method
mvn test -Dtest=FileSystemToolsTest
mvn test -Dtest=AliyunOssStorageTest#getFullKeyRejectsAbsolutePath
```

Required runtime config (in `src/main/resources/application.properties` or env): `aliyun.oss.endpoint`, `aliyun.oss.access-key-id`, `aliyun.oss.access-key-secret`, `spring.ai.harness.mcp.server.oss-bucket`. OSS credentials are intentionally absent from the committed properties file.

## Architecture: the request path

```
MCP client  --Authorization header-->  HeaderAuthenticationProvider → WorkspaceIdentity
                                          │
            StorageProviderFactory.getStorageProvider(McpTransportContext)
                                          │  builds an AliyunOssStorage scoped to that workspace's prefix
                                          ▼
                          FileSystemTools / SkillTools  (@McpTool, first arg is McpTransportContext)
                                          │
                            StorageProvider  ←  may be wrapped by decorators:
                                  • ObservedStorageProvider   (Micrometer, when observability.enabled)
                                  • QuotaEnforcedStorageProvider (when quota.enabled, default true)
                                          │
                          FileContentProcessor  (stateless; InputStream-only, never byte[])
                                          │
                                     Aliyun OSS
```

Cross-cutting ideas to internalize:

- **Tools never touch OSS or auth directly.** `FileSystemTools`/`SkillTools` inject `StorageProviderFactory` and call `getStorageProvider(ctx)` per request. Add new tools the same way (see AGENTS.md "Adding a New MCP Tool").
- **`FileContentProcessor` is the only place content is decoded/formatted** (text lines, image resize+Base64, PDF via `PagePdfDocumentReader`, Office via `TikaDocumentReader`). It operates on `InputStream` to avoid OOM, and is storage-agnostic — any new `StorageProvider` just supplies a stream and delegates here. Do not duplicate parsing logic in storage impls.
- **Snapshots are automatic and reversible.** `DefaultSnapshotProvider` creates a pre-snapshot before `Write` (of existing files), `Edit`, and `Trash`, stored under `.snapshots/{id}/`. `Rewind` itself creates an `action=REWIND` safety snapshot first (undo-of-undo). `.snapshots/`, `.trash/`, and `.shadow/` are in `INTERNAL_PATH_PATTERN` and hidden from directory listings.
- **Decorators make observability and quota pluggable with zero overhead when off** — the factory returns the raw `StorageProvider` when disabled, so there are no per-call `if` branches.

## Things AGENTS.md is behind on (verify before relying on AGENTS.md)

- **Storage quota** (`storage/QuotaManager.java`, `QuotaEnforcedStorageProvider.java`, `QuotaExceededException.java`) — not documented in AGENTS.md. Default 1 GB per workspace, tracked via a `.storage` meta file with incremental updates and a 24h full-recalculation interval. Config: `spring.ai.harness.mcp.server.quota.*` (`enabled`, `max-bytes`, `meta-file`, `recalculation-interval`, `include-snapshots`, `include-trash`, `include-shadow-cache`). A `.shadow/` shadow-cache directory exists alongside `.snapshots/`/`.trash/`.
- **`RelayTools`** relays ~40 tools (browser_*, computer, run_shell_command, run_ipython_cell) to a downstream streamable-http MCP server, gated by `spring.ai.harness.mcp.server.relay.enabled`. AGENTS.md's "26 proxy tools" count is stale.
- **`DownloadProperties`** in code has only `enabled` and `defaultTtl`; AGENTS.md's `max-ttl` / `public-endpoint` options do not exist.

If you touch any of these, update AGENTS.md (and this section) rather than perpetuating the drift.

## Conventions that matter

- **Chinese comments, English code.** `@McpTool` `description` text is English (it's an LLM-facing prompt); internal logic comments are Chinese.
- **No `System.out.println`.** Use SLF4J (`log.info/…`) — Lombok `@Slf4j`.
- **DTOs and read-only metadata use Java `record`.** Config uses `@ConfigurationProperties`; auto-config beans use `@ConditionalOnMissingBean` so callers can override.
- **No hardcoded FQCNs** — always `import`, never `packageName.ClassName` inline.
- **Constants coupled to prompts must change together.** `StorageProvider.MAX_LINES`, `MAX_LINE_LENGTH`, `DEFAULT_HEAD_LIMIT`, `MAX_IMAGE_EDGE` are marked "如有变动，需同步修改 prompt" — updating one side means updating the matching `@McpTool` description text.
- Tests: JUnit 5 + Mockito + AssertJ. **Never hit real OSS** — fully mock the `OSS` client. Security cases (absolute-path rejection, `..` traversal) must be covered. Core logic targets 80%+ line/branch coverage.
