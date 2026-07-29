# Workspace AGENTS.md — Spring AI Harness Utils

> [!IMPORTANT]
> **This project is under active development.** There are features still in progress, areas to polish, and libraries to supplement.
> This document is a **Living Document** at the project root, consolidating the technical architecture and coding standards for both the backend (`mcp-server`) and frontend (`server-frontend`) modules.
> Sub-module `AGENTS.md` files are kept in sync. When modifying code or architecture, **all related AGENTS.md files (root and sub-modules) MUST be updated accordingly**.

---

## Root Architecture Overview

The project consists of two core sub-modules:
1. **`spring-ai-harness-mcp-server`**: A stateless server built on Spring Boot & Spring AI MCP, providing workspace-isolated secure path filtering, Snapshot creation & version rollback, streaming multimedia file processing, and pluggable OTel tracing.
2. **`spring-ai-harness-server-frontend`**: A management Web console built with React 18 + Ant Design 4.7 + Vite, featuring a Windows Explorer-style file manager, an MCP Client debugger, a quota management console, and an audit log console.

### Unified Design Principles & Security Standards
- **No hardcoded FQCNs in code**: Classes must be imported via explicit `import` statements. Using full `packageName.ClassName` paths in method signatures, type declarations, or `new` instantiations is strictly forbidden.
- **Complete workspace isolation**: All underlying physical file operations must bind to the `{system}-{agent}-{user}` prefix. All paths must be validated before processing; absolute paths (starting with `/`) must be rejected with a `SecurityException`.
- **Documentation consistency first**: Any changes to constants (e.g., read line limits, image edge limits) must also update the corresponding `@McpTool` English descriptions for LLM consumption. Both sides must stay in sync.

---

## 1. Backend Module (spring-ai-harness-mcp-server)

### 1.1 Architecture & Isolation Model

#### Isolation Model Diagram
```
oss://{bucket}/{ossPrefix}/{system}-{agent}-{user}/
       │         │           │        │       │
       │         │           │        │       └── User-level isolation (delimiter: -)
       │         │           │        └────────── Agent-level isolation (delimiter: -)
       │         │           └─────────────────── System-level isolation
       │         └─────────────────────────────── Configurable prefix (default: mcp/workspaces/)
       └───────────────────────────────────────── OSS Bucket
```

From the agent's perspective, `pwd` defaults to `./`. The agent cannot perceive its full physical OSS path `oss://{bucket}/mcp/workspaces/{system}-{agent}-{user}/`.

#### Core Component Responsibilities

| Package | Responsibility | Security Boundary |
| :--- | :--- | :--- |
| `auth/` | Extract and validate identity from Headers, producing `WorkspaceIdentity` | Token sanitization, whitespace trimming, invalid format rejection |
| `tool/` | Define `@McpTool` tools and handle basic parameter validation | Parameter range checks, injection prevention |
| `storage/` | Physical file read/write/trash operations, factory assembly, and streaming content processing via `FileContentProcessor` | Absolute path interception, escape logic prevention |
| `snapshot/` | Version control management, pre-write and rollback snapshots | Snapshot hiding, recovery fallback |
| `autoconfig/` | Assemble the above beans, attach pluggable `ObservationRegistry` wrappers | Lightweight pluggable tracing control |

### 1.2 Required Configuration
```properties
# Alibaba Cloud OSS Connection
aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
aliyun.oss.access-key-id=<your-access-key-id>
aliyun.oss.access-key-secret=<your-access-key-secret>
spring.ai.harness.mcp.server.oss-bucket=<your-bucket-name>

# Observability Tracing (default: false)
spring.ai.harness.mcp.server.observability.enabled=false
# Export type: otlp, none (default: otlp)
spring.ai.harness.mcp.server.observability.export-type=otlp

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

# Multipart file upload limits
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=100MB
```

### 1.3 Backend Coding & Quality Standards

#### Coding Style Conventions
- **Logging**: Direct console output via `System.out.println` is strictly forbidden. Use SLF4J interfaces (via Lombok `@Slf4j`) for structured logging (`log.info`, `log.error`).
- **Java 17 Features**: DTOs and read-only metadata models should prefer Java 17 `record` declarations for immutability.
- **Parameter Validation**: Input-layer parameters must use Jakarta Bean Validation (`@NotNull`, `@Size`) for field boundary constraints.
- **Tool Descriptions**: `@McpTool` method descriptions and parameter annotations must be in **English** (for LLM comprehension), while business logic comments in code must be in **Chinese**.
- **Streaming File Processing**: All file content parsing (text, images, PDFs, Office documents) must be performed through `FileContentProcessor` using `InputStream`-based streaming to prevent OOM risks with large files. Storage implementations (`AliyunOssStorage`, etc.) should only handle byte transport, delegating content formatting to `FileContentProcessor`.

#### Unit Testing & Coverage Requirements
- **Full coverage**: Every new business logic or utility class must have a corresponding JUnit 5 test class.
- **Coverage threshold**: Core logic classes (path resolution, permission validation, content processing, etc.) must achieve **80%+** line coverage and branch coverage.
- **Mocking strategy**: All tests involving `AliyunOssStorage` must fully mock the `OSS` client and network requests via Mockito. Connecting to physical Alibaba Cloud OSS is strictly forbidden.

