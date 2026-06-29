# 自部署同步恢复异常降级与手动刷新计时修正方案

更新时间：2026-06-28

## 一、问题定位

本地浏览器测试 `self-host-sync-recovery-p0-plan.md` 时，手动停止 `room-server` 后出现 Next.js 开发模式红框：

```text
Unhandled Runtime Error
Error: Request failed with status code 502
```

网关日志显示 502 来自本地 `room-server` 不可达：

- `/room/?...transport=websocket`
- `/notification/?...transport=websocket`
- `/nest/v1/datasheets/:dstId/dataPack`
- `/nest/v1/datasheets/:dstId/records/subscriptions`
- `/nest/v1/automation/trigger-types?lang=zh-CN`

该问题不是同步恢复遮罩本身导致，而是恢复测试期间部分 HTTP 请求收到 502 后没有被业务层降级处理，Axios error 冒泡到 React/Next dev overlay。生产环境不会显示 Next 红框，但仍可能产生未处理 Promise、异常日志或局部 UI 报错。

另一个观察到的问题是“连接超时？手动刷新”入口曾在短时间内闪现。当前实现中 `showRefresh` 的 15 秒 timer 与 `active` 同时启动，而不是从遮罩实际显示后启动；当 `Reconnecting / ConnectingResource / SyncingData` 在重连过程中快速切换时，旧 timer 有机会影响新一轮可见状态。

## 二、目标

1. 同步恢复期间，预期内的本地连接中断、502、503、504、timeout、network error 不应变成未处理 Runtime Error。
2. 保留旧表格画面和恢复遮罩，不因一次临时 dataPack 或辅助请求失败直接切到错误页。
3. “手动刷新”入口只在遮罩实际可见满 15 秒后出现。
4. 状态切换、恢复完成、组件卸载时严格清理所有 timer，避免手动刷新按钮短暂闪现。
5. 不改变 socket/watch/changeset 的核心同步行为，不吞掉权限错误、数据格式错误、业务错误码等真实问题。

## 三、代码链路核对

### 1. dataPack 请求

相关文件：

- `packages/widget-sdk/src/resource/databus/client_data_loader.ts`
- `packages/core/src/modules/database/store/actions/resource/datasheet/datasheet.ts`

现状：

- `ClientDataLoader.loadDatasheetPack()` 在 `fetchDatasheetApi()` 失败后 dispatch `datasheetErrorCode(COMMON_ERR)`，随后 `throw e`。
- `fetchDatasheet()` 和 `fetchForeignDatasheet()` 也在 catch 中 dispatch 错误码后继续 `throw e`。

风险：

- 长时间挂起恢复时，如果旧表格数据仍可见，临时 502 不应立刻变成未处理异常。
- 但首次加载时如果没有旧数据，仍应保留现有错误处理，否则页面会静默空白。

### 2. 订阅请求

相关文件：

- `packages/core/src/modules/database/store/actions/subscriptions.ts`
- `packages/core/src/engine/engine.ts`
- `packages/datasheet/src/pc/components/view/view.tsx`

现状：

```ts
const { data } = await getSubscriptions(datasheetId, mirrorId);
```

没有 catch。该请求属于“关注记录列表”的辅助数据，断线恢复期间失败可以降级为空操作，后续重连或远端变更仍可再次触发。

### 3. 自动化类型请求

相关文件：

- `packages/datasheet/src/pc/components/robot/robot_panel/hook_trigger.ts`
- `packages/datasheet/src/pc/components/robot/robot_detail/api.ts`

现状：

`queryFn` 直接 `await nestReq.get(...)`。`loadableWithDefault()` 会给 UI 默认值，但底层 query 失败仍可能参与错误上报或在开发环境暴露。

该请求属于辅助配置数据。恢复期间失败时可返回空数组并允许后续重新获取。

### 4. 恢复遮罩 timer

相关文件：

- `packages/datasheet/src/pc/components/datasheet_pane/reconnecting/sync_recovering_overlay.tsx`

现状：

```ts
const visibleTimer = window.setTimeout(() => {
  setVisible(true);
}, 500);
const refreshTimer = window.setTimeout(() => {
  setShowRefresh(true);
}, 15000);
```

