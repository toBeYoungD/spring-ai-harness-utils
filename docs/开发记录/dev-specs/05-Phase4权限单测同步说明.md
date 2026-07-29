# Phase 4 权限执行面单测--同步说明（含完整源码）

> 对应 commit：`f514fef` test(04)
> 本文内嵌全部新增测试源码 + FileAclMatcher 修复，**公司内网无需拉取外部 git**，按本文创建文件即可复刻。

---

## 一、改动概要

为 Phase 4 权限执行面补充单元测试，并修复 `FileAclMatcher` 的不可变 List 排序 bug。

### 新增文件（3 个测试类）

| # | 路径 | 说明 |
|---|---|---|
| 1 | `permission/FileAclMatcherTest.java` | 判定引擎测试 |
| 2 | `permission/PermissionEnforcedStorageProviderTest.java` | 装饰器测试 |
| 3 | `autoconfig/PermissionPropertiesTest.java` | 配置转换测试 |

> 测试包路径前缀：`spring-ai-harness-mcp-server/src/test/java/io/github/springai/harness/`

### 修改文件（1 个 bug 修复）

| # | 路径 | 改动 |
|---|---|---|
| 4 | `permission/FileAclMatcher.java` | 构造时 rules 拷贝为可变 List |

---

## 二、bug 修复：FileAclMatcher 不可变 List 排序

### 问题

`FileAclMatcher` 构造时对 `fileAcl.rules()` 调用 `sort()`，但 `List.of()` / `.toList()` 返回的是**不可变 List**，sort 会抛 `UnsupportedOperationException`。实际运行时只要配置了任何 ACL 规则就会触发。

### 修复

新增 import：

```java
import java.util.ArrayList;
```

构造器改为拷贝为可变 List：

```java
public FileAclMatcher(PermissionConfig.FileAclConfig fileAcl) {
    // 拷贝为可变 List，避免 List.of()/.toList() 返回的不可变 List sort 失败
    this.rules = new ArrayList<>(fileAcl.rules());
    this.defaultPolicy = fileAcl.defaultPolicy();
    // 预排序：DESC by priority，同级 DENY > WRITE > READ -- 遍历时第一个命中即胜出
    this.rules.sort(Comparator
            .comparingInt(PermissionConfig.AclRule::priority).reversed()
            .thenComparing(r -> r.access().ordinal()));
}
```

---

## 三、新增测试完整源码

### 文件 1：`permission/FileAclMatcherTest.java`

```java
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
```

### 文件 2：`permission/PermissionEnforcedStorageProviderTest.java`

