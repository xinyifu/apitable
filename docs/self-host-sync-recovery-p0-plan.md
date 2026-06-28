# 自部署同步恢复可感知优化 P0 技术方案

更新时间：2026-06-28

## 一、目标

长时间闲置切回表格后，前端会进入 socket 连接恢复、资源 watch、缺失 changeset 补齐、必要 dataPack 重载和视图派生计算等同步恢复流程。当前页面通常仍显示旧表格数据，但在恢复完成前暂时不可点击、不可编辑、不可切换视图，右上角网络状态图标会转圈。

本方案只实现 P0 体验优化：

1. 保留当前表格画面，在同步恢复期间显示轻量遮罩，明确提示当前正在恢复同步。
2. 细分右上角网络状态图标的 tooltip 和动画资源，让用户能区分“正在恢复连接”“正在连接表格”“正在同步数据”和“已连接”。

本方案不修改 changeset 补齐逻辑，不修改 dataPack reload 策略，不新增 revision 预检接口，不做 30 秒强制清理。

## 二、实际代码检索结果

### 网络状态链路

相关文件：

- `packages/datasheet/src/pc/hooks/use_network.ts`
- `packages/datasheet/src/pc/components/network_status/network_status.tsx`
- `packages/datasheet/src/pc/components/network_status/style.module.less`

`useNetwork()` 当前按以下优先级计算右上角状态：

1. `state.space.reconnecting` -> `Network.Loading`
2. `!resourceNetworking.connected` -> `Network.Offline`
3. `resourceNetworking.syncing` -> `Network.Sync`
4. 其他 -> `Network.Online`

`NetworkStatus` 当前只支持 4 个 Lottie 容器：

- `network_online`
- `network_offline`
- `network_sync`
- `network_loading`

当前动画资源位于：

- `packages/datasheet/public/static/json/datasheet_icon_online.json`
- `packages/datasheet/public/static/json/datasheet_icon_offline.json`
- `packages/datasheet/public/static/json/datasheet_icon_sync.json`
- `packages/datasheet/public/static/json/datasheet_icon_loading.json`

代码导入路径使用别名：

```ts
import LoadingAnimationJson from 'static/json/datasheet_icon_loading.json';
```

### 表格 loading 与 skeleton

相关文件：

- `packages/datasheet/src/pc/components/datasheet_pane/datasheet_pane.tsx`
- `packages/datasheet/src/pc/components/view_container/view_container.tsx`
- `packages/core/src/modules/database/store/selectors/resource/datasheet/rows_calc.ts`
- `packages/core/src/modules/database/store/reducers/resource/datasheet/client.ts`

`DataSheetPaneBase` 传给 `DatasheetMain` 的 `loading` 当前由以下逻辑得到：

```ts
Boolean(!datasheet || datasheet.isPartOfData || datasheet.sourceId)
```

`ViewContainer` 自身的 skeleton 条件是：

```ts
!datasheet || datasheet.isPartOfData || !viewPrepared
```

`viewPrepared` 来源于：

```ts
state.datasheetMap[datasheetId]?.client?.viewDerivation[nodeViewId]
```

结论：

- 首次打开表格时，可能已有 skeleton/loading。
- 同步恢复遮罩必须加冷启动保护，不能叠在首次加载 skeleton 上。
- 同步恢复遮罩只应该在已有旧表格画面可见时显示。

### 已知调用方

需要更新 `useNetwork()` 返回值和 `NetworkStatus` props 的调用位置：

- `packages/datasheet/src/pc/components/tab_bar/tab/tab.tsx`
- `packages/datasheet/src/pc/components/dashboard_panel/tab_bar/tab_bar.tsx`
- `packages/datasheet/src/pc/components/mirror/mirror.tsx`

`packages/datasheet/src/pc/components/mobile_tool_bar/tool_bar_wrapper.tsx` 当前只调用 `useNetwork(...)`，没有渲染 `NetworkStatus`，因此只需要确认类型兼容，不需要传入 `reason`。

