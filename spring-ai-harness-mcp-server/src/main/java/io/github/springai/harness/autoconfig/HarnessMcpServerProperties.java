package io.github.springai.harness.autoconfig;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for Spring AI Harness MCP Server.
 */
@Data
@ConfigurationProperties(prefix = HarnessMcpServerProperties.CONFIG_PREFIX)
public class HarnessMcpServerProperties {

	/**
	 * Spring AI Bocom Harness Agents configuration prefix.
	 */
	public static final String CONFIG_PREFIX = "spring.ai.harness.mcp.server";

	/**
	 * OSS 固定前缀
	 */
	private String ossPrefix = "mcp/workspaces/";

	/**
	 * 使用 OSS 与 JUMP OSS 的 StorageProvider 时，需要指定 bucketName
	 */
	private String ossBucket;

	/**
	 * 工作空间路径
	 */
	private String pwd = "workspace/";

	/**
	 * 管理员 API 认证 Token
	 */
	private String adminToken = "admin-secret";

	private ObservabilityProperties observability = new ObservabilityProperties();

	private QuotaProperties quota = new QuotaProperties();

	private RelayProperties relay = new RelayProperties();

	private DownloadProperties download = new DownloadProperties();

	private AttachmentProperties attachment = new AttachmentProperties();

	private PermissionProperties permission = new PermissionProperties();

	@Data
	public static class ObservabilityProperties {
		/**
		 * Whether to enable OpenTelemetry observability tracing.
		 */
		private boolean enabled = false;

		/**
		 * Exporter type: "otlp", "none"
		 */
		private String exportType = "otlp";

		/**
		 * Tracing sampling probability.
		 */
		private double probability = 1.0;
	}

	@Data
	public static class QuotaProperties {
		/**
		 * 是否启用工作空间容量限制
		 */
		private boolean enabled = true;

		/**
		 * 每个 workspace 的容量上限（字节），默认 1GB (1073741824 bytes)
		 */
		private long maxBytes = 1073741824L;

		/**
		 * 容量元文件名，默认 .storage
		 */
		private String metaFile = ".storage";

		/**
		 * per-workspace 自定义上限元文件名，默认 .quota。
		 * 文件不存在或 limitBytes<=0 时回退全局 maxBytes。
		 */
		private String limitFile = ".quota";

		/**
		 * 容量全量重计算间隔，默认 24h
		 */
		private Duration recalculationInterval = Duration.ofHours(24);

		/**
		 * 是否将 .snapshots/ 纳入容量计算，默认不纳入
		 */
		private boolean includeSnapshots = false;

		/**
		 * 是否将 .trash/ 纳入容量计算，默认纳入
		 */
		private boolean includeTrash = true;

		/**
		 * 是否将 .shadow/ 影子缓存纳入容量计算，默认不纳入
		 */
		private boolean includeShadowCache = false;
	}

	@Data
	public static class RelayProperties {
		/**
		 * Whether to enable the MCP tools relay/proxy capability.
		 */
		private boolean enabled = false;

		/**
		 * The downstream streamable-http MCP server URL.
		 */
		private String url;

		/**
		 * The static request headers (e.g. Authorization) to pass to the downstream MCP server.
		 */
		private Map<String, String> headers = new HashMap<>();
	}

	@Data
	public static class DownloadProperties {
		/**
		 * 是否启用文件下载链接功能
		 */
		private boolean enabled = true;

		/**
		 * 默认下载链接有效期（默认：1小时）
		 */
		private Duration defaultTtl = Duration.ofHours(1);
	}

	@Data
	public static class AttachmentProperties {
		/**
		 * 附件存储根目录（相对于工作空间根路径），默认 attachments
		 */
		private String basePath = "attachments";

		/**
		 * 未指定 conversationId 时的默认值
		 */
		private String defaultConversationId = "default";
	}

}
