# 文件系统 MCP 工具权限隔离 — 开发方案

> **面向读者**：熟悉本仓库 mcp-server 模块的开发人员。阅读前建议先了解 [安全体系技术方案](spring-ai-harness-安全体系技术方案(正式版).md) 的五层纵深防御模型。
>
> **状态**：方案待评审。两个待确认事项见文末「待确认事项」。

---

## 一、背景与目标

### 1.1 需求

为文件系统的 MCP 工具增加**权限隔离**，包含两个正交维度：

1. **工具权限**：某用户能使用某工具、不能使用另一工具。例如用户 A 可用 `Read`、不可用 `Edit`。
2. **文件 ACL**：用户在使用工具时，工作区内部分文件可读/可写、部分不可读/不可写。例如可读 `src/**`、不可读 `secrets/**`。

用户角色与用户绑定，正式形态通过查询数据库或接口获取关联关系；**本期用 Spring 配置文件简单替代该查询过程**，后续可平滑替换为 DB/HTTP 实现。

另需一个**工具白名单**：名单内的工具可**绕过文件 ACL**（仍受工作区隔离前缀约束，不绕过隔离）。

### 1.2 与现有安全体系的关系

当前 mcp-server 已有**五层纵深防御**：认证 → 工作区隔离 → 路径安全 → 快照回滚 → 配额管控。其中「工作区隔离」通过 `{system}-{agent}-{user}` 前缀实现，使不同身份之间**越权在架构层面不可表达**（拿不到别的身份的存储实例）。

本方案新增**第六层：权限管控**，但它处理的是**同一身份内的运行时 ACL**——即同一工作区前缀下、部分文件/工具对当前用户不可用。这本质是运行时校验，是对既有「类型安全替代运行时校验」哲学的**补充而非颠覆**：隔离层保证跨身份不可越界，权限层在同一身份内做细粒度管控。

整个特性由 `permission.enabled` 控制，**默认关闭**。关闭时工厂返回原始存储、切面不激活，**零开销**（符合「零开销可插拔」原则）。

---

## 二、现状分析

基于对仓库代码的探查，关键事实如下：

| 维度 | 现状 |
|---|---|
| 身份模型 | `WorkspaceIdentity(system, agent, user)`，仅三段，**无角色/权限概念**。`HeaderAuthenticationProvider` 从 `Authorization` 头解析，不涉及权限。 |
| 工具注册 | `@McpTool` 注解扫描注册（`spring.ai.mcp.server.annotation-scanner.enabled=true`），每个方法**首参恒为 `McpTransportContext`**。无手动 `ToolCallback` 注册点。 |
| 拦截机制 | **无任何切面/拦截器/Filter** 包裹工具执行。 |
| 存储装配 | `StorageProviderFactory.getStorageProvider(context)` 每请求构造 `AliyunOssStorage` + 装饰器链（`QuotaEnforcedStorageProvider` → `ObservedStorageProvider`）。 |
| `StorageProvider` 接口 | 有 read/write/list/glob/grep/trash/delete/rename 等，**无 `edit()`**——`Edit` 是工具层 read-modify-write（`readString`+`writeString`+快照）。 |
| 异常出口 | `GlobalRestExceptionHandler` 已映射 `SecurityException→400`、`AuthenticationException→401`、`QuotaExceededException→413`。`adminToken` 配置项已声明但未被引用。 |
| AOP 依赖 | pom 未显式声明 `spring-boot-starter-aop`，actuator 不带入 aspectjweaver。 |
| 模块边界 | mcp-server 模块**自包含**，与 utils 模块各自有一份 `StorageProvider`（split-package）。本方案只动 mcp-server 那份。 |

**关键结论**：

- **唯一能同时覆盖 FileSystemTools / SkillTools / RelayTools 的拦截点**是工具层切面（`RelayTools` 不经过存储层，存储装饰器管不到它）。
- **文件 ACL 的天然落点**是 `StorageProvider` 装饰器（仿 `QuotaEnforcedStorageProvider`），且能统一覆盖经存储层的所有调用方（含 REST 控制器）。
- `Edit` 无独立存储方法，但经 `readString`+`writeString`，会被文件 ACL 装饰器天然覆盖。

---

## 三、总体设计

### 3.1 三层结构

