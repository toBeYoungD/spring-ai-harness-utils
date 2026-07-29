# Phase 4 统一权限执行面--同步说明（含完整源码）

> 对应 commit：`8ae6b2c`（初版）+ `b8ad306`（按人维度 + AND 合并修正）
> 本文内嵌全部新增/修改文件源码，**公司内网无需拉取外部 git**，按本文创建/修改文件即可复刻。

---

## 一、改动概要

为 mcp-server 新增 `permission` 包，实现文件路径级 ACL + 工具权限黑名单的运行时装饰器。

- **工具权限**：黑名单模型（`deniedTools`），按人（`{system}-{agent}-{user}`）配置，未配置的用户全部工具可用
- **文件 ACL**：**按人维度**，分管理员全局 + 用户自服务两层，glob + priority + AND 合并取严
  - 管理员 DENY 是安全底线，用户无法绕过
  - 用户可以比管理员更严，但不能更松
- **统一装饰器**：每次 `StorageProvider` 调用三步判定--内部路径豁免 -> 文件 ACL -> delegate

### 新增文件（5 个）

| # | 路径 | 说明 |
|---|---|---|
| 1 | `permission/PermissionConfig.java` | 统一配置模型（工具权限黑名单 + 文件 ACL 双层） |
| 2 | `permission/FileAclMatcher.java` | 判定引擎（admin/user AND 合并） |
| 3 | `permission/PermissionEnforcedStorageProvider.java` | 统一装饰器 |
| 4 | `permission/PermissionDeniedException.java` | 权限拒绝异常 |
| 5 | `autoconfig/PermissionProperties.java` | yaml 配置绑定（含 per-identity 用户规则） |

> 包路径前缀：`spring-ai-harness-mcp-server/src/main/java/io/github/springai/harness/`

### 修改文件（5 个）

