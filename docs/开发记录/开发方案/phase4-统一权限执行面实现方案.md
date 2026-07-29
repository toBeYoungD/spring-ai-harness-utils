# phase4-统一权限执行面实现方案

> 实现 Phase 4：一个 `PermissionEnforcedStorageProvider` 装饰器，每次 StorageProvider 调用时一次性完成工具权限 + 文件 ACL 两份判定。调用方只需包这一个装饰器。配置规则来自 Phase 3 的两套 yaml/JSON。

---

## 一、需求背景

- Phase 3 开发了两套配置面：管理员工具权限管理台 + 用户自服务文件 ACL 管理台，各有 REST 接口和 yaml/JSON 持久化。
- 但配置面只产出规则，不执行拦截。需要 Phase 4 开发一个**统一的执行面装饰器**，在每次 `StorageProvider` 调用时，一次性完成全部权限判定——工具权限 + 文件 ACL，合为一次判定。
- 之前的两份设计文档（MCP 工具权限隔离-开发方案、文件权限-应用端开发方案）各自独立拦截，导致调用方需要同时维护切面和装饰器两处。Phase 4 把它们合为一个装饰器，作为**唯一切入点**。

## 二、范围

| 维度 | 本期 | 不做 |
|---|---|---|
| 执行面（mcp-server） | 统一 PermissionEnforcedStorageProvider 装饰器 | 分散的切面+装饰器（弃用） |
| 执行面（utils SDK） | 统一 PermissionEnforcedStorageProvider 装饰器 + StorageProviderTools 改造 | — |
| 配置来源 | 加载 Phase 3 产出的 yaml + `.acl/rules.json` | 不重复开发配置面 |
| 策略通道 | 启动加载 + 本地缓存 + `.acl/.version` 变更检查 | 不做热下发/Redis/DB |

## 三、总体设计

### 3.1 唯一入口

```
mcp-server 每请求:
  Authorization -> WorkspaceIdentity
    -> StorageProviderFactory.getStorageProvider(ctx)
    -> AliyunOssStorage -> [quota] -> [observed] -> [PermissionEnforced 最外层]
                                                              |
                                             每次存储调用时统一执行一次判定（3.2）


utils SDK 应用装配:
  base = LocalFileStorage(workspaceRoot)
  guarded = new PermissionEnforcedStorageProvider(base, permissionConfig)
  tools = StorageProviderTools.builder(guarded).build()
  // 此后所有存储调用自动过判定，无需额外代码
```

### 3.2 统一判定流程

每次 `StorageProvider` 方法被调用时，装饰器按以下流程判定：

```
① 内部路径豁免
   .snapshots/ .trash/ .shadow/ .acl/ ?
     -> 是: 跳过全部判定，直接 delegate

② 工具权限判定
   mcp-server: 从 ThreadLocal/McpTransportContext 取当前工具名
     tool in allowedTools? 否 -> PermissionDeniedException
   utils SDK: 无工具上下文，跳过本步
     (工具筛选已在装配期完成，装饰器只做路径 ACL)

③ 文件 ACL 判定
   取所有 glob 匹配 path 的规则，按 priority 最大者
     同级最具体优先: DENY > WRITE > READ
     DENY -> PermissionDeniedException
     READ 且当前是 write 操作 -> PermissionDeniedException
     WRITE -> 读写都放行

④ delegate 调用真正的存储方法
```

### 3.3 配置模型

```java
record PermissionConfig(
    boolean enabled,
    ToolPermissionConfig toolPermission,   // Phase 3a 产出: allowedTools
    FileAclConfig fileAcl                  // Phase 3b 产出: rules
) {}

record AclRule(String pattern, Access access, int priority) {}
enum Access { DENY, READ, WRITE }
```

配置从 Phase 3 的两套持久化中加载，合并为一个 `PermissionConfig` 实例注入装饰器。

### 3.4 对调用方来说

```java
// mcp-server: 工厂自动装配，请求级隔离，无需额外代码
// PermissionEnforced 已包在装饰链最外层

// utils SDK:
PermissionConfig config = loadFromPhase3Persistence(workspaceIdentity);
StorageProvider guarded = new PermissionEnforcedStorageProvider(base, config);
StorageProviderTools tools = StorageProviderTools.builder(guarded).build();
// 完成——此后所有存储调用自动过判定
```

## 四、mcp-server 侧改动

### 4.1 新增装饰器

`storage/PermissionEnforcedStorageProvider.java`，实现 `StorageProvider`：

- 构造：`new PermissionEnforcedStorageProvider(delegate, permissionConfig, identity)`
- `subDirProvider(subDir)`：重新包装，pathPrefix 还原
- 每个存储方法：路径规范化 -> 拼接 pathPrefix -> 内部路径豁免 -> 工具权限判定 -> 文件 ACL 判定 -> delegate/拒绝

