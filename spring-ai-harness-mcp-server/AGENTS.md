# Spring AI Harness MCP Server — AGENTS.md

> [!IMPORTANT]
> **This project is under active development.** There are features still in progress, areas to polish, and new capabilities to add.
> This document (AGENTS.md) must be **continuously updated** as the project evolves, ensuring it always reflects the latest state of the code.
> If you discover discrepancies between this document and the actual code during development, **update this document** after completing the code changes.

## Project Overview

`spring-ai-harness-mcp-server` is a unified **MCP (Model Context Protocol) Server** that provides secure, controlled file system operations for various harness agents (openclaw, hermes, qwenpaw, etc.).

### Why This Project Exists

Different agents use different languages, SDKs, plugins, and hooks, which means solving security issues like unauthorized file access and dangerous command execution requires separate development efforts for each agent. Since all these agents support the MCP Server protocol, by:

1. **Disabling** agents' built-in file operation tools (read, write, edit, glob, grep, etc.)
2. **Replacing** them with a unified MCP Server providing these capabilities

We achieve **develop once, secure all agents**.

### Why Filesystem Is Separated from Sandbox

Sandboxes require per-user container isolation, consuming significant compute resources at scale. The filesystem MCP Server needs no container isolation — a small number of resources can serve many users. Since filesystem operations far outnumber sandbox operations, separating the two significantly reduces resource consumption.

---

## Architecture Design

### Core Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                        MCP Clients (Agents)                      │
│         openclaw / hermes / qwenpaw / ...                        │
│         Authorization: {system}-{agent}-{user}                   │
└─────────────────────────┬────────────────────────────────────────┘
                          │ HTTP (Streamable HTTP, Stateless)
                          │ POST /mcp
                          ▼
┌──────────────────────────────────────────────────────────────────┐
│              spring-ai-harness-mcp-server                        │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  HarnessMcpServerAutoConfiguration                      │     │
│  │  • WebMvcStatelessServerTransport (Stateless MCP)       │     │
│  │  • Register AuthenticationProvider & StorageFactory     │     │
│  └─────────────────────────────────────────────────────────┘     │
│                          │                                       │
│                          ▼                                       │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  Authentication & Storage Factory                       │     │
│  │  • HeaderAuthenticationProvider → WorkspaceIdentity     │     │
│  │  • DefaultStorageProviderFactory → AliyunOssStorage     │     │
│  └──────────────────────┬──────────────────────────────────┘     │
│                         │                                        │
│                         ▼                                        │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  FileSystemTools (@McpTool)                             │     │
│  │  • Read / Write / Edit / Glob / Grep / ListDir / Trash  │     │
│  │  • Delegates to StorageProviderFactory for isolation     │     │
│  └──────────────────────┬──────────────────────────────────┘     │
│                         │                                        │
│                         ▼                                        │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  StorageProvider (interface)                             │     │
│  │  └─ AliyunOssStorage (impl)                             │     │
│  │     • Streams via FileContentProcessor (no byte[] OOM)  │     │
│  │     • prefix: mcp/workspaces/{system}-{agent}-{user}/   │     │
│  └──────────────────────┬──────────────────────────────────┘     │
│                         │                                        │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  FileContentProcessor (stateless utility)               │     │
│  │  • streamToString / streamToLines (BufferedReader)      │     │
│  │  • processImageStream (AWT resize + Base64)             │     │
│  │  • processPdfStream (Spring AI PagePdfDocumentReader)   │     │
│  │  • processDocumentStream (Spring AI TikaDocumentReader) │     │
│  └─────────────────────────────────────────────────────────┘     │
│                         │                                        │
└─────────────────────────┼────────────────────────────────────────┘
                          │ Aliyun OSS SDK
                          ▼
               ┌─────────────────────┐
               │   Alibaba Cloud OSS │
               │   (Storage Backend) │
               └─────────────────────┘
```

### Workspace Isolation Model

```
oss://{bucket}/{ossPrefix}/{system}-{agent}-{user}/
       │         │           │        │       │
       │         │           │        │       └── User-level isolation (delimiter: -)
       │         │           │        └────────── Agent-level isolation (delimiter: -)
       │         │           └─────────────────── System-level isolation
       │         └─────────────────────────────── Configurable prefix (default: mcp/workspaces/)
       └───────────────────────────────────────── OSS Bucket
