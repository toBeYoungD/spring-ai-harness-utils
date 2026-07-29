# Phase 4 统一权限执行面--同步说明（含完整源码）

> 对应 commit：`8ae6b2c` feat/app-layer(04) + `c5a4d3f` docs/spec(04)
> 本文内嵌全部新增/修改文件源码，**公司内网无需拉取外部 git**，按本文创建/修改文件即可复刻。

---

## 一、改动概要

为 mcp-server 新增 `permission` 包，实现文件路径级 ACL + 工具权限黑名单的运行时装饰器。

- **工具权限**：黑名单模型（`deniedTools`），未配置的用户全部工具可用
- **文件 ACL**：glob 匹配 + `priority` 优先 + 同级 deny-wins（DENY > WRITE > READ）
- **统一装饰器**：每次 `StorageProvider` 调用三步判定--内部路径豁免 -> 文件 ACL -> delegate

### 新增文件（5 个）

| # | 路径 | 说明 |
|---|---|---|
| 1 | `permission/PermissionConfig.java` | 统一配置模型 |
| 2 | `permission/FileAclMatcher.java` | 判定引擎 |
| 3 | `permission/PermissionEnforcedStorageProvider.java` | 统一装饰器 |
| 4 | `permission/PermissionDeniedException.java` | 权限拒绝异常 |
| 5 | `autoconfig/PermissionProperties.java` | yaml 配置绑定 |

> 包路径前缀：`spring-ai-harness-mcp-server/src/main/java/io/github/springai/harness/`

### 修改文件（5 个）

| # | 路径 | 改动 |
|---|---|---|
| 6 | `autoconfig/HarnessMcpServerProperties.java` | +`permission` 字段 |
| 7 | `autoconfig/HarnessMcpServerAutoConfiguration.java` | +`@EnableConfigurationProperties` |
| 8 | `storage/DefaultStorageProviderFactory.java` | +permission 装饰层 |
| 9 | `controller/GlobalRestExceptionHandler.java` | +403 映射 + import |
| 10 | `resources/application.properties` | +权限配置段 |

---

## 二、新增文件完整源码

### 文件 1：`permission/PermissionConfig.java`

```java
package io.github.springai.harness.permission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 统一权限配置模型 -- Phase 4 配置面与执行面的数据契约。
 * 配置从 application.properties（spring.ai.harness.mcp.server.permission.*）加载。
 */
public record PermissionConfig(
        boolean enabled,
        ToolPermissionConfig toolPermission,
        FileAclConfig fileAcl
) {

    public static PermissionConfig disabled() {
        return new PermissionConfig(false, ToolPermissionConfig.ALLOW_ALL, FileAclConfig.ALLOW_ALL);
    }

    // ===== 工具权限（黑名单模型：未列出的工具默认可用）=====

    public record ToolPermissionConfig(Set<String> deniedTools) {
        public static final ToolPermissionConfig ALLOW_ALL = new ToolPermissionConfig(Set.of());

        public boolean isAllowed(String toolName) {
            return !deniedTools.contains(toolName) && !deniedTools.contains("*");
        }
    }

    // ===== 文件 ACL =====

    public record FileAclConfig(Policy defaultPolicy, List<AclRule> rules) {
        public static final FileAclConfig ALLOW_ALL = new FileAclConfig(Policy.ALLOW_ALL, List.of());

        public enum Policy {ALLOW_ALL, DENY_ALL}
    }

    public record AclRule(String pattern, Access access, int priority) {
        public AclRule {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("pattern must not be blank");
            }
            if (access == null) {
                throw new IllegalArgumentException("access must not be null");
            }
        }
    }

    public enum Access {
        DENY,   // 不可访问
        READ,   // 只读
        WRITE   // 可读写（隐含 READ）
    }

    // ===== Builder =====

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean enabled = true;
        private ToolPermissionConfig toolPermission = ToolPermissionConfig.ALLOW_ALL;
        private FileAclConfig fileAcl = FileAclConfig.ALLOW_ALL;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder toolPermission(ToolPermissionConfig toolPermission) {
            this.toolPermission = toolPermission != null ? toolPermission : ToolPermissionConfig.ALLOW_ALL;
            return this;
        }

        public Builder fileAcl(FileAclConfig fileAcl) {
            this.fileAcl = fileAcl != null ? fileAcl : FileAclConfig.ALLOW_ALL;
            return this;
        }

        public PermissionConfig build() {
            return new PermissionConfig(enabled, toolPermission, fileAcl);
        }
    }
}
```