## 三、术语

- **同步恢复**：浏览器长时间挂起后，前端恢复 socket 连接、重新 watch room、补齐缺失 changeset、必要时重新加载 dataPack，并重新计算当前视图派生数据的过程。
- **空间重连状态**：`state.space.reconnecting`，表示 socket 或 room 正在恢复连接。
- **资源连接状态**：当前 Datasheet/Mirror/Dashboard/Form 在 Redux 中的 `connected` 状态。
- **资源同步状态**：当前资源在 Redux 中的 `syncing` 状态。
- **恢复遮罩**：覆盖在当前表格画面上的轻量 UI 层，用于提示和拦截恢复期间的误操作，不替换旧表格内容。
- **网络状态原因**：右上角图标的细分原因，用于 tooltip 和动画资源选择。

## 四、修改范围

### 1. 新增同步恢复状态类型

新增或放置在遮罩组件附近：

`packages/datasheet/src/pc/components/datasheet_pane/reconnecting/sync_recovering_status.ts`

```ts
export enum SyncRecoveringStatus {
  None = 'none',
  Reconnecting = 'reconnecting',
  ConnectingResource = 'connecting_resource',
  SyncingData = 'syncing_data',
}
```

该状态仅用于 UI 表达，不参与同步引擎逻辑。

### 2. 新增同步恢复遮罩组件

新增文件：

`packages/datasheet/src/pc/components/datasheet_pane/reconnecting/SyncRecoveringOverlay.tsx`

新增样式文件：

`packages/datasheet/src/pc/components/datasheet_pane/reconnecting/sync_recovering_overlay.module.less`

职责：

- 接收 `status` 和 `enabled`。
- 在表格区域上方显示半透明遮罩。
- 拦截鼠标点击、双击、右键和滚轮等交互。
- 显示简短文案和 loading 图标。
- 不卸载或替换当前表格 DOM。

建议 props：

```ts
interface ISyncRecoveringOverlayProps {
  status: SyncRecoveringStatus;
  enabled: boolean;
}
```

显示规则：

- `enabled === false` 时不显示。
- `status === SyncRecoveringStatus.None` 时不显示。
- 非 `None` 时延迟 500ms 显示，避免快速恢复时闪烁。
- 状态恢复为 `None` 或 `enabled=false` 时立即隐藏并清理 timer。

React 延迟逻辑：

```tsx
const [visible, setVisible] = useState(false);

useEffect(() => {
  if (!enabled || status === SyncRecoveringStatus.None) {
    setVisible(false);
    return;
  }
  const timer = window.setTimeout(() => {
    setVisible(true);
  }, 500);
  return () => window.clearTimeout(timer);
}, [enabled, status]);
```

15 秒逃生门：

- 遮罩实际显示超过 15 秒后，显示“连接较慢，手动刷新”按钮。
- 按钮执行 `window.location.reload()`。
- 该按钮只作为用户主动逃生入口，不自动刷新。

### 3. 在 DatasheetPane 接入遮罩

修改文件：

`packages/datasheet/src/pc/components/datasheet_pane/datasheet_pane.tsx`

在 `DataSheetPaneBase` 中读取当前资源网络状态：

- `state.space.reconnecting`
- `Selectors.getResourceNetworking(state, resourceId, resourceType)`

当前资源取值：

- 有 `mirrorId` 时，`resourceId = mirrorId`，`resourceType = ResourceType.Mirror`
- 否则 `resourceId = datasheetId`，`resourceType = ResourceType.Datasheet`

状态优先级：

```ts
if (state.space.reconnecting) {
  status = SyncRecoveringStatus.Reconnecting;
} else if (!resourceNetworking?.connected) {
  status = SyncRecoveringStatus.ConnectingResource;
} else if (resourceNetworking?.syncing) {
  status = SyncRecoveringStatus.SyncingData;
} else {
  status = SyncRecoveringStatus.None;
}
```