```

From the agent's perspective:
- **pwd is `./`**, all path operations are relative to this
- **Absolute paths are forbidden**: Paths starting with `/` throw `SecurityException`
- The agent cannot perceive its actual location in OSS

### Sandbox Integration (Planned)

When agents need to execute shell or browser operations:
1. Mount the workspace's OSS path to `/workspace` in an all-in-one sandbox
2. The sandbox can access files under the workspace
3. Sandboxes are isolated per-user

### File Protection Capabilities

- Leverages OSS versioning or custom snapshot mechanisms for file operation rollback
- All file operations through the MCP Server can be reverted to prior points in time
- Prevents agent misoperations or destructive script executions

---

## Module Structure

```
spring-ai-harness-mcp-server/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/io/github/springai/harness/
    │   │   ├── auth/
    │   │   │   ├── AuthenticationException.java       # Authentication exception class
    │   │   │   ├── AuthenticationProvider.java        # Authentication abstract interface
    │   │   │   ├── HeaderAuthenticationProvider.java  # Header-based auth implementation
    │   │   │   └── WorkspaceIdentity.java             # Workspace identity record
    │   │   ├── autoconfig/
    │   │   │   ├── AliyunOssAutoConfiguration.java   # OSS client auto-configuration
    │   │   │   ├── AliyunOssProperties.java          # OSS connection config properties
    │   │   │   ├── HarnessMcpServerAutoConfiguration.java  # MCP Server auto-configuration
    │   │   │   ├── HarnessMcpServerProperties.java   # MCP Server config properties
    │   │   │   └── ObservabilityAutoConfiguration.java  # Observability auto-configuration
    │   │   ├── controller/
    │   │   │   ├── WorkspaceApiController.java        # User workspace REST Controller (/api/v1/workspace)
    │   │   │   ├── AttachmentController.java          # File attachment upload/manage REST Controller (/api/v1/workspace/attachments)
    │   │   │   └── AdminApiController.java            # Admin REST Controller (/api/v1/admin)
    │   │   ├── dto/
    │   │   │   ├── FileItemDto.java                   # File list item DTO
    │   │   │   ├── AttachmentDto.java                 # File attachment DTO
    │   │   │   └── WorkspaceInfoDto.java              # Workspace metadata DTO
    │   │   ├── skill/
    │   │   │   ├── MarkdownParser.java                # Markdown & YAML FrontMatter parser
    │   │   │   ├── SkillInfo.java                     # Skill metadata record
    │   │   │   ├── SkillProvider.java                 # Skill discovery & reading interface
    │   │   │   └── DefaultSkillProvider.java          # Workspace skill scanning implementation
    │   │   ├── snapshot/
    │   │   │   ├── SnapshotInfo.java                  # Snapshot metadata record
    │   │   │   ├── SnapshotProvider.java              # Snapshot creation, listing & restore interface
    │   │   │   ├── DefaultSnapshotProvider.java       # .snapshots/-based snapshot provider
    │   │   │   └── ObservedSnapshotProvider.java      # Snapshot observability decorator
    │   │   ├── storage/
    │   │   │   ├── DownloadLink.java                  # File temporary download link record
    │   │   │   ├── StorageProvider.java               # Storage abstract interface
    │   │   │   ├── StorageProviderFactory.java        # Storage factory abstract interface
    │   │   │   ├── DefaultStorageProviderFactory.java # Default storage factory implementation
    │   │   │   ├── AliyunOssStorage.java              # Alibaba Cloud OSS implementation
    │   │   │   ├── FileContentProcessor.java          # Stateless streaming file content processor
    │   │   │   └── ObservedStorageProvider.java       # Storage observability decorator
    │   │   └── tool/
    │   │       ├── FileSystemTools.java               # MCP file tools (Read/Write/Edit/Glob/Grep/ListDirectory/Trash/ListSnapshots/Rewind)
    │   │       ├── RelayMcpClientManager.java         # Downstream streamable-http MCP client connection manager & cache
    │   │       ├── RelayTools.java                    # 26 proxy tools delegating to downstream MCP server
    │   │       └── SkillTools.java                    # MCP Skill tools & resources
    │   └── resources/
    │       └── application.properties                 # Default configuration
    └── test/
        └── java/io/github/springai/harness/
            ├── auth/
            │   ├── HeaderAuthenticationProviderTest.java
            │   └── AuthenticationExceptionTest.java
            ├── autoconfig/
            │   ├── AliyunOssAutoConfigurationTest.java
            │   ├── HarnessMcpServerAutoConfigurationTest.java
            │   └── ObservabilityAutoConfigurationTest.java
            ├── controller/
            │   ├── WorkspaceApiControllerTest.java
            │   └── AdminApiControllerTest.java
            ├── skill/
            │   └── DefaultSkillProviderTest.java
            ├── snapshot/
            │   ├── DefaultSnapshotProviderTest.java
            │   └── ObservedSnapshotProviderTest.java
            ├── storage/
            │   ├── AliyunOssStorageTest.java
            │   ├── DefaultStorageProviderFactoryTest.java
            │   ├── FileContentProcessorTest.java
            │   └── ObservedStorageProviderTest.java
            └── tool/
                ├── FileSystemToolsTest.java
                ├── RelayMcpClientManagerTest.java
                ├── RelayToolsTest.java
                └── SkillToolsTest.java
