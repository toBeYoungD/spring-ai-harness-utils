# dev-spec 06：配额后端 per-workspace 自定义上限生效 + 管理台 REST API

> **元信息**
> - 任务简述：让 per-workspace 自定义配额上限真正被应用端写校验生效（`.quota` 元文件），并新增管理台 REST API（`/api/v1/admin/quota/**`）供前端 `QuotaConsole` 从 mock 切真实数据。
> - 创建会话 / 日期：2026-07-30
> - 分支：`internal-dev`
> - 对应 commit：（待提交）
> - 依赖敏感点摘要：无新外部依赖；OSS `listObjects` + `delimiter` 为标准 SDK 能力，公司内若换非 OSS 存储，`workspaceStorage` 构造需适配。

---

## 一、需求背景

前端 `QuotaConsole`（dev-spec 01）已能"设置自定义上限 / 手动重算 / 刷新"，但都是 **mock 数据**。后端 `QuotaManager.checkQuota()` 只读全局 `maxBytes`（`spring.ai.harness.mcp.server.quota.max-bytes`），**根本不读 per-workspace 自定义上限**--管理台设的上限应用端写校验时不生效。

目标：
1. per-workspace 自定义上限**真正被应用端校验**（写操作经 `QuotaEnforcedStorageProvider` 装饰器在写前调 `checkQuota`，按生效上限拦截）。
2. 提供管理台 REST API（`X-Admin-Token` 鉴权）让 `QuotaConsole` 枚举所有工作区配额、设上限、触发重算、查看全局配置。

---

## 二、现状分析

- `QuotaManager.checkQuota()`（[QuotaManager.java](../../spring-ai-harness-mcp-server/src/main/java/io/github/springai/harness/storage/QuotaManager.java)）只用 `quotaProperties.getMaxBytes()`，不读 per-workspace 上限。
- `QuotaEnforcedStorageProvider`（装饰器）已在 `writeString/writeFile/rename` 写前调 `checkQuota`，写后调 `updateUsedBytes`--**校验链路已就绪**，只差 `checkQuota` 读生效上限。
- 容量元文件 `.storage` 模式：`usedBytes` + `calculatedAt`，增量更新 + 24h 全量重算。本方案照此模式新增 `.quota`。
- `DefaultStorageProviderFactory` 按 `Authorization` 头构造 workspace-scoped `AliyunOssStorage`，前缀 `mcp/workspaces/{system}-{agent}-{user}/`。
- 无任何 `/api/v1/admin/quota` 端点；`api.js` 的 `listWorkspaces` 等也后端未实现（本方案不涉及 workspace 文件管理，仅配额）。
- `AliyunOssAutoConfiguration` 已定义 `@Bean OSS ossClient`，可注入。
- 鉴权模式参考并行会话的 `AuditController.checkAdmin()`（比对 `properties.getAdminToken()`）。

---

## 三、方案设计

### 3.1 per-workspace 上限持久化：`.quota` 元文件（与 `.storage` 同位）

每个工作区 OSS 前缀下新增 `.quota` 元文件，格式 `limitBytes=<n>\nupdatedAt=<ms>\n`：
- 文件**不存在**或 `limitBytes<=0` -> 回退全局默认 `maxBytes`
- 文件存在且 `limitBytes>0` -> 该值即自定义上限
- "回退默认" = 删除 `.quota` 文件

**为什么**：与既有 `.storage` 模式一致；`QuotaManager` per-request 拿 workspace-scoped `StorageProvider`，读 `.quota` 不需知道 workspace key；持久化在 OSS，重启不丢；无需中心状态。

### 3.2 应用端校验：`checkQuota` 改读 effective limit

`checkQuota` 把全局 `maxBytes` 换成 `getEffectiveLimit(storage)`（读 `.quota`，无则全局）。**写操作校验链路不变**--仍由 `QuotaEnforcedStorageProvider` 在写前调用。设好上限后**下一次写操作即按新上限校验**。

### 3.3 管理台枚举所有工作区：OSS `listObjects` + delimiter

`QuotaAdminController` 注入 `OSS` bean，用 `listObjects(prefix=ossPrefix, delimiter="/")` 拿 common prefixes 即所有 workspace key；每个 key 构造**裸** `AliyunOssStorage(oss, bucket, ossPrefix+key)`（不套配额/权限装饰器）读 `.storage`/`.quota`、写 `.quota`。

### 3.4 LIST 不触发全量重算