### 文件 2：`permission/FileAclMatcher.java`

```java
package io.github.springai.harness.permission;

import org.springframework.util.AntPathMatcher;

import java.util.Comparator;
import java.util.List;

/**
 * 文件 ACL 判定引擎 -- 无状态、线程安全。
 * 按 glob pattern 匹配路径，priority 越大越优先；同级 DENY > WRITE > READ。
 */
public class FileAclMatcher {

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<PermissionConfig.AclRule> rules;
    private final PermissionConfig.FileAclConfig.Policy defaultPolicy;

    public FileAclMatcher(PermissionConfig.FileAclConfig fileAcl) {
        this.rules = fileAcl.rules();
        this.defaultPolicy = fileAcl.defaultPolicy();
        // 预排序：DESC by priority，同级 DENY > WRITE > READ -- 遍历时第一个命中即胜出
        this.rules.sort(Comparator
                .comparingInt(PermissionConfig.AclRule::priority).reversed()
                .thenComparing(r -> r.access().ordinal()));
    }

    public boolean canRead(String workspaceRelativePath) {
        return check(workspaceRelativePath, false);
    }

    public boolean canWrite(String workspaceRelativePath) {
        return check(workspaceRelativePath, true);
    }

    private boolean check(String rawPath, boolean isWrite) {
        String path = FileAclMatcher.normalize(rawPath);

        PermissionConfig.Access winner = null;
        for (PermissionConfig.AclRule rule : rules) {
            if (pathMatcher.match(rule.pattern(), path)) {
                winner = rule.access();
                break; // 已排序，第一个命中即胜出
            }
        }

        if (winner == null) {
            return switch (defaultPolicy) {
                case ALLOW_ALL -> true;
                case DENY_ALL -> false;
            };
        }

        return switch (winner) {
            case DENY -> false;
            case READ -> !isWrite;
            case WRITE -> true;
        };
    }

    public PermissionConfig.AclRule findMatchingRule(String rawPath) {
        String path = FileAclMatcher.normalize(rawPath);
        return rules.stream()
                .filter(r -> pathMatcher.match(r.pattern(), path))
                .findFirst()
                .orElse(null);
    }

    static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String clean = path.replaceAll("/{2,}", "/");
        if (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        return clean;
    }
}
```

### 文件 3：`permission/PermissionEnforcedStorageProvider.java`

