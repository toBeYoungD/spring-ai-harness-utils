package io.github.springai.harness.permission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 统一权限配置模型 —— Phase 4 配置面与执行面的数据契约。
 * 配置从 {@code application.properties}（{{@code spring.ai.harness.mcp.server.permission.*}}）加载。
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
        DENY,   // 不可访问（最具体优先中胜出）
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
