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

## 权限模型和本地权限设置

当前 CE 本地版本里，后端具备节点/表权限计算能力。上游原始状态下，前端调用的节点权限配置接口没有完整可用。本轮最初用管理员登录态请求过这些接口：

- `GET /api/v1/node/listRole`
- `POST /api/v1/node/disableRoleExtend`
- `POST /api/v1/node/addRole`

返回为 `203 Resources do not exist`。也就是说：原始代码里前端可以读取、展示并执行权限结果，但不能稳定通过前端完成节点/表级成员权限配置。

本地分支已新增后端 HTTP 包装层：

```text
backend-server/application/src/main/java/com/apitable/workspace/controller/NodeRoleController.java
```

当前已补节点/表级角色接口：

- `GET /api/v1/node/listRole`
- `GET /api/v1/node/collaborator/page`
- `POST /api/v1/node/disableRoleExtend`
- `POST /api/v1/node/enableRoleExtend`
- `POST /api/v1/node/addRole`
- `POST /api/v1/node/editRole`
- `POST /api/v1/node/batchEditRole`
- `DELETE /api/v1/node/deleteRole`
- `DELETE /api/v1/node/batchDeleteRole`

读接口使用 `NodePermission.READ_NODE` 校验，写接口使用 `NodePermission.ASSIGN_NODE_ROLE` 校验。注意前端命名和 service 命名方向相反：

- 前端 `disableRoleExtend` 表示关闭继承、开启指定权限，后端调用 `INodeRoleService.enableNodeRole(...)`。
- 前端 `enableRoleExtend` 表示恢复继承，后端调用 `INodeRoleService.disableNodeRole(...)`。

2026-06-21 本地接口验证：

```text
登录账号: codex-row-20260621102017@local.test
测试节点: dstnbR4FlXGA8ik3uZ
测试成员 unitId: 2068532250797969410
```

验证结果：

- `listRole` 和 `collaborator/page` 返回 `success=true`。
- `disableRoleExtend` 可将测试节点从继承模式切到指定权限模式。
- `addRole` 可添加 `reader`。
- `editRole` 可改为 `editor`。
- `batchEditRole` 可改为 `updater`。
- `deleteRole` 可删除该成员角色。
- `batchDeleteRole` 可批量删除该成员角色。
- `enableRoleExtend` 可恢复继承模式。

测试结束后已确认：

- `dstnbR4FlXGA8ik3uZ` 的 control 记录为 `is_deleted=1`，恢复为继承模式。
- 原权限验证表 `dstRXUQSlYsun6kkgQ` 仍保持 `owner + reader`，未被本轮接口测试破坏。

### 节点/表级角色

节点角色定义在：

```text
backend-server/application/src/main/java/com/apitable/control/infrastructure/role/RoleConstants.java
backend-server/application/src/main/java/com/apitable/control/infrastructure/role/Node*.java
```

后端支持的节点/表级 `role_code`：

| role_code | 说明 | 是否适合普通分配 |
| --- | --- | --- |
| `reader` | 只读 | 是 |
| `updater` | 只读 + 新增行 + 编辑单元格 | 是 |
| `editor` | 可编辑表数据和视图配置 | 是 |
| `manager` | 可管理节点、字段、权限、归档等 | 是 |
| `owner` | 节点负责人，权限接近 manager | 不建议直接给普通成员分配 |
| `anonymous` | 匿名访问只读类角色 | 否 |
| `templateVisitor` | 模板访问只读类角色 | 否 |

`NodePermissionEnum` 中用于接口筛选的数字含义：

```text
0 -> manager
1 -> editor
2 -> updater
3 -> reader
```

### 角色对应能力

权限字段定义在：

```text
backend-server/application/src/main/java/com/apitable/control/infrastructure/permission/NodePermission.java
backend-server/application/src/main/java/com/apitable/workspace/vo/DatasheetPermissionView.java
```

主要角色能力：

