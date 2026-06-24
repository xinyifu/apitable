# 大表首次打开性能技术方案清单

本文记录自部署场景下，提升大表首次打开速度的可选技术实现。本文不做优先级排序，不表达取舍倾向，只记录各方案的实现方式、关键要点、当前项目涉及范围和复杂度，方便后续讨论。

## 背景范围

当前项目是 APITable/Vika fork，自部署场景下表格首次打开慢的主要相关模块包括：

| 模块 | 路径 | 相关职责 |
| --- | --- | --- |
| 前端应用 | `packages/datasheet` | Next.js、React、Redux、Konva 表格、工作台页面 |
| 共享核心 | `packages/core` | 数据模型、API action、selectors、视图派生计算、JOT/OT |
| room-server | `packages/room-server` | dataPack、记录读取、关联字段分析、协同服务 |
| backend-server | `backend-server` | 用户、空间、节点、权限、组织等接口 |
| databus/wasm | `packages/databus-*` | 潜在数据查询和 wasm 能力 |
| 网关/部署 | `gateway`、`docker-compose*.yaml` | 自部署服务拓扑、压缩、代理 |

当前已知链路是：前端打开表后请求 `dataPack`，room-server 构造 `snapshot.recordMap`、`foreignDatasheetMap`、`units`、`fieldPermissionMap` 等数据；前端接收后写入 Redux，再触发视图派生计算，最后由 Konva 网格绘制可视区域。

关键现状：

- `packages/datasheet/src/pc/components/konva_grid/*` 已经按可视行列范围绘制。
- `packages/room-server/src/database/datasheet/services/datasheet.service.ts` 的 `fetchCommonDataPack` 当前仍会构造主表 dataPack。
- `packages/room-server/src/database/datasheet/services/datasheet.record.service.ts` 在不传 `recordIds` 时会读取全量未归档记录。
- `packages/datasheet/src/pc/store/view_derivation_middleware.ts` 会在 `DATAPACK_LOADED` 后触发视图派生计算。
- `packages/datasheet/src/pc/utils/load_records.ts` 已有按 recordIds 补加载记录的能力。

## 复杂度定义

| 复杂度 | 含义 | 粗略规模 |
| --- | --- | --- |
| 低 | 不改变核心数据契约，主要是配置、埋点、日志、延迟加载非关键数据 | 1-10 个文件，100-600 行 |
| 中 | 改变单个链路或新增旁路接口，但不要求整体数据模型重写 | 8-25 个文件，600-2,000 行 |
| 高 | 改变前后端数据契约，要求多个功能适配 partial data 或服务端派生结果 | 20-50 个文件，1,500-5,000 行 |
| 很高 | 架构级改造，涉及查询引擎、实时协同、缓存一致性、权限语义和多视图行为 | 40-80+ 个文件，3,000-8,000+ 行 |

## 方案总览

| 方案 | 修改复杂度 | 主要涉及模块 |
| --- | --- | --- |
| 基线测量和链路埋点 | 低 | datasheet、core、room-server、gateway |
| 自部署 fast dataPack | 低到中 | room-server、core、datasheet |
| 网关压缩和传输优化 | 低 | gateway、web-server、docker compose |
| 服务端视图索引缓存 | 高到很高 | room-server、core、Redis、datasheet |
| 记录窗口化加载 | 高 | room-server、core、datasheet |
| 前端 partial recordMap | 高 | datasheet、core、widget-sdk |
| 服务端过滤/排序/分组/搜索下推 | 高到很高 | room-server、core、数据库 |
| rowCount/groupPoints/aggregation 拆分接口 | 中到高 | room-server、core、datasheet |
| 视图派生 Worker 化 | 中 | datasheet、core、worker |
| 主线程计算切片 | 中 | datasheet、core |
| 关联字段懒加载 | 中到高 | room-server、core、datasheet |
| 公式/lookup 预计算 | 高到很高 | room-server、core、数据库、缓存 |
| 实时协同订阅范围收窄 | 高到很高 | room-server、core、datasheet |
| 客户端 IndexedDB 缓存 | 中到高 | datasheet、core |
| 服务端 Redis/内存缓存和预热 | 中到高 | room-server、Redis、任务调度 |
| SSR/服务端预取首屏窗口 | 中 | Next.js、room-server |
| React 18 并发渲染能力 | 低到中 | datasheet |
| 替换或重构网格渲染层 | 很高 | datasheet grid、交互、编辑器 |
| databus/wasm 路径评估 | 高 | databus、core、datasheet、权限 |
| Arrow/二进制/列式传输 | 很高 | 前后端协议、序列化、core |
| DuckDB-Wasm/浏览器端分析 | 很高 | datasheet、数据协议、缓存 |
| 数据库索引/物化视图 | 中到很高 | room-server、init-db、数据库 |

