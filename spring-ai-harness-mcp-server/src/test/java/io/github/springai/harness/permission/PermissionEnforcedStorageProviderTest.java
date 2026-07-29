package io.github.springai.harness.permission;

import io.github.springai.harness.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
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