| role_code | 主要能力 |
| --- | --- |
| `reader` | `readable`。角色中包含 `EXPORT_NODE`，但最终 `exportable` 还会受空间全局导出策略影响。 |
| `updater` | `reader` + `rowCreatable` + `cellEditable`。 |
| `editor` | `reader` + `editable`、`sharable`、视图增删改、筛选、排序、分组、列隐藏、行高/列宽/列统计、行新增/删除、单元格编辑、视图布局/样式/关键字段/颜色配置等。 |
| `manager` | `editor` + `manageable`、新建子节点、重命名节点、编辑图标/描述、移动/复制/导入/删除节点、创建模板、分享保存/编辑配置、分配节点角色、字段增删改、归档/取消归档、字段权限管理、视图锁管理等。 |
| `owner` | 权限接近 `manager`，但服务层不允许通过普通 add/edit role 分配为 owner。 |
| `anonymous` / `templateVisitor` | 继承 `reader` 类能力，不能作为普通成员角色分配。 |

后端会返回或使用的主要布尔权限字段：

```text
readable
editable
manageable
rowCreatable
rowRemovable
rowArchivable
rowUnarchivable
cellEditable
fieldCreatable
fieldRenamable
fieldPropertyEditable
fieldRemovable
fieldPermissionManageable
viewCreatable
viewRenamable
viewRemovable
viewMovable
viewExportable
viewFilterable
columnSortable
columnHideable
fieldSortable
fieldGroupable
rowSortable
rowHighEditable
columnWidthEditable
columnCountEditable
childCreatable
renamable
iconEditable
descriptionEditable
movable
copyable
importable
exportable
removable
sharable
allowSaveConfigurable
allowEditConfigurable
templateCreatable
viewLayoutEditable
viewStyleEditable
viewKeyFieldEditable
viewColorOptionEditable
viewLockManageable
viewManualSaveManageable
viewOptionSaveEditable
```

### 字段/列级角色

字段角色定义在：

```text
backend-server/application/src/main/java/com/apitable/control/infrastructure/role/FieldReaderRole.java
backend-server/application/src/main/java/com/apitable/control/infrastructure/role/FieldEditorRole.java
```

字段/列级 `role_code`：

| role_code | 能力 |
| --- | --- |
| `reader` | 字段数据可读 |
| `editor` | 字段数据可读 + 可编辑 |

字段权限的 `control_id` 格式为：

```text
{datasheetId}-{fieldId}
```

例如：

```text
dstRXUQSlYsun6kkgQ-fldde6fnX7L96
```

字段权限会用到：

- `apitable_control`
- `apitable_control_role`
- `apitable_control_setting`

节点/表级权限通常不需要 `apitable_control_setting`；字段权限启用时服务层会初始化这一行。

### 权限相关数据库表

核心表：

| 表 | 作用 |
| --- | --- |
| `apitable_control` | 某个节点、字段或视图是否启用独立控制。 |
| `apitable_control_role` | 某个组织单元在某个 control 上的角色。 |
| `apitable_control_setting` | control 的额外配置，字段权限会用到。 |
| `apitable_unit` | 成员、团队、标签、角色等组织单元映射。 |
| `apitable_unit_member` | 空间成员。 |

`control_type` 含义：

```text
0 -> NODE
1 -> DATASHEET_FIELD
2 -> DATASHEET_VIEW
```

注意：权限分配用的是 `apitable_unit.id`，不是 `apitable_unit.unit_id` 字符串，也不是 `apitable_unit_member.id`，更不是 `apitable_user.id`。

查询某个邮箱在空间内的 member unit：

```sql
SET @space_id = 'spcseUiPojMJA';
SET @email = 'user@example.com';

SELECT
  u.id AS unit_id,
  u.unit_type,
  um.id AS member_id,
  au.id AS user_id,
  au.email
FROM apitable_user au
JOIN apitable_unit_member um
  ON um.user_id = au.id
JOIN apitable_unit u
  ON u.unit_ref_id = um.id
WHERE um.space_id = @space_id
  AND u.space_id = @space_id
  AND u.unit_type = 3
  AND au.email = @email
  AND um.is_deleted = 0
  AND u.is_deleted = 0;
```