问题：

- `refreshTimer` 从 `active` 开始计时，而不是从 `visible === true` 开始计时。
- `active` 期间状态变化会重复创建 timer。
- 需要把“延迟显示”和“显示后超时”拆成两个 effect。

## 四、实施方案

### 修改 1：新增同步恢复请求错误判定工具

建议新增文件：

`packages/datasheet/src/pc/utils/sync_recovering_request.ts`

职责：

- 判断 Axios error 是否属于同步恢复期间可降级的网络类错误。
- 判断当前 Redux 状态是否处于恢复态。
- 避免每个调用点重复写状态码和 URL 匹配逻辑。

建议接口：

```ts
export function isRecoveringNetworkError(error: unknown): boolean;

export function isSyncRecoveringState(state: IReduxState, resourceId?: string, resourceType?: ResourceType): boolean;

export function shouldSuppressRecoveringRequestError(
  error: unknown,
  state: IReduxState,
  options?: {
    resourceId?: string;
    resourceType?: ResourceType;
    requireExistingDatasheet?: boolean;
  },
): boolean;
```

判定规则：

- `axios.isAxiosError(error)` 为真。
- `error.response?.status` 为 `502 / 503 / 504`，或 `error.code` 为 `ECONNABORTED / ERR_NETWORK`，或 `!error.response`。
- 当前处于 `state.space.reconnecting === true`，或目标资源 `connected === false / syncing === true`。
- 对 dataPack 场景，如果 `requireExistingDatasheet === true`，必须已有非 `isPartOfData` 的旧 datasheet 数据，才允许降级。

不允许降级：

- `401 / 403 / 404 / 601` 等权限、登录、节点不存在类错误。
- 后端返回 `success: false` 的业务错误码。
- 非 Axios error、代码 bug、数据结构异常。

### 修改 2：dataPack 恢复期临时失败不继续 throw

修改文件：

- `packages/widget-sdk/src/resource/databus/client_data_loader.ts`
- `packages/core/src/modules/database/store/actions/resource/datasheet/datasheet.ts`

策略：

- 首次加载无旧数据：保持现有行为，dispatch error code 并 throw。
- 已有旧表格画面且处于同步恢复态：记录 warn，保持旧数据，不 dispatch `datasheetErrorCode(COMMON_ERR)`，返回 `null` 或调用 `failCb` 后结束。

建议伪代码：

```ts
} catch (e) {
  if (shouldSuppressRecoveringRequestError(e, getState(), {
    resourceId: datasheetId,
    resourceType: ResourceType.Datasheet,
    requireExistingDatasheet: true,
  })) {
    console.warn('[sync-recovering] suppress temporary dataPack error', e);
    return null;
  }
  dispatch(datasheetErrorCode(datasheetId, StatusCode.COMMON_ERR));
  throw e;
}
```

审查点：

- 不能在所有 dataPack 失败时返回 null，否则首次打开失败会无提示。
- 不能删除 `datasheetErrorCode` 的正常错误路径。
- `fetchDatasheetPackSuccess()` 不应收到空 responseBody。

### 修改 3：订阅请求失败降级为空操作

修改文件：

`packages/core/src/modules/database/store/actions/subscriptions.ts`

策略：

- `getSubscriptionsAction()` catch 网络恢复类错误，直接 return。
- 非恢复类错误继续 throw 或至少 console.warn 后 return，需要根据现有调用链是否期望抛错决定。

建议：

```ts
try {
  const { data } = await getSubscriptions(datasheetId, mirrorId);
  if (data?.success) {
    dispatch(setSubscriptionsAction(data.data || []));
  }
} catch (e) {
  if (shouldSuppressRecoveringRequestError(e, getState(), ...)) {
    console.warn('[sync-recovering] skip subscriptions during recovery', e);
    return;
  }
  throw e;
}
```

需要把 thunk 签名从 `(dispatch)` 改为 `(dispatch, getState)`。

### 修改 4：自动化类型请求恢复期返回默认空数组

修改文件：

`packages/datasheet/src/pc/components/robot/robot_panel/hook_trigger.ts`

