package io.github.springai.harness.permission;

import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文件 ACL 判定引擎 —— 无状态、线程安全。
 * 按 glob pattern 匹配路径，priority 越大越优先；同级 DENY > WRITE > READ。
 */
public class FileAclMatcher {

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<PermissionConfig.AclRule> rules;
    private final PermissionConfig.FileAclConfig.Policy defaultPolicy;

    public FileAclMatcher(PermissionConfig.FileAclConfig fileAcl) {
        // 拷贝为可变 List，避免 List.of()/.toList() 返回的不可变 List sort 失败
        this.rules = new ArrayList<>(fileAcl.rules());
        this.defaultPolicy = fileAcl.defaultPolicy();
        // 预排序：DESC by priority，同级 DENY > WRITE > READ —— 遍历时第一个命中即胜出
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