内部路径豁免：`.snapshots/` `.trash/` `.shadow/` `.acl/` 匹配 `INTERNAL_PATH_PATTERN` 的路径一律放行（否则快照/回滚/回收站全部失效）。

### 4.2 工具上下文

mcp-server 场景下需要知道"当前调用属于哪个 MCP 工具"，从 `McpTransportContext` 或 Phase 2 审计切面已写入的 ThreadLocal 中读取。无需新增切面。

### 4.3 工厂装配

`DefaultStorageProviderFactory` 装饰链最外层加 `PermissionEnforcedStorageProvider`：

```
AliyunOssStorage -> [quota] -> [observed] -> [PermissionEnforced 最外层]
```

`permission.enabled=false` 时不包装，零开销。

### 4.4 策略通道

- 启动加载：从 yaml (`PermissionProperties`) + `.acl/rules.json` 加载所有工作区的 `PermissionConfig`，缓存到内存
- 变更检查：每次请求时检查 `.acl/.version`，版本号变了则重新加载该工作区规则
- 本期：仅启动加载，增量变更检查后续迭代

### 4.5 异常出口

`PermissionDeniedException`（RuntimeException），`GlobalRestExceptionHandler` 新增 403 映射：

```json
{
  "error": "permission denied",
  "tool": "write",
  "path": "secrets/keys.txt",
  "reason": "file ACL: DENY by rule secrets/** (priority 10)"
}
```

## 五、utils SDK 侧改动

### 5.1 新增装饰器

`storage/PermissionEnforcedStorageProvider.java`（实现 utils 那份 `StorageProvider` 接口）：

- 与 mcp-server 版共用同一判定逻辑，区别：无工具上下文（跳过步骤②）
- 路径规范化 + pathPrefix 还原一致
- 拒绝抛 `PermissionDeniedException`

### 5.2 StorageProviderTools 改造

五个工具方法（`read`/`write`/`edit`/`glob`/`grep`）增加 `catch (PermissionDeniedException e)` 返回友好错误串：

```
"Error: permission denied: secrets/keys.txt (rule secrets/** DENY)"
```

与现有 `"Error: File does not exist"` 等模式一致。当前 Read/Edit/Grep 只 catch `IOException`，需增加 catch 分支；Write/Glob 已有 `catch Exception`，可精简为显式 catch。

## 六、实现步骤

| 步骤 | 内容 | 产出 |
|---|---|---|
| 1 | `PermissionConfig` 配置模型 + `AclRule` + `Access` 枚举 | 数据模型 |
| 2 | `FileAclMatcher` 判定引擎（AntPathMatcher + priority + 路径规范化） | 核心判定器 |
| 3 | mcp-server `PermissionEnforcedStorageProvider` 装饰器 | mcp-server 统一装饰器 |
| 4 | utils `PermissionEnforcedStorageProvider` 装饰器 | utils SDK 统一装饰器 |
| 5 | `StorageProviderTools` 五个方法加 `catch (PermissionDeniedException)` | 友好错误 |
| 6 | `DefaultStorageProviderFactory` 装饰链加 permission 最外层 | 工厂装配 |
| 7 | 策略通道：yaml + rules.json 加载 + 本地缓存 | 配置落地 |
| 8 | `GlobalRestExceptionHandler` 新增 403 映射 | 异常出口 |
| 9 | 集成测试：配置 -> 装饰器 -> StorageProviderTools -> 端到端 | 验证 |
| 10 | 文档：dev-spec + AGENTS.md 同步 | 文档交付 |

## 七、管理台全貌

```
App.js 暗色导航栏 + 主题切换
├─ File Manager          (暗色，原有)
├─ MCP Client Debugger   (暗色，原有)
├─ 配额管理               (亮色，dev-spec 01)
├─ 日志审计               (亮色，dev-spec 01)
├─ 工具权限               (亮色，Phase 3a，管理员侧)
└─ 文件 ACL              (亮色，Phase 3b，用户自服务)
```

五个亮色管理台共享 `console-light.css`、统一页头结构、统一分页。Phase 4 不新增 UI——只开发后端装饰器。

## 八、Phase 3 与 Phase 4 的职责分界

```
Phase 3（配置面）                  Phase 4（执行面）
─────────────────────────         ─────────────────────────
工具权限管理台 + REST              规则被加载到
  ↓ 写入 yaml                       ↓
文件 ACL 管理台 + REST           PermissionConfig 实例
  ↓ 写入 .acl/rules.json            ↓
                               PermissionEnforcedStorageProvider
                               （唯一判定位点）
                                  ↓
                               每次 StorageProvider 调用
                               一次性完成全部判定
```

Phase 3 = "配置什么"，Phase 4 = "怎么拦住"。