策略：

- 对 `/automation/trigger-types`、`/automation/action-types` 的 queryFn 加 catch。
- 如果是恢复期网络类错误，返回 `[]`。
- 非恢复期错误继续 throw，让开发环境暴露真实问题。

注意：

- `hook_trigger.ts` 当前没有直接访问 Redux store，只能通过 `store.getState()` 或封装工具读取。
- 不建议在 Axios 全局拦截器里吞 `/nest` 502，因为会影响所有业务请求。

### 修改 5：手动刷新 timer 改为以 visible 为起点

修改文件：

`packages/datasheet/src/pc/components/datasheet_pane/reconnecting/sync_recovering_overlay.tsx`

推荐结构：

```ts
useEffect(() => {
  if (!active) {
    setVisible(false);
    return;
  }

  const visibleTimer = window.setTimeout(() => {
    setVisible(true);
  }, 500);

  return () => window.clearTimeout(visibleTimer);
}, [active, status]);

useEffect(() => {
  setShowRefresh(false);

  if (!active || !visible) {
    return;
  }

  const refreshTimer = window.setTimeout(() => {
    setShowRefresh(true);
  }, 15000);

  return () => window.clearTimeout(refreshTimer);
}, [active, visible, status]);
```

关键点：

- `showRefresh` 在每次 `status` 切换时立即重置为 false。
- `refreshTimer` 只在 `visible === true` 后启动。
- `active === false` 时同时隐藏遮罩和手动刷新按钮。
- 组件卸载时两个 effect 都能清理自己的 timer。

## 五、技术审查结论

### 可行性

方案可行，且改动应保持小范围：

- timer 修正局限在 `SyncRecoveringOverlay`。
- 订阅和自动化类型请求是辅助数据，恢复期降级风险低。
- dataPack 是核心数据，必须只在“已有旧数据可见 + 当前处于恢复态 + 网络类错误”三个条件同时满足时降级。

### 主要风险

1. **过度吞错风险**
   如果把所有 502 都吞掉，真实后端故障会被隐藏。必须绑定恢复态和请求类型。

2. **旧数据过期风险**
   dataPack 临时失败后保留旧数据是符合恢复体验的，但必须依赖后续 socket/watch 或用户刷新继续恢复。日志要保留 warn，便于定位。

3. **跨包依赖风险**
   `packages/core` 不宜直接依赖 `packages/datasheet/src/pc/utils`。如果要在 core action 中复用判断逻辑，应把纯工具放在 core 可访问的位置，或在 core 内部实现最小版本。

4. **查询缓存风险**
   automation query 返回 `[]` 后可能被缓存为成功结果。建议恢复期错误返回时设置较短 retry 或让 query 保持失败但 UI loadable 默认降级。若直接返回 `[]`，恢复后需要支持 refetch。

### 推荐落地顺序

1. 先修 `SyncRecoveringOverlay` timer，风险最低、收益明确。
2. 修 `getSubscriptionsAction()`，把恢复期 502 降级为空操作。
3. 修 automation trigger/action types，请求失败时避免 Runtime Error，同时确认恢复后能重新拉取。
4. 最后处理 dataPack catch。该点风险最高，需要配合浏览器复测首次加载、恢复期重载、真实 404/权限错误三类场景。

## 六、验证标准

1. 停止本地 `room-server` 后，页面显示同步恢复遮罩，不出现 Next.js Runtime Error 红框。
2. 停止 `room-server` 期间，网关仍可出现 502 日志，但前端不产生未处理 Promise。
3. 已有旧表格数据保持可见。
4. 500ms 内快速恢复时不显示遮罩。
5. 遮罩实际显示满 15 秒前不出现“手动刷新”。
6. `Reconnecting / ConnectingResource / SyncingData` 快速切换时，“手动刷新”不会短暂闪现。
7. 恢复 `room-server` 后，遮罩和手动刷新按钮立即消失。
8. 首次打开表格时，如果 dataPack 真实失败，仍显示原有错误态，不静默保留空页面。
9. 权限错误、节点不存在、登录失效等业务错误仍按原逻辑处理。