## 1. 基线测量和链路埋点

### 目标

明确慢在服务端查询、字段分析、关联表扩展、网络传输、JSON 解析、Redux 写入、视图派生、React 渲染还是 Konva 绘制。

### 实现方式

服务端记录：

- `fetchCommonDataPack` 总耗时。
- `getNodeDetailInfo` 耗时。
- `getMetaDataByDstId` 耗时。
- `getRecordsByDstId` / `getRecordsByDstIdAndRecordIds` 耗时和记录数。
- `datasheetFieldHandler.analyze` 耗时。
- `foreignDatasheetMap` 记录数、units 数量、响应体大小。

前端记录：

- `fetchDatasheetApi` request start/end。
- dataPack 响应体大小和下载耗时。
- `receiveDataPack` 开始/结束。
- `DATAPACK_LOADED -> dispatchNewViewDerivation` 耗时。
- `setViewDerivation` 耗时。
- Konva 首次可见网格渲染时间。
- 首次可滚动/可点击时间。
- Long Task 数量和最长耗时。

### 当前项目触点

- `packages/room-server/src/database/datasheet/services/datasheet.service.ts`
- `packages/room-server/src/database/datasheet/services/datasheet.record.service.ts`
- `packages/room-server/src/database/datasheet/services/datasheet.field.handler.ts`
- `packages/core/src/modules/database/store/actions/resource/datasheet/datasheet.ts`
- `packages/datasheet/src/pc/store/view_derivation_middleware.ts`
- `packages/datasheet/src/pc/components/konva_grid/konva_grid_view.tsx`

### 复杂度

低。主要是日志、performance mark、开发调试面板或本地记录输出。

### 注意点

- 需要区分本地源码直跑和 docker compose 自部署。
- 需要分别测试普通表、含 link/lookup/formula/member 的表、字段权限表。
- 不要只记录服务端耗时，浏览器 JSON parse 和主线程 Long Task 同样重要。

## 2. 自部署 fast dataPack

### 目标

在不改变主要数据结构的情况下，减少首包中非首屏必需的数据和计算。

### 实现方式

增加自部署配置开关，例如：

```text
SELF_HOST_FAST_DATAPACK=true
SELF_HOST_DEFER_COMMENT_COUNT=true
SELF_HOST_DEFER_FOREIGN_RECORDS=true
SELF_HOST_DEFER_REVISION_HISTORY=true
```

候选减载项：

- 首屏不加载评论数量，评论面板或评论图标需要时再加载。
- 首屏不带完整 `revisionHistory`，历史面板需要时再加载。
- 限制或延迟加载 `foreignDatasheetMap` 中的关联记录。
- 调试大对象引用改为开关控制，例如全量 `linearRows` 挂到 `window`。
- 字段权限、当前视图、必要 meta 保持首包可用。

### 当前项目触点

- `packages/room-server/src/database/datasheet/services/datasheet.service.ts`
- `packages/room-server/src/database/datasheet/services/datasheet.record.service.ts`
- `packages/room-server/src/database/datasheet/services/datasheet.field.handler.ts`
- `packages/core/src/modules/database/store/actions/resource/datasheet/datasheet.ts`
- `packages/datasheet/src/pc/components/konva_grid/konva_grid_view.tsx`

### 复杂度

低到中。若只关评论数和调试引用，复杂度低；若拆 `revisionHistory`、`recordMeta` 或关联记录，复杂度上升到中。

### 注意点

- `CreatedTime`、`LastModifiedTime`、附件、行高、历史记录、公式可能依赖部分 record meta，不能盲删。
- 需要为延迟加载项提供 UI loading 或按需接口。
- 自部署可以接受配置开关，但默认行为仍要可回退。

## 3. 网关压缩和传输优化

### 目标

降低大 JSON dataPack 的网络传输成本。

### 实现方式

- 确认 gateway/web-server 对 JSON 响应启用 gzip 或 brotli。
- 确认静态资源 CDN/assetPrefix 不影响 API 压缩。
- 对 dataPack 响应记录压缩前后大小。
- 对本地源码直跑和 Docker Compose 分别验证。

### 当前项目触点

- `gateway`
- `docker-compose.yaml`
- `docker-compose.devenv.yaml`
- `packages/datasheet/server.js`

### 复杂度

低。

### 注意点