```

---

## Core Component Details

### 1. FileSystemTools

File: `tool/FileSystemTools.java`

MCP tool entry class providing 10 `@McpTool` methods — the file system and snapshot rollback capabilities available to agents:

| Tool | Method | Description |
|------|--------|-------------|
| `Read` | `read(ctx, filePath, offset, limit, startPage, endPage)` | Read file content with pagination (line-numbered `cat -n` format). Supports multimedia: images (PNG/JPG/JPEG) return MCP `ImageContent` with Base64; PDFs support page ranges; Office docs (DOCX/XLSX/PPTX) return extracted text. Returns `CallToolResult`. |
| `Write` | `write(ctx, filePath, content)` | Create or overwrite files (auto-triggers pre-write snapshot) |
| `Edit` | `edit(ctx, filePath, oldString, newString, replaceAll)` | Precise string replacement, single or replace-all (auto-triggers pre-write snapshot) |
| `Glob` | `glob(ctx, pattern, path)` | Glob pattern file search, returns up to 100 results |
| `Grep` | `grep(ctx, pattern, path, glob, outputMode, ...)` | Regex search with context lines, line numbers, pagination |
| `ListDirectory` | `listDirectory(ctx, path)` | List files and subdirectories (type, size, modification time) |
| `Trash` | `trash(ctx, filePath)` | Safely move files/directories to workspace recycle bin (`.trash/`, auto-triggers pre-write snapshot) |
| `ListSnapshots` | `listSnapshots(ctx, filePath)` | Query historical snapshot list, filterable by file path |
| `Rewind` | `rewind(ctx, snapshotId)` | Quickly revert a file to a specific snapshot state |
| `SendFileToUser` | `sendFileToUser(ctx, filePath, expiresInSeconds)` | Generates a temporary download link (presigned URL) for a file. Default TTL: 1h, Max: 8h. Returns JSON object. |

**Key decoupling design**: `FileSystemTools` does not handle Authorization header parsing or OSS client directly. It injects `StorageProviderFactory` and delegates to `getStorageProvider(McpTransportContext)` to obtain an isolated `StorageProvider` instance for each request identity.

### 2. FileContentProcessor

File: `storage/FileContentProcessor.java`

A **stateless utility class** that centralizes all file content decoding, formatting, and parsing logic. This class operates exclusively on `InputStream` objects to prevent heap OOM errors when processing large files. It is decoupled from any specific storage backend (OSS, S3, local, etc.).

| Method | Description |
|--------|-------------|
| `streamToString(InputStream)` | Streams lines via `BufferedReader` into a UTF-8 String |
| `streamToLines(InputStream)` | Streams lines via `BufferedReader` into a `List<String>` |
| `processImageStream(InputStream, String)` | Decodes image from stream, resizes proportionally if any side > `MAX_IMAGE_EDGE`, returns Base64 string |
| `processPdfStream(InputStream, Integer, Integer)` | Wraps stream as `InputStreamResource`, extracts pages via `PagePdfDocumentReader`, supports 1-based page range filtering |
| `processDocumentStream(InputStream)` | Wraps stream as `InputStreamResource`, extracts text via `TikaDocumentReader` (supports DOCX, XLSX, PPTX) |

**Design rationale**: By separating content processing from storage transport, any future `StorageProvider` implementation (e.g., AWS S3, local filesystem) only needs to provide an `InputStream` and delegate to `FileContentProcessor` — zero code duplication for parsing/formatting logic.

### 3. SkillTools (Skill MCP Tools & Resources)

File: `tool/SkillTools.java`

MCP Skill management entry class, exposing both **MCP Tools** and **MCP Resources**:

#### MCP Tools

| Tool | Method | Description |
|------|--------|-------------|
| `ListSkills` | `listSkills(ctx)` | List all available Skills under the workspace `skills/` directory |
| `ReadSkill` | `readSkill(ctx, skillName)` | Read the full `SKILL.md` instruction content for a specific Skill |

#### MCP Resources

| Resource URI | Method | MimeType | Description |
|--------------|--------|----------|-------------|
| `skill://list` | `listSkillsResource(ctx)` | `text/plain` | Pull full Skills metadata list via MCP Resource protocol |
| `skill://{skillName}` | `readSkillResource(ctx, skillName)` | `text/markdown` | Read a specific Skill's content via `skill://` URI protocol |