```
                    ┌─────────────────────────────────────┐
  Authorization 头 → │  ① 角色解析 RoleResolver            │  ConfigRoleResolver 读 yml（替代 DB）
                    │     WorkspaceIdentity → Set<角色>    │  未来可换 DB/HTTP 实现
                    └─────────────────────────────────────┘
                                    │
            ┌───────────────────────┴───────────────────────┐
            ▼                                               ▼
  ┌─────────────────────────┐                ┌──────────────────────────────┐
  │ ② 工具权限切面           │                │ ③ 文件 ACL 装饰器             │
  │   @Around @McpTool       │  写 ThreadLocal │   PermissionEnforcedStorage   │
  │   按 @McpTool#name() 判  │ ──────────────→ │   按角色 path ACL 拦 read/write│
  │   拒绝 → ForbiddenException                │   bypass 工具跳过（读 ThreadLocal）│
  └─────────────────────────┘                └──────────────────────────────┘
            │                                               │
            │  覆盖 FileSystemTools / SkillTools / RelayTools │  覆盖经存储层的调用
            └───────────────────────┬───────────────────────┘
                                    ▼
                      StorageProviderFactory 装配链：
        base(AliyunOssStorage) → [quota] → [observed] → [permission 最外层]
```

| 层 | 拦截点 | 覆盖范围 | 实现 |
|---|---|---|---|
| ① 角色解析 | —（被②③消费） | — | `RoleResolver` 接口 + `ConfigRoleResolver` |
| ② 工具权限 | `@McpTool` 切面，**调用时拒绝** | FileSystemTools / SkillTools / RelayTools | `ToolPermissionAspect` |
| ③ 文件 ACL | `StorageProvider` 装饰器 | FileSystemTools / SkillTools（经存储层） | `PermissionEnforcedStorageProvider` |
| 白名单旁路 | 切面写 ThreadLocal，装饰器读 | `bypass-file-acl-tools` 名单内工具跳过 ACL | `ToolPermissionContext` |

### 3.2 两个维度的拦截位置

- **工具权限**：切面在 `proceed()` 前判定。**仅调用时拒绝，不改动 `tools/list` 可见性**（按需求方明确指示）。
- **文件 ACL**：装饰器在每次存储调用前判定路径可读/可写。
- **白名单旁路**：切面 `proceed()` 前往 ThreadLocal 写入当前工具名 + bypass 标志；装饰器读取，bypass=true 则跳过 ACL。**仍受工作区隔离前缀约束，不绕过隔离**。

---

## 四、配置模型

新增 `PermissionProperties` 内嵌类，挂到 `HarnessMcpServerProperties`（命名空间 `spring.ai.harness.mcp.server.permission.*`）。

```yaml
spring:
  ai:
    harness:
      mcp:
        server:
          permission:
            enabled: false                       # 总开关，默认关闭（零开销可插拔）
            default-policy: allow-all            # 未映射身份的默认策略：allow-all(向后兼容) / deny-all
            bypass-file-acl-tools:               # 绕过文件 ACL 的工具白名单（不绕过隔离前缀）
              - list_skills
              - read_skill
              - list_snapshots
            user-roles:                          # 用户 -> 角色（完整身份 system-agent-user 作键，替代 DB 查询）
              openclaw-code-assistant-alice: [reader]
              openclaw-code-assistant-bob:   [editor]
              openclaw-code-assistant-admin: [admin]
            roles:                               # 角色权限定义
              reader:
                allowed-tools: [read, glob, grep, list_directory, list_snapshots]
                read-allow:  ["*"]
                read-deny:   ["secrets/**", ".env"]
                write-allow: []
                write-deny:  ["*"]
              editor:
                allowed-tools: ["*"]
                read-allow:  ["*"]
                read-deny:   ["secrets/**"]
                write-allow: ["src/**", "docs/**"]
                write-deny:  ["src/secrets/**"]
              admin:
                allowed-tools: ["*"]
                read-allow:  ["*"]
                read-deny:   []
                write-allow: ["*"]
                write-deny:  []
```

### 4.1 规则语义

- **角色键**：完整身份 `system-agent-user`（与工作区隔离单元一致，无跨 system 同名碰撞）。
- **多角色**（用户可配多个角色，并集 + deny 优先）：
  - 工具可用 = 任一角色 `allowed-tools` 含该工具（并集）；`*` 表示全部工具。
  - 路径可读 = (任一角色 `read-allow` 命中) ∧ (**无**角色 `read-deny` 命中)。
  - 路径可写 = (任一角色 `write-allow` 命中) ∧ (**无**角色 `write-deny` 命中)。
