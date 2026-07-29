package io.github.springai.harness.autoconfig;

import com.aliyun.oss.OSS;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.springai.harness.auth.AuthenticationProvider;
import io.github.springai.harness.auth.HeaderAuthenticationProvider;
import io.github.springai.harness.skill.DefaultSkillProvider;
import io.github.springai.harness.skill.SkillProvider;
import io.github.springai.harness.snapshot.DefaultSnapshotProvider;
import io.github.springai.harness.snapshot.SnapshotProvider;
import io.github.springai.harness.snapshot.ObservedSnapshotProvider;
import io.github.springai.harness.storage.DefaultStorageProviderFactory;
import io.github.springai.harness.storage.StorageProviderFactory;
import io.github.springai.harness.storage.QuotaManager;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;

/**
 * HarnessMcpServerAutoConfiguration
 *
 * @author ichaobuster
 */
@Configuration
@EnableConfigurationProperties({HarnessMcpServerProperties.class, PermissionProperties.class})
public class HarnessMcpServerAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public AuthenticationProvider authenticationProvider() {
		return new HeaderAuthenticationProvider();
	}

	@Bean
	@ConditionalOnMissingBean
	public QuotaManager quotaManager(HarnessMcpServerProperties properties) {
		return new QuotaManager(properties.getQuota());
	}

	@Bean
	@ConditionalOnMissingBean
	public StorageProviderFactory storageProviderFactory(
			OSS ossClient,
			HarnessMcpServerProperties properties,
			AuthenticationProvider authenticationProvider,
			QuotaManager quotaManager,
			ObjectProvider<ObservationRegistry> observationRegistryProvider) {
		return new DefaultStorageProviderFactory(ossClient, properties, authenticationProvider, quotaManager, observationRegistryProvider);
	}

	@Bean
	@ConditionalOnMissingBean
	public SkillProvider skillProvider(StorageProviderFactory storageProviderFactory) {
		return new DefaultSkillProvider(storageProviderFactory);
	}

	@Bean
	@ConditionalOnMissingBean
	public SnapshotProvider snapshotProvider(
			HarnessMcpServerProperties properties,
			ObjectProvider<ObservationRegistry> observationRegistryProvider) {
		SnapshotProvider baseSnapshotProvider = new DefaultSnapshotProvider();
		ObservationRegistry registry = observationRegistryProvider.getIfAvailable();
		if (registry != null && properties.getObservability().isEnabled()) {
			return new ObservedSnapshotProvider(baseSnapshotProvider, registry);
		}
		return baseSnapshotProvider;
	}

	@Bean
	@Primary
	public WebMvcStatelessServerTransport jumpWebMvcStatelessServerTransport(@Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper, McpServerStreamableHttpProperties serverProperties) {
		return WebMvcStatelessServerTransport.builder()
//				.jsonMapper(new JacksonMcpJsonMapper(objectMapper))
				.messageEndpoint(serverProperties.getMcpEndpoint())
				// TODO 自定义contextExtractor，Spring AI 有默认实现后考虑删除
				.contextExtractor(serverRequest -> McpTransportContext.create(Map.of(McpTransportContext.KEY, serverRequest)))
				.build();
	}

}
