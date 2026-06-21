# 本地自部署社区版限制定位

本文记录 APITable/AITable 本地 docker compose 自部署时，社区版默认限制的代码位置、版本引入点和本地源码修改方式。

参考页面：[AITable pricing](https://aitable.ai/pricing/) 的 Self-Hosted 对比维度包括 File nodes、Records per datasheet、Records per space、Attachments storage、API request、API QPS 等。

## 结论

社区版默认限制不在 `docker-compose.yaml` 里集中配置，而是在 backend 的默认订阅权益实现里定义，然后由 backend 和 room-server 分别执行校验。

默认权益入口：

- `backend-server/application/src/main/java/com/apitable/interfaces/billing/BillingContextConfig.java`
- `backend-server/application/src/main/java/com/apitable/interfaces/billing/facade/DefaultEntitlementServiceFacadeImpl.java`
- `backend-server/application/src/main/java/com/apitable/interfaces/billing/model/DefaultSubscriptionInfo.java`
- `backend-server/application/src/main/java/com/apitable/interfaces/billing/model/DefaultSubscriptionFeature.java`

当前 `develop` 中，默认社区版订阅是：

```java
// DefaultSubscriptionInfo.java
public DefaultSubscriptionInfo() {
    this("CE", "ce_unlimited", new DefaultSubscriptionFeature());
}

@Override
public boolean isFree() {
    return true;
}
```

具体限制值主要在 `DefaultSubscriptionFeature.java`：

```java
public Seat getSeat() {
    return new Seat(2L);
}

public CapacitySize getCapacitySize() {
    return new CapacitySize(1024 * 1024 * 1024L);
}

public FileNodeNums getFileNodeNums() {
    return new FileNodeNums(5L);
}

public RowsPerSheet getRowsPerSheet() {
    return new RowsPerSheet(100L);
}

public ArchivedRowsPerSheet getArchivedRowsPerSheet() {
    return new ArchivedRowsPerSheet(250L);
}

public TotalRows getTotalRows() {
    return new TotalRows(250L);
}

public ApiQpsNums getApiQpsNums() {
    return new ApiQpsNums(5L);
}
```

`NumberPlanFeature` 约定 `-1` 表示不限：

```java
public boolean isUnlimited() {
    return unlimited || (value != null && value == -1);
}
```

`DataSizePlanFeature` 约定负数容量表示不限：

```java
public boolean isUnlimited() {
    return value.isNegative();
}
```

## 版本引入点

`v1.5.0-beta` 中默认值仍然是 `-1`，例如 seat、capacity、rows per sheet、archived rows per sheet 等。

`v1.5.0-beta.1` 开始，默认值被改成社区版限制值。对应提交：

```text
0a52d9e4fdcf937a6f395b8b0613c9d942430aca
2023-11-27T18:52:08+08:00
sync: hosted cloud (#1481)
```

`v1.5.0-beta.2` 又增加了创建文件节点时的校验。对应提交：

```text
2b83a2e2351730bba82d1815844f9ca847b816f5
2023-11-28T19:52:29+08:00
feat: add node create check (#1491)
```

## 限制值如何传递

### 前端展示和 space subscription

`backend-server/application/src/main/java/com/apitable/space/assembler/SubscribeAssembler.java`

这里把 `SubscriptionFeature` 转成前端可见的订阅信息，例如：

- `maxSeats`
- `maxCapacitySizeInBytes`
- `maxSheetNums`
- `maxRowsPerSheet`
- `maxRowsInSpace`
- `maxApiCall`
- `maxGalleryViewsInSpace`
- `maxKanbanViewsInSpace`
- `maxFormViewsInSpace`
- `maxGanttViewsInSpace`
- `maxCalendarViewsInSpace`

### room-server 内部订阅接口

`backend-server/application/src/main/java/com/apitable/internal/controller/InternalSpaceController.java`

```java
@GetResource(path = "/space/{spaceId}/subscription", requiredLogin = false)
public ResponseData<InternalSpaceSubscriptionVo> getSpaceSubscription(
    @PathVariable("spaceId") String spaceId) {
    return ResponseData.success(internalSpaceService.getSpaceEntitlementVo(spaceId));
}
```

`backend-server/application/src/main/java/com/apitable/internal/assembler/BillingAssembler.java`

```java
subscriptionVo.setMaxRowsInSpace(billingPlanFeature.getTotalRows().getValue());
subscriptionVo.setMaxRowsPerSheet(billingPlanFeature.getRowsPerSheet().getValue());
subscriptionVo.setMaxArchivedRowsPerSheet(
    billingPlanFeature.getArchivedRowsPerSheet().getValue());
subscriptionVo.setAllowEmbed(billingPlanFeature.getAllowEmbed().getValue());
subscriptionVo.setAllowOrgApi(billingPlanFeature.getAllowOrgApi().getValue());
```

`packages/room-server/src/shared/services/rest/rest.service.ts`

```ts
private SPACE_SUBSCRIPTION = 'internal/space/%(spaceId)s/subscription';

async getSpaceSubscription(spaceId: string): Promise<InternalSpaceSubscriptionView> {
  const response = await lastValueFrom(
    this.httpService.get<InternalSpaceSubscriptionView>(
      sprintf(this.SPACE_SUBSCRIPTION, { spaceId })
    )
  );
  return response!.data;
}
```

## 实际拦截点

### 席位数量

`backend-server/application/src/main/java/com/apitable/space/service/impl/SpaceServiceImpl.java`

```java
public void checkSeatOverLimit(String spaceId, long addedSeatNums) {
    var subscriptionInfo =
        entitlementServiceFacade.getSpaceSubscription(spaceId);
    var seatNums = subscriptionInfo.getFeature().getSeat();
    if (!subscriptionInfo.isFree() && seatNums.isUnlimited()) {
        return;
    }
    var seatUsage = getSeatUsage(spaceId);
    var total = seatUsage.getTotal();
    if (total + addedSeatNums > seatNums.getValue()) {
        throw new BusinessException(LimitException.SEATS_OVER_LIMIT);
    }
}
```

注意：当前代码里 `checkSeatOverLimit` 对免费空间没有直接按 `seatNums.isUnlimited()` 放行。因此如果只把 `new Seat(2L)` 改成 `new Seat(-1L)`，这个入口会出现 `total + addedSeatNums > -1` 永远为真的问题。要么把 seat 改成足够大的正数，要么同时修正该校验逻辑。

### 文件节点数量

`backend-server/application/src/main/java/com/apitable/space/service/impl/SpaceServiceImpl.java`

```java
public void checkFileNumOverLimit(String spaceId, long addFileNums) {
    var subscriptionInfo =
        entitlementServiceFacade.getSpaceSubscription(spaceId);
    if (!subscriptionInfo.isFree()) {
        return;
    }
    var fileNodeNums =
        subscriptionInfo.getFeature().getFileNodeNums();
    var currentSheetNums = getNodeCountBySpaceId(spaceId, NodeType::isFolder);
    if (!fileNodeNums.isUnlimited()
        && (currentSheetNums + addFileNums > fileNodeNums.getValue())) {
        throw new BusinessException(LimitException.FILE_NUMS_OVER_LIMIT);
    }
}
```

`backend-server/application/src/main/java/com/apitable/workspace/service/impl/NodeServiceImpl.java`

```java
NodeType nodeType = NodeType.toEnum(nodeOpRo.getType());
if (!nodeType.isFolder()) {
    iSpaceService.checkFileNumOverLimit(spaceId);
}
```

### 每表行数、空间总行数、归档行数

`packages/room-server/src/database/ot/services/datasheet.ot.service.ts`

创建记录时：

```ts
if (subscribeInfo.maxRowsPerSheet >= 0 && afterCreateCountInDst > subscribeInfo.maxRowsPerSheet) {
  throw new ServerException(
    DatasheetException.getRECORD_ADD_LIMIT_PER_DATASHEETMsg(
      subscribeInfo.maxRowsPerSheet,
      afterCreateCountInDst,
    ),
  );
}

if (subscribeInfo.maxRowsInSpace >= 0 && afterCreateCountInSpace > subscribeInfo.maxRowsInSpace) {
  throw new ServerException(
    DatasheetException.getRECORD_ADD_LIMIT_WITHIN_SPACEMsg(
      subscribeInfo.maxRowsInSpace,
      afterCreateCountInSpace,
    ),
  );
}
```

归档记录时：

```ts
if (subscribeInfo.maxArchivedRowsPerSheet >= 0 && afterArchiveCountInDst > subscribeInfo.maxArchivedRowsPerSheet) {
  throw new ServerException(
    DatasheetException.getRECORD_ARCHIVE_LIMIT_PER_DATASHEETMsg(
      subscribeInfo.maxArchivedRowsPerSheet,
      afterArchiveCountInDst,
    ),
  );
}
```

这些 room-server 校验里，`-1` 会被视为不限。

### API QPS

`backend-server/application/src/main/java/com/apitable/interfaces/billing/model/DefaultSubscriptionFeature.java`

```java
public ApiQpsNums getApiQpsNums() {
    return new ApiQpsNums(5L);
}
```

`backend-server/application/src/main/java/com/apitable/internal/controller/InternalSpaceController.java`

```java
@GetResource(path = "/space/{spaceId}/apiRateLimit", requiredPermission = false)
public ResponseData<InternalSpaceApiRateLimitVo> apiRateLimit(
    @PathVariable("spaceId") String spaceId) {
    iSpaceService.checkExist(spaceId);
    Long userId = SessionContext.getUserId();
    iMemberService.checkUserIfInSpace(userId, spaceId);
    return ResponseData.success(
        internalSpaceService.getSpaceEntitlementApiRateLimitVo(spaceId));
}
```

`packages/room-server/src/shared/middleware/node.rate.limiter.middleware.ts`

```ts
let points = limiter.points;
if (!process.env.LIMIT_POINTS || parseInt(process.env.LIMIT_POINTS!) === 5) {
  const qps = await this.restService.getApiRateLimit({ token }, spaceId);
  if (qps?.qps && qps.qps !== -1) {
    points = qps.qps;
  }
}
```

注意：这里即使 backend 返回 `qps = -1`，room-server 也不会自动关闭 rate limiter，而是继续使用 `LIMIT_POINTS` 的默认值。要取消或放宽 QPS，实际应把 `LIMIT_POINTS` 设置成足够大的值，或者把 `ApiQpsNums` 设置成足够大的正数。

默认值在：

`packages/room-server/env/.env.defaults`

```env
LIMIT_POINTS=5
```

### 附件容量

`DefaultSubscriptionFeature.java` 当前返回 1GB：

```java
public CapacitySize getCapacitySize() {
    return new CapacitySize(1024 * 1024 * 1024L);
}
```

但当前代码里附件容量更偏展示和统计，不是明确硬拦截：

`backend-server/application/src/main/java/com/apitable/internal/controller/InternalSpaceController.java`

```java
InternalSpaceCapacityVo vo = iSpaceService.getSpaceCapacityVo(spaceId);
vo.setIsAllowOverLimit(true);
return ResponseData.success(vo);
```

`packages/room-server/src/shared/services/rest/rest.service.ts`

```ts
if (response!.data?.isAllowOverLimit) {
  return false;
}
```

`backend-server/application/src/main/java/com/apitable/asset/service/impl/AssetServiceImpl.java`

```java
// iSubscriptionService.checkCapacity(spaceId, fileSize, checksum);
```

## 本地自部署取消或放宽限制的修改方式

下面只针对本地自部署源码修改。修改前需要自行确认当前使用场景满足仓库许可证和商业授权要求。仓库说明中 Open Source Edition 使用 AGPL，企业版能力或商业授权不等同于修改这些默认数值。

### 方案 A：只放宽数值，风险最低

这是对当前代码最小的改法：只改 `DefaultSubscriptionFeature.java` 和 room-server 的 QPS 配置，不动校验逻辑。

把有限值改成足够大的正数：

```java
public Seat getSeat() {
    return new Seat(1000000L);
}

public CapacitySize getCapacitySize() {
    return new CapacitySize(1024L * 1024L * 1024L * 1024L); // 1TB
}

public FileNodeNums getFileNodeNums() {
    return new FileNodeNums(1000000L);
}

public RowsPerSheet getRowsPerSheet() {
    return new RowsPerSheet(1000000L);
}

public ArchivedRowsPerSheet getArchivedRowsPerSheet() {
    return new ArchivedRowsPerSheet(1000000L);
}

public TotalRows getTotalRows() {
    return new TotalRows(10000000L);
}

public ApiQpsNums getApiQpsNums() {
    return new ApiQpsNums(10000L);
}
```

同时给 room-server 设置较大的 QPS，例如 docker compose 对 room-server 服务增加：

```yaml
environment:
  LIMIT_POINTS: "10000"
```

这种方式避免了 `Seat(-1L)` 在当前 `checkSeatOverLimit` 入口里的兼容问题。

### 方案 B：按 `-1` 做真正不限

如果希望按系统原有语义使用 `-1` 表示不限，建议修改两处。

第一处：`DefaultSubscriptionFeature.java`

```java
public Seat getSeat() {
    return new Seat(-1L);
}

public CapacitySize getCapacitySize() {
    return new CapacitySize(-1L);
}

public FileNodeNums getFileNodeNums() {
    return new FileNodeNums(-1L);
}

public RowsPerSheet getRowsPerSheet() {
    return new RowsPerSheet(-1L);
}

public ArchivedRowsPerSheet getArchivedRowsPerSheet() {
    return new ArchivedRowsPerSheet(-1L);
}

public TotalRows getTotalRows() {
    return new TotalRows(-1L);
}
```

第二处：修正 `SpaceServiceImpl.java` 的席位校验，让免费空间也尊重 `seatNums.isUnlimited()`。

当前逻辑：

```java
if (total + addedSeatNums > seatNums.getValue()) {
    throw new BusinessException(LimitException.SEATS_OVER_LIMIT);
}
```

建议改成：

```java
if (!seatNums.isUnlimited() && total + addedSeatNums > seatNums.getValue()) {
    throw new BusinessException(LimitException.SEATS_OVER_LIMIT);
}
```

API QPS 不建议只改成 `new ApiQpsNums(-1L)`，因为 room-server 仍会使用默认 `LIMIT_POINTS=5`。如果要取消 QPS 的实际影响，使用较大的正数更直接：

```java
public ApiQpsNums getApiQpsNums() {
    return new ApiQpsNums(10000L);
}
```

并在 room-server 环境里设置：

```yaml
environment:
  LIMIT_POINTS: "10000"
```

## 不建议作为正式方案的开关

room-server 有调试/跳过校验开关：

`packages/room-server/src/app.environment.ts`

```ts
export const skipUsageVerification = Object.is(process.env.SKIP_USAGE_VERIFICATION, 'true');
export const skipApiUsageVerification = Object.is(process.env.SKIP_API_USAGE_VERIFICATION, 'true');
```

`rest.service.ts` 中开启 `SKIP_USAGE_VERIFICATION=true` 会让 room-server 返回大量 `-1` 或 `isAllowOverLimit=true`：

```ts
if (skipUsageVerification) {
  return {
    maxRowsPerSheet: -1,
    maxArchivedRowsPerSheet: -1,
    maxRowsInSpace: -1,
    maxGalleryViewsInSpace: -1,
    maxKanbanViewsInSpace: -1,
    maxGanttViewsInSpace: -1,
    maxCalendarViewsInSpace: -1,
    maxMessageCredits: 0,
    maxWidgetNums: -1,
    maxAutomationRunsNums: -1,
    allowEmbed: true,
    allowOrgApi: true,
  };
}
```

这个开关覆盖范围较大，适合排查，不适合作为清晰的自部署版本策略。

## 修改后的验证点

修改并重新构建镜像后，至少验证这些路径：

1. 新建或邀请第 3 个成员，不应再触发 `SEATS_OVER_LIMIT`。
2. 新建第 6 个非文件夹节点，不应再触发 `FILE_NUMS_OVER_LIMIT`。
3. 单表新增超过 100 行，不应再触发 `RECORD_ADD_LIMIT_PER_DATASHEET`。
4. 空间总记录超过 250 行，不应再触发 `RECORD_ADD_LIMIT_WITHIN_SPACE`。
5. API 高频请求时确认 room-server 使用的是新的 `LIMIT_POINTS` 或新的 `ApiQpsNums`。
6. 前端空间订阅/用量页面确认展示值与修改后的默认权益一致。

### 2026-06-21 本地源码运行实测

本次没有做 docker 镜像编译。backend 用 Gradle 源码运行在 `8081`，room-server 用本地 Node 16.15.0 + pnpm 8.6.12 源码运行在 `3333`，MySQL/Redis/RabbitMQ/MinIO/databus 使用 docker compose 数据服务。

已验证：

1. backend 编译通过：`backend-server\gradlew.bat :application:compileJava`。
2. room-server 健康检查通过：`GET http://127.0.0.1:3333/actuator/health` 返回 `status: ok`。
3. 前端订阅接口返回修改后的权益值，包括 `maxRowsPerSheet=1000000`、`maxRowsInSpace=10000000`、`maxSheetNums=1000000`、`maxApiCall=-1`。
4. 文件节点限制已放宽：同一空间连续创建 6 个 datasheet 节点成功，超过原社区版 5 个文件节点限制。
5. 每表行数和空间总行数限制已实测：通过 Fusion API `POST /fusion/v1/datasheets/{dstId}/records` 新增 255 条记录；新表原有 3 条默认记录，最终 `GET /fusion/v1/datasheets/{dstId}/records?pageSize=1000` 返回 258 条，数据库 `apitable_datasheet_record` 同步确认未删除记录数为 258，未触发 `RECORD_ADD_LIMIT_PER_DATASHEET` 或 `RECORD_ADD_LIMIT_WITHIN_SPACE`。
6. API 月调用量：`GET /api/v1/internal/space/{spaceId}/apiUsages` 返回 `isAllowOverLimit=true`、`apiCallNumsPerMonth=-1`、`maxApiUsageCount=-1`；Fusion API 实际调用写入了 `apitable_api_usage`，但没有被月调用量 guard 拦截。
7. API QPS：`GET /api/v1/internal/space/{spaceId}/apiRateLimit` 返回 `qps=10000`；对同一 API token 和同一 datasheet 并发发起 20 个 Fusion `GET /fields` 请求，全部返回 `200/SUCCESS`，未出现原 5 QPS 下的 frequently error。

本次行数测试样本：

```text
spaceId: spcseUiPojMJA
datasheetId: dstRXUQSlYsun6kkgQ
createdViaFusion: 255
recordsReturnedByFusionList: 258
database count: 258
apiRateLimit.qps: 10000
apiUsage.isAllowOverLimit: true
apiUsage.apiCallNumsPerMonth: -1
apiUsage.maxApiUsageCount: -1
```

如果只是本地自部署放宽默认限制，优先选择方案 A；如果要完全恢复 `v1.5.0-beta` 附近的不限语义，再选择方案 B，并同步修正席位校验。

## 本地开发测试 JDK

本项目可以使用项目本地 JDK，不需要配置全局 `JAVA_HOME`。

当前本地测试使用的 JDK 放在：

```text
.cache/jdk/jdk-17.0.19+10
```

`.cache` 已被 `.gitignore` 忽略，不会提交到仓库。

后续打开新的 PowerShell 终端后，在项目根目录执行：

```powershell
.\scripts\use-local-jdk.ps1
```

脚本会把当前终端进程的 `JAVA_HOME` 和 `PATH` 指向 `.cache/jdk` 下的本地 JDK。之后可直接执行 backend Gradle 命令，例如：

```powershell
cd backend-server
.\gradlew.bat :application:compileJava
.\gradlew.bat :application:bootRun
```

本轮已验证 `:application:compileJava` 可以通过。

## 权限管理

节点权限、字段权限、权限接口、数据库表和本地自部署修改说明已迁移到 [本地自部署权限管理](./self-host-permissions.md)。