### 4. Management REST API Module

Package: `controller/`, `dto/`

#### User Workspace Endpoints (`WorkspaceApiController` - `/api/v1/workspace`)

Requires identity via `Authorization: {system}-{agent}-{user}` header.

| HTTP Method | Path | Description |
|-------------|------|-------------|
| `GET` | `/api/v1/workspace/files?path=` | List workspace directory files |
| `GET` | `/api/v1/workspace/files/content?path=` | Read/download file content |
| `POST` | `/api/v1/workspace/files/upload?path=` | Upload/write file (triggers auto pre-snapshot) |
| `POST` | `/api/v1/workspace/files/move?fromPath=&toPath=` | Move/rename file or directory |
| `DELETE` | `/api/v1/workspace/files?path=&trash=true` | Delete or move to recycle bin |
| `GET` | `/api/v1/workspace/snapshots?path=` | Query file snapshot history |
| `POST` | `/api/v1/workspace/rewind/{snapshotId}` | One-click rollback to a specific snapshot |

#### File Attachment Endpoints (`AttachmentController` - `/api/v1/workspace/attachments`)

Requires identity via `Authorization: {system}-{agent}-{user}` header.

| HTTP Method | Path | Description |
|-------------|------|-------------|
| `POST` | `/api/v1/workspace/attachments` | Upload a binary file attachment (supports UUID dir partitioning and conversationId) |
| `GET` | `/api/v1/workspace/attachments?conversationId=` | List uploaded file attachments (filter by conversationId) |
| `DELETE` | `/api/v1/workspace/attachments/{attachmentId}?conversationId=&trash=true` | Move file attachment directory to trash (or delete permanently if trash=false) |

#### Admin Endpoints (`AdminApiController` - `/api/v1/admin`)

Requires admin identity via `X-Admin-Token: {adminToken}` header.

| HTTP Method | Path | Description |
|-------------|------|-------------|
| `GET` | `/api/v1/admin/workspaces` | List all OSS workspace spaces |
| `GET` | `/api/v1/admin/workspaces/{workspaceKey}/files?path=` | List files in a specific workspace |
| `POST` | `/api/v1/admin/workspaces/{workspaceKey}/files/move?fromPath=&toPath=` | Move/rename files in a specific workspace |
| `DELETE` | `/api/v1/admin/workspaces/{workspaceKey}/files?path=` | Force-delete files in a specific workspace |

#### Quota Admin Endpoints (`QuotaAdminController` - `/api/v1/admin/quota`)

Requires admin identity via `X-Admin-Token: {adminToken}` header. Cross-workspace: enumerates OSS workspace prefixes via `listObjects(prefix, delimiter="/")` and constructs a bare `AliyunOssStorage` per workspace (no quota/permission decorators) to read/write `.storage`/`.quota` meta.

