# dev-spec 06：文件 ACL 按人维度修正 + 管理员/用户 AND 合并

> **元信息**
> - 任务简述：将文件 ACL 从全局规则（所有身份共享）修正为按人维度（管理员全局 + 用户自服务双层），AND 合并取严。
> - 分支：`internal-dev`
> - 对应 commit：`b8ad306` feat/app-layer(04): 文件 ACL 改为按人维度 + 管理员/用户 AND 合并取严
> - 依赖敏感点摘要：无新增依赖；修正了 04 初版的设计偏差，已复刻 04 的公司内需按本文打补丁。

---

## 一、改动背景

- 04 初版的 `FileAclConfig` 是单层全局规则（`defaultPolicy + List<AclRule>`），所有身份共享同一套 ACL。
- 与技术方案 5.3（用户自服务）、mcp-server 工具权限隔离方案（按角色）、工具权限黑名单（已按人）均不一致。
- 无法实现"管理员设安全底线、用户自服务收紧自己工作区"的模型。

## 二、修正后的模型

```
管理员全局规则（file-acl-rules）--安全底线，所有身份共享
      AND 合并取严（canRead = adminCanRead && userCanRead）
用户自服务规则（user-file-acls[{identity}]）--per-identity，用户自配本工作区
```

- 管理员 DENY 不可被用户绕过（任何路径管理员说不可访问就不可访问）
- 用户可以比管理员更严，但不能更松（`READ + WRITE = READ`）
- 两方各自 rule 内部用 priority 排序（higher wins）+ deny-wins

## 三、改动清单

### 文件 1：`PermissionConfig.java`（FileAclConfig 拆为双层）

**改动**：`FileAclConfig` record 签名从单层改为双层。

```diff
-    public record FileAclConfig(Policy defaultPolicy, List<AclRule> rules) {
-        public static final FileAclConfig ALLOW_ALL = new FileAclConfig(Policy.ALLOW_ALL, List.of());
+    public record FileAclConfig(
+            Policy adminDefaultPolicy, List<AclRule> adminRules,   // 管理员全局规则
+            Policy userDefaultPolicy, List<AclRule> userRules      // 该身份用户自服务规则
+    ) {
+        public static final FileAclConfig ALLOW_ALL = new FileAclConfig(
+                Policy.ALLOW_ALL, List.of(), Policy.ALLOW_ALL, List.of());
```

### 文件 2：`FileAclMatcher.java`（单层判定 -> AND 合并）

**改动**：持两套独立 matcher，`canRead/canWrite` 做 AND。

关键变更：

```java
// 构造器：adminRules 和 userRules 各自拷贝 + 各自预排序
this.adminRules = new ArrayList<>(fileAcl.adminRules());
this.adminDefaultPolicy = fileAcl.adminDefaultPolicy();
this.userRules = new ArrayList<>(fileAcl.userRules());
this.userDefaultPolicy = fileAcl.userDefaultPolicy();
// 各自 DESC by priority，同级 DENY>WRITE>READ
this.adminRules.sort(cmp);
this.userRules.sort(cmp);

// canRead = adminCanRead && userCanRead（取严）
public boolean canRead(String workspaceRelativePath) {
    return adminCanRead(workspaceRelativePath) && userCanRead(workspaceRelativePath);
}

public boolean canWrite(String workspaceRelativePath) {
    return adminCanWrite(workspaceRelativePath) && userCanWrite(workspaceRelativePath);
}

// 新增 checkSingle 抽取单层判定逻辑（原 check 的循环体）
private boolean checkSingle(List<AclRule> rules, Policy defaultPolicy, String rawPath, boolean isWrite) {
    // ... 同原来 check 的循环逻辑
}
```

`findMatchingRule` 改为管理员优先返回：

```java
public AclRule findMatchingRule(String rawPath) {
    // 先查管理员（安全底线优先展示）
    AclRule admin = adminRules.stream().filter(...).findFirst().orElse(null);
    if (admin != null) return admin;
    // 再查用户
    return userRules.stream().filter(...).findFirst().orElse(null);
}
```

### 文件 3：`PermissionEnforcedStorageProvider.java`（reason 消息适配）

**改动**：`fileAcl().defaultPolicy()` 已不存在，改为展示 admin/user 双方的 default-policy。

```java
// checkRead / checkWrite 中的 reason fallback：
: "default-policy (admin=" + config.fileAcl().adminDefaultPolicy()
    + ", user=" + config.fileAcl().userDefaultPolicy() + ")";
```

### 文件 4：`PermissionProperties.java`（+ user-file-acls + 合并到 toPermissionConfig）

**改动**：

1. `defaultAclPolicy` 拆为 `adminAclDefaultPolicy` + `userAclDefaultPolicy`
2. 新增 `userFileAcls: Map<String, List<AclRuleProperties>>`（per-identity 用户规则）
3. `toPermissionConfig(identityKey)` 改为同时取出 admin 全局 + identity 用户规则合并注入

```java
// 管理员全局 ACL 规则
List<AclRule> adminRules = fileAclRules.stream()
        .map(r -> new AclRule(r.getPattern(), r.getAccess(), r.getPriority()))
        .toList();

// 用户自服务 ACL 规则（按 identity 取出）
List<AclRule> userRules = userFileAcls.getOrDefault(identityKey, List.of()).stream()
        .map(r -> new AclRule(r.getPattern(), r.getAccess(), r.getPriority()))
        .toList();

// 合并注入
new FileAclConfig(adminPolicy, adminRules, userPolicy, userRules)
```