| # | 路径 | 改动 |
|---|---|---|
| 6 | `autoconfig/HarnessMcpServerProperties.java` | +`permission` 字段 |
| 7 | `autoconfig/HarnessMcpServerAutoConfiguration.java` | +`@EnableConfigurationProperties` |
| 8 | `storage/DefaultStorageProviderFactory.java` | +permission 装饰层 |
| 9 | `controller/GlobalRestExceptionHandler.java` | +403 映射 + import |
| 10 | `resources/application.properties` | +权限配置段（含 per-identity） |

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

    // ===== 文件 ACL（管理员全局 + 用户自服务，AND 合并取严）=====

    public record FileAclConfig(
            Policy adminDefaultPolicy, List<AclRule> adminRules,   // 管理员全局规则（安全底线）
            Policy userDefaultPolicy, List<AclRule> userRules      // 该身份用户自服务规则（已按 identity 取出）
    ) {
        public static final FileAclConfig ALLOW_ALL = new FileAclConfig(
                Policy.ALLOW_ALL, List.of(), Policy.ALLOW_ALL, List.of());

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文件 ACL 判定引擎 -- 无状态、线程安全。
 *
 * 持两套规则：管理员全局规则 + 用户自服务规则，AND 合并取严--
 * canRead = adminCanRead && userCanRead，canWrite = adminCanWrite && userCanWrite。
 *
 * 语义：管理员的 DENY 是安全底线，用户无法绕过；用户可以比管理员更严，但不能更松。
 * 每套规则内部按 glob 匹配，priority 越大越优先，同级 DENY > WRITE > READ。
 */
public class FileAclMatcher {

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<PermissionConfig.AclRule> adminRules;
    private final PermissionConfig.FileAclConfig.Policy adminDefaultPolicy;
    private final List<PermissionConfig.AclRule> userRules;
    private final PermissionConfig.FileAclConfig.Policy userDefaultPolicy;

    public FileAclMatcher(PermissionConfig.FileAclConfig fileAcl) {
        this.adminRules = new ArrayList<>(fileAcl.adminRules());
        this.adminDefaultPolicy = fileAcl.adminDefaultPolicy();
        this.userRules = new ArrayList<>(fileAcl.userRules());
        this.userDefaultPolicy = fileAcl.userDefaultPolicy();
        // 各自预排序：DESC by priority，同级 DENY > WRITE > READ
        Comparator<PermissionConfig.AclRule> cmp = Comparator
                .comparingInt(PermissionConfig.AclRule::priority).reversed()
                .thenComparing(r -> r.access().ordinal());
        this.adminRules.sort(cmp);
        this.userRules.sort(cmp);
    }

    /** 判定给定工作区相对路径是否可读（管理员 AND 用户） */
    public boolean canRead(String workspaceRelativePath) {
        return adminCanRead(workspaceRelativePath) && userCanRead(workspaceRelativePath);
    }

    /** 判定给定工作区相对路径是否可写（管理员 AND 用户） */
    public boolean canWrite(String workspaceRelativePath) {
        return adminCanWrite(workspaceRelativePath) && userCanWrite(workspaceRelativePath);
    }

    private boolean adminCanRead(String path) {
        return checkSingle(adminRules, adminDefaultPolicy, path, false);
    }

    private boolean adminCanWrite(String path) {
        return checkSingle(adminRules, adminDefaultPolicy, path, true);
    }

    private boolean userCanRead(String path) {
        return checkSingle(userRules, userDefaultPolicy, path, false);
    }

    private boolean userCanWrite(String path) {
        return checkSingle(userRules, userDefaultPolicy, path, true);
    }

    private boolean checkSingle(List<PermissionConfig.AclRule> rules,
                                PermissionConfig.FileAclConfig.Policy defaultPolicy,
                                String rawPath, boolean isWrite) {
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

    /**
     * 获取导致拒绝的规则（用于异常消息）。
     * 优先返回管理员规则（安全底线），其次用户规则。null=无匹配（走 default-policy）。
     */
    public PermissionConfig.AclRule findMatchingRule(String rawPath) {
        String path = FileAclMatcher.normalize(rawPath);
        PermissionConfig.AclRule admin = adminRules.stream()
                .filter(r -> pathMatcher.match(r.pattern(), path))
                .findFirst().orElse(null);
        if (admin != null) {
            return admin;
        }
        return userRules.stream()
                .filter(r -> pathMatcher.match(r.pattern(), path))
                .findFirst().orElse(null);
    }

    /** 路径规范化：去前导 ./、/、多余斜杠 */
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
 * 判定顺序：内部路径豁免 -> 文件 ACL（管理员 AND 用户）-> delegate。
 * 装饰链最外层（拒绝先于配额/观测），permission.enabled=false 时不包装（零开销）。
 */
@Slf4j
public class PermissionEnforcedStorageProvider implements StorageProvider {

    private final StorageProvider delegate;
    private final FileAclMatcher fileAclMatcher;
    private final PermissionConfig config;
    private final String pathPrefix;

    public PermissionEnforcedStorageProvider(StorageProvider delegate, PermissionConfig config) {
        this(delegate, config, "");
    }

    private PermissionEnforcedStorageProvider(StorageProvider delegate, PermissionConfig config, String pathPrefix) {
        this.delegate = delegate;
        this.config = config;
        this.fileAclMatcher = new FileAclMatcher(config.fileAcl());
        this.pathPrefix = pathPrefix;
    }

    private String resolvePath(String rawPath) {
        if (pathPrefix.isEmpty()) {
            return rawPath;
        }
        String path = FileAclMatcher.normalize(rawPath);
        return pathPrefix + "/" + path;
    }

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
                    : "default-policy (admin=" + config.fileAcl().adminDefaultPolicy() + ", user=" + config.fileAcl().userDefaultPolicy() + ")";
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
                    : "default-policy (admin=" + config.fileAcl().adminDefaultPolicy() + ", user=" + config.fileAcl().userDefaultPolicy() + ")";
            throw new PermissionDeniedException(path, "write", reason);
        }
    }

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

    @Override public List<Info> listDirectory(String path) throws IOException {
        checkRead(path != null ? path : "");
        return delegate.listDirectory(path);
    }
    @Override public List<String> glob(String pattern, String path) throws IOException {
        checkRead(path != null ? path : "");
        return delegate.glob(pattern, path);
    }
    @Override public List<String> grep(String pattern, String path, String g, GrepOutputMode outputMode, Integer contextBefore,
            Integer contextAfter, Integer context, Boolean showLineNumbers, Boolean caseInsensitive,
            Integer headLimit, Integer offset, Boolean multiline) throws IOException {
        checkRead(path != null ? path : "");
        return delegate.grep(pattern, path, g, outputMode, contextBefore, contextAfter,
                context, showLineNumbers, caseInsensitive, headLimit, offset, multiline);
    }
    @Override public DownloadLink createDownloadLink(String path, Duration ttl) throws IOException {
        checkRead(path);
        return delegate.createDownloadLink(path, ttl);
    }

    // ===== Write 判定方法 =====
    @Override public void writeString(String path, String content) throws IOException { checkWrite(path); delegate.writeString(path, content); }
    @Override public void writeFile(String path, InputStream inputStream, long contentLength) throws IOException {
        checkWrite(path);
        delegate.writeFile(path, inputStream, contentLength);
    }
    @Override public void createDirectory(String path) throws IOException { checkWrite(path); delegate.createDirectory(path); }
    @Override public void trash(String path) throws IOException { checkWrite(path); delegate.trash(path); }
    @Override public void delete(String path) throws IOException { checkWrite(path); delegate.delete(path); }
    @Override public void rename(String oldPath, String newPath) throws IOException {
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

    public String getPath() { return path; }
    public String getOperation() { return operation; }
    public String getReason() { return reason; }
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
 *
 * 文件 ACL 分两层：
 * - file-acl-rules: 管理员全局规则（安全底线，所有身份共享）
 * - user-file-acls[{identity}]: 用户自服务规则（per-identity，按 {system-agent-user} 键）
 * 两层 AND 合并取严：管理员 DENY 不可绕过，用户可更严。
 */
@Data
public class PermissionProperties {

    private boolean enabled = false;
    private Policy defaultToolPolicy = Policy.ALLOW_ALL;
    private Policy adminAclDefaultPolicy = Policy.ALLOW_ALL;
    private Policy userAclDefaultPolicy = Policy.ALLOW_ALL;
    private Set<String> bypassFileAclTools = new HashSet<>();

    /** 用户工具权限（黑名单）(system-agent-user -> denied-tools) */
    private Map<String, Set<String>> userRoles = new LinkedHashMap<>();

    /** 管理员全局文件 ACL 规则 */
    private List<AclRuleProperties> fileAclRules = new ArrayList<>();

    /** 用户自服务文件 ACL 规则（per-identity）(system-agent-user -> rules) */
    private Map<String, List<AclRuleProperties>> userFileAcls = new LinkedHashMap<>();

    public enum Policy { ALLOW_ALL, DENY_ALL }

    @Data
    public static class AclRuleProperties {
        private String pattern;
        private PermissionConfig.Access access = PermissionConfig.Access.DENY;
        private int priority = 10;
    }

    public PermissionConfig toPermissionConfig(String identityKey) {
        if (!enabled) {
            return PermissionConfig.disabled();
        }

        Set<String> deniedTools = userRoles.getOrDefault(identityKey, Set.of());
        PermissionConfig.ToolPermissionConfig toolPerm = new PermissionConfig.ToolPermissionConfig(deniedTools);

        List<PermissionConfig.AclRule> adminRules = fileAclRules.stream()
                .map(r -> new PermissionConfig.AclRule(r.getPattern(), r.getAccess(), r.getPriority()))
                .toList();

        List<PermissionConfig.AclRule> userRules = userFileAcls.getOrDefault(identityKey, List.of()).stream()
                .map(r -> new PermissionConfig.AclRule(r.getPattern(), r.getAccess(), r.getPriority()))
                .toList();

        PermissionConfig.FileAclConfig.Policy adminPolicy = switch (adminAclDefaultPolicy) {
            case ALLOW_ALL -> PermissionConfig.FileAclConfig.Policy.ALLOW_ALL;
            case DENY_ALL -> PermissionConfig.FileAclConfig.Policy.DENY_ALL;
        };
        PermissionConfig.FileAclConfig.Policy userPolicy = switch (userAclDefaultPolicy) {
            case ALLOW_ALL -> PermissionConfig.FileAclConfig.Policy.ALLOW_ALL;
            case DENY_ALL -> PermissionConfig.FileAclConfig.Policy.DENY_ALL;
        };

        return PermissionConfig.builder()
                .enabled(true)
                .toolPermission(toolPerm)
                .fileAcl(new PermissionConfig.FileAclConfig(adminPolicy, adminRules, userPolicy, userRules))
                .build();
    }
}
```

---

## 三、修改文件改动

### 文件 6：`autoconfig/HarnessMcpServerProperties.java`

在 `attachment` 字段后新增：

```java
private PermissionProperties permission = new PermissionProperties();
```

### 文件 7：`autoconfig/HarnessMcpServerAutoConfiguration.java`

```java
@EnableConfigurationProperties({HarnessMcpServerProperties.class, PermissionProperties.class})
```

### 文件 8：`storage/DefaultStorageProviderFactory.java`

新增 import + 在 quota 装饰之后插入 permission 装饰：

```java
import io.github.springai.harness.permission.PermissionEnforcedStorageProvider;

// ...getStorageProvider() 中：
if (this.properties.getQuota().isEnabled()) {
    baseStorage = new QuotaEnforcedStorageProvider(baseStorage, this.quotaManager);
}

// 权限装饰器--装饰链最外层（拒绝先于配额/观测副作用）
if (this.properties.getPermission().isEnabled()) {
    String identityKey = identity.system() + "-" + identity.agent() + "-" + identity.user();
    var permConfig = this.properties.getPermission().toPermissionConfig(identityKey);
    baseStorage = new PermissionEnforcedStorageProvider(baseStorage, permConfig);
}
```

### 文件 9：`controller/GlobalRestExceptionHandler.java`

新增 import + 403 处理方法：

```java
import io.github.springai.harness.permission.PermissionDeniedException;

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

```properties
# ===== 权限配置（Phase 4）=====
spring.ai.harness.mcp.server.permission.enabled=true
spring.ai.harness.mcp.server.permission.default-tool-policy=allow-all
spring.ai.harness.mcp.server.permission.admin-acl-default-policy=allow-all
spring.ai.harness.mcp.server.permission.user-acl-default-policy=allow-all
spring.ai.harness.mcp.server.permission.bypass-file-acl-tools=read_skill,list_skills,list_snapshots,rewind

# 工具权限（黑名单：未列出即默认可用）
spring.ai.harness.mcp.server.permission.user-roles[openclaw-code-assistant-readonly]=write,edit,trash

# 管理员全局文件 ACL（安全底线，所有身份共享；用户规则不可绕过）
spring.ai.harness.mcp.server.permission.file-acl-rules[0].pattern=secrets/**
spring.ai.harness.mcp.server.permission.file-acl-rules[0].access=DENY
spring.ai.harness.mcp.server.permission.file-acl-rules[0].priority=10
spring.ai.harness.mcp.server.permission.file-acl-rules[1].pattern=**/*.env
spring.ai.harness.mcp.server.permission.file-acl-rules[1].access=DENY
spring.ai.harness.mcp.server.permission.file-acl-rules[1].priority=100

# 用户自服务文件 ACL（per-identity，与管理员全局 AND 合并取严）
spring.ai.harness.mcp.server.permission.user-file-acls[openclaw-code-assistant-alice][0].pattern=docs/secret/**
spring.ai.harness.mcp.server.permission.user-file-acls[openclaw-code-assistant-alice][0].access=DENY
spring.ai.harness.mcp.server.permission.user-file-acls[openclaw-code-assistant-alice][0].priority=20
```

---

## 四、配置说明

### 4.1 工具权限（黑名单，按人）

```properties
spring.ai.harness.mcp.server.permission.user-roles[{system}-{agent}-{user}]=工具1,工具2
# 未配置 = 全部可用；* = 全部禁止
```

### 4.2 文件 ACL（管理员全局 + 用户自服务，AND 合并）

**两层规则**：

| 层 | 配置键 | 作用域 | 语义 |
|---|---|---|---|
| 管理员全局 | `file-acl-rules` | 所有身份共享 | 安全底线，用户不可绕过 |
| 用户自服务 | `user-file-acls[{identity}]` | per-identity | 用户自配本工作区，可更严 |

**AND 合并取严**（`canRead = adminCanRead && userCanRead`）：

| 管理员 | 用户 | 最终 |
|---|---|---|
| DENY | 任意 | DENY（底线不可绕过） |
| READ | READ | READ |
| READ | DENY | DENY（用户更严） |
| READ | WRITE | READ（取严，用户不能放宽到写） |
| WRITE | READ | READ（用户收紧） |
| WRITE | WRITE | WRITE |
| 无 | 任意 | 用户规则 |
| 任意 | 无 | 管理员规则 |
| 无 | 无 | allow |

**priority**：每层内部，多条规则命中同一路径时，priority 大的胜出（更具体覆盖更宽）。同级 DENY > WRITE > READ。

---

## 五、装饰器链顺序

```
请求 -> AliyunOssStorage -> QuotaEnforced -> PermissionEnforced（最外层）-> Observed
```

三个装饰器各自独立 `enabled` 开关，可任意组合启停。

---

## 六、核心判定流程

```
① 内部路径豁免: .snapshots/.trash/.shadow/ -> 直接 delegate
② 文件 ACL: canRead = adminCanRead && userCanRead
   拒绝 -> PermissionDeniedException(403)
③ delegate
```

---

## 七、复刻步骤

1. 新建 `permission` 目录，按「二」创建 4 个 java 文件
2. 在 `autoconfig` 包下创建 PermissionProperties.java
3. 按「三」修改 5 个既有文件
4. 调整 `application.properties` 中身份名单和 ACL 规则
5. 编译验证：`./mvnw compile -pl spring-ai-harness-mcp-server`
6. 启动验证：`permission.enabled=true`，用被禁身份调用被拒工具/路径，应返回 403

---

## 八、依赖敏感点

| 项目 | 说明 |
|---|---|
| 外部依赖 | 无新增（AntPathMatcher 来自 spring-core） |
| AOP | 无（纯装饰器） |
| 向后兼容 | `permission.enabled=false`（默认）不包装，行为不变 |
| 公司内适配 | 身份名单、管理员全局规则、用户自服务规则按公司实际调整 |
| Java 版本 | 17+ |