- 压缩只能减少网络传输，不能减少浏览器 JSON parse 和 Redux 写入成本。
- 如果首包仍是全量 recordMap，压缩收益存在上限。

## 4. 服务端视图索引缓存

### 目标

把当前视图的行顺序、行数、分组断点等结果预先放到服务端或 Redis，前端不再拿全量数据后自己计算完整视图。

### 缓存内容

推荐缓存：

- `rowCount`
- 当前视图的 `rowIds`
- `groupPoints`
- 可序列化的 `linearRows`
- `visibleRowsIndexMap` 的可序列化形式
- 当前窗口 record ids

不建议作为主缓存内容：

- 完整 `recordMap` 直接发给前端。

### 缓存 key

示例：

```text
view-index:{dstId}:{viewId}:{revision}:{permissionScope}:{queryHash}
row-count:{dstId}:{viewId}:{revision}:{permissionScope}:{queryHash}
group-points:{dstId}:{viewId}:{revision}:{permissionScope}:{queryHash}
```

`queryHash` 应包含：

- filter
- sort
- group
- search
- collapsedGroupIds
- mirror/share/embed 状态
- 字段权限范围

### 当前项目触点

- `packages/room-server/src/database/datasheet/services/datasheet.service.ts`
- `packages/room-server/src/database/datasheet/controllers/datasheet.controller.ts`
- `packages/core/src/compute_manager/view_derivate/*`
- `packages/core/src/modules/database/store/selectors/resource/datasheet/*`
- Redis 或现有缓存服务
- `packages/datasheet/src/pc/store/view_derivation_middleware.ts`

### 复杂度

高到很高。

### 注意点

- 字段权限会影响可见字段、排序、分组和缓存结果。
- revision 变化可以先粗暴失效整表缓存。
- 复杂公式、lookup、link 依赖可能跨表，需要依赖图或保守失效。
- 服务端如果复用 `@apitable/core` 计算，能减少前后端语义分叉，但要处理 Node 环境和序列化。

## 5. 记录窗口化加载

### 目标

首屏只加载当前窗口记录，例如 100-300 行；滚动时再按窗口加载后续记录。

### 接口形态

示例：

```http
GET /nest/v1/datasheets/:dstId/viewIndex?viewId=viwxxx&offset=0&limit=300
GET /nest/v1/datasheets/:dstId/recordsWindow?viewId=viwxxx&offset=0&limit=300
```

返回：

```ts
interface ViewIndexWindow {
  revision: number;
  rowCount: number;
  offset: number;
  limit: number;
  rowIds: string[];
  groupPoints?: unknown;
}

interface RecordsWindow {
  revision: number;
  offset: number;
  limit: number;
  recordMap: Record<string, unknown>;
}
```

### 当前项目可复用能力

- `packages/core/src/modules/database/api/datasheet_api.ts` 的 `fetchRecords`
- `packages/datasheet/src/pc/utils/load_records.ts`
- `packages/room-server/src/database/datasheet/controllers/datasheet.controller.ts` 的 records 接口
- `packages/room-server/src/database/datasheet/services/datasheet.record.service.ts` 的按 recordIds 读取能力

### 当前项目需新增能力

- 按 view 查询 rowIds window。
- 前端滚动可见区到 recordIds window 的映射。
- 缺失记录 loading 状态。
- 预取策略，例如可见窗口上下各 300 行。

### 复杂度

高。

### 注意点

- 单纯 SQL 分页不能保证复杂视图正确，需要服务端视图索引或同源视图计算。
- 无筛选、无排序、无分组的普通 Grid 可以先实现较简单路径。
- 复杂视图需要确保 `offset/limit` 对应的是视图结果，而不是物理表顺序。

## 6. 前端 partial recordMap

### 目标

允许 `snapshot.recordMap` 只包含部分记录。前端在缺失记录时显示 loading 或触发补加载。

### 实现方式

- datasheet client state 增加 partial 状态，例如 `isPartialData`、`loadedRecordIds`、`loadingRecord`。
- `Selectors.getRecord`、单元格渲染、展开记录、复制、统计、选择区域等路径支持缺失记录。
- Grid 渲染缺失记录时返回 loading cell。
- 滚动时根据可见 rowIds 调用 `loadRecords` 或新窗口接口。
- 批量复制、导出、整列统计等操作遇到未加载数据时走服务端接口或先确认加载。

### 当前项目触点

- `packages/core/src/modules/database/store/reducers/resource/datasheet/*`
- `packages/core/src/modules/database/store/selectors/resource/datasheet/*`
- `packages/core/src/modules/database/store/actions/resource/datasheet/datasheet.ts`
- `packages/datasheet/src/pc/components/konva_grid/*`
- `packages/datasheet/src/pc/utils/load_records.ts`
- `packages/widget-sdk`

