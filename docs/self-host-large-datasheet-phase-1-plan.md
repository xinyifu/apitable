# 大表首次打开性能优化 — 第一阶段（快速减载）实施与验收方案

> **文件定位**：本文档为大表性能优化“阶段 1 — 快速减载”的详细工程设计与验收标准。它定义了如何在不改变协同模型与数据存储契约的前提下，通过延迟非核心数据和优化关联加载，大幅缩减首包大小及 TTFB 延时。

---

## 1. 目标与定位

在第一阶段（快速减载，预计工期 2-4 周），我们的核心任务是**“减少首包数据传输并提升 TTFB”**。所有改动应当以配置开关为护栏，允许随时热退回。

### 目标收益 (待基线验证)
- **多关联字段大表**：首包体积目标降幅为 **50% - 80%**。
- **普通大表**：首包体积目标降幅为 **10% - 20%**。
- **服务端接口 TTFB**：在 Redis 缓存命中时，`fetchCommonDataPack` 接口的响应时延应控制在 **200ms 以内**。

---

## 2. 实施方案与技术拆解

### 2.1 任务一：Fast DataPack（非核心数据延迟加载）
通过剥离首屏渲染非必需的数据，减轻网络和数据库的双重负担。

#### 1. 评论数延迟加载 (`commentCount`)
- **实现机制**：
  - 引入环境变量 `SELF_HOST_DEFER_COMMENT_COUNT=true` 控制。
  - 在 [datasheet.service.ts](file:///d:/CodeAI/vika/packages/room-server/src/database/datasheet/services/datasheet.service.ts) 中，当开关开启时，`fetchCommonDataPack` 直接跳过 `commentCount` 查询并返回空的哈希映射。
  - 前端 [load_records.ts](file:///d:/CodeAI/vika/packages/datasheet/src/pc/utils/load_records.ts) 或协作面板在交互展开时，按需调用已有 `fetchCommentCount` 接口填充。
- **控制文件**：
  - [datasheet.service.ts](file:///d:/CodeAI/vika/packages/room-server/src/database/datasheet/services/datasheet.service.ts)
  - [datasheet.controller.ts](file:///d:/CodeAI/vika/packages/room-server/src/database/datasheet/controllers/datasheet.controller.ts)

#### 2. 历史版本记录延迟加载 (`revisionHistory`)
- **实现机制**：
  - 首包中剔除 `revisionHistory` 大数组。
  - 前端工作台左侧/右侧历史记录面板在用户主动点开时，发起独立请求拉取。

#### 3. recordMeta 精简
- **实现机制**：
  - 评估并去除 `recordMeta` 中前端在首屏（首批可视行列渲染）时不关心的元数据字段，仅保留行高、折叠状态等基础排版属性。

---

### 2.2 任务二：关联字段懒加载与分层
外表全量加载是目前大表打开时内存与带宽膨胀的最主要来源。我们需要对其执行分层加载。

#### 1. 第一层：非关键关联数据延迟（展示型数据懒加载）
- **实现机制**：
  - 引入环境变量 `SELF_HOST_DEFER_FOREIGN_RECORDS=true` 控制。
  - 在 [datasheet.field.handler.ts](file:///d:/CodeAI/vika/packages/room-server/src/database/datasheet/services/datasheet.field.handler.ts) 中，当开关开启时，`DatasheetFieldHandler.analyze` 构建的 `foreignDatasheetMap` 只保留关联表 `meta`，**不加载和传输关联表的 recordMap 数据**。
  - 前端 link 单元格渲染时，仅显示本地缓存中已有的部分标题。对于未加载的内容，当单元格进入可视区域（Viewport）时，触发增量 `loadRecords` 按需调取关联记录值。

#### 2. 第二层：计算语义关联保障（公式/过滤/排序前置加载）
- **实现机制**：
  - 检查依赖树。如果当前视图的 `filter`（筛选）、`sort`（排序）、`group`（分组）或关键的 `formula`（公式）、`lookup`（查阅）字段依赖于未被加载的外表记录：
    - **不能直接进行 loading 占位**，否则会产生计算语义错误（如过滤出空行、排序错乱等）。
    - 需提供前置分析逻辑，针对这部分对计算有影响的“关键外表数据”，采取**前置定向加载**，而非全量加载。这保证了在不影响视图计算一致性的前提下实现最大程度的体积裁剪。

---

### 2.3 任务三：基础数据 Redis 缓存
减少数据库全表扫描次数，利用 Redis 缓存高频且变化缓慢的数据。

#### 1. 缓存目标
- 优先缓存 `meta`（字段与视图定义）、`fieldPermissionMap`（字段权限）及 `units` / `userMap` 等组织架构数据。
- 暂不缓存极大的主表 `recordMap`，避免内存溢出及协同修改时复杂的一致性维护。
- 缓存的 Redis key 格式设计：`dstId:revision:permissionScope`。

#### 2. 失效机制
- 每次收到客户端发送的数据变更协同（通过 OT/JOT 触发 revision 递增）时，通过失效服务使该 `dstId` 下的前序缓存条目立即失效。

---

## 3. 验收标准与测试矩阵

### 3.1 功能验收（无功能回归）

1. **功能完整性校验**：
   - 在懒加载环境下，关联单元格切换、新记录添加、级联公式重算等基本功能应无报错，不能导致 JS 抛出 NPE（Null Pointer Exception）。
   - 评论按钮以及历史记录在点击对应侧边栏后，能以异步加载转圈的方式正确显示相应数据。
2. **开关容灾退回校验**：
   - 环境变量 `SELF_HOST_DEFER_COMMENT_COUNT` 与 `SELF_HOST_DEFER_FOREIGN_RECORDS` 切换为 `false` 后，系统必须可完全回退到旧版全量包加载机制。

### 3.2 性能验收（基于阶段 0 的测量基线）

我们将基于 10k（常规大表）与 50k（压力大表）的测试场景，在以下指标中衡量收益：

| 性能验收指标 | 重构前基线 | 重构后验收指标 | 测量方法 |
|---|---|---|---|
| **首包体积大小 (JSON size)** | 实际测量值 | 目标降幅 **50% - 80%** (含多关联表) / **10% - 20%** (普通大表) | Chrome DevTools Network 标签页 |
| **首包等待时间 (TTFB)** | 实际测量值 | 目标：Redis 命中时 **< 200ms** | Chrome DevTools Timings 面板 |
| **后端 dataPack 构造时间** | 实际测量值 | 目标：平均构造耗时降低 **40% 以上** | room-server 生产日志计时 |
| **首次可见网格时间 (FMP)** | 实际测量值 | 目标：首屏网格绘制时机提前 **30% 以上** | 前端 `performance.mark` 日志监测 |

### 3.3 单元测试与接口回归

- **后端测试**：
  - 编写集成测试，调用 `GET /datasheets/:dstId/dataPack` 并添加对应延迟加载 Header / Query 参数，验证返回的 JSON 中 `commentCount` 与 `foreignDatasheetMap` 被按预期进行裁剪。
- **前端测试**：
  - 为 Redux selector 编写单元测试（重点是 [cell_calc.ts](file:///d:/CodeAI/vika/packages/core/src/modules/database/store/selectors/resource/datasheet/cell_calc.ts) 和 [rows_calc.ts](file:///d:/CodeAI/vika/packages/core/src/modules/database/store/selectors/resource/datasheet/rows_calc.ts)），确保在其获取的部分记录为 `undefined` 时拥有健壮的回退降级方案，不能导致渲染层崩溃。
