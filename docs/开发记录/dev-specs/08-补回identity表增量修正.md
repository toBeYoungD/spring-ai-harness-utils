# dev-spec 08：补回 workspace_identity 表--增量修正（含完整源码）

> **元信息**
> - 任务简述：07 误将 `workspace_identity` 表去掉，实际应保留。本文在 07 基础上增量补回 identity 表及相关方法。
> - 分支：`internal-dev`（参考实现文档，外部仓库不落 DB 代码）
> - 对应基线：[07-权限数据库存储实现方案.md](07-权限数据库存储实现方案.md)（无 identity 表版本）
> - 依赖敏感点摘要：新增 1 张表 + 外键约束（可选）；PermissionStore 接口增身份管理方法；向后兼容 07。

---

## 一、背景

- 07 按"无 identity 表 + admin/user 分两表"实现，但用户实际**认同 identity 表**（"不要 identity 表"仅为影响评估）。
- 本文在 07 基础上**增量补回** `workspace_identity` 表，并给 Store 增加身份管理方法。
- 最终表结构：4 张表（`workspace_identity` + `tool_permission_denied` + `admin_acl_rule` + `user_acl_rule`）。

## 二、新增表：`workspace_identity`（工作区身份注册）

```sql
CREATE TABLE workspace_identity (
    identity_key VARCHAR(128) PRIMARY KEY,     -- {system}-{agent}-{user}
    system       VARCHAR(64)  NOT NULL,
    agent        VARCHAR(64)  NOT NULL,
    user         VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_system (system)
);
```

用途：
- 管理台**列已配权限的用户**：`SELECT * FROM workspace_identity`
- **按 system 聚合统计**（stat 卡"涉及系统数"）：`SELECT system, COUNT(*) FROM workspace_identity GROUP BY system`
- **展示三段身份**：直接读 `system`/`agent`/`user` 字段，无需从 `identity_key` 字符串解析（避免 user 名含 `-` 的解析风险）

## 三、外键约束（可选）

`tool_permission_denied` 和 `user_acl_rule` 的 `identity_key` 可加外键约束到 `workspace_identity`，保证不出现"幽灵身份"：

```sql
ALTER TABLE tool_permission_denied
    ADD CONSTRAINT fk_tp_identity
    FOREIGN KEY (identity_key) REFERENCES workspace_identity(identity_key);

ALTER TABLE user_acl_rule
    ADD CONSTRAINT fk_uar_identity
    FOREIGN KEY (identity_key) REFERENCES workspace_identity(identity_key);
```

- **加外键**：数据一致性强，删除身份可级联删规则（`ON DELETE CASCADE`）
- **不加外键**：性能略好，配置面写规则时不强制先注册身份（更灵活）

建议：**加外键 + `ON DELETE CASCADE`**，删身份时自动清规则，无残留。

```sql
ALTER TABLE tool_permission_denied
    ADD CONSTRAINT fk_tp_identity
    FOREIGN KEY (identity_key) REFERENCES workspace_identity(identity_key)
    ON DELETE CASCADE;

ALTER TABLE user_acl_rule
    ADD CONSTRAINT fk_uar_identity
    FOREIGN KEY (identity_key) REFERENCES workspace_identity(identity_key)
    ON DELETE CASCADE;
```

`admin_acl_rule` 无 `identity_key`（全局规则），不需要外键。

## 四、PermissionStore 接口增身份管理方法

在 07 的 `PermissionStore` 接口基础上，新增身份注册/查询方法（供 Phase 3 管理台用）：

```java
public interface PermissionStore {
    // ===== 07 既有（不变）=====
    PermissionConfig loadByIdentity(String identityKey);
    Set<String> loadDeniedTools(String identityKey);
    void saveDeniedTools(String identityKey, Set<String> toolNames);
    void deleteDeniedTools(String identityKey);
    List<PermissionConfig.AclRule> loadAdminRules();
    void saveAdminRule(PermissionConfig.AclRule rule);
    void deleteAdminRule(Long id);
    List<PermissionConfig.AclRule> loadUserRules(String identityKey);
    void saveUserRule(String identityKey, PermissionConfig.AclRule rule);
    void deleteUserRule(Long id);

    // ===== 08 新增：身份管理 =====

    /** 列出所有已注册身份（管理台用户列表） */
    List<WorkspaceIdentity> loadAllIdentities();

    /** 按 system 聚合统计（stat 卡） */
    List<String> loadSystems();

    /** 注册身份（配权限前先注册） */
    void registerIdentity(String identityKey, String system, String agent, String user);

    /** 删除身份（级联删规则，需外键 ON DELETE CASCADE） */
    void deleteIdentity(String identityKey);
}
```

身份记录类型（新增 `permission/WorkspaceIdentity.java`）：

```java
package io.github.springai.harness.permission;

/**
 * 工作区身份记录（DB 存储用）。
 * identity_key = {system}-{agent}-{user}，与运行时 WorkspaceIdentity 对齐。
 */
public record WorkspaceIdentity(
        String identityKey,
        String system,
        String agent,
        String user
) {}
```

## 五、DbPermissionStore 增身份实现