### 复杂度

高。

### 注意点

- 当前很多 selector 默认完整 `recordMap`，需要逐步审查。
- 小程序、仪表盘、API 面板、导出、复制粘贴可能默认读取完整数据。
- 需要定义哪些功能自动补加载，哪些功能走服务端整范围操作。

## 7. 服务端过滤/排序/分组/搜索下推

### 目标

把视图的过滤、排序、分组、搜索放到服务端执行，前端只拿结果窗口和必要索引。

### 实现方式

新增服务端查询层：

- 输入 viewId、filter、sort、group、search、offset、limit。
- 合并视图默认 filter/sort/group 和调用方附加条件。
- 返回当前 view 结果中的 rowIds window。
- 同时支持 rowCount 和 groupPoints。

可能实现路径：

1. 复用 `@apitable/core` 的 view derivation，在 room-server 运行并缓存。
2. 将 filter/sort/group 编译为 SQL 查询。
3. 混合路径：简单字段 SQL 下推，复杂 formula/lookup 回退到服务端内存计算并缓存。

### 当前项目触点

- `packages/core/src/compute_manager/view_derivate/*`
- `packages/core/src/modules/database/store/selectors/resource/datasheet/rows_calc.ts`
- `packages/room-server/src/database/datasheet/services/*`
- `packages/room-server/src/database/datasheet/repositories/*`
- 数据库索引和查询构造层

### 复杂度

高到很高。

### 注意点

- SQL 下推需要处理字段类型、空值排序、选择字段顺序、成员字段、关联字段、lookup 和 formula。
- 与前端现有语义不一致会导致排序/过滤结果差异。
- 自部署场景可以先覆盖常见字段类型，复杂字段走回退路径，但回退行为必须显式配置。

## 8. rowCount、groupPoints、aggregation 拆分接口

### 目标

让行数、分组断点、统计结果独立加载，不阻塞首屏记录窗口。

### 实现方式

新增或拆分接口：

```http
GET /datasheets/:dstId/views/:viewId/rowCount
GET /datasheets/:dstId/views/:viewId/groupPoints
POST /datasheets/:dstId/views/:viewId/aggregations
```

前端行为：

- Grid 首屏先使用 `rowCount` 建立滚动高度。
- `groupPoints` 未返回前显示普通行或分组 loading。
- footer 统计、列统计、选区统计异步更新。

### 当前项目触点

- `packages/room-server/src/database/datasheet/controllers/datasheet.controller.ts`
- `packages/room-server/src/database/datasheet/services/datasheet.service.ts`
- `packages/core/src/modules/database/api/datasheet_api.ts`
- `packages/datasheet/src/pc/components/konva_grid/*`
- `packages/datasheet/src/pc/store/view_derivation_middleware.ts`

### 复杂度

中到高。

### 注意点

- 统计和分组要使用与视图相同的 filter/sort/search 条件。
- 分组折叠状态会影响 linearRows，但不一定影响 rowCount。
- 选区统计如果选择范围未加载，应走服务端统计接口。

## 9. 视图派生 Worker 化

### 目标

把过滤、排序、分组、搜索、linearRows 构建从 UI 主线程移到 Worker，降低 dataPack 到达后的 Long Task。

### 实现方式

- 新增 view derivation worker 协议。
- 输入：snapshot、view、pageParams、fieldPermissionMap、search 状态、group collapse 状态。
- 输出：可序列化的 `IViewDerivation` 等价结构。
- 主线程恢复 `Map` 等结构后 dispatch `setViewDerivation`。
- 给每次计算加任务版本号，避免旧结果覆盖新视图。
- 先覆盖 Grid view，再扩展其他视图。

### 当前项目触点

- `packages/datasheet/src/pc/worker/index.ts`
- `packages/datasheet/src/pc/worker/store/*`
- `packages/datasheet/src/pc/store/view_derivation_middleware.ts`
- `packages/core/src/compute_manager/view_derivate/*`
- `packages/core/src/modules/database/store/actions/resource/*`

### 复杂度

中。

### 注意点

- 当前 worker 开关为关闭状态，不能只改 `useWorker = true`。
- snapshot 传输成本可能很高，尤其仍是全量 recordMap 时。
- Worker 化减少主线程卡顿，但不减少首包体积。

## 10. 主线程计算切片

### 目标

在不引入 Worker 或 Worker 传输成本过高时，把全量视图派生拆成小任务，让出主线程。