| HTTP Method | Path | Description |
|-------------|------|-------------|
| `GET` | `/api/v1/admin/quota/workspaces` | List all workspaces with quota status (used bytes / limit / effective limit / status) |
| `GET` | `/api/v1/admin/quota/workspaces/{key}` | Single workspace quota detail |
| `PUT` | `/api/v1/admin/quota/workspaces/{key}/limit` | Set per-workspace custom limit (body `{"limitBytes":N}`; `null`/`<=0` reverts to global default by deleting `.quota`) |
| `POST` | `/api/v1/admin/quota/workspaces/{key}/recalc` | Trigger full recalculation for a workspace (rewrites `.storage`) |
| `GET` | `/api/v1/admin/quota/config` | Read-only global quota config (from `application.properties`) |

**Enforcement is NOT in this controller** - `QuotaEnforcedStorageProvider` calls `QuotaManager.checkQuota()` before every write, using the effective limit (custom `.quota` value if set, else global `max-bytes`). Setting a custom limit takes effect on the next write operation.

### 5. StorageProvider & StorageProviderFactory

Package: `storage/`

Storage abstract interface defining all file and recycle bin operation contracts. Key constants:

| Constant | Value | Description |
|----------|-------|-------------|
| `MAX_RESULT` | 100 | Maximum Glob results |
| `MAX_DEPTH` | 50 | Maximum traversal depth |
| `MAX_LINES` | 2000 | Default maximum read lines |
| `MAX_LINE_LENGTH` | 2000 | Maximum single line length (truncated beyond) |
| `DEFAULT_HEAD_LIMIT` | 250 | Default Grep head limit |
| `MAX_IMAGE_EDGE` | 2048 | Maximum image dimension per side (proportionally resized if exceeded) |
| `IGNORED_PATH_PATTERN` | `.git`, `node_modules`, `.trash`, `.snapshots`, etc. | Auto-ignored path patterns |

Factory and implementations:
- **`StorageProviderFactory`**: Defines the `getStorageProvider(McpTransportContext)` contract.
- **`DefaultStorageProviderFactory`**: Combines `AuthenticationProvider` to extract identity and build workspace-prefixed `AliyunOssStorage`.
- **`AliyunOssStorage`**: Alibaba Cloud OSS implementation of `StorageProvider`. All read methods open `OSSObjectInputStream` via try-with-resources and delegate content processing to `FileContentProcessor`. Provides `trash(path)` for soft-deleting to `.trash/{timestamp}/{path}`. **Security core** in `getFullKey(path)`:

```java
private String getFullKey(String path) {
    if (!StringUtils.hasText(path)) {
        return this.prefix;
    }
    if (path.startsWith("/")) {
        throw new SecurityException("Absolute paths are not allowed: '" + path + "'");
    }
    if (path.startsWith("./")) {
        return this.prefix + path.substring(2);
    }
    return this.prefix + path;
}
```

### 6. Authentication Module

Package: `auth/`

- **`WorkspaceIdentity`**: Identity record (`system`, `agent`, `user`) providing `getWorkspacePath(prefix)` to generate formatted OSS prefix paths.
- **`AuthenticationException`**: Authentication/Authorization parsing exception class.
- **`AuthenticationProvider`**: Authentication abstract interface defining `authenticate(ServerRequest)`.
- **`HeaderAuthenticationProvider`**: Parses identity from `Authorization` header, supporting `system-agent-user` and `system/agent/user` formats (auto-handles `Bearer` prefix and whitespace trimming).

### 7. Snapshot Module

Package: `snapshot/`

- **`SnapshotInfo`**: Snapshot metadata record (`snapshotId`, `filePath`, `action`, `snapshotPath`, `timestamp`).
- **`SnapshotProvider`**: Snapshot service interface defining `createSnapshot`, `listSnapshots`, `rewind`.
- **`DefaultSnapshotProvider`**: `.snapshots/{snapshotId}/` path-based implementation.
  - **Auto pre-snapshots**: Automatically generates snapshots before `Write` (modifying existing files), `Edit`, and `Trash` operations.
  - **Metadata storage**: Records `filePath`, `action`, and millisecond timestamp in `.snapshots/{snapshotId}/meta.txt`.
  - **Safe recovery with double fallback**: When calling `rewind`, the system auto-generates an `action=REWIND` safety snapshot before overwriting the current file, enabling undo-of-undo.
  - **Path hiding**: `.snapshots/` is hardcoded in `StorageProvider.IGNORED_PATH_PATTERN` to avoid polluting directory listings.

