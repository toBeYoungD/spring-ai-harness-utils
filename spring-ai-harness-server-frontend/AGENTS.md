# AGENTS.md — spring-ai-harness-server-frontend

Spring AI Harness Server Web UI Manager module for OSS File & Workspace Management.

## Module Architecture

```
spring-ai-harness-server-frontend/
├── .agents/
│   └── AGENTS.md                # Dedicated frontend documentation & guidelines
├── index.html                   # Entry HTML with custom fonts & script injection
├── vite.config.js               # Vite project configuration with proxies & react plugin
├── src/
│   ├── components/
│   │   ├── FileExplorer.js      # Main Windows Explorer style file manager
│   │   ├── FileListTable.js     # List view component with Ant Design Table
│   │   ├── FileGridCards.js     # Grid view component with Ant Design Cards
│   │   ├── FileViewerModal.js   # In-browser text editor & preview modal
│   │   ├── SnapshotDrawer.js    # Snapshot history & Rewind drawer
│   │   ├── NewItemModal.js      # Modal for creating new text files & folders
│   │   ├── McpDebugger.js       # MCP Client debugger panel with JSON-RPC wire inspector (dark)
│   │   ├── QuotaConsole.js      # Quota management console (light, scoped theme)
│   │   └── AuditConsole.js      # Audit log console (light, scoped theme)
│   ├── services/
│   │   └── api.js               # Axios REST client (Workspace/Admin APIs & /mcp calls; quota/audit still mock)
│   ├── styles/
│   │   ├── index.css            # Dark mode tokens, glassmorphism, micro-animations
│   │   └── console-light.css    # Light theme for quota/audit consoles (scoped isolation)
│   ├── App.js                   # Root React layout containing the navigation header
│   └── index.js                 # React DOM root entry
└── package.json                 # React project configuration with Vite scripts
```

---

## Tech Stack & Design Rules

- **Framework**: React 18 (JavaScript) with Vite build system.
- **UI Library**: Ant Design (`antd` v4.7) + `@ant-design/icons` 4.7 + `moment`. (Downgraded from v5; existing components migrated to v4 API — `Tabs.TabPane`, `Modal/Drawer visible`, `Breadcrumb.Item`, `ConfigProvider` without `theme`.)
- **Styling**: Existing components use the dark glassmorphism theme (`index.css`); the new quota/audit consoles use a scoped light theme (`console-light.css` under `.console-light`/`.console-modal`/`.console-drawer`). Theme unification is deferred.
- **State & Drag & Drop**: Native HTML5 Drag & Drop API for moving files/folders across breadcrumb paths and target folders.
- **MCP Client Debugging**: Calls standard JSON-RPC 2.0 methods (`tools/list`, `tools/call`, `resources/list`, `resources/read`) on `/mcp` via HTTP POST, displaying the raw wire traffic in a split inspector view.

---

## Frontend Coding & Design Standards

### UI & Component Guidelines
- **Ant Design first**: Do not hand-write basic layouts arbitrarily. Prefer and reuse Ant Design v4 layout and typography components (`Space`, `Row`, `Col`, `Table`, `Card`). (Note: `Flex` is v5-only and unavailable after the v4.7 downgrade.)
- **Style consistency**: Do not introduce TailwindCSS or heavy CSS-in-JS libraries. Existing components use the dark glassmorphism theme from `styles/index.css`; quota/audit consoles use the scoped light theme from `styles/console-light.css`. Use unified custom Theme Tokens (avoid hardcoded hex colors). Theme unification is deferred.
- **Icon usage**: Always use `@ant-design/icons` with per-component imports for tree-shaking optimization by Vite/Rollup.
- **Form validation**: For complex forms, use Ant Design's `Form` component with built-in validation rules for maintainability.

### Code Style Conventions
- **Component pattern**: Prefer Functional Components + React Hooks. Use `useMemo` and `useCallback` for complex calculations or child prop passing to avoid unnecessary re-renders.
- **Naming conventions**:
  * Component files: `PascalCase` (e.g., `FileListTable.js`)
  * Custom hooks: `camelCase` starting with `use`
  * Utility/non-component files: `kebab-case`
- **Destructured props**: Component props should be destructured directly in the function signature for readability.
- **Minimize state pollution**: Avoid unnecessary state synchronization or overly nested state tree designs.

---

## Available Scripts

- `npm run dev`: Runs the app in development mode at http://localhost:3000
- `npm run build`: Bundles the app for production in the `dist/` folder.
- `npm run preview`: Previews the production build locally.

---

## Testing & Quality Requirements

- **Unit test target**: Write tests for public components, custom hooks, and utility functions when possible, ensuring robustness of core tool functions and data sanitization logic.
- **Business logic coverage**: Target coverage should reach **80%**.

---

## AI Assistant Development Guidelines & Quality Guardrails

1. **Style constraints**: Never introduce unrequested third-party style libraries (e.g., TailwindCSS). Always reuse existing glassmorphism styles and dark theme tokens.
2. **State update performance**: Be mindful of React 18 state batching characteristics. Avoid excessive re-renders that impact drag-and-drop fluidity in large file list views.
3. **API type safety**: When calling `services/api.js` to send requests, validate parameter boundaries carefully — absolute file paths will cause backend API errors.
4. **Surgical edits**: When modifying component code, maintain consistent indentation and formatting within the component. Minimize unnecessary format changes.