### 实现方式

- 将过滤、排序准备、分组构建、搜索等步骤拆成批次。
- 使用 `requestIdleCallback`、`scheduler.postTask`、`setTimeout(0)` 或 `MessageChannel` 分片。
- 首屏先显示基础行，后续逐步补齐排序/分组/搜索状态。

### 当前项目触点

- `packages/datasheet/src/pc/store/view_derivation_middleware.ts`
- `packages/core/src/compute_manager/view_derivate/*`

### 复杂度

中。

### 注意点

- 切片会引入中间状态，必须定义 UI loading 和可交互边界。
- 排序/分组未完成时的编辑、选择、复制行为要明确。
- 与 Worker 相比，代码侵入可能更细碎。

## 11. 关联字段懒加载

### 目标

避免首屏因为 link、lookup、formula、member 等字段扫描全表并扩展大量关联数据。

### 实现方式

- 首包只返回关联表 meta 和当前窗口依赖记录。
- 可见 link 单元格需要标题时按记录补加载。
- 展开记录、编辑 link、查看 lookup 时再加载关联表窗口。
- member/user 信息可以独立缓存并按需加载。

### 当前项目触点

- `packages/room-server/src/database/datasheet/services/datasheet.field.handler.ts`
- `packages/room-server/src/database/datasheet/services/datasheet.service.ts`
- `packages/core/src/modules/database/store/selectors/resource/datasheet/cell_calc.ts`
- `packages/datasheet/src/pc/components/konva_grid/components/cell/*`

### 复杂度

中到高。

### 注意点

- lookup/formula 可能依赖不可见关联记录，显示值和排序/过滤都可能受影响。
- 如果只懒加载显示层，排序/过滤仍需服务端或缓存层保证正确。
- 关联字段 loading 态要避免误判为空值。

## 12. 公式和 lookup 预计算

### 目标

把公式、lookup、rollup 等计算结果预先写入缓存或数据库列，打开表时直接读取结果。

### 实现方式

- 为计算字段维护依赖图。
- 记录编辑后产生计算任务。
- 后台任务更新受影响记录的计算结果。
- 首屏读取已计算结果；未完成时显示计算中或旧值。

### 当前项目触点

- `packages/core/src/compute_manager/*`
- `packages/core/src/modules/database/store/selectors/resource/datasheet/cell_calc.ts`
- `packages/room-server/src/database/datasheet/services/datasheet.field.handler.ts`
- 数据库记录表或计算缓存表
- 队列服务，例如 RabbitMQ/Redis job

### 复杂度

高到很高。

### 注意点

- 需要处理跨表依赖、循环依赖、字段类型变更、批量导入。
- 需要定义强一致还是最终一致。
- 自部署可以接受短暂旧值，但要有明确配置和 UI 表达。

## 13. 实时协同订阅范围收窄

### 目标

避免打开大表时订阅整张表的实时变化，改为订阅当前视图或当前窗口。

### 实现方式

- 首屏先加载静态窗口。
- 实时连接延迟到首屏渲染后建立。
- 订阅当前 view query 或当前 recordIds window。
- 滚动窗口变化时更新订阅范围。
- 大批量更新时合并通知，只触发缓存失效或局部刷新。

### 当前项目触点

- `packages/room-server` 协同相关服务
- `packages/core/src/engine/*`
- `packages/datasheet/src/pc/resource_service`
- `packages/datasheet/src/pc/store/*`

### 复杂度

高到很高。

### 注意点

- 当前 JOT/OT、协同光标、编辑冲突处理可能假设完整资源。
- 订阅范围变化要避免丢事件。
- 大范围操作需要通知前端刷新 viewIndex，而不是逐条推送所有记录。

## 14. 客户端 IndexedDB 缓存

### 目标

提升重复打开同一张表或同一视图的速度。

### 缓存内容

- meta
- view 配置
- fieldPermissionMap
- rowIds 或 viewIndex
- record windows
- units/userMap

### 缓存 key

```text
dstId + viewId + revision + permissionScope + queryHash
```

### 当前项目触点

- `packages/datasheet/src/pc/store/*`
- `packages/core/src/modules/database/store/actions/resource/datasheet/datasheet.ts`
- 新增 IndexedDB 存储工具

### 复杂度

中到高。

### 注意点

- 首次打开无收益，重复打开有收益。
- 需要 revision 校验，避免旧数据污染。
- 权限变化后必须失效。

## 15. 服务端 Redis/内存缓存和预热

### 目标

利用自部署可增加服务端负载的前提，把热门表和热门视图提前计算好。

