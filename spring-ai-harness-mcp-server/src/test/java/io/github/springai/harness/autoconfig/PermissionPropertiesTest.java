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
 * 含管理员全局 + 用户自服务 ACL 的 AND 合并。
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
    @DisplayName("管理员全局 ACL 规则转换: pattern + access + priority")
    void adminAclRulesConversion() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        PermissionProperties.AclRuleProperties r1 = new PermissionProperties.AclRuleProperties();
        r1.setPattern("secrets/**");
        r1.setAccess(PermissionConfig.Access.DENY);
        r1.setPriority(10);
        props.setFileAclRules(List.of(r1));

        PermissionConfig config = props.toPermissionConfig("any-user");

        assertThat(config.fileAcl().adminRules()).hasSize(1);
        assertThat(config.fileAcl().adminRules().get(0).pattern()).isEqualTo("secrets/**");
        assertThat(config.fileAcl().adminRules().get(0).access()).isEqualTo(PermissionConfig.Access.DENY);
        assertThat(config.fileAcl().adminRules().get(0).priority()).isEqualTo(10);
        // 未配 user 规则 -> 用户规则集为空
        assertThat(config.fileAcl().userRules()).isEmpty();
    }

    @Test
    @DisplayName("用户自服务 ACL 规则: 按 identity 取出")
    void userAclRulesPerIdentity() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        PermissionProperties.AclRuleProperties aliceRule = new PermissionProperties.AclRuleProperties();
        aliceRule.setPattern("docs/secret/**");
        aliceRule.setAccess(PermissionConfig.Access.DENY);
        aliceRule.setPriority(20);
        props.setUserFileAcls(Map.of(
                "openclaw-code-assistant-alice", List.of(aliceRule)));

        // alice 的身份 -> 取到自己的规则
        PermissionConfig aliceConfig = props.toPermissionConfig("openclaw-code-assistant-alice");
        assertThat(aliceConfig.fileAcl().userRules()).hasSize(1);
        assertThat(aliceConfig.fileAcl().userRules().get(0).pattern()).isEqualTo("docs/secret/**");

        // bob 的身份 -> 无用户规则
        PermissionConfig bobConfig = props.toPermissionConfig("openclaw-code-assistant-bob");
        assertThat(bobConfig.fileAcl().userRules()).isEmpty();
    }

    @Test
    @DisplayName("管理员 + 用户规则同时存在: 两层都注入")
    void bothAclRulesInjected() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        PermissionProperties.AclRuleProperties adminRule = new PermissionProperties.AclRuleProperties();
        adminRule.setPattern("secrets/**");
        adminRule.setAccess(PermissionConfig.Access.DENY);
        adminRule.setPriority(10);
        props.setFileAclRules(List.of(adminRule));

        PermissionProperties.AclRuleProperties userRule = new PermissionProperties.AclRuleProperties();
        userRule.setPattern("docs/secret/**");
        userRule.setAccess(PermissionConfig.Access.DENY);
        userRule.setPriority(20);
        props.setUserFileAcls(Map.of(
                "openclaw-code-assistant-alice", List.of(userRule)));

        PermissionConfig config = props.toPermissionConfig("openclaw-code-assistant-alice");

        assertThat(config.fileAcl().adminRules()).hasSize(1);
        assertThat(config.fileAcl().userRules()).hasSize(1);
    }

    @Test
    @DisplayName("admin-acl-default-policy=deny-all")
    void adminDenyAllPolicy() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        props.setAdminAclDefaultPolicy(PermissionProperties.Policy.DENY_ALL);

        PermissionConfig config = props.toPermissionConfig("any-user");

        assertThat(config.fileAcl().adminDefaultPolicy()).isEqualTo(PermissionConfig.FileAclConfig.Policy.DENY_ALL);
    }

    @Test
    @DisplayName("user-acl-default-policy=deny-all")
    void userDenyAllPolicy() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        props.setUserAclDefaultPolicy(PermissionProperties.Policy.DENY_ALL);

        PermissionConfig config = props.toPermissionConfig("any-user");

        assertThat(config.fileAcl().userDefaultPolicy()).isEqualTo(PermissionConfig.FileAclConfig.Policy.DENY_ALL);
    }

    @Test
    @DisplayName("默认: admin/user 都 allow-all")
    void defaultPoliciesAllowAll() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);

        PermissionConfig config = props.toPermissionConfig("any-user");

        assertThat(config.fileAcl().adminDefaultPolicy()).isEqualTo(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL);
        assertThat(config.fileAcl().userDefaultPolicy()).isEqualTo(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL);
    }

    @Test
    @DisplayName("AclRuleProperties 默认 access=DENY, priority=10")
    void aclRuleDefaults() {
        PermissionProperties.AclRuleProperties r = new PermissionProperties.AclRuleProperties();
        assertThat(r.getAccess()).isEqualTo(PermissionConfig.Access.DENY);
        assertThat(r.getPriority()).isEqualTo(10);
    }
}