### 8. AutoConfiguration

- **`AliyunOssAutoConfiguration`**: Creates `OSS` client bean based on `aliyun.oss.*` configuration.
- **`HarnessMcpServerAutoConfiguration`**: Configures stateless MCP Server transport, auto-wires `AuthenticationProvider` and `StorageProviderFactory` with `@ConditionalOnMissingBean`.
- **`ObservabilityAutoConfiguration`**: Conditionally loads based on `spring.ai.harness.mcp.server.observability.enabled=true`, auto-wires OpenTelemetry `Sampler`, `Resource`, and `SpanExporter` (supports otlp export, none fallback).

### 9. Observability Module

The observability module uses the **Decorator Pattern** for lightweight, pluggable design:
- **`ObservedStorageProvider`**: Decorates all `StorageProvider` methods with Micrometer `Observation` instrumentation.
- **`ObservedSnapshotProvider`**: Decorates snapshot creation and rewind methods.
- **Zero-overhead pluggable assembly**: When observability is disabled, the factory returns raw storage instances — no per-operation `if` branch checks, ensuring zero runtime overhead.

### 10. Storage Quota Module

Package: `storage/`

Per-workspace storage quota, enforced via the Decorator Pattern (pluggable, zero-overhead when off):

- **`QuotaManager`**: Tracks usage via a `.storage` meta file (`usedBytes` + `calculatedAt`), with incremental updates on every write and a 24h full-recalculation interval. **Per-workspace custom limit** via a `.quota` meta file (`limitBytes` + `updatedAt`): `getEffectiveLimit()` returns the custom limit if `>0`, else the global `max-bytes`. `checkQuota()` enforces the effective limit; `getCachedMeta()` reads usage without triggering recalculation (for admin LIST).
- **`QuotaEnforcedStorageProvider`**: Decorator that calls `checkQuota(delta)` before `writeString`/`writeFile`/`rename` (only for positive deltas) and `updateUsedBytes(delta)` after. Excluded paths (`.storage`, `.quota`, and optionally `.snapshots/`/`.trash/`/`.shadow/`) skip both checks. `getFullKey()` security still applies via the delegate.
- **Meta files excluded from usage**: `.storage` and `.quota` never count toward quota; `getExcludePrefixes()` lists them for full recalculation.
- **Admin API**: `QuotaAdminController` (`/api/v1/admin/quota/**`, `X-Admin-Token`) enumerates workspaces and manages custom limits - see §4. Enforcement is in the decorator, not the controller - setting a custom limit takes effect on the next write.
- **Zero-overhead pluggable assembly**: When `quota.enabled=false`, `DefaultStorageProviderFactory` returns the raw `AliyunOssStorage` - no per-operation `if` branch.

---

## Configuration Reference

### Required Configuration

```properties
# Alibaba Cloud OSS Connection
aliyun.oss.endpoint=https://oss-cn-xxx.aliyuncs.com
aliyun.oss.access-key-id=<your-access-key-id>
aliyun.oss.access-key-secret=<your-access-key-secret>

# MCP Server
spring.ai.harness.mcp.server.oss-bucket=<your-bucket-name>
```

### Optional Configuration