```java
package io.github.springai.harness.permission;

import io.github.springai.harness.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * 统一权限装饰器--每次 StorageProvider 调用时一次性完成全部权限判定。
 *
 * 判定顺序：内部路径豁免 -> 文件 ACL -> delegate。
 * 装饰链最外层（拒绝先于配额/观测），permission.enabled=false 时不包装（零开销）。
 */
@Slf4j
public class PermissionEnforcedStorageProvider implements StorageProvider {

    private final StorageProvider delegate;
    private final FileAclMatcher fileAclMatcher;
    private final PermissionConfig config;
    private final String pathPrefix; // 子存储前缀，用于 subDirProvider 路径还原

    public PermissionEnforcedStorageProvider(StorageProvider delegate, PermissionConfig config) {
        this(delegate, config, "");
    }

    private PermissionEnforcedStorageProvider(StorageProvider delegate, PermissionConfig config, String pathPrefix) {
        this.delegate = delegate;
        this.config = config;
        this.fileAclMatcher = new FileAclMatcher(config.fileAcl());
        this.pathPrefix = pathPrefix;
    }

    // ===== 判定辅助 =====

    /** 将子提供者的相对路径还原为工作区根相对路径，供 ACL 匹配 */
    private String resolvePath(String rawPath) {
        if (pathPrefix.isEmpty()) {
            return rawPath;
        }
        String path = FileAclMatcher.normalize(rawPath);
        return pathPrefix + "/" + path;
    }

    /** 内部路径豁免--.snapshots/ .trash/ .shadow/ 一律放行 */
    private boolean isInternalPath(String path) {
        if (path == null) return false;
        String clean = FileAclMatcher.normalize(path);
        for (String p : INTERNAL_PATH_PATTERN) {
            String trimmed = p.startsWith("/") ? p.substring(1) : p;
            if (clean.equals(trimmed) || clean.startsWith(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private void checkRead(String rawPath) {
        String path = resolvePath(rawPath);
        if (isInternalPath(path)) return;

        if (!fileAclMatcher.canRead(path)) {
            PermissionConfig.AclRule rule = fileAclMatcher.findMatchingRule(path);
            String reason = rule != null
                    ? rule.access() + " by " + rule.pattern() + " (p" + rule.priority() + ")"
                    : "default-policy " + config.fileAcl().defaultPolicy();
            throw new PermissionDeniedException(path, "read", reason);
        }
    }

    private void checkWrite(String rawPath) {
        String path = resolvePath(rawPath);
        if (isInternalPath(path)) return;

        if (!fileAclMatcher.canWrite(path)) {
            PermissionConfig.AclRule rule = fileAclMatcher.findMatchingRule(path);
            String reason = rule != null
                    ? rule.access() + " by " + rule.pattern() + " (p" + rule.priority() + ")"
                    : "default-policy " + config.fileAcl().defaultPolicy();
            throw new PermissionDeniedException(path, "write", reason);
        }
    }

    // ===== 无判定（直接委托） =====

    @Override public char getSeparator() { return delegate.getSeparator(); }
    @Override public boolean isIgnoredPath(String path) { return delegate.isIgnoredPath(path); }

    @Override
    public StorageProvider subDirProvider(String subDir) {
        String newPrefix = pathPrefix.isEmpty() ? subDir : pathPrefix + "/" + subDir;
        return new PermissionEnforcedStorageProvider(delegate.subDirProvider(subDir), config, newPrefix);
    }

    @Override public long calculateTotalSize(List<String> excludePrefixes) throws IOException {
        return delegate.calculateTotalSize(excludePrefixes);
    }

    // ===== Read 判定方法 =====

    @Override public boolean exists(String path) { checkRead(path); return delegate.exists(path); }
    @Override public boolean isDirectory(String path) { checkRead(path); return delegate.isDirectory(path); }
    @Override public String readString(String path) throws IOException { checkRead(path); return delegate.readString(path); }
    @Override public List<String> readAllLines(String path) throws IOException { checkRead(path); return delegate.readAllLines(path); }
    @Override public String readImage(String path) throws IOException { checkRead(path); return delegate.readImage(path); }
    @Override public String readPdf(String path, Integer startPage, Integer endPage) throws IOException { checkRead(path); return delegate.readPdf(path, startPage, endPage); }
    @Override public String readDocument(String path) throws IOException { checkRead(path); return delegate.readDocument(path); }
    @Override public Info getInfo(String path) throws IOException { checkRead(path); return delegate.getInfo(path); }
    @Override public List<Info> getInfo(List<String> paths) { return delegate.getInfo(paths); }

    @Override
    public List<Info> listDirectory(String path) throws IOException {
        checkRead(path != null ? path : "");
        return delegate.listDirectory(path);
    }

    @Override
    public List<String> glob(String pattern, String path) throws IOException {
        checkRead(path != null ? path : "");
        return delegate.glob(pattern, path);
    }

    @Override
    public List<String> grep(String pattern, String path, String g, GrepOutputMode outputMode, Integer contextBefore,
            Integer contextAfter, Integer context, Boolean showLineNumbers, Boolean caseInsensitive,
            Integer headLimit, Integer offset, Boolean multiline) throws IOException {
        checkRead(path != null ? path : "");
        return delegate.grep(pattern, path, g, outputMode, contextBefore, contextAfter,
                context, showLineNumbers, caseInsensitive, headLimit, offset, multiline);
    }

    @Override
    public DownloadLink createDownloadLink(String path, Duration ttl) throws IOException {
        checkRead(path);
        return delegate.createDownloadLink(path, ttl);
    }

    // ===== Write 判定方法 =====

    @Override public void writeString(String path, String content) throws IOException { checkWrite(path); delegate.writeString(path, content); }

    @Override
    public void writeFile(String path, InputStream inputStream, long contentLength) throws IOException {
        checkWrite(path);
        delegate.writeFile(path, inputStream, contentLength);
    }

    @Override public void createDirectory(String path) throws IOException { checkWrite(path); delegate.createDirectory(path); }
    @Override public void trash(String path) throws IOException { checkWrite(path); delegate.trash(path); }
    @Override public void delete(String path) throws IOException { checkWrite(path); delegate.delete(path); }

    @Override
    public void rename(String oldPath, String newPath) throws IOException {
        checkWrite(oldPath);
        checkWrite(newPath);
        delegate.rename(oldPath, newPath);
    }
}
```

