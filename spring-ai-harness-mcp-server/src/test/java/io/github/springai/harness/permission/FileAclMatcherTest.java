package io.github.springai.harness.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FileAclMatcher} 单元测试 -- 判定引擎核心逻辑。
 */
@DisplayName("FileAclMatcher 判定引擎")
class FileAclMatcherTest {

    private PermissionConfig.FileAclConfig acl(PermissionConfig.FileAclConfig.Policy policy, PermissionConfig.AclRule... rules) {
        return new PermissionConfig.FileAclConfig(policy, List.of(rules));
    }

    private PermissionConfig.AclRule rule(String pattern, PermissionConfig.Access access, int priority) {
        return new PermissionConfig.AclRule(pattern, access, priority);
    }

    @Nested
    @DisplayName("默认策略（无匹配规则）")
    class DefaultPolicy {

        @Test
        @DisplayName("allow-all: 无匹配放行 read/write")
        void allowAll() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL));
            assertThat(m.canRead("any/path")).isTrue();
            assertThat(m.canWrite("any/path")).isTrue();
        }

        @Test
        @DisplayName("deny-all: 无匹配拒绝 read/write")
        void denyAll() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.DENY_ALL));
            assertThat(m.canRead("any/path")).isFalse();
            assertThat(m.canWrite("any/path")).isFalse();
        }
    }

    @Nested
    @DisplayName("glob 匹配")
    class GlobMatch {

        @Test
        @DisplayName("* 单段匹配，不跨段")
        void singleStarSingleSegment() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                    rule("*.env", PermissionConfig.Access.DENY, 10)));
            assertThat(m.canRead("config.env")).isFalse();
            assertThat(m.canRead("dir/config.env")).isTrue(); // * 不跨段
        }

        @Test
        @DisplayName("** 跨段匹配")
        void doubleStarCrossSegment() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                    rule("**/*.env", PermissionConfig.Access.DENY, 10)));
            assertThat(m.canRead("dir/config.env")).isFalse();
            assertThat(m.canRead("a/b/c.env")).isFalse();
        }

        @Test
        @DisplayName("secrets/** 匹配 secrets 下所有")
        void secretsAll() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                    rule("secrets/**", PermissionConfig.Access.DENY, 10)));
            assertThat(m.canRead("secrets/key.txt")).isFalse();
            assertThat(m.canRead("secrets/sub/key.txt")).isFalse();
            assertThat(m.canRead("src/Main.java")).isTrue();
        }
    }

    @Nested
    @DisplayName("priority 优先")
    class Priority {

        @Test
        @DisplayName("更具体规则（高 priority）覆盖更宽规则")
        void moreSpecificWins() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                    rule("secrets/**", PermissionConfig.Access.DENY, 10),
                    rule("secrets/public/**", PermissionConfig.Access.READ, 20)));
            assertThat(m.canRead("secrets/public/readme.md")).isTrue();  // READ p20 胜出
            assertThat(m.canRead("secrets/private/key.txt")).isFalse();  // DENY p10
        }

        @Test
        @DisplayName("同级 deny-wins: 同 priority 下 DENY > WRITE > READ")
        void samePriorityDenyWins() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                    rule("doc/**", PermissionConfig.Access.WRITE, 10),
                    rule("doc/**", PermissionConfig.Access.DENY, 10)));
            assertThat(m.canRead("doc/a.txt")).isFalse();
            assertThat(m.canWrite("doc/a.txt")).isFalse();
        }

        @Test
        @DisplayName("同级 WRITE 胜 READ")
        void samePriorityWriteOverRead() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                    rule("doc/**", PermissionConfig.Access.READ, 10),
                    rule("doc/**", PermissionConfig.Access.WRITE, 10)));
            assertThat(m.canWrite("doc/a.txt")).isTrue();
            assertThat(m.canRead("doc/a.txt")).isTrue();
        }
    }

    @Nested
    @DisplayName("access 语义")
    class AccessSemantics {

        @Test
        @DisplayName("WRITE 隐含 READ")
        void writeImpliesRead() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.DENY_ALL,
                    rule("src/**", PermissionConfig.Access.WRITE, 10)));
            assertThat(m.canRead("src/Main.java")).isTrue();
            assertThat(m.canWrite("src/Main.java")).isTrue();
        }

        @Test
        @DisplayName("READ 路径: 可读不可写")
        void readOnly() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.DENY_ALL,
                    rule("docs/**", PermissionConfig.Access.READ, 10)));
            assertThat(m.canRead("docs/policy.md")).isTrue();
            assertThat(m.canWrite("docs/policy.md")).isFalse();
        }

        @Test
        @DisplayName("DENY: 读写全拒")
        void denyAll() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                    rule("secrets/**", PermissionConfig.Access.DENY, 10)));
            assertThat(m.canRead("secrets/key.txt")).isFalse();
            assertThat(m.canWrite("secrets/key.txt")).isFalse();
        }
    }

    @Nested
    @DisplayName("路径规范化")
    class Normalize {

        @Test
        @DisplayName("去前导 / 、./ 、多余斜杠")
        void normalizeLeadingSlashes() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                    rule("secrets/**", PermissionConfig.Access.DENY, 10)));
            assertThat(m.canRead("/secrets/key.txt")).isFalse();
            assertThat(m.canRead("./secrets/key.txt")).isFalse();
            assertThat(m.canRead("secrets//key.txt")).isFalse();
            assertThat(m.canRead("secrets///key.txt")).isFalse();
        }

        @Test
        @DisplayName("null 路径不抛异常")
        void nullPath() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL));
            assertThat(m.canRead(null)).isTrue();
            assertThat(m.canWrite(null)).isTrue();
        }
    }

    @Nested
    @DisplayName("findMatchingRule")
    class FindMatchingRule {

        @Test
        @DisplayName("返回 priority 最大的匹配规则")
        void returnsHighestPriority() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                    rule("secrets/**", PermissionConfig.Access.DENY, 10),
                    rule("secrets/public/**", PermissionConfig.Access.READ, 20)));
            PermissionConfig.AclRule rule = m.findMatchingRule("secrets/public/a.txt");
            assertThat(rule).isNotNull();
            assertThat(rule.access()).isEqualTo(PermissionConfig.Access.READ);
            assertThat(rule.priority()).isEqualTo(20);
        }

        @Test
        @DisplayName("无匹配返回 null")
        void noMatchReturnsNull() {
            FileAclMatcher m = new FileAclMatcher(acl(PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                    rule("secrets/**", PermissionConfig.Access.DENY, 10)));
            assertThat(m.findMatchingRule("src/Main.java")).isNull();
        }
    }

    @Test
    @DisplayName("AclRule 校验: pattern 不能为空")
    void aclRuleValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionConfig.AclRule("", PermissionConfig.Access.DENY, 10));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionConfig.AclRule("secrets/**", null, 10));
    }
}