### 预热时机

- 服务启动后预热常用表。
- 用户登录后预热最近打开的表。
- 导入完成后预热默认视图。
- 第一次打开完成后缓存结果供后续打开。
- 定时任务刷新高频表。

### 缓存内容

- viewIndex
- rowCount
- groupPoints
- record windows
- formula/lookup 结果

### 当前项目触点

- `packages/room-server`
- Redis
- RabbitMQ 或已有任务机制
- docker compose 资源配置

### 复杂度

中到高。

### 注意点

- 需要控制内存上限和淘汰策略。
- 需要区分用户权限维度。
- 粗暴 revision 失效简单但会增加写后冷启动成本。

## 16. SSR 或服务端预取首屏窗口

### 目标

让 Next.js 首屏 HTML 或初始数据中带上表结构和首屏记录窗口，减少浏览器首轮请求等待。

### 实现方式

- SSR 阶段请求 table meta、fields、views、当前 view 首屏 records。
- 首屏 records 限制在 100-300 行。
- 前端 hydration 后继续使用窗口化加载。

### 当前项目触点

- `packages/datasheet/pages/workbench/[[...all]].tsx`
- `packages/datasheet/pages/_app.tsx`
- `packages/core/src/modules/database/store/actions/resource/datasheet/datasheet.ts`
- room-server 新窗口接口

### 复杂度

中。

### 注意点

- SSR 不能解决大 JSON 传输问题，必须限制首屏窗口。
- 需要处理登录态、权限、share/embed/template。
- Next 12 和当前路由结构可能增加接入成本。

## 17. React 18 并发渲染能力

### 目标

把非紧急 UI 更新标记为 transition，减少表打开期间输入和点击被阻塞。

### 实现方式

- 对切换视图、搜索、工具栏展开、非关键面板更新使用 `startTransition`。
- 对搜索输入使用 deferred value。
- 对大状态更新后续 UI 派生拆成非紧急更新。

### 当前项目触点

- `packages/datasheet/src/pc/components/*`
- `packages/datasheet/src/pc/store/view_derivation_middleware.ts`
- 搜索、筛选、分组、视图切换相关组件

### 复杂度

低到中。

### 注意点

- React 并发能力不能减少数据量。
- 如果长任务来自同步计算或 JSON parse，transition 只能部分缓解。

## 18. 替换或重构网格渲染层

### 目标

用 TanStack Virtual、react-window、Glide Data Grid 或自研 Canvas 网格替换当前 Konva 网格。

### 实现方式

可能路径：

- DOM 虚拟化：TanStack Virtual / react-window。
- Canvas 网格：Glide Data Grid 或自研 canvas。
- 保留 Konva，仅重构数据依赖和可见区逻辑。

### 当前项目触点

- `packages/datasheet/src/pc/components/konva_grid/*`
- 单元格编辑器
- 选区、复制粘贴、填充柄、冻结列、拖拽、统计、协同光标
- 导出和截图逻辑

### 复杂度

很高。

### 注意点

- 当前网格已经按可视行列绘制，替换渲染层未必解决首包和视图派生问题。
- 替换会影响大量交互细节。
- 如果实施，应先证明渲染本身是瓶颈。

## 19. databus/wasm 路径评估

### 目标

评估使用已有 databus-server 或 databus-wasm 替代部分 room-server dataPack 读取/计算路径。

### 实现方式

- 梳理 `getBrowserDatabusApiEnabled()` 现状。
- 验证 databus 返回数据语义是否包含权限、关联字段、视图条件、revision。
- 对比 room-server dataPack 和 databus dataPack 的响应大小、耗时和字段完整性。
- 若语义一致，作为 dataPack 或 recordsWindow 的实现后端。

### 当前项目触点

- `packages/core/src/modules/database/api/datasheet_api.ts`
- `packages/databus-client`
- `packages/databus-wasm-web`
- `packages/databus-wasm-nodejs`
- `docker-compose.yaml` 中 databus-server

### 复杂度

高。

### 注意点

- 当前浏览器 dataPack wasm 调用未实际启用。
- 权限、关联字段、协同 revision 语义必须验证。
- 不应只打开开关上线。

## 20. Arrow、二进制和列式传输

### 目标

减少大 JSON 的传输、解析和内存开销。

### 实现方式

- 服务端将记录窗口或列数据编码为 Arrow/二进制格式。
- 前端解码为列式结构或按需 cell accessor。
- 对数值、日期、枚举、布尔等字段做列式压缩。
- 保留 JSON 兼容路径。

### 当前项目触点