### 文件 4：`permission/PermissionDeniedException.java`

```java
package io.github.springai.harness.permission;

/**
 * 权限拒绝时抛出的运行时异常。
 * 携带路径和操作信息，便于 GlobalRestExceptionHandler 返回友好错误。
 */
public class PermissionDeniedException extends RuntimeException {

    private final String path;
    private final String operation;
    private final String reason;

    public PermissionDeniedException(String path, String operation, String reason) {
        super(String.format("permission denied: %s %s (%s)", operation, path, reason));
        this.path = path;
        this.operation = operation;
        this.reason = reason;
    }

    public String getPath() {
        return path;
    }

    public String getOperation() {
        return operation;
    }

    public String getReason() {
        return reason;
    }
}
```

### 文件 5：`autoconfig/PermissionProperties.java`

```java
package io.github.springai.harness.autoconfig;

import io.github.springai.harness.permission.PermissionConfig;
import lombok.Data;

import java.util.*;

/**
 * 权限配置 Properties -- 绑定 yaml 到 PermissionConfig。
 * 命名空间: spring.ai.harness.mcp.server.permission.*
 */
@Data
public class PermissionProperties {

    /** 总开关，默认关闭（零开销可插拔） */
    private boolean enabled = false;

    /** 未配置工具权限的默认策略 */
    private Policy defaultToolPolicy = Policy.ALLOW_ALL;

    /** 未匹配 ACL 规则的默认策略 */
    private Policy defaultAclPolicy = Policy.ALLOW_ALL;

    /** 绕过文件 ACL 的工具白名单（本期预留，后续迭代） */
    private Set<String> bypassFileAclTools = new HashSet<>();

    /** 用户工具权限配置（黑名单模型：未列出的工具默认可用）(system-agent-user -> denied-tools) */
    private Map<String, Set<String>> userRoles = new LinkedHashMap<>();

    /** 文件 ACL 规则（全局默认规则） */
    private List<AclRuleProperties> fileAclRules = new ArrayList<>();

    public enum Policy { ALLOW_ALL, DENY_ALL }

    @Data
    public static class AclRuleProperties {
        private String pattern;
        private PermissionConfig.Access access = PermissionConfig.Access.DENY;
        private int priority = 10;
    }

    /**
     * 将 yaml 配置转换为装饰器消费的统一 PermissionConfig。
     *
     * @param identityKey 当前请求的三段身份键 (system-agent-user)
     */
    public PermissionConfig toPermissionConfig(String identityKey) {
        if (!enabled) {
            return PermissionConfig.disabled();
        }

        // 工具权限：黑名单模型--取 userRoles 中匹配的 denied-tools，空集=全部放行
        Set<String> deniedTools = userRoles.getOrDefault(identityKey, Set.of());
        PermissionConfig.ToolPermissionConfig toolPerm = new PermissionConfig.ToolPermissionConfig(deniedTools);

        // 文件 ACL：转换 yaml 规则为内存模型
        List<PermissionConfig.AclRule> rules = fileAclRules.stream()
                .map(r -> new PermissionConfig.AclRule(r.getPattern(), r.getAccess(), r.getPriority()))
                .toList();

        PermissionConfig.FileAclConfig.Policy aclPolicy = switch (defaultAclPolicy) {
            case ALLOW_ALL -> PermissionConfig.FileAclConfig.Policy.ALLOW_ALL;
            case DENY_ALL -> PermissionConfig.FileAclConfig.Policy.DENY_ALL;
        };

        return PermissionConfig.builder()
                .enabled(true)
                .toolPermission(toolPerm)
                .fileAcl(new PermissionConfig.FileAclConfig(aclPolicy, rules))
                .build();
    }
}
```