冷启动保护：

```ts
const hasVisibleDatasheet = Boolean(datasheet && !datasheet.isPartOfData && !datasheet.sourceId);
const shouldShowOverlay = hasVisibleDatasheet && status !== SyncRecoveringStatus.None;
```

说明：

- 不能只用 `!resourceNetworking?.connected` 触发遮罩。
- 首次打开表格时，如果 `datasheet` 不存在或当前仍在 skeleton 阶段，不显示同步恢复遮罩。
- 遮罩只用于“已有旧画面但暂不可操作”的恢复场景。

渲染位置：

```tsx
const datasheetMainWithOverlay = (
  <div className={styles.datasheetMainWithOverlay}>
    {datasheetMain}
    <SyncRecoveringOverlay status={status} enabled={shouldShowOverlay} />
  </div>
);
```

后续 `VikaSplitPanel.panelLeft` 或无右侧面板时使用 `datasheetMainWithOverlay`。

样式要求：

- wrapper 使用 `position: relative; width: 100%; height: 100%;`
- overlay 使用 `position: absolute; inset: 0;`
- overlay 覆盖主表格操作区。
- 不覆盖全局 Modal。

### 4. 保证右上角网络图标 hover 可用

修改文件：

`packages/datasheet/src/pc/components/network_status/style.module.less`

当前 `.networkStatus` 只有 flex 和高度设置。需要补充层级：

```less
.networkStatus {
  position: relative;
  z-index: 101;
}
```

遮罩 z-index 必须低于 `.networkStatus`。建议：

```less
.syncRecoveringOverlay {
  z-index: 100;
}
```

注意：

- 右上角网络状态图标在 `TabBar` / `Mirror` / `Dashboard` 中渲染。
- 遮罩拦截表格操作时，网络状态图标仍必须可 hover，tooltip 仍必须可读。

### 5. 细分右上角网络状态原因

修改文件：

`packages/datasheet/src/pc/hooks/use_network.ts`

新增枚举：

```ts
export enum NetworkReason {
  Reconnecting = 'reconnecting',
  ConnectingResource = 'connecting_resource',
  SyncingData = 'syncing_data',
  Online = 'online',
}
```

保持当前 `Network` 枚举兼容，同时返回 `reason`：

```ts
if (IOConnecting) {
  setStatus(Network.Reconnecting);
  setReason(NetworkReason.Reconnecting);
  return;
}
if (!connected) {
  setStatus(Network.ConnectingResource);
  setReason(NetworkReason.ConnectingResource);
  return;
}
if (syncing) {
  setStatus(Network.SyncingData);
  setReason(NetworkReason.SyncingData);
  return;
}
setStatus(Network.Online);
setReason(NetworkReason.Online);
```

为了支持独立动画资源，`Network` 枚举也需要扩展为语义状态：

```ts
export enum Network {
  Online = 'online',
  Offline = 'offline',
  Sync = 'sync',
  Loading = 'loading',
  Reconnecting = 'reconnecting',
  ConnectingResource = 'connecting_resource',
  SyncingData = 'syncing_data',
}
```

兼容规则：

- 旧状态 `Loading`、`Offline`、`Sync` 暂保留。
- 新代码使用 `Reconnecting`、`ConnectingResource`、`SyncingData`。
- 未升级调用方仍可继续传旧状态。

`automatic = false` 时仍返回 `setStatus`：

```ts
return {
  status,
  setStatus,
  reason,
};
```

### 6. 新增右上角对应动画资源

新增文件：

- `packages/datasheet/public/static/json/datasheet_icon_reconnecting.json`
- `packages/datasheet/public/static/json/datasheet_icon_connecting_resource.json`
- `packages/datasheet/public/static/json/datasheet_icon_syncing_data.json`

资源要求：