---

## 2. Frontend Module (spring-ai-harness-server-frontend)

### 2.1 Tech Stack & Architecture
- **Core Framework**: React 18 (JavaScript) + Vite build chain.
- **Component System**: Ant Design (`antd` v4.7). Existing components use the dark glassmorphism theme (`styles/index.css`); the new quota/audit consoles use a scoped light theme (`styles/console-light.css` under `.console-light`/`.console-modal`/`.console-drawer`). Theme unification is deferred.
- **File Drag & Drop**: HTML5 native Drag & Drop API for cross-breadcrumb and directory drag-move-rename.
- **Build Scripts**:
  * Dev hot reload: `npm run dev` (port 3000)
  * Production build: `npm run build` (output to `dist/`)

```
spring-ai-harness-server-frontend/
├── index.html                   # Entry HTML with ESM script imports
├── vite.config.js               # Vite config (JSX Esbuild transform & proxy forwarding)
├── src/
│   ├── components/
│   │   ├── FileExplorer.js      # Windows Explorer-style main component (dark)
│   │   ├── FileListTable.js     # Table list view component
│   │   ├── FileGridCards.js     # Grid card view component
│   │   ├── FileViewerModal.js   # In-browser text editor & preview modal
│   │   ├── NewItemModal.js      # Modal for creating new files/folders
│   │   ├── McpDebugger.js       # Built-in MCP Client JSON-RPC debug panel (dark)
│   │   ├── SnapshotDrawer.js    # Sidebar snapshot list & Rewind console
│   │   ├── QuotaConsole.js      # Quota management console (light, scoped)
│   │   └── AuditConsole.js      # Audit log console (light, scoped)
│   ├── services/
│   │   └── api.js               # Axios backend service & /mcp dispatcher (quota/audit still mock)
│   ├── styles/
│   │   ├── index.css            # Dark glassmorphism theme tokens
│   │   └── console-light.css    # Light theme for quota/audit consoles (scoped isolation)
│   ├── App.js                   # Top-level sticky navbar & view switching
│   └── index.js                 # React DOM root entry
```

### 2.2 Dev Server Reverse Proxy
During local development (`npm run dev`), Vite's dev proxy transparently routes matching traffic to the Spring Boot backend:
- `/api/**` -> `http://localhost:8080/api/**` (Business REST API)
- `/mcp` -> `http://localhost:8080/mcp` (Stateless JSON-RPC endpoint)

### 2.3 Frontend Coding & Design Standards

#### UI & Component Guidelines
- **Ant Design first**: Do not hand-write basic layouts arbitrarily. Prefer and reuse Ant Design v4 layout components (`Space`, `Row`, `Col`, `Table`, `Card`). (Note: `Flex` is v5-only and unavailable after the v4.7 downgrade.)
- **Style consistency**: Do not introduce TailwindCSS or heavy CSS-in-JS libraries. Existing components use the dark glassmorphism theme from `styles/index.css`; the quota/audit consoles use the scoped light theme from `styles/console-light.css`. Use unified custom Theme Tokens (avoid hardcoded hex colors). Theme unification is deferred.
- **Icon usage**: Always use `@ant-design/icons` with per-component imports for tree-shaking optimization by Vite/Rollup.

#### Code Style Conventions
- **Component pattern**: Prefer Functional Components + React Hooks. Use `useMemo` and `useCallback` for complex calculations or child prop passing to avoid unnecessary re-renders.
- **Naming conventions**:
  * Component files: `PascalCase` (e.g., `FileListTable.js`)
  * Custom hooks: `camelCase` starting with `use`
  * Utility/non-component files: `kebab-case`
- **Destructured props**: Component props should be destructured directly in the function signature for readability.

---

## 3. Global Build & Test Commands

### Build & Test Backend
```bash
# Compile backend
./mvnw compile -pl spring-ai-harness-mcp-server

# Run backend unit tests (all tests must be fully mocked, no physical OSS connection)
./mvnw test -pl spring-ai-harness-mcp-server
```

### Build & Run Frontend
```bash
# Enter frontend directory
cd spring-ai-harness-server-frontend

# Install dependencies
npm install

# Offline fetch Windows-native esbuild/rollup deps (only when packaging offline bundles for Windows from Mac)
npm install --os=win32 --cpu=x64

# Dev hot reload
npm run dev

# Production build
npm run build
```

---

## 4. AI Assistant Development Guidelines & Quality Guardrails

1. **Safety first (Surgical Edits)**: When modifying existing components or code logic, make minimal "surgical" changes. Preserve existing formatting and indentation. Never introduce file system paths that escape workspace isolation boundaries.
2. **Test alongside development**: After adding any new method or logic fix, immediately run the full unit test suite (`./mvnw test`) to confirm no regression has occurred.
3. **Configuration file guardrails**: Never modify public Maven or npm repository source URLs in `pom.xml`, `package.json`, or local build configurations without explicit approval.
