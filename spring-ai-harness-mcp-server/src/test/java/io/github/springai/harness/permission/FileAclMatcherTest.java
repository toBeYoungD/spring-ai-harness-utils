package io.github.springai.harness.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FileAclMatcher} 单元测试 -- 判定引擎核心逻辑（管理员全局 + 用户自服务 AND 合并）。
 */
@DisplayName("FileAclMatcher 判定引擎")
class FileAclMatcherTest {

    private PermissionConfig.AclRule rule(String pattern, PermissionConfig.Access access, int priority) {
        return new PermissionConfig.AclRule(pattern, access, priority);
    }

    /** 仅管理员规则（user allow-all），用于单层判定测试 */
    private PermissionConfig.FileAclConfig adminAcl(PermissionConfig.AclRule... rules) {
        return new PermissionConfig.FileAclConfig(
                PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, List.of(rules),
                PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, List.of());
    }

    /** 仅用户规则（admin allow-all），用于单层判定测试 */
    private PermissionConfig.FileAclConfig userAcl(PermissionConfig.AclRule... rules) {
        return new PermissionConfig.FileAclConfig(
                PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, List.of(),
                PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, List.of(rules));
    }

    /** 管理员 + 用户双层 */
    private PermissionConfig.FileAclConfig bothAcl(
            List<PermissionConfig.AclRule> adminRules, List<PermissionConfig.AclRule> userRules) {
        return new PermissionConfig.FileAclConfig(
                PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, adminRules,
                PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, userRules);
    }

    @Nested
    @DisplayName("单层判定（管理员规则，user allow-all）")
    class SingleLayer {

        @Test
        @DisplayName("allow-all: 无匹配放行")
        void allowAll() {
            FileAclMatcher m = new FileAclMatcher(adminAcl());
            assertThat(m.canRead("any/path")).isTrue();
            assertThat(m.canWrite("any/path")).isTrue();
        }

        @Test
        @DisplayName("deny-all: 无匹配拒绝")
        void denyAll() {
            FileAclMatcher m = new FileAclMatcher(new PermissionConfig.FileAclConfig(
                    PermissionConfig.FileAclConfig.Policy.DENY_ALL, List.of(),
                    PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, List.of()));
            assertThat(m.canRead("any/path")).isFalse();
            assertThat(m.canWrite("any/path")).isFalse();
        }

        @Test
        @DisplayName("* 单段匹配，不跨段")
        void singleStar() {
            FileAclMatcher m = new FileAclMatcher(adminAcl(rule("*.env", PermissionConfig.Access.DENY, 10)));
            assertThat(m.canRead("config.env")).isFalse();
            assertThat(m.canRead("dir/config.env")).isTrue();
        }

        @Test
        @DisplayName("** 跨段匹配")
        void doubleStar() {
            FileAclMatcher m = new FileAclMatcher(adminAcl(rule("**/*.env", PermissionConfig.Access.DENY, 10)));
            assertThat(m.canRead("dir/config.env")).isFalse();
            assertThat(m.canRead("a/b/c.env")).isFalse();
        }

        @Test
        @DisplayName("priority 优先: 更具体覆盖更宽")
        void priority() {
            FileAclMatcher m = new FileAclMatcher(adminAcl(
                    rule("secrets/**", PermissionConfig.Access.DENY, 10),
                    rule("secrets/public/**", PermissionConfig.Access.READ, 20)));
            assertThat(m.canRead("secrets/public/readme.md")).isTrue();
            assertThat(m.canRead("secrets/private/key.txt")).isFalse();
        }

        @Test
        @DisplayName("同级 deny-wins")
        void denyWins() {
            FileAclMatcher m = new FileAclMatcher(adminAcl(
                    rule("doc/**", PermissionConfig.Access.WRITE, 10),
                    rule("doc/**", PermissionConfig.Access.DENY, 10)));
            assertThat(m.canRead("doc/a.txt")).isFalse();
            assertThat(m.canWrite("doc/a.txt")).isFalse();
        }

        @Test
        @DisplayName("WRITE 隐含 READ")
        void writeImpliesRead() {
            FileAclMatcher m = new FileAclMatcher(adminAcl(rule("src/**", PermissionConfig.Access.WRITE, 10)));
            assertThat(m.canRead("src/Main.java")).isTrue();
            assertThat(m.canWrite("src/Main.java")).isTrue();
        }

        @Test
        @DisplayName("READ 路径: 可读不可写")
        void readOnly() {
            FileAclMatcher m = new FileAclMatcher(adminAcl(rule("docs/**", PermissionConfig.Access.READ, 10)));
            assertThat(m.canRead("docs/policy.md")).isTrue();
            assertThat(m.canWrite("docs/policy.md")).isFalse();
        }
    }

    @Nested
    @DisplayName("AND 合并（管理员 × 用户，取严）")
    class AndMerge {

        @Test
        @DisplayName("管理员 DENY 不可被用户放宽: 用户配 READ 仍 DENY")
        void adminDenyNotOverridable() {
            FileAclMatcher m = new FileAclMatcher(bothAcl(
                    List.of(rule("secrets/**", PermissionConfig.Access.DENY, 10)),
                    List.of(rule("secrets/public/**", PermissionConfig.Access.READ, 20))));
            // 用户想放宽 secrets/public，但管理员 DENY 是底线 -> 仍拒绝
            assertThat(m.canRead("secrets/public/readme.md")).isFalse();
            assertThat(m.canRead("secrets/private/key.txt")).isFalse();
        }