`getUsedBytes()` 缺/过期 `.storage` 会触发 fullRecalc。LIST 每个工作区调会变 N 次重算。新增 `getCachedMeta(storage)` 只读 `.storage` 缓存（缺则 null，不重算）供 LIST；显式 `POST /recalc` 才调 `fullRecalculation`。

### 3.5 `.quota`/`.storage` 不计入容量

`QuotaEnforcedStorageProvider.isExcludedPath` 已排除 `.storage`，补排除 `.quota`；`QuotaManager.getExcludePrefixes` 全量重算排除列表补 `.quota`。

### 3.6 关键代码片段

**QuotaManager 生效上限**：
```java
public long getEffectiveLimit(StorageProvider storage) {
    long custom = getCustomLimit(storage);
    return custom > 0 ? custom : quotaProperties.getMaxBytes();
}
public void checkQuota(StorageProvider storage, long deltaBytes) {
    if (deltaBytes <= 0) return;
    long maxBytes = getEffectiveLimit(storage);  // 原为 quotaProperties.getMaxBytes()
    long usedBytes = getUsedBytes(storage);
    if (usedBytes + deltaBytes > maxBytes) throw new QuotaExceededException(...);
}
```

**QuotaAdminController 枚举 + 裸存储**：
```java
private List<String> listWorkspaceKeys() {
    String prefix = normalizePrefix(properties.getOssPrefix());
    ListObjectsRequest req = new ListObjectsRequest(bucket).withPrefix(prefix).withDelimiter("/").withMaxKeys(1000);
    // commonPrefix 形如 mcp/workspaces/sys-ag-usr/ -> 去前缀+尾斜杠 -> key
}
private StorageProvider workspaceStorage(String key) {
    return new AliyunOssStorage(ossClient, bucket, normalizePrefix(ossPrefix) + key + "/");
}
```

---

## 四、改动清单

### 后端（`spring-ai-harness-mcp-server`）

| 操作 | 文件 | 说明 |
|---|---|---|
| 修改 | `autoconfig/HarnessMcpServerProperties.java` | `QuotaProperties` 加 `limitFile=".quota"` 字段 |
| 修改 | `storage/QuotaManager.java` | `checkQuota` 用 `getEffectiveLimit`；新增 `getEffectiveLimit/getCustomLimit/setCustomLimit/clearCustomLimit/getCachedMeta/getGlobalMaxBytes/getLimitFile`；`getExcludePrefixes` 加 `.quota`；`.quota` 读写解析；新增 `QuotaLimit` record |
| 修改 | `storage/QuotaEnforcedStorageProvider.java` | `isExcludedPath` 补 `.quota` 排除 |
| 新增 | `dto/WorkspaceQuotaDto.java` | record：key/system/agent/user/usedBytes/limitBytes/custom/effectiveLimit/lastRecalcAt |
| 新增 | `dto/QuotaConfigDto.java` | record：maxBytes/limitFile/metaFile/recalcIntervalHours/includeSnapshots/includeTrash/includeShadowCache（只读） |
| 新增 | `dto/SetQuotaLimitRequest.java` | record：`limitBytes`（Long，null/<=0=回退默认） |
| 新增 | `controller/QuotaAdminController.java` | `@RestController @RequestMapping("/api/v1/admin/quota")`，`X-Admin-Token` 鉴权 |

**端点**（全部 `X-Admin-Token`）：
```
GET   /api/v1/admin/quota/workspaces          # 列全部工作区配额（枚举 OSS 前缀 + 读 .storage/.quota）
GET   /api/v1/admin/quota/workspaces/{key}    # 单工作区详情
PUT   /api/v1/admin/quota/workspaces/{key}/limit   # 设自定义上限（body {limitBytes}，null/0=回退默认->删 .quota）
POST  /api/v1/admin/quota/workspaces/{key}/recalc  # 触发该工作区全量重算
GET   /api/v1/admin/quota/config              # 全局配置（只读，源自 properties）
```

### 测试

| 操作 | 文件 | 说明 |
|---|---|---|
| 修改 | `storage/QuotaManagerTest.java` | `getExcludePrefixes` 断言加 `.quota`；新增 effective limit（自定义/回退）、checkQuota 用自定义上限、set/clear 自定义上限、getCachedMeta 用例 |
| 修改 | `storage/QuotaEnforcedStorageProviderTest.java` | setUp 加 `getLimitFile` mock；新增 `.quota` 路径不触发 checkQuota 用例 |
| 新增 | `controller/QuotaAdminControllerTest.java` | MockMvc + mock `OSS`/`QuotaManager`，验证枚举/设上限/重算/全局配置/403 鉴权 |

