package io.github.springai.harness.controller;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.github.springai.harness.dto.QuotaConfigDto;
import io.github.springai.harness.dto.SetQuotaLimitRequest;
import io.github.springai.harness.dto.WorkspaceQuotaDto;
import io.github.springai.harness.storage.AliyunOssStorage;
import io.github.springai.harness.storage.QuotaManager;
import io.github.springai.harness.storage.StorageProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配额管理台 REST API。
 * 所有接口需 X-Admin-Token 鉴权（与 {@link HarnessMcpServerProperties#getAdminToken()} 比对）。
 *
 * <p>跨工作区：直接用 OSS client 枚举 ossPrefix 下的 common prefixes 得到所有 workspace key，
 * 每个工作区构造裸 {@link AliyunOssStorage}（不套配额/权限装饰器）读写 .storage/.quota 元文件。
 * 写操作校验不在本控制器--{@link io.github.springai.harness.storage.QuotaEnforcedStorageProvider}
 * 在应用端写前调 {@link QuotaManager#checkQuota}，按生效上限（自定义优先）拦截。
 *
 * @author ichaobuster
 */
@RestController
@RequestMapping("/api/v1/admin/quota")
@Slf4j
public class QuotaAdminController {

	@Autowired
	private OSS ossClient;

	@Autowired
	private HarnessMcpServerProperties properties;

	@Autowired
	private QuotaManager quotaManager;

	private boolean checkAdmin(HttpServletRequest req) {
		String token = req.getHeader("X-Admin-Token");
		String expected = properties.getAdminToken();
		return expected != null && expected.equals(token);
	}

	private ResponseEntity<?> forbidden() {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("error", "Unauthorized: invalid X-Admin-Token"));
	}

	/** 规范化 OSS 前缀，确保以 / 结尾。 */
	private String normalizePrefix(String p) {
		if (p == null || p.isEmpty()) {
			return "";
		}
		return p.endsWith("/") ? p : p + "/";
	}

	/**
	 * 枚举所有 workspace key（OSS 前缀下的 common prefixes）。
	 * commonPrefix 形如 {ossPrefix}openclaw-research-alice/，去掉前缀和尾斜杠即 key。
	 */
	private List<String> listWorkspaceKeys() {
		String prefix = normalizePrefix(properties.getOssPrefix());
		List<String> keys = new ArrayList<>();
		String nextMarker = null;
		do {
			ListObjectsRequest req = new ListObjectsRequest(properties.getOssBucket())
					.withPrefix(prefix)
					.withDelimiter("/")
					.withMarker(nextMarker)
					.withMaxKeys(1000);
			ObjectListing listing = ossClient.listObjects(req);
			for (String commonPrefix : listing.getCommonPrefixes()) {
				String rel = commonPrefix.substring(prefix.length());
				if (rel.endsWith("/")) {
					rel = rel.substring(0, rel.length() - 1);
				}
				if (!rel.isEmpty()) {
					keys.add(rel);
				}
			}
			nextMarker = listing.getNextMarker();
		} while (nextMarker != null);
		return keys;
	}

	/** 为指定 workspace key 构造裸 AliyunOssStorage（不套配额/权限装饰器）。 */
	private StorageProvider workspaceStorage(String key) {
		String prefix = normalizePrefix(properties.getOssPrefix()) + key + "/";
		return new AliyunOssStorage(ossClient, properties.getOssBucket(), prefix);
	}

	/** 组装单个工作区配额 DTO（只读 .storage 缓存，不触发重算）。 */
	private WorkspaceQuotaDto toDto(String key) {
		StorageProvider storage = workspaceStorage(key);
		long usedBytes = 0L;
		Long lastRecalcAt = null;
		QuotaManager.StorageMeta meta = quotaManager.getCachedMeta(storage);
		if (meta != null) {
			usedBytes = meta.usedBytes();
			lastRecalcAt = meta.calculatedAt();
		}
		long customLimit = quotaManager.getCustomLimit(storage);
		boolean custom = customLimit > 0;
		long effectiveLimit = custom ? customLimit : quotaManager.getGlobalMaxBytes();
		// 真实 workspace key 恒为 system-agent-user 三段（auth 强制）；split 兜底防越界
		String[] parts = key.split("-", 3);
		String system = parts.length > 0 ? parts[0] : null;
		String agent = parts.length > 1 ? parts[1] : null;
		String user = parts.length > 2 ? parts[2] : null;
		return new WorkspaceQuotaDto(key, system, agent, user, usedBytes, customLimit, custom, effectiveLimit, lastRecalcAt);
	}

	// ---- 端点 ----

	@GetMapping("/workspaces")
	public ResponseEntity<?> listWorkspaces(HttpServletRequest req) {
		if (!checkAdmin(req)) {
			return forbidden();
		}
		List<String> keys = listWorkspaceKeys();
		List<WorkspaceQuotaDto> dtos = new ArrayList<>();
		for (String key : keys) {
			try {
				dtos.add(toDto(key));
			} catch (Exception e) {
				log.warn("读取工作区 {} 配额状态失败: {}", key, e.getMessage());
			}
		}
		return ResponseEntity.ok(dtos);
	}

	@GetMapping("/workspaces/{key}")
	public ResponseEntity<?> getWorkspace(HttpServletRequest req, @PathVariable String key) {
		if (!checkAdmin(req)) {
			return forbidden();
		}
		try {
			return ResponseEntity.ok(toDto(key));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
		}
	}

	/**
	 * 设置 per-workspace 自定义上限（写 .quota）。
	 * limitBytes 为 null 或 <=0 时回退全局默认（删除 .quota）。下次写操作即按新上限校验。
	 */
	@PutMapping("/workspaces/{key}/limit")
	public ResponseEntity<?> setLimit(HttpServletRequest req, @PathVariable String key,
			@RequestBody(required = false) SetQuotaLimitRequest body) {
		if (!checkAdmin(req)) {
			return forbidden();
		}
		Long limitBytes = body != null ? body.limitBytes() : null;
		StorageProvider storage = workspaceStorage(key);
		if (limitBytes == null || limitBytes <= 0) {
			quotaManager.clearCustomLimit(storage);
			return ResponseEntity.ok(Map.of("message", "已回退全局默认上限", "key", key));
		}
		quotaManager.setCustomLimit(storage, limitBytes);
		return ResponseEntity.ok(Map.of("message", "自定义上限已设置，下一次写操作即生效",
				"key", key, "limitBytes", limitBytes));
	}

	/** 触发指定工作区全量容量重算（重写 .storage）。 */
	@PostMapping("/workspaces/{key}/recalc")
	public ResponseEntity<?> recalc(HttpServletRequest req, @PathVariable String key) {
		if (!checkAdmin(req)) {
			return forbidden();
		}
		StorageProvider storage = workspaceStorage(key);
		long used = quotaManager.fullRecalculation(storage);
		return ResponseEntity.ok(Map.of("message", "全量重算完成", "key", key, "usedBytes", used));
	}

	/** 全局配额配置（只读，源自 application.properties）。 */
	@GetMapping("/config")
	public ResponseEntity<?> getConfig(HttpServletRequest req) {
		if (!checkAdmin(req)) {
			return forbidden();
		}
		HarnessMcpServerProperties.QuotaProperties q = properties.getQuota();
		long recalcHours = q.getRecalculationInterval() != null ? q.getRecalculationInterval().toHours() : 0L;
		QuotaConfigDto dto = new QuotaConfigDto(
				q.getMaxBytes(),
				q.getLimitFile(),
				q.getMetaFile(),
				recalcHours,
				q.isIncludeSnapshots(),
				q.isIncludeTrash(),
				q.isIncludeShadowCache());
		return ResponseEntity.ok(dto);
	}
}
