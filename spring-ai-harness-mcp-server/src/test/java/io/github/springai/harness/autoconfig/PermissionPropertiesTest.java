package io.github.springai.harness.autoconfig;

import io.github.springai.harness.permission.PermissionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PermissionProperties} 单元测试 -- yaml 配置到 PermissionConfig 的转换。
 */
@DisplayName("PermissionProperties 配置转换")
class PermissionPropertiesTest {

    @Test
    @DisplayName("enabled=false: 返回 disabled 配置")
    void disabledWhenNotEnabled() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(false);

        PermissionConfig config = props.toPermissionConfig("openclaw-code-assistant-alice");

        assertThat(config.enabled()).isFalse();
    }

    @Test
    @DisplayName("enabled=true + 身份匹配: 取该身份的 deniedTools 黑名单")
    void toolPermissionBlacklist() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        props.setUserRoles(Map.of(
                "openclaw-code-assistant-readonly", Set.of("write", "edit", "trash")));

        PermissionConfig config = props.toPermissionConfig("openclaw-code-assistant-readonly");

        assertThat(config.enabled()).isTrue();
        assertThat(config.toolPermission().isAllowed("read")).isTrue();
        assertThat(config.toolPermission().isAllowed("glob")).isTrue();
        assertThat(config.toolPermission().isAllowed("write")).isFalse();
        assertThat(config.toolPermission().isAllowed("edit")).isFalse();
        assertThat(config.toolPermission().isAllowed("trash")).isFalse();
    }

    @Test
    @DisplayName("enabled=true + 身份未配置: deniedTools 为空，全部工具放行")
    void unconfiguredIdentityAllAllowed() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        props.setUserRoles(Map.of(
                "openclaw-code-assistant-readonly", Set.of("write")));

        PermissionConfig config = props.toPermissionConfig("hermes-research-charlie");

        assertThat(config.toolPermission().isAllowed("write")).isTrue();
        assertThat(config.toolPermission().isAllowed("read")).isTrue();
        assertThat(config.toolPermission().isAllowed("edit")).isTrue();
    }

    @Test
    @DisplayName("deniedTools 含 * : 全部工具禁止")
    void denyAllTools() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        props.setUserRoles(Map.of(
                "openclaw-code-assistant-blocked", Set.of("*")));

        PermissionConfig config = props.toPermissionConfig("openclaw-code-assistant-blocked");

        assertThat(config.toolPermission().isAllowed("read")).isFalse();
        assertThat(config.toolPermission().isAllowed("write")).isFalse();
        assertThat(config.toolPermission().isAllowed("any-tool")).isFalse();
    }

    @Test
    @DisplayName("文件 ACL 规则转换: pattern + access + priority")
    void fileAclRulesConversion() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        PermissionProperties.AclRuleProperties r1 = new PermissionProperties.AclRuleProperties();
        r1.setPattern("secrets/**");
        r1.setAccess(PermissionConfig.Access.DENY);
        r1.setPriority(10);
        PermissionProperties.AclRuleProperties r2 = new PermissionProperties.AclRuleProperties();
        r2.setPattern("**/*.env");
        r2.setAccess(PermissionConfig.Access.DENY);
        r2.setPriority(100);
        props.setFileAclRules(List.of(r1, r2));

        PermissionConfig config = props.toPermissionConfig("any-user");

        assertThat(config.fileAcl().rules()).hasSize(2);
        assertThat(config.fileAcl().rules().get(0).pattern()).isEqualTo("secrets/**");
        assertThat(config.fileAcl().rules().get(0).access()).isEqualTo(PermissionConfig.Access.DENY);
        assertThat(config.fileAcl().rules().get(0).priority()).isEqualTo(10);
        assertThat(config.fileAcl().rules().get(1).pattern()).isEqualTo("**/*.env");
        assertThat(config.fileAcl().rules().get(1).priority()).isEqualTo(100);
    }

    @Test
    @DisplayName("default-acl-policy=deny-all: 无匹配规则拒绝")
    void denyAllAclPolicy() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        props.setDefaultAclPolicy(PermissionProperties.Policy.DENY_ALL);

        PermissionConfig config = props.toPermissionConfig("any-user");

        assertThat(config.fileAcl().defaultPolicy()).isEqualTo(PermissionConfig.FileAclConfig.Policy.DENY_ALL);
    }

    @Test
    @DisplayName("default-acl-policy=allow-all（默认）: 无匹配规则放行")
    void allowAllAclPolicyDefault() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);

        PermissionConfig config = props.toPermissionConfig("any-user");

        assertThat(config.fileAcl().defaultPolicy()).isEqualTo(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL);
    }

    @Test
    @DisplayName("AclRuleProperties 默认 access=DENY, priority=10")
    void aclRuleDefaults() {
        PermissionProperties.AclRuleProperties r = new PermissionProperties.AclRuleProperties();
        assertThat(r.getAccess()).isEqualTo(PermissionConfig.Access.DENY);
        assertThat(r.getPriority()).isEqualTo(10);
    }
}
