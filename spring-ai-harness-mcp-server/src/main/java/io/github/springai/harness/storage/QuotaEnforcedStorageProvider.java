package io.github.springai.harness.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Decorator for {@link StorageProvider} that enforces storage quota checks and tracks usage.
 *
 * @author ichaobuster
 */
public class QuotaEnforcedStorageProvider implements StorageProvider {

	private final StorageProvider delegate;

	private final QuotaManager quotaManager;

	public QuotaEnforcedStorageProvider(StorageProvider delegate, QuotaManager quotaManager) {
		this.delegate = delegate;
		this.quotaManager = quotaManager;
	}

	private boolean isExcludedPath(String path) {
		if (path == null) {
			return false;
		}
		// 规范化路径，去除多余斜杠
		String cleanPath = path.replaceAll("/{2,}", "/");
		if (cleanPath.startsWith("./")) {
			cleanPath = cleanPath.substring(2);
		}
		if (cleanPath.startsWith("/")) {
			cleanPath = cleanPath.substring(1);
		}

		if (!quotaManager.isTrashIncluded() && cleanPath.startsWith(".trash/")) {
			return true;
		}
		if (!quotaManager.isSnapshotsIncluded() && cleanPath.startsWith(".snapshots/")) {
			return true;
		}
		if (!quotaManager.isShadowCacheIncluded() && cleanPath.startsWith(".shadow/")) {
			return true;
		}
		// 元文件本身不计入容量
		if (cleanPath.equals(quotaManager.getMetaFile())) {
			return true;
		}
		// per-workspace 自定义上限元文件同样不计入容量、不触发校验
		if (cleanPath.equals(quotaManager.getLimitFile())) {
			return true;
		}
		return false;
	}

	private long getPathSize(String path) {
		if (!delegate.exists(path)) {
			return 0L;
		}
		if (delegate.isDirectory(path)) {
			try {
				// 目录大小计算，不进行任何排除
				return delegate.subDirProvider(path).calculateTotalSize(null);
			} catch (Exception e) {
				return 0L;
			}
		} else {
			try {
				return delegate.getInfo(path).size();
			} catch (Exception e) {
				return 0L;
			}
		}
	}

	@Override
	public char getSeparator() {
		return delegate.getSeparator();
	}

	@Override
	public boolean isIgnoredPath(String path) {
		return delegate.isIgnoredPath(path);
	}

	@Override
	public StorageProvider subDirProvider(String subDir) {
		return new QuotaEnforcedStorageProvider(delegate.subDirProvider(subDir), quotaManager);
	}

	@Override
	public boolean exists(String path) {
		return delegate.exists(path);
	}

	@Override
	public boolean isDirectory(String path) {
		return delegate.isDirectory(path);
	}

	@Override
	public List<Info> listDirectory(String path) throws IOException {
		return delegate.listDirectory(path);
	}

	@Override
	public String readString(String path) throws IOException {
		return delegate.readString(path);
	}

	@Override
	public List<String> readAllLines(String path) throws IOException {
		return delegate.readAllLines(path);
	}

	@Override
	public void writeString(String path, String content) throws IOException {
		if (isExcludedPath(path)) {
			delegate.writeString(path, content);
			return;
		}

		byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
		long newSize = bytes.length;
		long oldSize = 0;
		if (delegate.exists(path) && !delegate.isDirectory(path)) {
			try {
				oldSize = delegate.getInfo(path).size();
			} catch (Exception ignored) {}
		}

		long delta = newSize - oldSize;
		if (delta > 0) {
			quotaManager.checkQuota(delegate, delta);
		}

		delegate.writeString(path, content);
		quotaManager.updateUsedBytes(delegate, delta);
	}

	@Override
	public void writeFile(String path, InputStream inputStream, long contentLength) throws IOException {
		if (isExcludedPath(path)) {
			delegate.writeFile(path, inputStream, contentLength);
			return;
		}

		long newSize = contentLength >= 0 ? contentLength : 0;
		long oldSize = 0;
		if (delegate.exists(path) && !delegate.isDirectory(path)) {
			try {
				oldSize = delegate.getInfo(path).size();
			} catch (Exception ignored) {}
		}

		long delta = newSize - oldSize;
		if (delta > 0) {
			quotaManager.checkQuota(delegate, delta);
		}

		delegate.writeFile(path, inputStream, contentLength);
		quotaManager.updateUsedBytes(delegate, delta);
	}

	@Override
	public void trash(String path) throws IOException {
		boolean oldExcluded = isExcludedPath(path);
		boolean newExcluded = !quotaManager.isTrashIncluded();

		long size = getPathSize(path);
		delegate.trash(path);

		if (!oldExcluded && newExcluded) {
			quotaManager.updateUsedBytes(delegate, -size);
		}
	}

	@Override
	public void delete(String path) throws IOException {
		boolean oldExcluded = isExcludedPath(path);
		long size = getPathSize(path);

		delegate.delete(path);

		if (!oldExcluded) {
			quotaManager.updateUsedBytes(delegate, -size);
		}
	}

	@Override
	public void rename(String oldPath, String newPath) throws IOException {
		boolean oldExcluded = isExcludedPath(oldPath);
		boolean newExcluded = isExcludedPath(newPath);

		long size = getPathSize(oldPath);

		if (oldExcluded && !newExcluded) {
			quotaManager.checkQuota(delegate, size);
			delegate.rename(oldPath, newPath);
			quotaManager.updateUsedBytes(delegate, size);
		} else if (!oldExcluded && newExcluded) {
			delegate.rename(oldPath, newPath);
			quotaManager.updateUsedBytes(delegate, -size);
		} else {
			delegate.rename(oldPath, newPath);
		}
	}

	@Override
	public List<String> glob(String pattern, String path) throws IOException {
		return delegate.glob(pattern, path);
	}

	@Override
	public List<String> grep(String pattern, String path, String glob, GrepOutputMode outputMode, Integer contextBefore, Integer contextAfter, Integer context, Boolean showLineNumbers, Boolean caseInsensitive, Integer headLimit, Integer offset, Boolean multiline) throws IOException {
		return delegate.grep(pattern, path, glob, outputMode, contextBefore, contextAfter, context, showLineNumbers, caseInsensitive, headLimit, offset, multiline);
	}

	@Override
	public Info getInfo(String path) throws IOException {
		return delegate.getInfo(path);
	}

	@Override
	public List<Info> getInfo(List<String> paths) {
		return delegate.getInfo(paths);
	}

	@Override
	public String readImage(String path) throws IOException {
		return delegate.readImage(path);
	}

	@Override
	public String readPdf(String path, Integer startPage, Integer endPage) throws IOException {
		return delegate.readPdf(path, startPage, endPage);
	}

	@Override
	public String readDocument(String path) throws IOException {
		return delegate.readDocument(path);
	}

	@Override
	public long calculateTotalSize(List<String> excludePrefixes) throws IOException {
		return delegate.calculateTotalSize(excludePrefixes);
	}

	@Override
	public void createDirectory(String path) throws IOException {
		delegate.createDirectory(path);
	}

	@Override
	public DownloadLink createDownloadLink(String path, Duration ttl) throws IOException {
		return delegate.createDownloadLink(path, ttl);
	}
}
