# Phase 4 权限执行面单测--同步说明（含完整源码）

> 对应 commit：`f514fef`（初版）+ `b8ad306`（适配按人维度 + AND 合并修正）
> 本文内嵌全部新增测试源码 + FileAclMatcher 修复，**公司内网无需拉取外部 git**，按本文创建文件即可复刻。

---

## 一、改动概要

为 Phase 4 权限执行面补充单元测试，并修复 `FileAclMatcher` 的不可变 List 排序 bug。测试已适配管理员全局 + 用户自服务 AND 合并模型（见 [04 同步文档](04-Phase4-统一权限执行面同步说明.md)）。

### 新增文件（3 个测试类）

| # | 路径 | 说明 |
|---|---|---|
| 1 | `permission/FileAclMatcherTest.java` | 判定引擎测试（含 AND 合并冲突矩阵） |
| 2 | `permission/PermissionEnforcedStorageProviderTest.java` | 装饰器测试 |
| 3 | `autoconfig/PermissionPropertiesTest.java` | 配置转换测试（含 per-identity） |

> 测试包路径前缀：`spring-ai-harness-mcp-server/src/test/java/io/github/springai/harness/`

### 修改文件（1 个 bug 修复）

| # | 路径 | 改动 |
|---|---|---|
| 4 | `permission/FileAclMatcher.java` | 构造时 rules 拷贝为可变 List |

---

## 二、bug 修复：FileAclMatcher 不可变 List 排序

`FileAclMatcher` 构造时对 `fileAcl.rules()` 调用 `sort()`，但 `List.of()` / `.toList()` 返回不可变 List，sort 抛 `UnsupportedOperationException`。修复：拷贝为可变 List。

```java
// 新增 import
import java.util.ArrayList;

// 构造器（adminRules 和 userRules 各自拷贝）
this.adminRules = new ArrayList<>(fileAcl.adminRules());
this.userRules = new ArrayList<>(fileAcl.userRules());
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
            assertThat(m.canRead("secrets/public/readme.md")).isFalse();
            assertThat(m.canRead("secrets/private/key.txt")).isFalse();
        }

        @Test
        @DisplayName("用户可以更严: 管理员 READ + 用户 DENY -> DENY")
        void userCanStricter() {
            FileAclMatcher m = new FileAclMatcher(bothAcl(
                    List.of(rule("docs/**", PermissionConfig.Access.READ, 10)),
                    List.of(rule("docs/secret/**", PermissionConfig.Access.DENY, 20))));
            assertThat(m.canRead("docs/policy.md")).isTrue();
            assertThat(m.canRead("docs/secret/k.txt")).isFalse();
        }

        @Test
        @DisplayName("管理员 READ + 用户 WRITE -> READ（取严，用户不能放宽到写）")
        void userCannotBroadenToWrite() {
            FileAclMatcher m = new FileAclMatcher(bothAcl(
                    List.of(rule("docs/**", PermissionConfig.Access.READ, 10)),
                    List.of(rule("docs/**", PermissionConfig.Access.WRITE, 10))));
            assertThat(m.canRead("docs/policy.md")).isTrue();
            assertThat(m.canWrite("docs/policy.md")).isFalse();
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
            assertThat(m.canRead("src/Main.java")).isTrue();
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

    /** 全放行配置（无规则 + allow-all） */
    private PermissionConfig allowAllConfig() {
        return PermissionConfig.builder()
                .enabled(true)
                .fileAcl(new PermissionConfig.FileAclConfig(
                        PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, List.of(),
                        PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, List.of()))
                .build();
    }

    /** secrets/** DENY 配置（放在管理员全局层） */
    private PermissionConfig denySecretsConfig() {
        return PermissionConfig.builder()
                .enabled(true)
                .fileAcl(new PermissionConfig.FileAclConfig(
                        PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                        List.of(new PermissionConfig.AclRule("secrets/**", PermissionConfig.Access.DENY, 10)),
                        PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, List.of()))
                .build();
    }

    /** docs/** READ 配置（只读，放在管理员全局层） */
    private PermissionConfig readDocsConfig() {
        return PermissionConfig.builder()
                .enabled(true)
                .fileAcl(new PermissionConfig.FileAclConfig(
                        PermissionConfig.FileAclConfig.Policy.ALLOW_ALL,
                        List.of(new PermissionConfig.AclRule("docs/**", PermissionConfig.Access.READ, 10)),
                        PermissionConfig.FileAclConfig.Policy.ALLOW_ALL, List.of()))
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
        @DisplayName("DENY 规则: read 被拒，不 delegate")
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
            assertThat(p.readString("docs/policy.md")).isEqualTo("content");
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
        @DisplayName("rename: old 不可写即拒")
        void renameOldDenied() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());
            assertThatThrownBy(() -> p.rename("secrets/key.txt", "dst.txt"))
                    .isInstanceOf(PermissionDeniedException.class);
            verify(delegate, never()).rename(anyString(), anyString());
        }

        @Test
        @DisplayName("rename: new 不可写即拒")
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
        @DisplayName(".snapshots/ 路径豁免")
        void snapshotsExempt() throws IOException {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());
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
        void getSeparator() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, allowAllConfig());
            when(delegate.getSeparator()).thenReturn('/');
            assertThat(p.getSeparator()).isEqualTo('/');
            verify(delegate).getSeparator();
        }

        @Test
        void isIgnoredPath() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, allowAllConfig());
            when(delegate.isIgnoredPath("path")).thenReturn(true);
            assertThat(p.isIgnoredPath("path")).isTrue();
            verify(delegate).isIgnoredPath("path");
        }

        @Test
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
        void subDirPathPrefix() {
            PermissionEnforcedStorageProvider p = new PermissionEnforcedStorageProvider(delegate, denySecretsConfig());
            StorageProvider subDelegate = mock(StorageProvider.class);
            when(delegate.subDirProvider("secrets")).thenReturn(subDelegate);

            StorageProvider subProvider = p.subDirProvider("secrets");
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
        assertThat(config.toolPermission().isAllowed("write")).isFalse();
        assertThat(config.toolPermission().isAllowed("edit")).isFalse();
        assertThat(config.toolPermission().isAllowed("trash")).isFalse();
    }

    @Test
    @DisplayName("enabled=true + 身份未配置: 全部工具放行")
    void unconfiguredIdentityAllAllowed() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        props.setUserRoles(Map.of("openclaw-code-assistant-readonly", Set.of("write")));
        PermissionConfig config = props.toPermissionConfig("hermes-research-charlie");
        assertThat(config.toolPermission().isAllowed("write")).isTrue();
        assertThat(config.toolPermission().isAllowed("read")).isTrue();
    }

    @Test
    @DisplayName("deniedTools 含 * : 全部工具禁止")
    void denyAllTools() {
        PermissionProperties props = new PermissionProperties();
        props.setEnabled(true);
        props.setUserRoles(Map.of("openclaw-code-assistant-blocked", Set.of("*")));
        PermissionConfig config = props.toPermissionConfig("openclaw-code-assistant-blocked");
        assertThat(config.toolPermission().isAllowed("read")).isFalse();
        assertThat(config.toolPermission().isAllowed("any-tool")).isFalse();
    }

    @Test
    @DisplayName("管理员全局 ACL 规则转换")
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
        props.setUserFileAcls(Map.of("openclaw-code-assistant-alice", List.of(aliceRule)));

        PermissionConfig aliceConfig = props.toPermissionConfig("openclaw-code-assistant-alice");
        assertThat(aliceConfig.fileAcl().userRules()).hasSize(1);
        assertThat(aliceConfig.fileAcl().userRules().get(0).pattern()).isEqualTo("docs/secret/**");

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
        props.setUserFileAcls(Map.of("openclaw-code-assistant-alice", List.of(userRule)));

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
```