        @Test
        @DisplayName("用户可以更严: 管理员 READ + 用户 DENY -> DENY")
        void userCanStricter() {
            FileAclMatcher m = new FileAclMatcher(bothAcl(
                    List.of(rule("docs/**", PermissionConfig.Access.READ, 10)),
                    List.of(rule("docs/secret/**", PermissionConfig.Access.DENY, 20))));
            assertThat(m.canRead("docs/policy.md")).isTrue();   // 管理员 READ，用户无规则 -> 可读
            assertThat(m.canRead("docs/secret/k.txt")).isFalse(); // 用户 DENY -> 取严拒绝
        }

        @Test
        @DisplayName("管理员 READ + 用户 WRITE -> READ（取严，用户不能放宽到写）")
        void userCannotBroadenToWrite() {
            FileAclMatcher m = new FileAclMatcher(bothAcl(
                    List.of(rule("docs/**", PermissionConfig.Access.READ, 10)),
                    List.of(rule("docs/**", PermissionConfig.Access.WRITE, 10))));
            assertThat(m.canRead("docs/policy.md")).isTrue();   // 双方都允许读
            assertThat(m.canWrite("docs/policy.md")).isFalse();  // 管理员只读 -> 取严不可写
        }

        @Test
        @DisplayName("管理员 WRITE + 用户 READ -> READ（用户收紧为只读）")
        void userNarrowsWriteToRead() {
            FileAclMatcher m = new FileAclMatcher(bothAcl(
                    List.of(rule("src/**", PermissionConfig.Access.WRITE, 10)),
                    List.of(rule("src/**", PermissionConfig.Access.READ, 10))));
            assertThat(m.canRead("src/Main.java")).isTrue();
            assertThat(m.canWrite("src/Main.java")).isFalse();
        }

        @Test
        @DisplayName("双方都 WRITE -> 可写")
        void bothWrite() {
            FileAclMatcher m = new FileAclMatcher(bothAcl(
                    List.of(rule("src/**", PermissionConfig.Access.WRITE, 10)),
                    List.of(rule("src/**", PermissionConfig.Access.WRITE, 10))));
            assertThat(m.canRead("src/Main.java")).isTrue();
            assertThat(m.canWrite("src/Main.java")).isTrue();
        }

        @Test
        @DisplayName("管理员无规则 + 用户 DENY -> 用户拒绝（用户自服务生效）")
        void userDenyWhenAdminSilent() {
            FileAclMatcher m = new FileAclMatcher(bothAcl(
                    List.of(),
                    List.of(rule("temp/**", PermissionConfig.Access.DENY, 10))));
            assertThat(m.canRead("temp/junk.txt")).isFalse();
            assertThat(m.canRead("src/Main.java")).isTrue(); // 管理员无规则，用户无规则 -> allow
        }

        @Test
        @DisplayName("管理员 deny-all + 用户 allow-all -> 拒绝（管理员兜底底线）")
        void adminDenyAllNotOverridable() {
            FileAclMatcher m = new FileAclMatcher(new PermissionConfig.FileAclConfig(
                    PermissionConfig.FileAclConfig.Policy.DENY_ALL, List.of(),
                    PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, List.of()));
            assertThat(m.canRead("any/path")).isFalse();
            assertThat(m.canWrite("any/path")).isFalse();
        }
    }

    @Nested
    @DisplayName("路径规范化")
    class Normalize {

        @Test
        @DisplayName("去前导 / 、./ 、多余斜杠")
        void normalize() {
            FileAclMatcher m = new FileAclMatcher(adminAcl(rule("secrets/**", PermissionConfig.Access.DENY, 10)));
            assertThat(m.canRead("/secrets/key.txt")).isFalse();
            assertThat(m.canRead("./secrets/key.txt")).isFalse();
            assertThat(m.canRead("secrets//key.txt")).isFalse();
        }

        @Test
        @DisplayName("null 路径不抛异常")
        void nullPath() {
            FileAclMatcher m = new FileAclMatcher(adminAcl());
            assertThat(m.canRead(null)).isTrue();
        }
    }

    @Nested
    @DisplayName("findMatchingRule")
    class FindMatchingRule {

        @Test
        @DisplayName("管理员规则优先返回")
        void adminRuleFirst() {
            FileAclMatcher m = new FileAclMatcher(bothAcl(
                    List.of(rule("secrets/**", PermissionConfig.Access.DENY, 10)),
                    List.of(rule("secrets/public/**", PermissionConfig.Access.READ, 20))));
            PermissionConfig.AclRule r = m.findMatchingRule("secrets/public/a.txt");
            assertThat(r).isNotNull();
            // 管理员规则优先展示（即使 priority 低）
            assertThat(r.pattern()).isEqualTo("secrets/**");
        }

        @Test
        @DisplayName("无管理员规则时返回用户规则")
        void userRuleFallback() {
            FileAclMatcher m = new FileAclMatcher(bothAcl(
                    List.of(),
                    List.of(rule("temp/**", PermissionConfig.Access.DENY, 10))));
            PermissionConfig.AclRule r = m.findMatchingRule("temp/a.txt");
            assertThat(r).isNotNull();
            assertThat(r.pattern()).isEqualTo("temp/**");
        }

        @Test
        @DisplayName("无匹配返回 null")
        void noMatch() {
            FileAclMatcher m = new FileAclMatcher(adminAcl(rule("secrets/**", PermissionConfig.Access.DENY, 10)));
            assertThat(m.findMatchingRule("src/Main.java")).isNull();
        }
    }

    @Test
    @DisplayName("AclRule 校验: pattern/access 非空")
    void aclRuleValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionConfig.AclRule("", PermissionConfig.Access.DENY, 10));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionConfig.AclRule("secrets/**", null, 10));
    }
}
