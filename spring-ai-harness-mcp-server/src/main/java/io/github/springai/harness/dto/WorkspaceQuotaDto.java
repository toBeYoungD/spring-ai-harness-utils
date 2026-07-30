package io.github.springai.harness.dto;

/**
 * 工作区配额状态 DTO（管理台用）。
 *
 * @param key             workspace 身份键（system-agent-user）
 * @param system          system 标识
 * @param agent           agent 标识
 * @param user            user 标识
 * @param usedBytes       已用字节
 * @param limitBytes      自定义上限字节（0 表示未自定义，回退全局默认）
 * @param custom          是否自定义上限
 * @param effectiveLimit  生效上限字节（自定义优先，回退全局）
 * @param lastRecalcAt    上次全量重算时间戳（epoch millis，null 表示无缓存）
 * @author ichaobuster
 */
public record WorkspaceQuotaDto(
		String key,
		String system,
		String agent,
		String user,
		long usedBytes,
		long limitBytes,
		boolean custom,
		long effectiveLimit,
		Long lastRecalcAt
) {
}