---

## 三、修改文件改动

### 文件 6：`autoconfig/HarnessMcpServerProperties.java`

在 `attachment` 字段后新增 `permission` 字段：

```java
private AttachmentProperties attachment = new AttachmentProperties();

private PermissionProperties permission = new PermissionProperties();
```

### 文件 7：`autoconfig/HarnessMcpServerAutoConfiguration.java`

`@EnableConfigurationProperties` 加入 `PermissionProperties.class`：

```java
@EnableConfigurationProperties({HarnessMcpServerProperties.class, PermissionProperties.class})
```

### 文件 8：`storage/DefaultStorageProviderFactory.java`

新增 import：

```java
import io.github.springai.harness.permission.PermissionEnforcedStorageProvider;
```

在 `getStorageProvider` 方法中，quota 装饰之后、observed 之前，插入 permission 装饰：

```java
if (this.properties.getQuota().isEnabled()) {
    baseStorage = new QuotaEnforcedStorageProvider(baseStorage, this.quotaManager);
}

// 权限装饰器--装饰链最外层（拒绝先于配额/观测副作用）
if (this.properties.getPermission().isEnabled()) {
    String identityKey = identity.system() + "-" + identity.agent() + "-" + identity.user();
    var permConfig = this.properties.getPermission().toPermissionConfig(identityKey);
    baseStorage = new PermissionEnforcedStorageProvider(baseStorage, permConfig);
}

ObservationRegistry registry = observationRegistryProvider != null ? observationRegistryProvider.getIfAvailable() : null;
// ... 原有 observed 逻辑不变
```

### 文件 9：`controller/GlobalRestExceptionHandler.java`

新增 import：

```java
import io.github.springai.harness.permission.PermissionDeniedException;
```

新增 403 异常处理方法（放在 `handleAuthenticationException` 之后）：

```java
@ExceptionHandler(PermissionDeniedException.class)
public ResponseEntity<Map<String, String>> handlePermissionDeniedException(HttpServletRequest request, PermissionDeniedException e) {
    log.warn("权限拒绝 [{} {}]: path={} op={}", request.getMethod(), request.getRequestURI(), e.getPath(), e.getOperation());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("error", "permission denied",
                    "path", e.getPath() != null ? e.getPath() : "",
                    "operation", e.getOperation() != null ? e.getOperation() : "",
                    "reason", e.getReason() != null ? e.getReason() : ""));
}
```

### 文件 10：`resources/application.properties`

在文件末尾新增权限配置段：

```properties
# ===== 权限配置（Phase 4）=====
spring.ai.harness.mcp.server.permission.enabled=true
spring.ai.harness.mcp.server.permission.default-tool-policy=allow-all
spring.ai.harness.mcp.server.permission.default-acl-policy=allow-all
spring.ai.harness.mcp.server.permission.bypass-file-acl-tools=read_skill,list_skills,list_snapshots,rewind
# 工具权限示例（黑名单：未列出即默认可用）
spring.ai.harness.mcp.server.permission.user-roles[openclaw-code-assistant-readonly]=write,edit,trash
# user-roles 为空或未配置的用户：全部工具可用
# 文件 ACL 规则（priority 越大越优先；同级 DENY > WRITE > READ）
spring.ai.harness.mcp.server.permission.file-acl-rules[0].pattern=secrets/**
spring.ai.harness.mcp.server.permission.file-acl-rules[0].access=DENY
spring.ai.harness.mcp.server.permission.file-acl-rules[0].priority=10
spring.ai.harness.mcp.server.permission.file-acl-rules[1].pattern=**/*.env
spring.ai.harness.mcp.server.permission.file-acl-rules[1].access=DENY
spring.ai.harness.mcp.server.permission.file-acl-rules[1].priority=100
```