- **glob 匹配**：Spring `AntPathMatcher`，匹配工作区相对路径（如 `src/**`）。单独 `*` 表示全部路径。
- **内部路径豁免**：`.snapshots/`、`.trash/`、`.shadow/`（`StorageProvider.INTERNAL_PATH_PATTERN`）一律放行，否则快照/回滚/回收站机制全部失效。
- **未映射身份**：按 `default-policy` 处理。`allow-all` 向后兼容（未配置用户行为不变），`deny-all` 为白名单式（未配置即禁）。

---

## 五、新增 / 改动类清单

### 5.1 新增类

#### `auth/RoleResolver.java`（接口）+ `auth/ConfigRoleResolver.java`

```java
public interface RoleResolver {
    /** 返回该身份拥有的角色集合；未映射返回空集（由 default-policy 兜底）。替代 DB/接口查询。 */
    Set<String> resolveRoles(WorkspaceIdentity identity);
}
```

`ConfigRoleResolver` 读 `PermissionProperties.user-roles`，键 = `identity.system()+"-"+agent()+"-"+user()`。未来换 DB 实现只需替换此 Bean（`@ConditionalOnMissingBean`）。

#### `permission/PermissionService.java`

核心判定器，无状态，结果按 identity 轻量缓存：

```java
public class PermissionService {
    boolean canUseTool(WorkspaceIdentity identity, String toolName);  // 并集
    boolean canRead(WorkspaceIdentity identity, String path);         // read-allow ∧ ¬read-deny
    boolean canWrite(WorkspaceIdentity identity, String path);        // write-allow ∧ ¬write-deny
    boolean isBypassTool(String toolName);                            // 命中 bypass-file-acl-tools
    boolean isInternalPath(String path);                              // .snapshots/.trash/.shadow 放行
}
```

内部用 `AntPathMatcher` 做 glob 匹配。

#### `permission/ToolPermissionContext.java`（ThreadLocal）

持当前工具名 + bypass 标志。切面 `proceed()` 前写、`finally` 清。

> ⚠️ 线程池复用场景下 ThreadLocal 泄漏会把上个请求的工具上下文带进下个请求，`finally` 必须清理。

#### `permission/ToolPermissionAspect.java`

```java
@Around("@annotation(org.springaicommunity.mcp.annotation.McpTool)")
public Object check(ProceedingJoinPoint pjp) throws Throwable {
    McpTransportContext ctx = (McpTransportContext) pjp.getArgs()[0];   // 首参恒为 context
    WorkspaceIdentity id = authenticationProvider.authenticate(
            (ServerRequest) ctx.get(McpTransportContext.KEY));
    String tool = McpToolNameFrom(pjp.getSignature());                  // 取 @McpTool#name()

    if (permissionEnabled && !permissionService.canUseTool(id, tool)) {
        throw new ForbiddenException("tool " + tool + " not allowed for " + id);
    }
    try {
        ToolPermissionContext.set(tool, permissionService.isBypassTool(tool));
        return pjp.proceed();
    } finally {
        ToolPermissionContext.clear();                                  // 关键：必须清
    }
}
```

**身份二次解析**：工厂和切面各自从同一 `ServerRequest` 解析身份（无状态幂等，开销可忽略）。

#### `storage/PermissionEnforcedStorageProvider.java`（装饰器，仿 `QuotaEnforcedStorageProvider`）

- 构造：`new PermissionEnforcedStorageProvider(delegate, permissionService, identity)`（identity 由工厂在装配时传入——工厂本就解析了 identity）。
- `subDirProvider(subDir)` 重新包装（同 Quota 模式）。
- 每个方法先查 `ToolPermissionContext`：
  - **无上下文**（REST 控制器调用）→ **跳过 ACL**（ACL 只作用于 MCP 工具调用；REST 管理端点已有 `admin-token` 门禁，不重复管）。
  - **bypass=true** → 跳过 ACL。
  - 否则按 read/write 判定，拒绝抛 `ForbiddenException`。

#### `permission/ForbiddenException.java`

```java
public class ForbiddenException extends RuntimeException { ... }
```

### 5.2 改动类