### 文件 5：`application.properties`（配置样例补 per-identity）

**改动**：

1. `default-acl-policy` 拆为 `admin-acl-default-policy` + `user-acl-default-policy`
2. 新增 per-identity 用户规则样例

```properties
# 旧（删除）
spring.ai.harness.mcp.server.permission.default-acl-policy=allow-all

# 新（替换）
spring.ai.harness.mcp.server.permission.admin-acl-default-policy=allow-all
spring.ai.harness.mcp.server.permission.user-acl-default-policy=allow-all

# 新增：用户自服务 ACL（per-identity，与管理员全局 AND 合并取严）
spring.ai.harness.mcp.server.permission.user-file-acls[openclaw-code-assistant-alice][0].pattern=docs/secret/**
spring.ai.harness.mcp.server.permission.user-file-acls[openclaw-code-assistant-alice][0].access=DENY
spring.ai.harness.mcp.server.permission.user-file-acls[openclaw-code-assistant-alice][0].priority=20
```

### 文件 6：`FileAclMatcherTest.java`（测试适配新模型）

**改动**：

1. Helper `acl(policy, rules...)` 改为 `adminAcl(rules...)`（单层测试用） + `userAcl(rules...)` + `bothAcl(adminRules, userRules)`
2. 原有测试用例用 `adminAcl` 包装
3. **新增 `AndMerge` Nested 类**：7 个 AND 合并冲突矩阵用例

新增关键测试：

| 测试场景 | admin | user | 预期 |
|---|---|---|---|
| DENY 不可放宽 | `secrets/** DENY p10` | `secrets/public/** READ p20` | `secrets/public/` 仍 DENY |
| 用户可更严 | `docs/** READ p10` | `docs/secret/** DENY p20` | `docs/secret/` DENY |
| 用户不能放宽到写 | `docs/** READ p10` | `docs/** WRITE p10` | write 被拒 |
| 用户可收紧到只读 | `src/** WRITE p10` | `src/** READ p10` | write 被拒 |
| 双方都允许写 | `src/** WRITE p10` | `src/** WRITE p10` | write 放行 |
| 用户自服务 DENY | 无 | `temp/** DENY p10` | `temp/` 被拒 |
| 管理员 deny-all 兜底 | `Policy.DENY_ALL` | `Policy.ALLOW_ALL` | 全部拒绝 |

### 文件 7：`PermissionEnforcedStorageProviderTest.java`（config helper 适配）

**改动**：三个 config helper（`allowAllConfig`/`denySecretsConfig`/`readDocsConfig`）的 `FileAclConfig` 构造从单层改为双层签名（规则放入 admin 层，user 层 allow-all）。其余测试用例逻辑不变。

### 文件 8：`PermissionPropertiesTest.java`（适配新字段 + per-identity 测试）

**改动**：

1. `defaultAclPolicy` 测试拆为 `adminAclDefaultPolicy` + `userAclDefaultPolicy` 分别测试
2. `fileAclRulesConversion` 改为校验 `adminRules()` 和 `userRules()` 分别取值
3. **新增**：`userAclRulesPerIdentity`（按 identity 取出用户规则、未配身份空）、`bothAclRulesInjected`（两层同时注入）

---

## 四、AND 合并冲突矩阵

| 管理员 | 用户 | 最终 |
|---|---|---|
| DENY | 任意 | **DENY**（底线不可绕过） |
| READ | READ | READ |
| READ | DENY | **DENY**（用户更严） |
| READ | WRITE | **READ**（取严，不能放宽） |
| WRITE | READ | **READ**（用户收紧） |
| WRITE | WRITE | WRITE |
| 无 | 任意 | 用户规则 |
| 任意 | 无 | 管理员规则 |
| 无 | 无 | allow |

---

## 五、装饰器/工厂/异常处理

**不受影响**：`PermissionEnforcedStorageProvider` 装饰器、`DefaultStorageProviderFactory` 装配、`GlobalRestExceptionHandler` 403 映射——它们只消费 `PermissionConfig`，不感知内部 FileAclConfig 结构变化。`toPermissionConfig` 已在工厂里按 per-request identity 调用，天然按人维度。

---

## 六、复刻步骤（公司在已有 04 初版的基础上增量应用）

1. 按「三」逐个文件对照 diff 片段修改（8 个文件）
2. 重点：`FileAclConfig` 签名变了，所有 `new FileAclConfig(...)` 和 `config.fileAcl().defaultPolicy()` 等调用点都要改
3. `application.properties` 中 `default-acl-policy` 改名为 `admin-acl-default-policy` + `user-acl-default-policy`，补 per-identity 样例
4. 运行测试：`./mvnw test -pl spring-ai-harness-mcp-server -Dtest=FileAclMatcherTest,PermissionEnforcedStorageProviderTest,PermissionPropertiesTest`
5. 预期：全部通过，包括新增的 AND 合并冲突矩阵 7 个用例

---

## 七、依赖敏感点

| 项目 | 说明 |
|---|---|
| 外部依赖 | 无新增 |
| 配置兼容 | 属性名从 `default-acl-policy` 改为 `admin-acl-default-policy` + `user-acl-default-policy`，**不兼容**，已复刻 04 的必须改 |
| 代码兼容 | `FileAclConfig` 构造签名变了，所有 `new FileAclConfig(...)` 调用点都要改 |
| 向后兼容 | `permission.enabled=false` 行为不变 |
| 公司内适配 | 管理员全局规则、per-identity 用户规则按公司实际调整 |