- Lottie JSON。
- 尺寸保持 `72x72`，与现有 `datasheet_icon_*.json` 一致。
- 图标最终渲染尺寸仍由 `.network svg { width: 36px; height: 36px; }` 控制。
- `reconnecting` 用于 socket/room 恢复连接。
- `connecting_resource` 用于 socket 已恢复但资源尚未 connected。
- `syncing_data` 用于资源正在同步数据。

初版落地方式：

- 如果没有新的设计导出文件，可以先复制现有资源作为占位：
  - `datasheet_icon_reconnecting.json` 初版可基于 `datasheet_icon_loading.json`
  - `datasheet_icon_connecting_resource.json` 初版可基于 `datasheet_icon_loading.json`
  - `datasheet_icon_syncing_data.json` 初版可基于 `datasheet_icon_sync.json`
- 后续再替换为正式设计资源。

### 7. 扩展 NetworkStatus 动画与 tooltip

修改文件：

`packages/datasheet/src/pc/components/network_status/network_status.tsx`

新增导入：

```ts
import ReconnectingAnimationJson from 'static/json/datasheet_icon_reconnecting.json';
import ConnectingResourceAnimationJson from 'static/json/datasheet_icon_connecting_resource.json';
import SyncingDataAnimationJson from 'static/json/datasheet_icon_syncing_data.json';
```

扩展 `ID`：

```ts
NETWORK_RECONNECTING: 'network_reconnecting',
NETWORK_CONNECTING_RESOURCE: 'network_connecting_resource',
NETWORK_SYNCING_DATA: 'network_syncing_data',
```

扩展 props：

```ts
interface INetworkStatusProps {
  currentStatus?: Network;
  reason?: NetworkReason;
}
```

tooltip 文案：

- 优先使用 `reason`。
- 如果没有 `reason`，继续使用现有 `NetworkTip[currentStatus]`，保证旧调用兼容。

动画显示：

```tsx
<div id={ID.NETWORK_RECONNECTING} style={{ display: currentStatus === Network.Reconnecting ? 'flex' : 'none' }} />
<div id={ID.NETWORK_CONNECTING_RESOURCE} style={{ display: currentStatus === Network.ConnectingResource ? 'flex' : 'none' }} />
<div id={ID.NETWORK_SYNCING_DATA} style={{ display: currentStatus === Network.SyncingData ? 'flex' : 'none' }} />
```

同时保留旧 4 个容器，避免旧状态传入时无图标。

注意：

- `useMount()` 中清空 DOM 容器和 `lottie.loadAnimation()` 都要包含新增容器。
- 新增动画应保持 `loop/autoplay` 与恢复状态一致：`Reconnecting`、`ConnectingResource`、`SyncingData` 均 `loop: true, autoplay: true`。

### 8. 增加 i18n 文案

修改文件：

- `packages/core/src/config/stringkeys.interface.ts`
- `packages/i18n-lang/src/config/strings.zh-CN.json`
- `packages/i18n-lang/src/config/strings.en-US.json`

新增 key：

```ts
'sync_recovering_reconnecting': 'sync_recovering_reconnecting',
'sync_recovering_connecting_resource': 'sync_recovering_connecting_resource',
'sync_recovering_syncing_data': 'sync_recovering_syncing_data',
'sync_recovering_manual_refresh': 'sync_recovering_manual_refresh',
'network_icon_hover_connecting_resource': 'network_icon_hover_connecting_resource',
'network_icon_hover_syncing_data': 'network_icon_hover_syncing_data',
```

中文：

```json
"sync_recovering_reconnecting": "正在恢复连接...",
"sync_recovering_connecting_resource": "正在连接表格...",
"sync_recovering_syncing_data": "正在同步数据...",
"sync_recovering_manual_refresh": "连接较慢，手动刷新",
"network_icon_hover_connecting_resource": "正在连接表格",
"network_icon_hover_syncing_data": "正在同步数据"
```

英文：

