# Phase 4 统一权限执行面——同步说明

> 对应 commit：`8ae6b2c` feat/app-layer(04)

---

## 一、改动概要

为 mcp-server 新增 `permission` 包，实现文件路径级 ACL 的控制台配置与运行时装饰器。工具权限禁为**黑名单模式**（未配置即全可用），文件 ACL 格使用 **glob 匹配 + priority 优先 + deny-wins** 判定。

### 新增文件

| 文件 | 说明 |
|---|---|
| `permission/PermissionConfig.java` | 统一配置模型：工具权限黑名单 + 文件 ACL（pattern + access + priority）+ builder |
| `permission/FileAclMatcher.java` | 无状态判定引擎：AntPathMatcher glob 匹配，priority 大者优先，同级 deny-wins |
| `permission/PermissionEnforcedStorageProvider.java` | 统一装饰器：每次 StorageProvider 调用三步判定——内部路径豁免(→ ①基础安全防线，第一阶段强制通过)→文件 ACL(→ ②基于文件内容级别的精细化权限控制，需根据角色与策略进行二次核定)→delegate(→ ③前两段判定通过后，将请求转发至真实的底层存储实现，完成最终操作) |
| `permission/PermissionDeniedException.java` | 权限拒绝异常（RuntimeException，携带 path/op/reason） |
| `autoconfig/PermissionProperties.java` | yaml 配置 → PermissionConfig 转换器 |

### 修改文件

| 文件 | 改动 |
|---|---|
| `HarnessMcpServerProperties.java` | +`private PermissionProperties permission` 字段 |
| `HarnessMcpServerAutoConfiguration.java` | +`@EnableConfigurationProperties(PermissionProperties.class)` |
| `DefaultStorageProviderFactory.java` | 装饰链 + permission 层：`new PermissionEnforcedStorageProvider(base, permConfig)` |
| `GlobalRestExceptionHandler.java` | +`PermissionDeniedException` → 403 Forbidden |
| `application.properties` | 权限配置样例（黑名单 + 2 条 ACL 规则） |

## 二、装饰器链顺序

```
请求
  → AliyunOssStorage（基础存储）
    → QuotaEnforcedStorageProvider（配额检查）
      → PermissionEnforcedStorageProvider（权限检查，最外层）
        → ObservedStorageProvider（可观测性，若启用）
```

`PermissionEnforced` 放在最外层（被拒调用不触发配额计算、不产生观测 span）。

## 三、配置说明

所有配置在 `spring.ai.harness.mcp.server.permission.*` 下。

### 3.1 基础开关

```properties
spring.ai.harness.mcp.server.permission.enabled=true        # 总开关
spring.ai.harness.mcp.server.permission.default-acl-policy=allow-all  # 未匹配规则的兜底
```

### 3.2 工具权限（黑名单模型）

```properties
# 格式：user-roles[{system}-{agent}-{user}] = 禁止的工具列表（逗号分隔）
# 未配置的用户 = 全部工具可用
# * = 全部禁止
spring.ai.harness.mcp.server.permission.user-roles[openclaw-code-assistant-readonly]=write,edit,trash
```

### 3.3 文件 ACL 规则

```properties
# 每条规则：pattern（glob）+ access（DENY/READ/WRITE）+ priority（int，越大越优先）
# 同级最具体：DENY > READ > WRITE

# 全局默认：secrets 不可访问
spring.ai.harness.mcp.server.permission.file-acl-rules[0].pattern=secrets/**
spring.ai.harness.mcp.server.permission.file-acl-rules[0].access=DENY
spring.ai.harness.mcp.server.permission.file-acl-rules[0].priority=10

# .env 文件不可访问（更高优先级）
spring.ai.harness.mcp.server.permission.file-acl-rules[1].pattern=**/*.env
spring.ai.harness.mcp.server.permission.file-acl-rules[1].access=DENY
spring.ai.harness.mcp.server.permission.file-acl-rules[1].priority=100
```

**判定逻辑**：路径被多条规则命中时，取 priority 最大者。`priority` 越大表示规则越具体（如 `config/secrets.yml` 的 priority 应高于 `secrets/**` 的 priority）。同级（相同 priority）最具体：DENY > READ > WRITE。

示例：若另有一规则 `config/secrets.yml READ priority=20`，访问 `config/secrets.yml` 时 priority=20 覆盖 priority=10 的 DENY → 可读。

## 四、核心判定流程

每次 `StorageProvider` 方法被调用：

```
① 内部路径豁免（线程安全，串行校验）
   .snapshots/ .trash/ .shadow/ ?
     → 是: 跳过全部判定，直接 delegate

② 文件 ACL 判定（AntPathMatcher glob 匹配）
   取所有匹配规则中 priority 最大者
     → DENY → PermissionDeniedException(403)
     → READ 且当前是 write 操作 → PermissionDeniedException(403)
     → WRITE → 读/写都放行

③ delegate 调用真正的存储方法
```

## 五、与配额/观测的关系

三者是**独立 decorator**，通过各自 `enabled` 开关控制，可任意组合启停：

| quota | permission | 效果 |
|---|---|---|
| true | true | 配额 + 权限（全栈管控） |
| true | false | 只有配额 |
| false | true | 只有权限 |
| false | false | 裸实例 |

各自在 factory 装配链中独立条件包装，互不感知。

## 六、复刻步骤

1. 定位新增文件：`spring-ai-harness-mcp-server/.../permission/*.java` + `autoconfig/PermissionProperties.java`
2. 定位修改文件：`HarnessMcpServerProperties.java`、`HarnessMcpServerAutoConfiguration.java`、`DefaultStorageProviderFactory.java`、`GlobalRestExceptionHandler.java`、`application.properties`
3. 按 diff 应用改动（新增 5 文件 + 修改 5 文件）
4. 按公司内 ACL 策略调整 `application.properties` 中的规则样例
5. 编译验证：`./mvnw compile -pl spring-ai-harness-mcp-server`
6. 兜底对照：`git diff main..internal-dev -- spring-ai-harness-mcp-server/`

## 七、依赖敏感点

| 项目 | 说明 |
|---|---|
| 外部依赖 | **无新增**——AntPathMatcher 来自 spring-core（已有），其余均为纯 Java |
| AOP/切面 | **无**——纯装饰器模式，不引入 spring-boot-starter-aop |
| 配置文件 | 新增 `permission.*` 配置段，与既有 `quota.*`/`observability.*` 平级，互不冲突 |
| 向后兼容 | `permission.enabled=false`（默认）时工厂不包装装饰器，行为完全不变 |
| 公司内适配 | 工具权限的用户名单、文件 ACL 规则需按公司内实际的 system/agent/user 名单和安全策略调整 |
