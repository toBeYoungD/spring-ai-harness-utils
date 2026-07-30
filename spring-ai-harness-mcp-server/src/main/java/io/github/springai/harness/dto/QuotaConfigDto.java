package io.github.springai.harness.dto;

/**
 * 全局配额配置 DTO（只读，源自 application.properties）。
 *
 * @param maxBytes              全局默认上限（字节）
 * @param limitFile             per-workspace 自定义上限元文件名
 * @param metaFile              容量元文件名
 * @param recalcIntervalHours   全量重算间隔（小时，向下取整，仅展示用）
 * @param includeSnapshots      .snapshots/ 是否计入容量
 * @param includeTrash          .trash/ 是否计入容量
 * @param includeShadowCache    .shadow/ 是否计入容量
 * @author ichaobuster
 */
public record QuotaConfigDto(
		long maxBytes,
		String limitFile,
		String metaFile,
		long recalcIntervalHours,
		boolean includeSnapshots,
		boolean includeTrash,
		boolean includeShadowCache
) {
}