---

## 四、测试覆盖点汇总

### FileAclMatcherTest（判定引擎）

| 分类 | 覆盖点 |
|---|---|
| 单层判定 | allow-all/deny-all、`*`/`**` glob、priority 优先、deny-wins、WRITE 隐含 READ、READ 只读 |
| **AND 合并** | 管理员 DENY 不可被用户放宽、用户可更严、用户不能放宽到写、用户可收紧、双方 WRITE、用户自服务 DENY、管理员 deny-all 兜底 |
| 路径规范化 | 前导 `/`/`./`/多余斜杠、null |
| findMatchingRule | 管理员优先、用户回退、无匹配 null |
| 校验 | pattern/access 非空 |

### PermissionEnforcedStorageProviderTest（装饰器）

| 分类 | 覆盖点 |
|---|---|
| Read | allow-all 放行、DENY 拒绝、exists/listDirectory/glob 基路径判定 |
| Write | allow-all、DENY、READ 路径 write 拒、delete、rename old/new 任一不可写即拒 |
| 内部路径豁免 | .snapshots/.trash/.shadow |
| 无判定方法 | getSeparator/isIgnoredPath/calculateTotalSize |
| subDirProvider | pathPrefix 还原 |
| 异常 | 携带 path/op/reason |

### PermissionPropertiesTest（配置转换）

| 分类 | 覆盖点 |
|---|---|
| 工具权限 | enabled 开关、黑名单匹配、未配置全放行、`*` 全禁 |
| 管理员 ACL | 规则转换 |
| 用户 ACL | per-identity 取出、未配置身份空 |
| 双层 | 两层同时注入 |
| default-policy | admin/user 各自 allow-all/deny-all |
| 默认值 | AclRuleProperties 默认 |

---

## 五、复刻步骤

1. 修复 `permission/FileAclMatcher.java`：按「二」新增 `import java.util.ArrayList;`，构造器 adminRules/userRules 各拷贝为 `new ArrayList<>(...)`
2. 在 `src/test/java/io/github/springai/harness/permission/` 下创建 `FileAclMatcherTest.java` 和 `PermissionEnforcedStorageProviderTest.java`
3. 在 `src/test/java/io/github/springai/harness/autoconfig/` 下创建 `PermissionPropertiesTest.java`
4. 运行：`./mvnw test -pl spring-ai-harness-mcp-server -Dtest=FileAclMatcherTest,PermissionEnforcedStorageProviderTest,PermissionPropertiesTest`
5. 预期：全部通过

---

## 六、依赖敏感点

| 项目 | 说明 |
|---|---|
| 测试依赖 | JUnit 5 + Mockito + AssertJ（已有，无新增） |
| Java 版本 | 17+ |
| mock 策略 | 全 Mockito mock，不连真 OSS |
| 覆盖率 | 核心逻辑 80%+ |
| bug 修复影响 | FileAclMatcher 不可变 List 修复无回归风险 |
