package io.github.springai.harness.dto;

/**
 * 设置 per-workspace 自定义上限请求。
 *
 * @param limitBytes 自定义上限字节；null 或 &lt;=0 表示回退全局默认（删除 .quota）
 * @author ichaobuster
 */
public record SetQuotaLimitRequest(Long limitBytes) {
}