| 文件 | 改动 |
|---|---|
| `autoconfig/HarnessMcpServerProperties.java` | 新增 `PermissionProperties` 内嵌类 + `permission` 字段。 |
| `storage/DefaultStorageProviderFactory.java` | 装饰链**最外层**条件包装 `PermissionEnforcedStorageProvider`（`permission.enabled=true` 时），传入已解析的 identity。 |
| `controller/GlobalRestExceptionHandler.java` | 新增 `ForbiddenException → 403` 映射。 |
| `autoconfig/HarnessMcpServerAutoConfiguration.java` | 注册 `RoleResolver` / `PermissionService` / `ToolPermissionAspect` 三个 Bean（`@ConditionalOnMissingBean` + `@ConditionalOnProperty(permission.enabled=true)`）；`PermissionProperties` 加入 `@EnableConfigurationProperties`。 |
| `pom.xml` | 新增 `spring-boot-starter-aop` 依赖（切面必需）。 |

### 5.3 装饰器装配顺序

```
base(AliyunOssStorage) → [quota if enabled] → [observed if enabled] → [permission if enabled]
```

`permission` 放**最外层**：拒绝先于观测/配额副作用，被拒调用不产生 observation span、不触发配额计算。`permission.enabled=false` 时不包装，零开销。

---

## 六、文件 ACL 拦截矩阵

| `StorageProvider` 方法 | 判定 | 说明 |
|---|---|---|
| `readString` / `readAllLines` / `readImage` / `readPdf` / `readDocument` / `getInfo` / `exists` / `isDirectory` | read | 拒绝抛 `ForbiddenException` |
| `writeString` / `writeFile` / `trash` / `delete` / `createDirectory` | write | 同上 |
| `rename(oldPath, newPath)` | write(both) | oldPath 与 newPath 都要可写 |
| `listDirectory` / `glob` / `grep` | read(基路径) | 判定查询基路径可读性；**不做逐条结果过滤**（见风险） |
| `createDownloadLink` | read | |
| `calculateTotalSize` / `subDirProvider` | 跳过 | 元数据 / 内部装配 |

**Edit 天然覆盖**：Edit 是工具层 `readString` + `writeString`，装饰器分别判 read 和 write，故 Edit 需要该路径**同时可读可写**。Edit 的前置快照写 `.snapshots/`（内部路径豁免），不受影响。

---

## 七、关键决策汇总

| # | 决策 | 说明 |
|---|---|---|
| 1 | 工具权限仅调用时拒绝 | 不动 `tools/list` 可见性（按需求方指示） |
| 2 | 角色键 = 完整身份 `system-agent-user` | 与隔离单元一致，无跨 system 碰撞 |
| 3 | 文件 ACL = 白+黑组合，deny 优先 | read/write 分离，`read-allow ∧ ¬read-deny` |
| 4 | 白名单配置驱动 | `bypass-file-acl-tools`，绕过文件 ACL 但**不绕过工作区隔离** |
| 5 | REST 控制器跳过 ACL | 无工具上下文 → 跳过（已有 admin-token 门禁） |
| 6 | 未映射身份 `default-policy=allow-all` | 向后兼容，可改 `deny-all` |
| 7 | 只动 mcp-server 的 `StorageProvider` | 不碰 utils 模块独立副本 |
| 8 | 装饰器放装饰链最外层 | 拒绝先于观测/配额 |

---

## 八、实现步骤

| 步骤 | 内容 | 产出 |
|---|---|---|
| **1** | **可行性 Spike（最先做）**：加 `spring-boot-starter-aop`，写最小切面验证两点——(a) 框架调用 `@McpTool` 方法时切面**是否真的触发**；(b) 切面抛异常时 MCP 客户端**是否收到 `isError=true` 结果**。 | Spike 结论；不通过则回退 JSON-RPC Filter 方案 |
| 2 | `PermissionProperties` + 绑定到 `HarnessMcpServerProperties`，yml 样例 | 配置可加载 |
| 3 | `RoleResolver` + `ConfigRoleResolver` | + 测试 |
| 4 | `PermissionService`（AntPathMatcher、多角色并集+deny-wins、bypass、内部路径豁免） | + 测试 |
| 5 | `ForbiddenException` + `GlobalRestExceptionHandler` 403 映射 | + 测试 |
| 6 | `ToolPermissionAspect` + `ToolPermissionContext` | + 测试 |
| 7 | `PermissionEnforcedStorageProvider` + 工厂接线（最外层、条件包装） | + 装饰器测试 + 工厂测试更新 |
| 8 | Autoconfig 注册三个 Bean | + 配置测试更新 |
| 9 | 集成测试：`/mcp` 端到端三用户场景 | 集成测试用例 |
| 10 | 文档：更新 `AGENTS.md` + mcp-server `CLAUDE.md` | 文档同步 |