- room-server API 协议
- `packages/core` 数据模型
- `packages/datasheet` 单元格读取逻辑
- 网关响应类型和压缩配置

### 复杂度

很高。

### 注意点

- 只有在已完成窗口化加载后，二进制传输才更容易落地。
- 当前 `recordMap` 是对象结构，列式数据会影响大量 selector 和 cell_calc。
- 需要兼容复杂字段类型。

## 21. DuckDB-Wasm 或浏览器端分析引擎

### 目标

把部分筛选、搜索、聚合、分析放到浏览器端列式引擎中执行。

### 实现方式

- 服务端提供 Arrow/Parquet/列式数据块。
- 前端将数据加载进 DuckDB-Wasm。
- 用 SQL 执行筛选、聚合、统计或离线查询。

### 当前项目触点

- datasheet 数据加载协议
- 前端缓存
- 查询和统计 UI
- Worker/wasm 初始化

### 复杂度

很高。

### 注意点

- 对首次打开未必有帮助，因为仍要下载数据块。
- 更适合报表、离线分析、复杂统计，不适合作为第一层首屏优化。
- 会增加 wasm 包体和内存占用。

## 22. 数据库索引和物化视图

### 目标

让服务端查询 rowIds、rowCount、groupPoints 更快。

### 实现方式

- 为常用排序字段、视图行顺序字段建立索引。
- 为 link/lookup 关联表建立索引。
- 对热门视图建立物化行索引表。
- 对搜索字段建立全文索引或倒排索引。

### 当前项目触点

- `init-db/src/main/resources/db/changelog/*`
- `packages/room-server/src/database/datasheet/repositories/*`
- 数据库 migration
- 自部署初始化脚本

### 复杂度

中到很高。

### 注意点

- 不同数据库支持能力不同。
- 每个视图都建物化结构会增加写入成本。
- 自部署可以接受更多服务端存储和写入成本，但需要可配置和可回滚。

## 23. 搜索索引

### 目标

避免前端全量记录搜索和高亮计算。

### 实现方式

- 服务端建立搜索索引。
- 搜索接口返回命中的 record ids、field ids 和当前页窗口。
- 前端只显示命中窗口和高亮信息。

### 当前项目触点

- `packages/core/src/modules/database/store/selectors/resource/datasheet/rows_calc.ts`
- `packages/core/src/compute_manager/view_derivate/*`
- `packages/room-server`
- 数据库全文索引或独立搜索服务

### 复杂度

中到高。

### 注意点

- 搜索语义要和当前前端一致。
- 高亮信息可能需要返回 fieldId。
- 权限会影响搜索结果。

## 24. 大批量操作服务端化

### 目标

当复制整列、删除大量行、清空选择区域、批量重复记录时，不要求前端加载全部记录。

### 实现方式

- 选择区域转换为服务端 query/range。
- 服务端按 viewIndex 执行批量操作。
- 返回任务进度和结果摘要。
- 前端只刷新受影响窗口和 viewIndex。

### 当前项目触点

- `packages/datasheet/src/pc/components/konva_grid/*`
- `packages/core/src/commands/*`
- `packages/room-server`
- 协同/通知/任务进度

### 复杂度

高。

### 注意点

- 当前复制粘贴、填充、删除可能依赖本地完整数据。
- 服务端化后需要权限校验、事务、失败回滚和协同广播。

## 25. 自部署服务端负载换前端速度

### 目标

利用自部署场景可增加服务端负载、内存和缓存的特点，减少浏览器端数据量和计算量。

### 可配置项

```text
SELF_HOST_FAST_DATAPACK=true
SELF_HOST_VIEW_INDEX_CACHE=true
SELF_HOST_RECORD_WINDOW_CACHE=true
SELF_HOST_PREWARM_RECENT_TABLES=true
SELF_HOST_DEFER_COMMENT_COUNT=true
SELF_HOST_DEFER_FOREIGN_RECORDS=true
SELF_HOST_STALE_STATS_SECONDS=5
SELF_HOST_ASYNC_FORMULA_CACHE=true
```

### 可接受取舍

- 统计结果允许秒级缓存。
- 大表公式/lookup 允许短暂旧值或计算中状态。
- 评论数、历史记录、关联详情延迟加载。
- 实时订阅在首屏后延迟接入。
- 批量变更后先失效缓存，再异步重建。

### 当前项目触点

- `.env`
- `docker-compose*.yaml`
- `packages/room-server`
- `packages/datasheet`
- Redis/RabbitMQ

### 复杂度

中到很高，取决于开启哪些能力。

### 注意点

