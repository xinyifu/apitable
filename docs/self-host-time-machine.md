# 自部署时光机操作记录增强方案

本文记录本 fork 中社区版时光机的前端体验增强方案、阶段边界、验收方式和可提交节点。

## 背景

自部署社区版开启：

```env
TIME_MACHINE_VISIBLE=true
```

后，前端会显示维格表工具栏中的时光机入口。当前社区版源码可用的是：

- 操作记录列表。
- 点击操作记录后预览对应 revision 的历史快照。

当前社区版源码不包含企业版 Backup 模块，因此不处理：

- 版本历史。
- 创建版本。
- 恢复版本。
- 企业版备份列表。

## 当前问题

当前操作记录只显示动作摘要，例如：

```text
在 1 个单元格中编辑了数据
新增了 1 行记录
已粘贴 1 个数据项
修改了列配置新增了 1 行记录
```

用户无法直接判断操作影响了哪一条业务记录。对销售、直营、代理等大表来说，这会导致操作记录只能用于粗略回放，无法用于快速定位问题。

## 用户体验原则

### recordId 必须常驻

记录标题用于阅读，`recordId` 用于唯一定位。以下情况都要求 `recordId` 可见：

- 标题重复。
- 标题为空。
- 主字段后来被修改。
- 记录已经删除。
- 历史数据与当前数据不一致。

标题存在时弱化显示 `recordId`，但不能隐藏。

推荐展示：

```text
编辑了 1 个单元格
张三-拉卡拉电签  recA1b2C3...
```

标题不存在时：

```text
编辑了 1 个单元格
recA1b2C3...
```

删除记录：

```text
删除了 1 行记录
已删除 · 张三-拉卡拉电签  recA1b2C3...
```

### 多记录操作必须折叠

粘贴或批量导入可能影响大量记录。默认只显示前 3 条记录，剩余显示：

```text
+ 27 条
```

第一版不做展开面板，避免一次渲染大量 chip 影响操作记录滚动性能。

### 点击行为保持克制

第一版只做信息展示，不改变主交互：

- 点击操作记录项：保持现有历史预览行为。
- 点击 recordId：暂不实现复制，避免和整行点击预览冲突。
- 点击记录 chip 打开详情：第二阶段再做。

## 技术设计

### 涉及文件

```text
packages/datasheet/src/pc/components/time_machine/index.tsx
packages/datasheet/src/pc/components/time_machine/utils.ts
packages/datasheet/src/pc/components/time_machine/interface.ts
packages/datasheet/src/pc/components/time_machine/style.module.less
```

### 数据解析

在 `utils.ts` 中新增操作详情解析能力。

目标结构：

```ts
interface ITimeMachineRecordRef {
  recordId: string;
  title?: string;
  titleSource: 'current' | 'history' | 'none';
  status: 'exists' | 'deleted' | 'unknown';
  fieldIds: string[];
}

interface ITimeMachineOperationDetail {
  summary: string;
  records: ITimeMachineRecordRef[];
}
```

解析规则：

- 从 `op.actions[].p` 中识别 `recordMap` 路径。
- `['recordMap', recordId]` 表示整行新增、删除或替换。
- `['recordMap', recordId, 'data', fieldId]` 表示单元格级变更。
- 同一个 changeset 内对 `recordId` 去重。
- `oi` 存在且当前 snapshot 有该记录时，状态为 `exists`。
- `od` 存在且当前 snapshot 没有该记录时，状态为 `deleted`。
- 无法判断时，状态为 `unknown`。

### 标题解析

标题来源优先级：

1. 当前 snapshot 中 `recordMap[recordId]` 的主字段值。
2. action 中历史记录对象 `od.data` 或 `oi.data` 的主字段值。
3. 无标题，仅显示 `recordId`。

主字段选择：

- 优先使用当前视图第一列 `snapshot.meta.views[0].columns[0].fieldId`。
- 若不可用，回退到 fieldMap 的第一个字段。

标题格式只做轻量转换：

- 字符串直接显示。
- 数字、布尔值转字符串。
- 文本 segment 数组拼接 `text`。
- 其他复杂类型先显示空标题，避免误导。
- `titleSource` 只用于内部判断，不在前端提示中展示“标题来自当前/历史数据”。

### 前端展示

在 `index.tsx` 中把原来的：

```tsx
<span>{getOperationInfo(ops)}</span>
```

替换为：

```tsx
const detail = getOperationDetail(ops, curDatasheet.snapshot);

<span>{detail.summary}</span>
<TimeMachineRecordRefs records={detail.records} />
```

第一版可以直接在同文件中实现小组件，避免增加不必要文件：

```tsx
const TimeMachineRecordRefs = ({ records }) => ...
```

样式放在 `style.module.less`：

- `.recordRefs`
- `.recordRef`
- `.recordTitle`
- `.recordId`
- `.recordDeleted`
- `.recordMore`

## 分阶段提交节点

### Commit 1：文档与验收方案

可提交内容：

- 新增本文档。
- 不包含功能代码。

建议提交信息：

```text
记录自部署时光机操作记录增强方案

- 新增时光机操作记录增强设计文档
- 明确 recordId 常驻显示和分阶段提交节点
- 补充前端验收清单
```

### Commit 2：操作记录显示受影响记录

可提交内容：

- `utils.ts` 增加 changeset 记录解析。
- `index.tsx` 显示标题、状态和常驻 `recordId`。
- `style.module.less` 增加轻量样式。
- 移除时光机组件中的调试 `console.log`。

验收后提交。

### Commit 3：交互增强

可提交内容：

- 当前存在的记录 chip 支持打开行详情。
- 删除记录点击给出提示。
- recordId 支持复制。

该阶段必须单独验收，避免和第一版展示逻辑混在一起。

## 第一阶段验收清单

### 环境

```env
TIME_MACHINE_VISIBLE=true
```

打开任意维格表，点击工具栏时光机按钮。

### 验收项

- 工具栏显示时光机入口。
- 打开时光机后仍只显示社区版已有的“操作记录”。
- 每条涉及记录的操作都至少显示一个 `recordId`。
- 有标题时显示标题，同时弱化显示 `recordId`。
- 无标题时仍显示 `recordId`。
- 删除记录显示“已删除”状态。
- 批量操作默认最多显示 3 条记录，其余显示 `+ N 条`。
- 点击操作记录项仍进入历史预览，例如“正在预览，版本 xxxx”。
- 不出现版本历史、创建版本、恢复版本等企业版能力。
- 控制台不再输出时光机调试日志：
  - `Load changesetList`
  - `ops`
  - `---------preview actions`

### 非目标

第一阶段不验收：

- 打开行详情。
- 复制 recordId。
- 展示字段名。
- 展示具体新旧值。
- 回滚功能。

## 第二阶段验收清单

在第一阶段基础上继续验收：

- 点击当前存在的记录 chip，会打开该记录详情路由，即 URL 追加该行 `recordId` 并弹出记录详情面板。
- 点击 `recordId` 文本，只复制 `recordId`，不触发整条操作记录的历史预览。
- 复制成功后显示“已复制 recordId”提示。
- 点击已删除记录 chip 时不打开详情，显示记录已删除的反馈提示。
- 点击 chip 或 `recordId` 不影响整条操作记录点击预览历史版本的能力。
- 控制台不出现时光机组件新增的运行时错误。