```java
package io.github.springai.harness.permission;

import io.github.springai.harness.storage.StorageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * {@link PermissionEnforcedStorageProvider} 单元测试 -- 仿 QuotaEnforcedStorageProviderTest 模式。
 */
@DisplayName("PermissionEnforcedStorageProvider 装饰器")
@ExtendWith(MockitoExtension.class)
class PermissionEnforcedStorageProviderTest {

    @Mock
    private StorageProvider delegate;

    // ===== 配置构造辅助 =====

    /** 全放行配置（无规则 + allow-all） */
    private PermissionConfig allowAllConfig() {
        return PermissionConfig.builder()
                .enabled(true)
                .fileAcl(new PermissionConfig.FileAclConfig(
                        PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, List.of()))
                .build();
    }

    /** secrets/** DENY 配置 */
    private PermissionConfig denySecretsConfig() {
        return PermissionConfig.builder()
                .enabled(true)
                .fileAcl(new PermissionConfig.FileAclConfig(
                        PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                        List.of(new PermissionConfig.AclRule("secrets/**", PermissionConfig.Access.DENY, 10))))
                .build();
    }

    /** docs/** READ 配置（只读） */
    private PermissionConfig readDocsConfig() {
        return PermissionConfig.builder()
                .enabled(true)
                .fileAcl(new PermissionConfig.FileAclConfig(
                        PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                        List.of(new PermissionConfig.AclRule("docs/**", PermissionConfig.Access.READ, 10))))
                .build();
    }

    @Nested
    @DisplayName("Read 判定")
    class ReadCheck {

        @Test
        @DisplayName("allow-all: read 放行并 delegate")
        void readAllowAll() throws IOException {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, allowAllConfig());
            when(delegate.readString("src/Main.java")).thenReturn("content");

            assertThat(p.readString("src/Main.java")).isEqualTo("content");
            verify(delegate).readString("src/Main.java");
        }

        @Test
        @DisplayName("DENY 规则: read 被拒，不 delegate，抛 PermissionDeniedException")
        void readDenied() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

            assertThatThrownBy(() -> p.readString("secrets/key.txt"))
                    .isInstanceOf(PermissionDeniedException.class)
                    .hasMessageContaining("read")
                    .hasMessageContaining("secrets/key.txt");

            verify(delegate, never()).readString(anyString());
        }

        @Test
        @DisplayName("exists 也受 ACL 判定")
        void existsDenied() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

            assertThatThrownBy(() -> p.exists("secrets/key.txt"))
                    .isInstanceOf(PermissionDeniedException.class);
            verify(delegate, never()).exists(anyString());
        }

        @Test
        @DisplayName("listDirectory 基路径判定")
        void listDirectoryDenied() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

            assertThatThrownBy(() -> p.listDirectory("secrets/sub"))
                    .isInstanceOf(PermissionDeniedException.class);
            verify(delegate, never()).listDirectory(anyString());
        }

        @Test
        @DisplayName("glob 基路径判定")
        void globDenied() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

            assertThatThrownBy(() -> p.glob("**", "secrets/sub"))
                    .isInstanceOf(PermissionDeniedException.class);
            verify(delegate, never()).glob(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Write 判定")
    class WriteCheck {

        @Test
        @DisplayName("allow-all: write 放行并 delegate")
        void writeAllowAll() throws IOException {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, allowAllConfig());

            p.writeString("src/Main.java", "content");

            verify(delegate).writeString("src/Main.java", "content");
        }

        @Test
        @DisplayName("DENY 规则: write 被拒")
        void writeDenied() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

            assertThatThrownBy(() -> p.writeString("secrets/key.txt", "content"))
                    .isInstanceOf(PermissionDeniedException.class);
            verify(delegate, never()).writeString(anyString(), anyString());
        }

        @Test
        @DisplayName("READ 规则: read 放行但 write 被拒")
        void readOnlyWriteDenied() throws IOException {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, readDocsConfig());
            when(delegate.readString("docs/policy.md")).thenReturn("content");

            // read 放行
            assertThat(p.readString("docs/policy.md")).isEqualTo("content");
            // write 被拒
            assertThatThrownBy(() -> p.writeString("docs/policy.md", "new"))
                    .isInstanceOf(PermissionDeniedException.class);
            verify(delegate, never()).writeString(anyString(), anyString());
        }

        @Test
        @DisplayName("delete 受 write 判定")
        void deleteDenied() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

            assertThatThrownBy(() -> p.delete("secrets/key.txt"))
                    .isInstanceOf(PermissionDeniedException.class);
            verify(delegate, never()).delete(anyString());
        }

        @Test
        @DisplayName("rename: old 不可写即拒，不 delegate")
        void renameOldDenied() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

            assertThatThrownBy(() -> p.rename("secrets/key.txt", "dst.txt"))
                    .isInstanceOf(PermissionDeniedException.class);
            verify(delegate, never()).rename(anyString(), anyString());
        }

        @Test
        @DisplayName("rename: new 不可写即拒，不 delegate")
        void renameNewDenied() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

            assertThatThrownBy(() -> p.rename("src.txt", "secrets/key.txt"))
                    .isInstanceOf(PermissionDeniedException.class);
            verify(delegate, never()).rename(anyString(), anyString());
        }

        @Test
        @DisplayName("rename: 两路径都可写则 delegate")
        void renameBothAllowed() throws IOException {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, allowAllConfig());

            p.rename("a.txt", "b.txt");

            verify(delegate).rename("a.txt", "b.txt");
        }
    }

    @Nested
    @DisplayName("内部路径豁免")
    class InternalPathExemption {

        @Test
        @DisplayName(".snapshots/ 路径豁免: 即使有 DENY 规则也放行")
        void snapshotsExempt() throws IOException {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

            // .snapshots/ 不受 secrets/** DENY 影响（内部路径豁免）
            p.readString(".snapshots/snap1/old.txt");

            verify(delegate).readString(".snapshots/snap1/old.txt");
        }

        @Test
        @DisplayName(".trash/ 路径豁免")
        void trashExempt() throws IOException {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

            p.writeString(".trash/old.txt", "content");

            verify(delegate).writeString(".trash/old.txt", "content");
        }

        @Test
        @DisplayName(".shadow/ 路径豁免")
        void shadowExempt() throws IOException {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

            p.readString(".shadow/cache.txt");

            verify(delegate).readString(".shadow/cache.txt");
        }
    }

    @Nested
    @DisplayName("无判定方法（直接委托）")
    class NoCheckMethods {

        @Test
        @DisplayName("getSeparator 直接委托")
        void getSeparator() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, allowAllConfig());
            when(delegate.getSeparator()).thenReturn('/');

            assertThat(p.getSeparator()).isEqualTo('/');
            verify(delegate).getSeparator();
        }

        @Test
        @DisplayName("isIgnoredPath 直接委托")
        void isIgnoredPath() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, allowAllConfig());
            when(delegate.isIgnoredPath("path")).thenReturn(true);

            assertThat(p.isIgnoredPath("path")).isTrue();
            verify(delegate).isIgnoredPath("path");
        }

        @Test
        @DisplayName("calculateTotalSize 直接委托")
        void calculateTotalSize() throws IOException {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, allowAllConfig());
            when(delegate.calculateTotalSize(List.of("ex"))).thenReturn(500L);

            assertThat(p.calculateTotalSize(List.of("ex"))).isEqualTo(500L);
            verify(delegate).calculateTotalSize(List.of("ex"));
        }
    }

    @Nested
    @DisplayName("subDirProvider 路径还原")
    class SubDirProvider {

        @Test
        @DisplayName("子装饰器按工作区根相对路径匹配 ACL")
        void subDirPathPrefix() throws IOException {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());
            StorageProvider subDelegate = mock(StorageProvider.class);
            when(delegate.subDirProvider("secrets")).thenReturn(subDelegate);

            StorageProvider subProvider = p.subDirProvider("secrets");

            // 子装饰器 readString("key.txt") 应还原为 "secrets/key.txt" 匹配 DENY 规则
            assertThat(subProvider).isInstanceOf(PermissionEnforcedStorageProvider.class);
            assertThatThrownBy(() -> subProvider.readString("key.txt"))
                    .isInstanceOf(PermissionDeniedException.class);
            verify(subDelegate, never()).readString(anyString());
        }
    }

    @Test
    @DisplayName("PermissionDeniedException 携带 path/op/reason")
    void exceptionCarriesDetails() {
        PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());

        assertThatThrownBy(() -> p.readString("secrets/key.txt"))
                .isInstanceOfSatisfying(PermissionDeniedException.class, e -> {
                    assertThat(e.getPath()).isEqualTo("secrets/key.txt");
                    assertThat(e.getOperation()).isEqualTo("read");
                    assertThat(e.getReason()).contains("DENY");
                });
    }
}
```