> **第 1 步必须先做**：切面触发与异常透传是最大不确定性。若切面不触发（注解扫描器绕过 Spring 代理），回退方案为在 `/mcp` 端点加 JSON-RPC Filter，拦截 `tools/call` 解析工具名+身份前置拒绝，并同步拦截 `tools/list` 做可见性裁剪。

---

## 九、测试计划

遵循 AGENTS.md 要求：核心逻辑（路径解析、权限判定）80%+ 行/分支覆盖；全部 Mockito mock，禁止连真实 OSS。

| 测试类 | 覆盖点 |
|---|---|
| `ConfigRoleResolverTest` | 映射命中 / 未命中 / 多角色 |
| `PermissionServiceTest` | 工具并集、路径 allow+deny、deny-wins、`*`、多角色组合、bypass、内部路径豁免 |
| `PermissionEnforcedStorageProviderTest` | 仿 `QuotaEnforcedStorageProviderTest`：每个受控方法 allow/deny、`rename` 双路径、内部路径豁免、bypass 旁路、无工具上下文跳过 |
| `ToolPermissionAspectTest` | 放行 / 拒绝、ThreadLocal set/clear（含异常路径）、bypass 标志 |
| `DefaultStorageProviderFactoryTest`（更新） | permission 开/关装饰器装配与顺序 |
| `HarnessMcpServerAutoConfigurationTest`（更新） | 新 Bean 注册、`@ConditionalOnMissingBean` |
| `GlobalRestExceptionHandlerTest`（更新） | `ForbiddenException → 403` |
| 集成测试 | `/mcp` 端到端：alice(reader) 能 read 不能 write secrets；bob(editor) 能写 src 不能写 secrets；admin 全能 |

---

## 十、风险与已知限制

| 风险 / 限制 | 影响 | 应对 |
|---|---|---|
| **切面是否触发** | 注解扫描器可能绕过 Spring 代理直接调用方法，切面不生效 | 第 1 步 Spike 验证；回退 JSON-RPC Filter |
| **异常如何透传到 MCP 客户端** | 切面抛 `ForbiddenException` 可能不被框架转为 `isError=true` 结果，客户端收到 500 | 第 1 步 Spike 验证；回退为按方法返回类型构造错误 `CallToolResult` |
| **ThreadLocal 泄漏** | 线程池复用导致下个请求读到上个请求的工具上下文 | `finally` 必须清；测试覆盖异常路径清理 |
| **glob/grep/listDirectory 不做逐条结果过滤** | 被拒路径的**文件名**仍可能出现在列表中（内容读不到） | 本期仅判基路径可读性；逐条过滤作为增强项后续迭代 |
| **Edit 的 read+write 双判** | 角色对某路径只读不可写时，Edit 在 `writeString` 阶段被拒 | 符合预期，无需额外处理 |
| **AOP 对非 public / 自调用不生效** | `@McpTool` 方法均 public 且由框架外部调用 | 不受影响 |
| **多角色 deny-wins 语义** | 语义需明确，否则配置者易误判 | 文档与测试显式覆盖 |

---

## 十一、待确认事项

1. **第 1 步 Spike 是否先行**：是先跑 Spike（加 aop 依赖 + 最小切面验证拦截与异常透传）再铺开，还是直接按切面方案全量实现、遇问题再回退？
2. **`default-policy` 默认值**：`allow-all`（未映射用户不受限，向后兼容）还是 `deny-all`（白名单式，未配置即禁）？

---

## 附：相关文件索引（均相对仓库根）

- 身份：`spring-ai-harness-mcp-server/src/main/java/io/github/springai/harness/auth/{HeaderAuthenticationProvider,WorkspaceIdentity,AuthenticationProvider,AuthenticationException}.java`
- 存储：`.../storage/{StorageProvider,StorageProviderFactory,DefaultStorageProviderFactory,AliyunOssStorage,QuotaEnforcedStorageProvider,ObservedStorageProvider}.java`
- 工具：`.../tool/{FileSystemTools,SkillTools,RelayTools}.java`
- 装配：`.../autoconfig/{HarnessMcpServerAutoConfiguration,HarnessMcpServerProperties}.java`
- 异常出口：`.../controller/GlobalRestExceptionHandler.java`
- 配置：`spring-ai-harness-mcp-server/src/main/resources/application.properties`
- 安全体系背景：[spring-ai-harness-安全体系技术方案(正式版).md](spring-ai-harness-安全体系技术方案(正式版).md)