```json
"sync_recovering_reconnecting": "Reconnecting...",
"sync_recovering_connecting_resource": "Connecting to datasheet...",
"sync_recovering_syncing_data": "Syncing data...",
"sync_recovering_manual_refresh": "Still waiting? Refresh",
"network_icon_hover_connecting_resource": "Connecting to datasheet",
"network_icon_hover_syncing_data": "Syncing data"
```

复用现有 key：

- `network_icon_hover_reconnection`
- `network_icon_hover_connected`

### 9. 更新调用方

修改文件：

- `packages/datasheet/src/pc/components/tab_bar/tab/tab.tsx`
- `packages/datasheet/src/pc/components/dashboard_panel/tab_bar/tab_bar.tsx`
- `packages/datasheet/src/pc/components/mirror/mirror.tsx`

修改方式：

```tsx
const { status, reason } = useNetwork(true, datasheetId!, ResourceType.Datasheet);
<NetworkStatus currentStatus={status} reason={reason} />
```

`packages/datasheet/src/pc/components/mobile_tool_bar/tool_bar_wrapper.tsx` 当前只调用 `useNetwork(...networkParams)`，不渲染 `NetworkStatus`。本次只需保证 `useNetwork` 返回值兼容，不需要修改 UI。

## 五、交互细节

### 遮罩展示

- 仅在已有可见表格画面时展示。
- 延迟 500ms 后展示。
- 状态恢复后立即隐藏。
- 遮罩层使用 `pointer-events: auto`。
- 文案使用 i18n，不硬编码中文。

### 遮罩层级

- 高于表格、视图栏和工具栏主操作区域。
- 低于右上角网络状态图标。
- 低于全局 Modal、错误弹窗、右侧展开记录弹窗。

### 可访问性

- 遮罩根节点添加 `role="status"`。
- 文案区域使用 `aria-live="polite"`。

## 六、验收标准

1. 首次打开表格时，不在 skeleton 上额外叠加同步恢复遮罩。
2. 长时间闲置切回后，如果处于 `reconnecting`，表格区域显示“正在恢复连接...”。
3. 如果 socket 已恢复但当前资源尚未 connected，显示“正在连接表格...”。
4. 如果当前资源处于 syncing，显示“正在同步数据...”。
5. 恢复完成后遮罩消失，右上角 tooltip 显示“已连接”。
6. 恢复期间旧表格内容仍可见，不出现大面积 skeleton 替换。
7. 恢复期间表格主区域点击、双击、编辑入口被遮罩拦截。
8. 遮罩显示期间，右上角网络状态图标仍可 hover 并展示 tooltip。
9. 快速恢复小于 500ms 时不闪现遮罩。
10. 遮罩显示超过 15 秒后出现手动刷新入口，且不自动刷新。
11. 右上角 `Reconnecting`、`ConnectingResource`、`SyncingData` 均使用独立动画资源。
12. 不改变现有 socket、watch、changeset、dataPack reload 行为。

## 七、建议测试

### 静态检查

```powershell
pnpm --filter @apitable/datasheet lint
```

### 手动验证

1. 首次打开表格，确认只出现原有 loading/skeleton，不出现同步恢复遮罩。
2. 打开表格，保持页面正常加载完成。
3. 切换到其他标签页或锁屏较长时间。
4. 返回表格页面。
5. 确认恢复期间旧表格仍显示。
6. 确认恢复期间出现同步恢复遮罩。
7. 确认右上角网络图标仍可 hover。
8. 确认右上角 tooltip 与实际状态一致。
9. 确认恢复完成后遮罩消失，表格可正常点击、编辑和切换视图。

### 回归验证

1. 普通短暂断网重连表现不回退。
2. Datasheet 顶部网络状态不回退。
3. Mirror 顶部网络状态不回退。
4. Dashboard 顶部网络状态不回退。
5. 移动端工具栏不因 `useNetwork` 返回值扩展而报错。