```properties
# Workspace path prefix (default: mcp/workspaces/)
spring.ai.harness.mcp.server.oss-prefix=mcp/workspaces/

# MCP endpoint (default: /mcp)
spring.ai.mcp.server.stateless.mcp-endpoint=/mcp

# Observability tracing (default: false)
spring.ai.harness.mcp.server.observability.enabled=false
# Export type: otlp, none (default: otlp)
spring.ai.harness.mcp.server.observability.export-type=otlp
# Sampling probability: 0.0 ~ 1.0 (default: 1.0)
spring.ai.harness.mcp.server.observability.probability=1.0

# Relay streamable-http MCP Server Configuration
spring.ai.harness.mcp.server.relay.enabled=false
spring.ai.harness.mcp.server.relay.url=http://localhost:8081
# Map of static headers (e.g., Authorization)
spring.ai.harness.mcp.server.relay.headers.Authorization=Bearer <downstream-token>

# Temporary Download URL Configuration
spring.ai.harness.mcp.server.download.enabled=true
spring.ai.harness.mcp.server.download.default-ttl=1h
spring.ai.harness.mcp.server.download.max-ttl=8h
spring.ai.harness.mcp.server.download.public-endpoint=

# File Upload Attachment Configuration
spring.ai.harness.mcp.server.attachment.base-path=attachments
spring.ai.harness.mcp.server.attachment.default-conversation-id=default

# Storage Quota Configuration
spring.ai.harness.mcp.server.quota.enabled=true
# Global default limit per workspace (bytes, default 1GB)
spring.ai.harness.mcp.server.quota.max-bytes=1073741824
# Usage meta file (default .storage) / per-workspace custom-limit meta file (default .quota)
spring.ai.harness.mcp.server.quota.meta-file=.storage
spring.ai.harness.mcp.server.quota.limit-file=.quota
# Full recalculation interval (default 24h)
spring.ai.harness.mcp.server.quota.recalculation-interval=24h
# Whether .snapshots/ / .trash/ / .shadow/ count toward quota (defaults: false / true / false)
spring.ai.harness.mcp.server.quota.include-snapshots=false
spring.ai.harness.mcp.server.quota.include-trash=true
spring.ai.harness.mcp.server.quota.include-shadow-cache=false

# Multipart file upload limits
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=100MB
```

---

## Security Standards

### Mandatory

- **Never** introduce code paths that bypass workspace isolation
- **All file path operations** must go through `getFullKey()`, ensuring paths are constrained within the workspace prefix
- **Absolute paths** (starting with `/`) must be rejected with `SecurityException`
- **Authorization header** is the sole identity source, format: `{system}-{agent}-{user}`, must be strictly validated
- **Path resolution & OSS mechanics**: In the OSS native SDK, Object Keys are literal strings — `..` is not resolved as parent directory like in POSIX filesystems. After `getFullKey()` prepends the prefix, keys containing `..` still reside under the prefix. However, when normalizing paths for POSIX sandbox mounting, `..` variants should still be stripped
- **Do not expose** OSS bucket, prefix, or other internal storage details to agents
- **Error messages** in tool methods must not contain full OSS key paths — return relative paths only

### Security TODOs

> ⚠️ The following are known security hardening items to prioritize during development:

1. Authorization header parsing needs strengthening (currently simple `-` splitting, pending refactor to independent auth module)
2. Path normalization to sanitize `./foo/../bar` and similar irregular paths
3. Consider POSIX symlink/path escape risks after sandbox mounting

---

## Development Standards

### Tech Stack

- **Java 17+**
- **Spring Boot 3.5.x**
- **Spring AI 1.1.x** (MCP Server implementation)
- **Aliyun OSS SDK 3.18.x**
- **Lombok** (`@Data`, `@Slf4j`, etc.)
- **MCP Annotations**: `@McpTool`, `@McpToolParam` (from `org.springaicommunity.mcp.annotation`)
- **Spring AI PDF Document Reader** (PDF parsing via `PagePdfDocumentReader`)
- **Spring AI Tika Document Reader** (Office document parsing via `TikaDocumentReader`)

### Coding Conventions

- Use Chinese comments, English code.
- `@McpTool` `description` uses English (LLM-facing prompt); internal logic comments use Chinese.
- Use Lombok to reduce boilerplate (`@Data`, `@Getter/@Setter`, `@Slf4j`).
- **Logging**: `System.out.println` is strictly forbidden. Use SLF4J (`log.info()`, `log.error()`).
- **Java 17 features**: DTOs and read-only metadata models prefer `record` declarations for immutability.
- **Parameter validation**: Use Jakarta Bean Validation (`@NotNull`, `@NotBlank`, `@Size`) for input boundary constraints.
- Configuration properties use `@ConfigurationProperties`.
- Auto-configuration classes use `@ConditionalOnMissingBean` for extensibility.
- Follow Spring's `// @formatter:off` / `// @formatter:on` comment-based formatting control.
- **No hardcoded FQCNs**: Classes must be imported via `import` statements. Never use full `packageName.ClassName` in method signatures, variable types, or `new` instantiations.
- **Constant changes must sync prompts**: Constants in `StorageProvider` marked with sync notes (e.g., `MAX_LINES`, `MAX_LINE_LENGTH`, `MAX_IMAGE_EDGE`) are coupled to `@McpTool` description text — both sides must be updated together.
- **Streaming file processing**: All file content parsing must go through `FileContentProcessor` using `InputStream`-based streaming. Storage implementations must only handle byte transport and delegate formatting to `FileContentProcessor`.