### 本地直接改库设置节点权限

长期方案建议补后端 controller 调用现有 service，不建议把 SQL 作为正式权限管理方式。原因：

- 直接 SQL 不会触发服务层审计事件。
- 直接 SQL 不会发前端权限变更通知，需要刷新页面。
- 直接 SQL 需要自己保证同一 unit 只有一个有效非 owner 角色。
- `id` 不是自增，服务层使用 MyBatis `IdWorker` 生成，手写 SQL 要保证主键不冲突。

但本地自部署测试可以用直接 SQL。以下模板按服务层核心逻辑写入。

变量示例：

```sql
SET @space_id = 'spcseUiPojMJA';
SET @node_id = 'dstRXUQSlYsun6kkgQ';
SET @admin_user_id = 2068519345960554498;
SET @owner_unit_id = 2068519346484842499;
SET @target_unit_id = 2068532250797969410;
SET @target_role = 'reader';
```

生成本地测试用 ID。单机测试可用，正式场景应通过服务层生成 ID：

```sql
SET @base_id = (
  SELECT GREATEST(
    COALESCE((SELECT MAX(id) FROM apitable_control), 0),
    COALESCE((SELECT MAX(id) FROM apitable_control_role), 0),
    COALESCE((SELECT MAX(id) FROM apitable_control_setting), 0),
    2069000000000000000
  ) + 1000
);
```

启用某个节点/表的独立权限：

```sql
START TRANSACTION;

UPDATE apitable_control
SET is_deleted = 0,
    updated_by = @admin_user_id
WHERE space_id = @space_id
  AND control_id = @node_id
  AND control_type = 0;

INSERT INTO apitable_control
  (id, space_id, control_id, control_type, is_deleted, created_by, updated_by)
SELECT
  @base_id + 1, @space_id, @node_id, 0, 0, @admin_user_id, @admin_user_id
WHERE NOT EXISTS (
  SELECT 1
  FROM apitable_control
  WHERE space_id = @space_id
    AND control_id = @node_id
    AND control_type = 0
);

-- 保留一个 owner，避免无人可管理。
INSERT INTO apitable_control_role
  (id, control_id, unit_id, role_code, is_deleted, created_by, updated_by)
VALUES
  (@base_id + 2, @node_id, @owner_unit_id, 'owner', 0, @admin_user_id, @admin_user_id)
ON DUPLICATE KEY UPDATE
  is_deleted = 0,
  updated_by = @admin_user_id;

COMMIT;
```

给目标成员设置 `reader` / `updater` / `editor` / `manager`：

```sql
START TRANSACTION;

-- 先删除该 unit 在该节点上的旧非 owner 角色，避免多个角色同时有效。
UPDATE apitable_control_role
SET is_deleted = 1,
    updated_by = @admin_user_id
WHERE control_id = @node_id
  AND unit_id = @target_unit_id
  AND role_code <> 'owner'
  AND is_deleted = 0;

-- 再写入目标角色。
INSERT INTO apitable_control_role
  (id, control_id, unit_id, role_code, is_deleted, created_by, updated_by)
VALUES
  (@base_id + 3, @node_id, @target_unit_id, @target_role, 0, @admin_user_id, @admin_user_id)
ON DUPLICATE KEY UPDATE
  is_deleted = 0,
  updated_by = @admin_user_id;

COMMIT;
```

删除某个成员在该节点上的显式权限：

```sql
UPDATE apitable_control_role
SET is_deleted = 1,
    updated_by = @admin_user_id
WHERE control_id = @node_id
  AND unit_id = @target_unit_id
  AND role_code <> 'owner'
  AND is_deleted = 0;
```

关闭某个节点/表的独立权限，恢复继承：