### 前端（`spring-ai-harness-server-frontend`）

| 操作 | 文件 | 说明 |
|---|---|---|
| 修改 | `src/services/api.js` | 加 `listQuotaWorkspaces/getQuotaWorkspace/setQuotaLimit/recalcQuota/getQuotaConfig`（`X-Admin-Token`） |
| 修改 | `src/components/QuotaConsole.js` | mock -> `useEffect` 调真实 API；加载/错误态；设上限/重算/刷新调真实 API；**API 失败回退 mock**（dev 预览无后端时 UI 仍可见）；页头加 `X-Admin-Token` 输入；`ConfigDrawer` 读 `/quota/config` 只读展示；`effLimit` 优先用后端 `effectiveLimit` |

---

## 五、依赖敏感点

- **无新外部依赖**。`com.aliyun.oss.OSS` / `ListObjectsRequest` 已在（`AliyunOssAutoConfiguration`）。
- `listObjects(prefix, delimiter="/")` 是 OSS 标准"列目录"能力，公司内外一致。
- **公司内若换非 OSS 存储**（如本地/MinIO/S3）：`QuotaAdminController.workspaceStorage()` 直接 `new AliyunOssStorage(...)` 是唯一硬绑 OSS 之处。适配方式：抽一个"按 workspaceKey 构造裸 StorageProvider"的工厂方法，或让 `StorageProviderFactory` 暴露 admin 专用构造口。当前仅 OSS，暂不抽象。
- **身份分段**：`HeaderAuthenticationProvider` 按 `-` 严格切 3 段（system-agent-user），故真实 workspace key 恒 3 段、各段无连字符。DTO 的 `split("-", 3)` 仅作展示分段，兜底防越界。

---

## 六、验证方式

1. **编译**：`./mvnw compile -pl spring-ai-harness-mcp-server`（通过）
2. **单测**：`./mvnw test -pl spring-ai-harness-mcp-server -Dtest='QuotaManagerTest,QuotaEnforcedStorageProviderTest,QuotaAdminControllerTest'`（全 mock，不连真 OSS；58 用例全绿）
   - ⚠️ **当前 `internal-dev` 注意**：Phase 4 权限测试（`PermissionEnforcedStorageProviderTest`/`FileAclMatcherTest`/`PermissionPropertiesTest`，commit `f514fef`）与 `PermissionConfig.FileAclConfig` 4 参构造器失配，导致**整个模块 test-compile 失败**。这是并行会话 Phase 4 的遗留，与本方案无关。运行本方案单测前需临时移走上述 3 个权限测试文件，或待 Phase 4 修复。公司内复刻时若已修复则直接跑全量。
3. **前端**：`cd spring-ai-harness-server-frontend && npm run build`（通过）；`npm run dev`(:3000) 预览--有后端走真实 API，无后端回退 mock。
4. **手动链路**（需真 OSS）：启动 server -> `PUT /api/v1/admin/quota/workspaces/{key}/limit` 设小上限 -> 用该 workspace 身份写超量文件 -> 应抛 `QuotaExceededException`，证明自定义上限生效。

---

## 七、复刻步骤

1. `autoconfig/HarnessMcpServerProperties.java`：`QuotaProperties` 加 `private String limitFile = ".quota";`
2. `storage/QuotaManager.java`：按改动清单加方法 + 改 `checkQuota` + `getExcludePrefixes` 加 `.quota`
3. `storage/QuotaEnforcedStorageProvider.java`：`isExcludedPath` 加 `.quota` 判断
4. `dto/`：新增 3 个 record（`WorkspaceQuotaDto`/`QuotaConfigDto`/`SetQuotaLimitRequest`）
5. `controller/QuotaAdminController.java`：新增（端点 + `listWorkspaceKeys` + `workspaceStorage` + `toDto` + `checkAdmin`）
6. 前端 `api.js` + `QuotaConsole.js`：按改动清单接入
7. 单测：3 个测试类按清单补/改
8. 验证：编译 + 配额单测 + 前端 build（注意权限测试编译阻塞，见六.2）
9. 兜底对照：`git diff main..internal-dev -- spring-ai-harness-mcp-server/src/main/java/io/github/springai/harness/{storage/QuotaManager.java,storage/QuotaEnforcedStorageProvider.java,controller/QuotaAdminController.java,autoconfig/HarnessMcpServerProperties.java,dto/WorkspaceQuotaDto.java,dto/QuotaConfigDto.java,dto/SetQuotaLimitRequest.java} spring-ai-harness-server-frontend/src/{services/api.js,components/QuotaConsole.js}`