- 配置项必须可观测、可回滚。
- 自部署默认值可以更激进，但需要保留兼容模式。
- 文档需明确哪些配置会影响一致性或实时性。

## 26. Teable 类架构对照

Teable 的大表速度来自一组组合实现：

- 首屏记录分页，API 默认有限制。
- Grid 滚动触发 `skip/take` 更新。
- `recordMap` 以 row index 为 key，缺失时显示 loading cell。
- `rowCount`、`groupPoints`、`aggregation` 独立查询。
- 服务端先查当前 query 的 record ids，再批量取 snapshot。
- 查询层支持 filter、sort、group、search、skip、take。
- 实时订阅基于 query，而不是整表全量订阅。
- doc ids 查询可以使用服务端缓存。

映射到当前项目，需要对应以下能力：

| Teable 能力 | vika 对应改造 |
| --- | --- |
| `skip/take` records API | 新增 view/window records API |
| row index -> recordMap | 前端 partial recordMap |
| loading cell | Konva 单元格缺失记录 loading |
| 服务端 query row ids | 服务端视图索引或 SQL 下推 |
| rowCount/groupPoints 独立接口 | 拆分统计和分组接口 |
| query 级实时订阅 | 协同订阅范围收窄 |
| doc ids cache | Redis viewIndex cache |

### 复杂度

高到很高。

### 注意点

- 这是架构迁移，不是单个库替换。
- 当前项目已有局部加载能力，但核心视图计算和完整 recordMap 假设需要改造。

## 当前项目文件影响索引

### 前端数据请求和状态

- `packages/core/src/modules/database/api/datasheet_api.ts`
- `packages/core/src/modules/database/api/url.data.ts`
- `packages/core/src/modules/database/store/actions/resource/datasheet/datasheet.ts`
- `packages/core/src/modules/database/store/reducers/resource/datasheet/*`
- `packages/core/src/modules/database/store/selectors/resource/datasheet/*`
- `packages/datasheet/src/pc/utils/load_records.ts`

### 前端视图派生

- `packages/datasheet/src/pc/store/view_derivation_middleware.ts`
- `packages/core/src/compute_manager/view_derivate/factory.ts`
- `packages/core/src/compute_manager/view_derivate/view_derivate_base.ts`
- `packages/core/src/compute_manager/view_derivate/view_derivate_grid.ts`
- `packages/core/src/compute_manager/view_derivate/slice/*`

### 前端网格渲染

- `packages/datasheet/src/pc/components/konva_grid/konva_grid_view.tsx`
- `packages/datasheet/src/pc/components/konva_grid/konva_grid_stage.tsx`
- `packages/datasheet/src/pc/components/konva_grid/hooks/use_grid.tsx`
- `packages/datasheet/src/pc/components/konva_grid/hooks/use_grid_cells.tsx`
- `packages/datasheet/src/pc/components/konva_grid/components/cell/*`

### Worker

- `packages/datasheet/src/pc/worker/index.ts`
- `packages/datasheet/src/pc/worker/store/store_worker.ts`
- `packages/datasheet/src/pc/worker/store/with_compute.ts`
- `packages/datasheet/src/pc/worker/store/compute.ts`

### room-server dataPack 和记录读取

- `packages/room-server/src/database/datasheet/controllers/datasheet.controller.ts`
- `packages/room-server/src/database/datasheet/services/datasheet.service.ts`
- `packages/room-server/src/database/datasheet/services/datasheet.record.service.ts`
- `packages/room-server/src/database/datasheet/services/datasheet.field.handler.ts`
- `packages/room-server/src/database/datasheet/repositories/datasheet.meta.repository.ts`
- `packages/room-server/src/database/datasheet/repositories/datasheet.record.repository.ts`
- `packages/room-server/src/shared/helpers/db.helper.ts`

### 数据库和部署

- `init-db/src/main/resources/db/changelog/*`
- `docker-compose.yaml`
- `docker-compose.devenv.yaml`
- `gateway`
- Redis/RabbitMQ 相关配置

## 后续讨论模板

后续讨论某个方案时，可以按以下问题展开：

1. 这个方案减少的是服务端耗时、网络大小、浏览器解析、主线程计算，还是绘制成本？
2. 是否改变 `dataPack` 或 `recordMap` 契约？
3. 是否要求前端支持 partial data？
4. 是否影响权限、公式、lookup、link、协同、widget、导出？
5. 缓存 key 包含哪些维度？
6. 缓存失效按 revision 粗暴失效，还是增量更新？
7. 自部署是否接受短暂旧值、异步统计或延迟实时？
8. 验收指标是什么？