---

## 四、配置说明

所有配置在 `spring.ai.harness.mcp.server.permission.*` 下。

### 4.1 工具权限（黑名单模型）

```properties
# 格式：user-roles[{system}-{agent}-{user}] = 禁止的工具列表（逗号分隔）
# 未配置的用户 = 全部工具可用
# * = 全部禁止
spring.ai.harness.mcp.server.permission.user-roles[openclaw-code-assistant-readonly]=write,edit,trash
```

### 4.2 文件 ACL 规则

```properties
# 每条规则：pattern（glob）+ access（DENY/READ/WRITE）+ priority（int，越大越优先）
# 同级最具体：DENY > WRITE > READ
spring.ai.harness.mcp.server.permission.file-acl-rules[0].pattern=secrets/**
spring.ai.harness.mcp.server.permission.file-acl-rules[0].access=DENY
spring.ai.harness.mcp.server.permission.file-acl-rules[0].priority=10
```

**priority 作用**：多条规则同时命中一个路径时，priority 大的胜出。用于表达"更具体的规则覆盖更宽的规则"。

示例：`secrets/** DENY priority=10` + `secrets/public/** READ priority=20` -> `secrets/public/` 可读（priority 20 覆盖 10），其余 `secrets/` 不可访问。

**判定逻辑**：
- 取所有匹配规则中 priority 最大者
- DENY -> 拒绝
- READ -> 可读，write 操作拒绝
- WRITE -> 读/写都放行（隐含 READ）
- 无匹配 -> `default-acl-policy`（allow-all / deny-all）

---

## 五、装饰器链顺序

```
请求
  -> AliyunOssStorage（基础存储）
    -> QuotaEnforcedStorageProvider（配额检查）
      -> PermissionEnforcedStorageProvider（权限检查，最外层）
        -> ObservedStorageProvider（可观测性，若启用）
```

`PermissionEnforced` 在配额之后、观测之前：被拒调用不触发配额计算、不产生观测 span。

三个装饰器各自独立 `enabled` 开关，可任意组合启停，互不感知。

---

## 六、核心判定流程

每次 `StorageProvider` 方法被调用：

```
① 内部路径豁免
   .snapshots/ .trash/ .shadow/ ?
     -> 是: 跳过全部判定，直接 delegate

② 文件 ACL 判定（AntPathMatcher glob 匹配）
   取所有匹配规则中 priority 最大者
     -> DENY -> PermissionDeniedException(403)
     -> READ 且当前是 write 操作 -> PermissionDeniedException(403)
     -> WRITE -> 读/写都放行

③ delegate 调用真正的存储方法
```

---

## 七、复刻步骤

1. 在 `spring-ai-harness-mcp-server/src/main/java/io/github/springai/harness/` 下新建 `permission` 目录
2. 按本文「二、新增文件完整源码」创建 4 个文件（PermissionConfig / FileAclMatcher / PermissionEnforcedStorageProvider / PermissionDeniedException）
3. 在 `autoconfig` 包下创建 PermissionProperties.java
4. 按本文「三、修改文件改动」修改 5 个既有文件
5. 按公司内实际的 system/agent/user 名单和安全策略调整 `application.properties` 中的规则
6. 编译验证：`./mvnw compile -pl spring-ai-harness-mcp-server`
7. 启动验证：配 `permission.enabled=true`，用被禁身份调用被拒工具/路径，应返回 403

---

## 八、依赖敏感点

| 项目 | 说明 |
|---|---|
| 外部依赖 | **无新增**--AntPathMatcher 来自 spring-core（已有），其余均为纯 Java |
| AOP/切面 | **无**--纯装饰器模式，不引入 spring-boot-starter-aop |
| 配置文件 | 新增 `permission.*` 配置段，与既有 `quota.*`/`observability.*` 平级，互不冲突 |
| 向后兼容 | `permission.enabled=false`（默认）时工厂不包装装饰器，行为完全不变 |
| 公司内适配 | 工具权限用户名单、文件 ACL 规则需按公司内实际身份和安全策略调整 |
| Java 版本 | 要求 Java 17+（record / switch 表达式 / `var`） |
