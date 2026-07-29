package io.github.springai.harness.permission;

import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文件 ACL 判定引擎 -- 无状态、线程安全。
 *
 * <p>持两套规则：管理员全局规则 + 用户自服务规则，<b>AND 合并取严</b>--
 * {@code canRead = adminCanRead && userCanRead}，{@code canWrite = adminCanWrite && userCanWrite}。
 *
 * <p>语义：管理员的 DENY 是安全底线，用户无法绕过；用户可以比管理员更严，但不能更松。
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
            return admin; // 管理员规则优先返回（即使不是拒绝方，也优先展示管理员约束）
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
