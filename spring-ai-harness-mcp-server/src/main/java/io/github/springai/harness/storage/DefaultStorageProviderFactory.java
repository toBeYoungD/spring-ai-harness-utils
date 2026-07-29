package io.github.springai.harness.storage;

import com.aliyun.oss.OSS;
import io.github.springai.harness.auth.AuthenticationProvider;
import io.github.springai.harness.auth.WorkspaceIdentity;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.micrometer.observation.ObservationRegistry;
import io.github.springai.harness.permission.PermissionEnforcedStorageProvider;
import io.modelcontextprotocol.common.McpTransportContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Default implementation of StorageProviderFactory using AuthenticationProvider to extract identity
 * and construct AliyunOssStorage.
 *
 * @author ichaobuster
 */
@Slf4j
public class DefaultStorageProviderFactory implements StorageProviderFactory {

	private final OSS ossClient;
	private final HarnessMcpServerProperties properties;
	private final AuthenticationProvider authenticationProvider;
	private final QuotaManager quotaManager;
	private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

	public DefaultStorageProviderFactory(OSS ossClient, HarnessMcpServerProperties properties, AuthenticationProvider authenticationProvider) {
		this(ossClient, properties, authenticationProvider, null, null);
	}

	public DefaultStorageProviderFactory(OSS ossClient, HarnessMcpServerProperties properties, AuthenticationProvider authenticationProvider, ObjectProvider<ObservationRegistry> observationRegistryProvider) {
		this(ossClient, properties, authenticationProvider, null, observationRegistryProvider);
	}

	public DefaultStorageProviderFactory(OSS ossClient, HarnessMcpServerProperties properties, AuthenticationProvider authenticationProvider, QuotaManager quotaManager, ObjectProvider<ObservationRegistry> observationRegistryProvider) {
		this.ossClient = ossClient;
		this.properties = properties;
		this.authenticationProvider = authenticationProvider;
		this.quotaManager = quotaManager != null ? quotaManager : new QuotaManager(properties.getQuota());
		this.observationRegistryProvider = observationRegistryProvider;
	}

	@Override
	public StorageProvider getStorageProvider(McpTransportContext context) {
		ServerRequest serverRequest = (ServerRequest) context.get(McpTransportContext.KEY);
		WorkspaceIdentity identity = this.authenticationProvider.authenticate(serverRequest);
		String workspaceKey = identity.getWorkspacePath(this.properties.getOssPrefix());
		StorageProvider baseStorage = new AliyunOssStorage(this.ossClient, this.properties.getOssBucket(), workspaceKey);

		// 检查工作空间根目录是否存在，若不存在则创建之，以防访问根目录报错
		if (!baseStorage.exists("")) {
			try {
				baseStorage.createDirectory("");
			}
			catch (Exception e) {
				log.warn("初始化工作空间根目录失败: {}", e.getMessage());
			}
		}

		if (this.properties.getQuota().isEnabled()) {
			baseStorage = new QuotaEnforcedStorageProvider(baseStorage, this.quotaManager);
		}

		// 权限装饰器——装饰链最外层（拒绝先于配额/观测副作用）
		if (this.properties.getPermission().isEnabled()) {
			String identityKey = identity.system() + "-" + identity.agent() + "-" + identity.user();
			var permConfig = this.properties.getPermission().toPermissionConfig(identityKey);
			baseStorage = new PermissionEnforcedStorageProvider(baseStorage, permConfig);
		}

		ObservationRegistry registry = observationRegistryProvider != null ? observationRegistryProvider.getIfAvailable() : null;
		if (registry != null && this.properties.getObservability().isEnabled()) {
			return new ObservedStorageProvider(baseStorage, registry);
		}
		return baseStorage;
	}
}