### Layer Responsibilities

| Layer | Responsibility | Security Boundary |
|-------|---------------|-------------------|
| `auth/` | Request identity extraction & validation, producing `WorkspaceIdentity` | Header parsing, token sanitization, format error rejection |
| `tool/` | MCP tool definitions & parameter validation, uses `StorageProviderFactory` for isolated storage | Parameter format validation, invalid path rejection |
| `storage/` | Storage abstraction, factory pattern, OSS implementation, streaming content processing via `FileContentProcessor` | Path isolation, absolute path rejection |
| `autoconfig/` | Spring Boot auto-configuration, assembling Server, Auth & StorageFactory beans | Config injection, pluggable component replacement |

### Adding a New MCP Tool

1. Add an `@McpTool` method in `FileSystemTools`
2. First parameter must be `McpTransportContext context`
3. Call `getStorageProvider(context)` to obtain an isolated `StorageProvider`
4. Define the new operation in the `StorageProvider` interface
5. Implement in `AliyunOssStorage` (delegate content processing to `FileContentProcessor`)
6. Write unit tests (mock OSS client)
7. Ensure all path operations go through `getFullKey()` for security validation

### Adding a New StorageProvider Implementation

1. Implement the `StorageProvider` interface
2. Ensure `getFullKey()` or equivalent rejects absolute paths and `..` traversal
3. Delegate all content parsing to `FileContentProcessor` (pass `InputStream` directly)
4. Write corresponding `AutoConfiguration` and `Properties` classes
5. Add `@ConditionalOnMissingBean` or `@ConditionalOnProperty` to avoid conflicts

---

## Testing

### Build & Test Commands

```bash
# Compile this module only
./mvnw compile -pl spring-ai-harness-mcp-server

# Run this module's unit tests
./mvnw test -pl spring-ai-harness-mcp-server

# Run full test suite
./mvnw test
```

### Test Conventions & Quality Guardrails

- Use **JUnit 5** + **Mockito** + **AssertJ** for testing.
- **Mocking strategy**: All tests involving `AliyunOssStorage` must fully mock the `OSS` client and network requests via Mockito. Connecting to physical OSS in unit tests is strictly forbidden.
- **Coverage threshold**: Core logic classes (path resolution, permission validation, content processing, etc.) must achieve **80%+** line coverage and branch coverage.
- Security-related test cases (path escape, absolute path rejection, etc.) must be fully covered.
- Test class naming: `{TestedClass}Test.java`

---

## Related Modules

- [`spring-ai-harness-utils`](../spring-ai-harness-utils/): Provides Context Compact and other advisor capabilities for agent loop context management
- [`spring-ai-harness-utils-bom`](../spring-ai-harness-utils-bom/): BOM (Bill of Materials) version management
- [`examples/`](../examples/): Example projects

---

## Documentation Maintenance

> [!CAUTION]
> **This document is a Living Document** and must be maintained in sync with code changes. Outdated documentation is more misleading than no documentation.

### When to Update This Document

The following situations **require** updating this AGENTS.md:

- **Add/remove/rename** MCP tool methods (`@McpTool`)
- **Add/remove/rename** source files, packages, or module structure changes
- **Modify** `StorageProvider` interface contracts (new methods, constant changes, etc.)
- **Modify** `FileContentProcessor` methods or add new processing capabilities
- **Add** new `StorageProvider` implementations (e.g., extending from OSS to other storage)
- **Modify** security-related logic (path validation, Authorization parsing, workspace isolation)
- **Modify** configuration properties (add/remove/rename `@ConfigurationProperties` fields)
- **Complete** planned features (sandbox integration, file versioning/snapshots, etc.)
- **Fix** security TODO items
- **Change** tech stack versions (Spring Boot, Spring AI, OSS SDK major upgrades)

### How to Update

1. Locate the affected sections in this document
2. Update descriptions, tables, code examples, or architecture diagrams to match the code
3. If a feature moves from "planned" to "implemented", move it from the planning section to the appropriate formal section
4. If new TODOs are identified, add them to "Security TODOs" or the relevant section
5. Ensure the module structure tree matches the actual files