在 07 的 `DbPermissionStore` 基础上，新增身份管理方法：

```java
// 新增 RowMapper
private static final RowMapper<WorkspaceIdentity> IDENTITY_MAPPER = (rs, rowNum) ->
        new WorkspaceIdentity(
                rs.getString("identity_key"),
                rs.getString("system"),
                rs.getString("agent"),
                rs.getString("user"));

@Override
public List<WorkspaceIdentity> loadAllIdentities() {
    return jdbcTemplate.query(
            "SELECT identity_key, system, agent, user FROM workspace_identity",
            IDENTITY_MAPPER);
}

@Override
public List<String> loadSystems() {
    return jdbcTemplate.queryForList(
            "SELECT DISTINCT system FROM workspace_identity ORDER BY system",
            String.class);
}

@Override
public void registerIdentity(String identityKey, String system, String agent, String user) {
    jdbcTemplate.update(
            "INSERT INTO workspace_identity (identity_key, system, agent, user) VALUES (?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE system = VALUES(system), agent = VALUES(agent), user = VALUES(user)",
            identityKey, system, agent, user);
}

@Override
public void deleteIdentity(String identityKey) {
    // 若加了外键 ON DELETE CASCADE，删 identity 会自动清 tool_permission_denied 和 user_acl_rule
    jdbcTemplate.update("DELETE FROM workspace_identity WHERE identity_key = ?", identityKey);
}
```

> `ON DUPLICATE KEY UPDATE` 是 MySQL 语法；PostgreSQL 用 `ON CONFLICT ... DO UPDATE`，按公司数据库调整。

## 六、配置面写入流程变化

配权限前需先注册身份（若加了外键，必须先注册才能写规则）：

```java
// Phase 3 ToolPermissionController / FileAclController 中：
public void saveToolPermission(String identityKey, Set<String> deniedTools) {
    // 先确保身份已注册（拆 identity_key 得三段，或由调用方传入三段）
    String[] parts = identityKey.split("-", 3);
    permissionStore.registerIdentity(identityKey, parts[0], parts[1], parts[2]);
    // 再写规则
    permissionStore.saveDeniedTools(identityKey, deniedTools);
}
```

> `identity_key.split("-", 3)` 仍有 user 名含 `-` 的风险。**推荐**：配置面 REST 请求体直接传 `system`/`agent`/`user` 三段，Controller 拼成 `identity_key` 并注册，避免反解。

## 七、管理台视图改善（有 identity 表后）

| 管理台需求 | 07（无 identity 表） | 08（有 identity 表） |
|---|---|---|
| 列已配用户 | `SELECT DISTINCT identity_key FROM (tool_permission_denied UNION user_acl_rule)` | `SELECT * FROM workspace_identity` |
| 按 system 聚合 | 从 identity_key 字符串 split | `GROUP BY system`（直接读字段） |
| 涉及系统数 | 字符串解析，user 名含 `-` 出错 | `SELECT COUNT(DISTINCT system)` |
| 展示三段 | 从 identity_key 解析 | 直接读 system/agent/user |
| 删用户 | 手动删三张表 | 删 identity，外键级联清规则 |

## 八、CachingPermissionStore 的影响

缓存层无需改动--身份管理方法是配置面 CRUD，不进运行时热路径缓存。`loadByIdentity` 仍走 `CachingPermissionStore` 缓存，身份注册/删除只触发对应 identity 的缓存失效：

```java
@Override
public void deleteIdentity(String identityKey) {
    delegate.deleteIdentity(identityKey);
    cache.remove(identityKey);  // 失效该身份缓存
}
```

## 九、复刻步骤（07 基础上增量）

1. 建 `workspace_identity` 表（按「二」DDL）
2. 给 `tool_permission_denied` 和 `user_acl_rule` 加外键（按「三」，可选但推荐 `ON DELETE CASCADE`）
3. 新增 `permission/WorkspaceIdentity.java` record
4. `PermissionStore` 接口加 4 个身份方法（按「四」）
5. `DbPermissionStore` 实现这 4 个方法（按「五」）
6. `CachingPermissionStore` 在 `deleteIdentity` 后 invalidate 缓存（按「八」）
7. 配置面 Controller 写规则前先 `registerIdentity`（按「六」）
8. 验证：管理台列用户/按 system 聚合能正常展示

## 十、依赖敏感点

| 项目 | 说明 |
|---|---|
| 新增表 | `workspace_identity`（1 张） |
| 外键 | 可选，推荐 `ON DELETE CASCADE` 自动清规则 |
| 接口扩展 | `PermissionStore` 加 4 个方法，07 的 `YamlPermissionStore` 需同步实现（或抛 `UnsupportedOperationException`，yaml 模式不支持身份管理） |
| 向后兼容 | `loadByIdentity` 运行时路径不变，07 的缓存与判定逻辑零改动 |
| SQL 方言 | `ON DUPLICATE KEY`（MySQL）/ `ON CONFLICT`（PG），按公司数据库调整 |
| 身份注册 | 配置面写规则前需注册身份（若加外键则必须）；推荐 REST 传三段而非反解 identity_key |
| 公司内适配 | 表 DDL、外键策略、方言按公司实际 |