### 文件 3：`autoconfig/PermissionPropertiesTest.java`

```java
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
```

---

## 四、测试覆盖点汇总

### FileAclMatcherTest（判定引擎）

| 分类 | 覆盖点 |
|---|---|
| 默认策略 | allow-all 放行、deny-all 拒绝 |
| glob 匹配 | `*` 单段不跨段、`**` 跨段、`secrets/**` 递归 |
| priority | 更具体(高 priority)覆盖更宽、同级 deny-wins(DENY>WRITE>READ)、同级 WRITE 胜 READ |
| access 语义 | WRITE 隐含 READ、READ 只读不可写、DENY 全拒 |
| 路径规范化 | 去前导 `/`、`./`、多余斜杠、null 不抛异常 |
| findMatchingRule | 返回 priority 最大规则、无匹配返回 null |
| AclRule 校验 | pattern 空/blank 抛异常、access null 抛异常 |

### PermissionEnforcedStorageProviderTest（装饰器）

| 分类 | 覆盖点 |
|---|---|
| Read 判定 | allow-all 放行 delegate、DENY 拒绝不 delegate、exists/listDirectory/glob 基路径判定 |
| Write 判定 | allow-all 放行、DENY 拒绝、READ 路径 read 放行 write 拒、delete 受 write 判定、rename old/new 任一不可写即拒、两路径都可写则 delegate |
| 内部路径豁免 | `.snapshots/`/`.trash/`/`.shadow/` 即使有 DENY 规则也放行 |
| 无判定方法 | getSeparator/isIgnoredPath/calculateTotalSize 直接委托 |
| subDirProvider | 子装饰器按 pathPrefix 还原到工作区根相对路径匹配 ACL |
| 异常详情 | PermissionDeniedException 携带 path/op/reason |

### PermissionPropertiesTest（配置转换）

| 分类 | 覆盖点 |
|---|---|
| enabled 开关 | false 返回 disabled |
| 工具权限黑名单 | 身份匹配取 deniedTools、未配置身份全放行、`*` 全禁 |
| 文件 ACL 转换 | pattern/access/priority 正确转换 |
| default-policy | allow-all(默认)/deny-all |
| 默认值 | AclRuleProperties 默认 access=DENY priority=10 |

---

## 五、复刻步骤

1. 修复 `permission/FileAclMatcher.java`：按本文「二」新增 `import java.util.ArrayList;`，构造器改 `this.rules = new ArrayList<>(fileAcl.rules());`
2. 在 `src/test/java/io/github/springai/harness/permission/` 下创建 `FileAclMatcherTest.java` 和 `PermissionEnforcedStorageProviderTest.java`（按本文「三」源码）
3. 在 `src/test/java/io/github/springai/harness/autoconfig/` 下创建 `PermissionPropertiesTest.java`
4. 运行测试：`./mvnw test -pl spring-ai-harness-mcp-server -Dtest=FileAclMatcherTest,PermissionEnforcedStorageProviderTest,PermissionPropertiesTest`
5. 预期：全部通过

---

## 六、依赖敏感点

| 项目 | 说明 |
|---|---|
| 测试依赖 | JUnit 5 + Mockito + AssertJ（mcp-server 已有，无新增） |
| Java 版本 | 17+（record / switch 表达式 / `var`） |
| mock 策略 | 全 Mockito mock `StorageProvider` delegate，不连真 OSS，符合 AGENTS.md |
| 覆盖率目标 | 核心逻辑（FileAclMatcher + 装饰器判定）80%+ 行/分支覆盖 |
| bug 修复影响 | FileAclMatcher 不可变 List 修复对既有行为无影响（仅修正 sort 失败），无回归风险 |