```sql
START TRANSACTION;

UPDATE apitable_control
SET is_deleted = 1,
    updated_by = @admin_user_id
WHERE control_id = @node_id
  AND control_type = 0
  AND is_deleted = 0;

UPDATE apitable_control_role
SET is_deleted = 1,
    updated_by = @admin_user_id
WHERE control_id = @node_id
  AND is_deleted = 0;

COMMIT;
```

### 权限继承规则

如果某个节点没有启用独立权限，后端会向上查找最近启用独立权限的父节点。

如果父级也没有启用独立权限，则默认根团队获得 `manager` 级工作台权限。

这对应 `NodeRoleServiceImpl.getRoleToUnitIds` 中的逻辑：

```text
当前节点启用独立权限 -> 使用当前节点 control_role
否则查最近父节点独立权限 -> 使用父节点 control_role
否则 -> root team 默认 manager
```

因此，只给某个节点写入 `apitable_control_role` 但没有写入有效的 `apitable_control` 行时，权限不会进入独立权限模式。

### 字段权限直接改库模板

字段权限 control_id：

```sql
SET @dst_id = 'dstRXUQSlYsun6kkgQ';
SET @field_id = 'fldde6fnX7L96';
SET @field_control_id = CONCAT(@dst_id, '-', @field_id);
SET @field_role = 'reader'; -- reader 或 editor
```

启用字段权限：

```sql
START TRANSACTION;

INSERT INTO apitable_control
  (id, space_id, control_id, control_type, is_deleted, created_by, updated_by)
VALUES
  (@base_id + 10, @space_id, @field_control_id, 1, 0, @admin_user_id, @admin_user_id)
ON DUPLICATE KEY UPDATE
  is_deleted = 0,
  updated_by = @admin_user_id;

INSERT INTO apitable_control_setting
  (id, control_id, props, is_deleted, created_by, updated_by)
VALUES
  (@base_id + 11, @field_control_id, '{}', 0, @admin_user_id, @admin_user_id)
ON DUPLICATE KEY UPDATE
  is_deleted = 0,
  updated_by = @admin_user_id;

UPDATE apitable_control_role
SET is_deleted = 1,
    updated_by = @admin_user_id
WHERE control_id = @field_control_id
  AND unit_id = @target_unit_id
  AND is_deleted = 0;

INSERT INTO apitable_control_role
  (id, control_id, unit_id, role_code, is_deleted, created_by, updated_by)
VALUES
  (@base_id + 12, @field_control_id, @target_unit_id, @field_role, 0, @admin_user_id, @admin_user_id)
ON DUPLICATE KEY UPDATE
  is_deleted = 0,
  updated_by = @admin_user_id;

COMMIT;
```

### 建议的长期修改方式

如果要长期在前端配置权限，建议补后端接口，而不是长期直接改库。

可复用的后端 service：

```text
backend-server/application/src/main/java/com/apitable/workspace/service/INodeRoleService.java
backend-server/application/src/main/java/com/apitable/workspace/service/IFieldRoleService.java
```

节点权限可包装：

- `enableNodeRole(userId, spaceId, nodeId, includeExtend)`
- `disableNodeRole(userId, nodeId)`
- `addNodeRole(userId, nodeId, role, unitIds)`
- `updateNodeRole(userId, nodeId, role, unitIds)`
- `deleteNodeRole(userId, nodeId, unitId)`
- `getNodeRoleUnitList(nodeId)`
- `getNodeRoleMembers(...)`

字段权限可包装：

- `enableFieldRole(userId, dstId, fieldId, includeExtend)`
- `addFieldRole(userId, controlId, unitIds, role)`
- `editFieldRole(userId, controlId, unitIds, role)`
- `deleteFieldRole(controlId, datasheetId, unitId)`
- `updateFieldRoleProp(userId, controlId, prop)`

这样前端现有的权限 UI 才能通过正常 HTTP 接口落库，并保留服务层校验、审计和事件逻辑。

