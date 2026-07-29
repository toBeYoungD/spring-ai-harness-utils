package io.github.springai.harness.autoconfig;

import io.github.springai.harness.permission.PermissionConfig;
import lombok.Data;

import java.util.*;

/**
 * 权限配置 Properties —— 绑定 yaml 到 PermissionConfig。
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

        // 工具权限：黑名单模型——取 userRoles 中匹配的 denied-tools，空集=全部放行
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
