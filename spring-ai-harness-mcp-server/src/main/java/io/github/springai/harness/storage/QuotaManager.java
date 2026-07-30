package io.github.springai.harness.storage;

import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Manager for workspace storage quota.
 * Tracks usage, validates limit and updates capacity using .storage file.
 *
 * <p>per-workspace 自定义上限通过 .quota 元文件持久化（与 .storage 同位）：
 * 文件不存在或 limitBytes<=0 时回退全局 maxBytes；存在且 >0 即自定义上限生效。
 * 应用端写校验由 {@link QuotaEnforcedStorageProvider} 装饰器在写前调 {@link #checkQuota} 触发，
 * 故管理台设置上限后下一次写操作即按新上限校验。
 *
 * @author ichaobuster
 */
@Slf4j
public class QuotaManager {

	private final HarnessMcpServerProperties.QuotaProperties quotaProperties;

	public QuotaManager(HarnessMcpServerProperties.QuotaProperties quotaProperties) {
		this.quotaProperties = quotaProperties;
	}

	public boolean isTrashIncluded() {
		return quotaProperties.isIncludeTrash();
	}

	public boolean isSnapshotsIncluded() {
		return quotaProperties.isIncludeSnapshots();
	}

	public boolean isShadowCacheIncluded() {
		return quotaProperties.isIncludeShadowCache();
	}

	public String getMetaFile() {
		return quotaProperties.getMetaFile();
	}

	/**
	 * per-workspace 自定义上限元文件名（默认 .quota）。
	 */
	public String getLimitFile() {
		return quotaProperties.getLimitFile();
	}

	/**
	 * 全局默认上限（字节）。
	 */
	public long getGlobalMaxBytes() {
		return quotaProperties.getMaxBytes();
	}

	/**
	 * 获取已用容量（字节）。
	 * 如果元文件不存在、过期或损坏，将触发全量计算。
	 *
	 * @param storage 底层存储提供者（应避免传入包装了容量校验的外部提供者，防递归）
	 * @return 已用字节数
	 */
	public synchronized long getUsedBytes(StorageProvider storage) {
		String metaFile = quotaProperties.getMetaFile();
		try {
			if (storage.exists(metaFile) && !storage.isDirectory(metaFile)) {
				String content = storage.readString(metaFile);
				StorageMeta meta = parseMeta(content);
				if (meta != null && !isExpired(meta.calculatedAt())) {
					return meta.usedBytes();
				}
			}
		} catch (Exception e) {
			log.warn("读取容量元文件失败，将进行全量重计算: {}", e.getMessage());
		}

		return fullRecalculation(storage);
	}

	/**
	 * 只读 .storage 缓存元数据，不触发全量重算（不存在/损坏返回 null）。
	 * 供管理台 LIST 批量展示用，避免逐工作区触发重算。
	 */
	public StorageMeta getCachedMeta(StorageProvider storage) {
		String metaFile = quotaProperties.getMetaFile();
		try {
			if (storage.exists(metaFile) && !storage.isDirectory(metaFile)) {
				return parseMeta(storage.readString(metaFile));
			}
		} catch (Exception e) {
			log.warn("读取容量元文件失败: {}", e.getMessage());
		}
		return null;
	}

	/**
	 * 读取 .quota 自定义上限；不存在或 limitBytes<=0 返回 0（表示回退全局默认）。
	 */
	public long getCustomLimit(StorageProvider storage) {
		String limitFile = quotaProperties.getLimitFile();
		try {
			if (storage.exists(limitFile) && !storage.isDirectory(limitFile)) {
				QuotaLimit limit = parseLimit(storage.readString(limitFile));
				if (limit != null) {
					return limit.limitBytes();
				}
			}
		} catch (Exception e) {
			log.warn("读取配额上限元文件失败，回退全局默认: {}", e.getMessage());
		}
		return 0L;
	}

	/**
	 * 生效上限：自定义 >0 用自定义，否则全局 maxBytes。
	 */
	public long getEffectiveLimit(StorageProvider storage) {
		long custom = getCustomLimit(storage);
		return custom > 0 ? custom : quotaProperties.getMaxBytes();
	}

	/**
	 * 校验写入 deltaBytes 大小后是否超限。按生效上限（自定义优先，回退全局）校验。
	 *
	 * @param storage 底层存储提供者
	 * @param deltaBytes 新增的字节数（可能为负数，但负数或零不进行校验）
	 * @throws QuotaExceededException 如果容量超限
	 */
	public void checkQuota(StorageProvider storage, long deltaBytes) {
		if (deltaBytes <= 0) {
			return;
		}
		long maxBytes = getEffectiveLimit(storage);
		long usedBytes = getUsedBytes(storage);
		if (usedBytes + deltaBytes > maxBytes) {
			throw new QuotaExceededException(
					String.format("Storage quota exceeded. Limit: %d bytes, Used: %d bytes, Requested: %d bytes.",
							maxBytes, usedBytes, deltaBytes),
					usedBytes, maxBytes, deltaBytes);
		}
	}

	/**
	 * 增量更新已用容量。
	 * 同时更新过期时间戳。
	 *
	 * @param storage 底层存储提供者
	 * @param deltaBytes 增量字节数
	 */
	public synchronized void updateUsedBytes(StorageProvider storage, long deltaBytes) {
		if (deltaBytes == 0) {
			return;
		}
		long usedBytes = getUsedBytes(storage);
		long newUsedBytes = Math.max(0L, usedBytes + deltaBytes);

		StorageMeta newMeta = new StorageMeta(newUsedBytes, System.currentTimeMillis());
		try {
			writeMeta(storage, newMeta);
		} catch (Exception e) {
			log.error("更新容量元文件失败: {}", e.getMessage(), e);
		}
	}

	/**
	 * 设置 per-workspace 自定义上限（写 .quota）。limitBytes<=0 等同于清除（回退全局默认）。
	 */
	public synchronized void setCustomLimit(StorageProvider storage, long limitBytes) {
		if (limitBytes <= 0) {
			clearCustomLimit(storage);
			return;
		}
		QuotaLimit limit = new QuotaLimit(limitBytes, System.currentTimeMillis());
		try {
			writeLimit(storage, limit);
		} catch (Exception e) {
			log.error("写入配额上限元文件失败: {}", e.getMessage(), e);
		}
	}

	/**
	 * 清除 per-workspace 自定义上限（删 .quota），回退全局默认。
	 */
	public void clearCustomLimit(StorageProvider storage) {
		String limitFile = quotaProperties.getLimitFile();
		try {
			if (storage.exists(limitFile)) {
				storage.delete(limitFile);
			}
		} catch (Exception e) {
			log.error("清除配额上限元文件失败: {}", e.getMessage(), e);
		}
	}

	/**
	 * 全量重新计算已用容量并更新元文件。
	 *
	 * @param storage 底层存储提供者
	 * @return 计算得到的总字节数
	 */
	public synchronized long fullRecalculation(StorageProvider storage) {
		log.info("触发工作空间全量容量重计算");
		try {
			List<String> excludePrefixes = getExcludePrefixes();
			long totalSize = storage.calculateTotalSize(excludePrefixes);
			StorageMeta meta = new StorageMeta(totalSize, System.currentTimeMillis());
			writeMeta(storage, meta);
			return totalSize;
		} catch (Exception e) {
			log.error("全量容量计算失败，默认返回 0: {}", e.getMessage(), e);
			return 0L;
		}
	}

	private List<String> getExcludePrefixes() {
		List<String> excludes = new ArrayList<>();
		if (!quotaProperties.isIncludeSnapshots()) {
			excludes.add(".snapshots/");
		}
		if (!quotaProperties.isIncludeTrash()) {
			excludes.add(".trash/");
		}
		if (!quotaProperties.isIncludeShadowCache()) {
			excludes.add(".shadow/");
		}
		// 元文件自身不计入容量
		excludes.add(quotaProperties.getMetaFile());
		excludes.add(quotaProperties.getLimitFile());
		return excludes;
	}

	private boolean isExpired(long calculatedAt) {
		Duration interval = quotaProperties.getRecalculationInterval();
		if (interval == null || interval.isNegative() || interval.isZero()) {
			return false;
		}
		return System.currentTimeMillis() - calculatedAt > interval.toMillis();
	}

	private void writeMeta(StorageProvider storage, StorageMeta meta) throws IOException {
		String content = String.format("usedBytes=%d\ncalculatedAt=%d\n", meta.usedBytes(), meta.calculatedAt());
		storage.writeString(quotaProperties.getMetaFile(), content);
	}

	private void writeLimit(StorageProvider storage, QuotaLimit limit) throws IOException {
		String content = String.format("limitBytes=%d\nupdatedAt=%d\n", limit.limitBytes(), limit.updatedAt());
		storage.writeString(quotaProperties.getLimitFile(), content);
	}

	private StorageMeta parseMeta(String content) {
		if (!StringUtils.hasText(content)) {
			return null;
		}
		Long usedBytes = null;
		Long calculatedAt = null;
		String[] lines = content.split("\n");
		for (String line : lines) {
			int idx = line.indexOf('=');
			if (idx > 0) {
				String key = line.substring(0, idx).trim();
				String val = line.substring(idx + 1).trim();
				if ("usedBytes".equals(key)) {
					try {
						usedBytes = Long.parseLong(val);
					} catch (NumberFormatException ignored) {}
				} else if ("calculatedAt".equals(key)) {
					try {
						calculatedAt = Long.parseLong(val);
					} catch (NumberFormatException ignored) {}
				}
			}
		}
		if (usedBytes != null && calculatedAt != null) {
			return new StorageMeta(usedBytes, calculatedAt);
		}
		return null;
	}

	private QuotaLimit parseLimit(String content) {
		if (!StringUtils.hasText(content)) {
			return null;
		}
		Long limitBytes = null;
		Long updatedAt = null;
		String[] lines = content.split("\n");
		for (String line : lines) {
			int idx = line.indexOf('=');
			if (idx > 0) {
				String key = line.substring(0, idx).trim();
				String val = line.substring(idx + 1).trim();
				if ("limitBytes".equals(key)) {
					try {
						limitBytes = Long.parseLong(val);
					} catch (NumberFormatException ignored) {}
				} else if ("updatedAt".equals(key)) {
					try {
						updatedAt = Long.parseLong(val);
					} catch (NumberFormatException ignored) {}
				}
			}
		}
		if (limitBytes != null) {
			return new QuotaLimit(limitBytes, updatedAt != null ? updatedAt : 0L);
		}
		return null;
	}

	public record StorageMeta(long usedBytes, long calculatedAt) {}

	/**
	 * per-workspace 自定义上限元数据。
	 */
	public record QuotaLimit(long limitBytes, long updatedAt) {}
}