### 已补齐的权限接口

本地自部署已补以下控制器，前端无需改 URL：

```text
backend-server/application/src/main/java/com/apitable/workspace/controller/NodeRoleController.java
backend-server/application/src/main/java/com/apitable/workspace/controller/FieldPermissionController.java
backend-server/application/src/main/java/com/apitable/organization/controller/OrgYachCompatibilityController.java
```

节点权限接口：

- `GET /api/v1/node/listRole`
- `GET /api/v1/node/collaborator/page`
- `POST /api/v1/node/disableRoleExtend`
- `POST /api/v1/node/enableRoleExtend`
- `POST /api/v1/node/addRole`
- `POST /api/v1/node/editRole`
- `POST /api/v1/node/batchEditRole`
- `DELETE /api/v1/node/deleteRole`
- `DELETE /api/v1/node/batchDeleteRole`

字段权限接口：

- `GET /api/v1/datasheet/field/permission`
- `GET /api/v1/datasheet/{dstId}/field/{fieldId}/listRole`
- `GET /api/v1/datasheet/{dstId}/field/{fieldId}/collaborator/page`
- `POST /api/v1/datasheet/{dstId}/field/{fieldId}/permission/{enable|disable}`
- `POST /api/v1/datasheet/{dstId}/field/{fieldId}/addRole`
- `POST /api/v1/datasheet/{dstId}/field/{fieldId}/editRole`
- `POST /api/v1/datasheet/{dstId}/field/{fieldId}/batchEditRole`
- `POST /api/v1/datasheet/{dstId}/field/{fieldId}/updateRoleSetting`
- `DELETE /api/v1/datasheet/{dstId}/field/{fieldId}/deleteRole`
- `DELETE /api/v1/datasheet/{dstId}/field/{fieldId}/batchDeleteRole`

Yach/小组兼容接口：

- `GET /api/v1/org/yach/group/page`
- `POST /api/v1/org/yach/node/addRole`
- `POST /api/v1/org/yach/datasheet/{dstId}/field/{fieldId}/addRole`

本地社区版没有企业 IM 群组来源时，`org/yach/group/page` 会把本地标签
`UnitType.TAG` 暴露成前端的小组列表；如果没有标签，接口返回空分页但不会 403。

### 2026-06-21 本地权限测试记录

测试空间和表：

```text
spaceId: spcseUiPojMJA
nodeId/datasheetId: dstRXUQSlYsun6kkgQ
viewId: viwk5EFMsieW5
name: Codex Row Limit Test
```

测试用户：

```text
email: codex-perm-reader-202606211112@local.test
password: Passw0rd!123
userId: 2068532300349476865
memberId: 2068532250718277634
unitId: 2068532250797969410
```

本地写入：

```text
apitable_control:
control_id = dstRXUQSlYsun6kkgQ
control_type = 0
is_deleted = 0

apitable_control_role:
owner unit = 2068519346484842499
reader unit = 2068532250797969410
```

验证结果：

1. `GET /api/v1/node/collaborator/info?nodeId=dstRXUQSlYsun6kkgQ&uuid=446b5f5f57844a6483f068fb00398ccd` 返回 `role=reader`。
2. `GET /api/v1/internal/node/dstRXUQSlYsun6kkgQ/permission` 使用该用户登录态返回 `role=reader`、`readable=true`、`editable=false`、`manageable=false`、`rowCreatable=false`、`cellEditable=false`。
3. 前端登录该用户后，目标表显示 `只可阅读`，插入行、隐藏列、筛选、分组、排序、分享、API、自动化按钮均为禁用。
4. Fusion API 使用该用户 API Key 读取记录成功，`total=258`。
5. Fusion API 使用该用户 API Key 新增记录失败，返回 `code=602`、`message=Operation denied`。
6. 失败写入后 Fusion 和数据库记录数均仍为 `258`。

截图：

```text
output/playwright/permission-reader-codex-row.png
```
