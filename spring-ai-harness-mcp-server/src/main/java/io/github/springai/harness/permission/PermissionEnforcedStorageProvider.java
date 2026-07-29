package io.github.springai.harness.permission;

import io.github.springai.harness.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * 统一权限装饰器——每次 StorageProvider 调用时一次性完成全部权限判定。
 *
 * <p>判定顺序：内部路径豁免 → 工具权限（本期仅配置文件白名单，无运行时 ThreadLocal，Phase 2 审计切面补齐）→ 文件 ACL → delegate。
 *
 * <p>装饰链最外层（拒绝先于配额/观测），permission.enabled=false 时不包装（零开销）。
 */
@Slf4j
public class PermissionEnforcedStorageProvider implements StorageProvider {

    private final StorageProvider delegate;
    private final FileAclMatcher fileAclMatcher;
    private final PermissionConfig config;
    private final String pathPrefix; // 子存储前缀，用于 subDirProvider 路径还原

    public PermissionEnforcedStorageProvider(StorageProvider delegate, PermissionConfig config) {
        this(delegate, config, "");
    }

    private PermissionEnforcedStorageProvider(StorageProvider delegate, PermissionConfig config, String pathPrefix) {
        this.delegate = delegate;
        this.config = config;
        this.fileAclMatcher = new FileAclMatcher(config.fileAcl());
        this.pathPrefix = pathPrefix;
    }

    // ===== 判定辅助 =====

    /** 将子提供者的相对路径还原为工作区根相对路径，供 ACL 匹配 */
    private String resolvePath(String rawPath) {
        if (pathPrefix.isEmpty()) {
            return rawPath;
        }
        String path = FileAclMatcher.normalize(rawPath);
        return pathPrefix + "/" + path;
    }

    /** 内部路径豁免——.snapshots/ .trash/ .shadow/ 一律放行 */
    private boolean isInternalPath(String path) {
        if (path == null) return false;
        String clean = FileAclMatcher.normalize(path);
        for (String p : INTERNAL_PATH_PATTERN) {
            String trimmed = p.startsWith("/") ? p.substring(1) : p;
            if (clean.equals(trimmed) || clean.startsWith(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private void checkRead(String rawPath) {
        String path = resolvePath(rawPath);
        if (isInternalPath(path)) return;

        if (!fileAclMatcher.canRead(path)) {
            PermissionConfig.AclRule rule = fileAclMatcher.findMatchingRule(path);
            String reason = rule != null
                    ? rule.access() + " by " + rule.pattern() + " (p" + rule.priority() + ")"
                    : "default-policy " + config.fileAcl().defaultPolicy();
            throw new PermissionDeniedException(path, "read", reason);
        }
    }

    private void checkWrite(String rawPath) {
        String path = resolvePath(rawPath);
        if (isInternalPath(path)) return;

        if (!fileAclMatcher.canWrite(path)) {
            PermissionConfig.AclRule rule = fileAclMatcher.findMatchingRule(path);
            String reason = rule != null
                    ? rule.access() + " by " + rule.pattern() + " (p" + rule.priority() + ")"
                    : "default-policy " + config.fileAcl().defaultPolicy();
            throw new PermissionDeniedException(path, "write", reason);
        }
    }

    // ===== 无判定（直接委托） =====

    @Override public char getSeparator() { return delegate.getSeparator(); }
    @Override public boolean isIgnoredPath(String path) { return delegate.isIgnoredPath(path); }

    @Override
    public StorageProvider subDirProvider(String subDir) {
        String newPrefix = pathPrefix.isEmpty() ? subDir : pathPrefix + "/" + subDir;
        return new PermissionEnforcedStorageProvider(delegate.subDirProvider(subDir), config, newPrefix);
    }

    @Override public long calculateTotalSize(List<String> excludePrefixes) throws IOException {
        return delegate.calculateTotalSize(excludePrefixes);
    }

    // ===== Read 判定方法 =====

    @Override public boolean exists(String path) { checkRead(path); return delegate.exists(path); }
    @Override public boolean isDirectory(String path) { checkRead(path); return delegate.isDirectory(path); }
    @Override public String readString(String path) throws IOException { checkRead(path); return delegate.readString(path); }
    @Override public List<String> readAllLines(String path) throws IOException { checkRead(path); return delegate.readAllLines(path); }
    @Override public String readImage(String path) throws IOException { checkRead(path); return delegate.readImage(path); }
    @Override public String readPdf(String path, Integer startPage, Integer endPage) throws IOException { checkRead(path); return delegate.readPdf(path, startPage, endPage); }
    @Override public String readDocument(String path) throws IOException { checkRead(path); return delegate.readDocument(path); }
    @Override public Info getInfo(String path) throws IOException { checkRead(path); return delegate.getInfo(path); }
    @Override public List<Info> getInfo(List<String> paths) { return delegate.getInfo(paths); } // 默认实现逐个调 getInfo

    @Override
    public List<Info> listDirectory(String path) throws IOException {
        checkRead(path != null ? path : "");
        return delegate.listDirectory(path);
    }

    @Override
    public List<String> glob(String pattern, String path) throws IOException {
        checkRead(path != null ? path : "");
        return delegate.glob(pattern, path);
    }

    @Override
    public List<String> grep(String pattern, String path, String g, GrepOutputMode outputMode, Integer contextBefore,
            Integer contextAfter, Integer context, Boolean showLineNumbers, Boolean caseInsensitive,
            Integer headLimit, Integer offset, Boolean multiline) throws IOException {
        checkRead(path != null ? path : "");
        return delegate.grep(pattern, path, g, outputMode, contextBefore, contextAfter,
                context, showLineNumbers, caseInsensitive, headLimit, offset, multiline);
    }

    @Override
    public DownloadLink createDownloadLink(String path, Duration ttl) throws IOException {
        checkRead(path);
        return delegate.createDownloadLink(path, ttl);
    }

    // ===== Write 判定方法 =====

    @Override public void writeString(String path, String content) throws IOException { checkWrite(path); delegate.writeString(path, content); }

    @Override
    public void writeFile(String path, InputStream inputStream, long contentLength) throws IOException {
        checkWrite(path);
        delegate.writeFile(path, inputStream, contentLength);
    }

    @Override public void createDirectory(String path) throws IOException { checkWrite(path); delegate.createDirectory(path); }
    @Override public void trash(String path) throws IOException { checkWrite(path); delegate.trash(path); }
    @Override public void delete(String path) throws IOException { checkWrite(path); delegate.delete(path); }

    @Override
    public void rename(String oldPath, String newPath) throws IOException {
        checkWrite(oldPath);
        checkWrite(newPath);
        delegate.rename(oldPath, newPath);
    }
}
