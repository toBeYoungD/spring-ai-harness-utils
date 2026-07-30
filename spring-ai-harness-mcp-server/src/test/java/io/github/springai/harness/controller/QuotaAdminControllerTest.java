package io.github.springai.harness.controller;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.github.springai.harness.storage.QuotaManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link QuotaAdminController} 单测--MockMvc 验证 X-Admin-Token 鉴权、工作区枚举、设上限、重算、全局配置。
 * QuotaManager 与 OSS 均为 mock，构造裸 AliyunOssStorage 时不触发真实 OSS 调用。
 */
@DisplayName("QuotaAdminController Unit Tests")
@ExtendWith(MockitoExtension.class)
class QuotaAdminControllerTest {

	private static final String TOKEN = "admin-secret";
	private static final String KEY = "sys-ag-usr";

	@Mock
	private OSS ossClient;

	@Mock
	private QuotaManager quotaManager;

	@Mock
	private ObjectListing objectListing;

	private HarnessMcpServerProperties properties;
	private QuotaAdminController controller;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		controller = new QuotaAdminController();
		properties = new HarnessMcpServerProperties();
		properties.setAdminToken(TOKEN);
		properties.setOssBucket("test-bucket");
		properties.setOssPrefix("mcp/workspaces/");
		ReflectionTestUtils.setField(controller, "ossClient", ossClient);
		ReflectionTestUtils.setField(controller, "properties", properties);
		ReflectionTestUtils.setField(controller, "quotaManager", quotaManager);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	/** 配置 OSS listObjects 返回单个 workspace 前缀。 */
	private void stubOneWorkspace() {
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(objectListing);
		when(objectListing.getCommonPrefixes()).thenReturn(List.of("mcp/workspaces/" + KEY + "/"));
		when(objectListing.getNextMarker()).thenReturn(null);
	}

	// ---- 鉴权 ----

	@Test
	@DisplayName("Should return 403 when X-Admin-Token missing")
	void shouldReturn403WhenTokenMissing() throws Exception {
		mockMvc.perform(get("/api/v1/admin/quota/workspaces"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("Unauthorized: invalid X-Admin-Token"));
	}

	@Test
	@DisplayName("Should return 403 when X-Admin-Token wrong")
	void shouldReturn403WhenTokenWrong() throws Exception {
		mockMvc.perform(get("/api/v1/admin/quota/workspaces").header("X-Admin-Token", "wrong"))
				.andExpect(status().isForbidden());
	}

	// ---- 工作区列表/详情 ----

	@Test
	@DisplayName("Should list workspaces with quota status")
	void shouldListWorkspaces() throws Exception {
		stubOneWorkspace();
		long now = System.currentTimeMillis();
		when(quotaManager.getCachedMeta(any())).thenReturn(new QuotaManager.StorageMeta(777L, now));
		when(quotaManager.getCustomLimit(any())).thenReturn(500L);
		// custom=500 >0 -> 走自定义分支，getGlobalMaxBytes 不应被调用（不 stub 验证之）

		mockMvc.perform(get("/api/v1/admin/quota/workspaces").header("X-Admin-Token", TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].key").value(KEY))
				.andExpect(jsonPath("$[0].system").value("sys"))
				.andExpect(jsonPath("$[0].agent").value("ag"))
				.andExpect(jsonPath("$[0].user").value("usr"))
				.andExpect(jsonPath("$[0].usedBytes").value(777))
				.andExpect(jsonPath("$[0].limitBytes").value(500))
				.andExpect(jsonPath("$[0].custom").value(true))
				.andExpect(jsonPath("$[0].effectiveLimit").value(500));
	}

	@Test
	@DisplayName("Should use global limit when no custom limit set")
	void shouldUseGlobalLimitWhenNoCustom() throws Exception {
		stubOneWorkspace();
		when(quotaManager.getCachedMeta(any())).thenReturn(null);
		when(quotaManager.getCustomLimit(any())).thenReturn(0L);
		when(quotaManager.getGlobalMaxBytes()).thenReturn(1000L);

		mockMvc.perform(get("/api/v1/admin/quota/workspaces").header("X-Admin-Token", TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].usedBytes").value(0))
				.andExpect(jsonPath("$[0].custom").value(false))
				.andExpect(jsonPath("$[0].effectiveLimit").value(1000));
	}

	@Test
	@DisplayName("Should get single workspace detail")
	void shouldGetWorkspaceDetail() throws Exception {
		long now = System.currentTimeMillis();
		when(quotaManager.getCachedMeta(any())).thenReturn(new QuotaManager.StorageMeta(100L, now));
		when(quotaManager.getCustomLimit(any())).thenReturn(0L);
		when(quotaManager.getGlobalMaxBytes()).thenReturn(1000L);

		mockMvc.perform(get("/api/v1/admin/quota/workspaces/" + KEY).header("X-Admin-Token", TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.key").value(KEY))
				.andExpect(jsonPath("$.usedBytes").value(100))
				.andExpect(jsonPath("$.effectiveLimit").value(1000));
	}

	// ---- 设上限 ----

	@Test
	@DisplayName("Should set custom limit via PUT")
	void shouldSetCustomLimit() throws Exception {
		String body = new ObjectMapper().writeValueAsString(Map.of("limitBytes", 2048L));

		mockMvc.perform(put("/api/v1/admin/quota/workspaces/" + KEY + "/limit")
						.header("X-Admin-Token", TOKEN)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.limitBytes").value(2048));

		verify(quotaManager).setCustomLimit(any(), eq(2048L));
	}

	@Test
	@DisplayName("Should revert to global default when limitBytes is null")
	void shouldRevertToDefaultWhenLimitNull() throws Exception {
		// Map.of 不允许 null value，直接用 JSON 字面量
		String body = "{\"limitBytes\":null}";

		mockMvc.perform(put("/api/v1/admin/quota/workspaces/" + KEY + "/limit")
						.header("X-Admin-Token", TOKEN)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());

		verify(quotaManager).clearCustomLimit(any());
	}

	// ---- 重算 ----

	@Test
	@DisplayName("Should trigger full recalculation")
	void shouldTriggerRecalc() throws Exception {
		when(quotaManager.fullRecalculation(any())).thenReturn(4321L);

		mockMvc.perform(post("/api/v1/admin/quota/workspaces/" + KEY + "/recalc").header("X-Admin-Token", TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.usedBytes").value(4321));

		verify(quotaManager).fullRecalculation(any());
	}

	// ---- 全局配置 ----

	@Test
	@DisplayName("Should return read-only global quota config")
	void shouldReturnGlobalConfig() throws Exception {
		mockMvc.perform(get("/api/v1/admin/quota/config").header("X-Admin-Token", TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.limitFile").value(".quota"))
				.andExpect(jsonPath("$.metaFile").value(".storage"))
				.andExpect(jsonPath("$.recalcIntervalHours").value(24))
				.andExpect(jsonPath("$.includeTrash").value(true))
				.andExpect(jsonPath("$.includeSnapshots").value(false));
	}
}
