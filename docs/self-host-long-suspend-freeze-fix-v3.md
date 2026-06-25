# 最终实施方案 v3：生产环境长时间挂起返回卡死问题修复

> v3 基于 v2 修正两个关键并发问题：`watch()` 不能“跳过即成功”，旧 `RoomService` 的异步 `watch()` 结果不能在切表后继续产生副作用。Socket.IO 语义参考：[Client Socket Instance](https://socket.io/docs/v3/client-socket-instance/)。

## 核心调整

| 问题 | v3 处理 |
|---|---|
| 多次 `disconnect` 叠加局部 `setInterval` | 改为单个实例级 `setTimeout` 链式重连 |
| 并发 `watch()` 跳过导致调用方误判完成 | `RoomService.watch()` 改为 single-flight Promise，后续调用复用并等待同一个 Promise |
| 旧 room 在切表后继续执行 `bindSocketMessage()` 等副作用 | `RoomService` 增加 `disposed/watchEpoch`，`leaveRoom()` 后旧 watch 结果自动失效 |
| `LEAVE_ROOM` ACK 不返回卡住切表 | `IO.unWatch()` 同步 `offAll()`，并加 3 秒 ACK 超时 |
| `waitPrepareComplete()` 轮询和失败悬挂 | 改 resolver 队列，失败后保存错误并立即 reject |
| 重连状态在初始化路径残留 | `switchResource()` 成功初始化房间后兜底清除 `reconnecting` |
| 重连成功后本地缓冲操作同步延迟 | 断线发送短路时释放 `roomIOClear`，watch 成功后主动 `nextSend()` |
| 手动重连固定节奏形成波峰 | capped backoff 上限调整为 15 秒，并加入随机 jitter |
| 网络图标状态源不完整 | 补充资源级断开/同步状态清理，必要时调整 `useNetwork` 优先级 |

## 修改 1：`ResourceService` 重连机制

文件：`packages/widget-sdk/src/resource/service.ts`

新增字段：

```ts
private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
```

`destroy()` 清理定时器：

```ts
if (this.reconnectTimer) {
  clearTimeout(this.reconnectTimer);
  this.reconnectTimer = null;
}
```

`disconnect/connect` 逻辑：

```ts
socket.on('disconnect', (reason: any) => {
  console.warn('! socket disconnect, reason:', reason);

  this.store.dispatch(StoreActions.setReconnecting(true));
  this.roomService?.setConnected(false);

  if (this.reconnectTimer) {
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
  }

  if (reason === 'io server disconnect') {
    this.scheduleReconnect(socket, 1);
  }
});

socket.on('connect', () => {
  this.reportSocketError = true;
  this.store.dispatch(StoreActions.setConnected(true));

  if (this.reconnectTimer) {
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
  }

  if (this.store.getState().space.reconnecting && this.roomService) {
    void this.safeWatch();
  }
});
```

新增方法：

```ts
private scheduleReconnect(socket: SocketIOClient.Socket, attempt: number) {
  const MAX_DELAY = 15_000;
  const baseDelay = Math.min(RECONNECT_DELAY * Math.pow(2, attempt - 1), MAX_DELAY);
  const jitter = 0.8 + Math.random() * 0.4;
  const delay = Math.round(baseDelay * jitter);

  this.reconnectTimer = setTimeout(() => {
    if (socket.connected) {
      return;
    }
    console.warn(`[ResourceService] reconnect attempt ${attempt}`);
    socket.connect();
    this.scheduleReconnect(socket, attempt + 1);
  }, delay);
}

private async safeWatch() {
  const roomSnapshot = this.roomService;

  try {
    const applied = await roomSnapshot.watch();

    if (this.roomService !== roomSnapshot || !applied) {
      console.warn('[ResourceService] reconnect watch ignored because room changed or watch was canceled');
      return;
    }

    this.store.dispatch(StoreActions.setReconnecting(false));
    console.log('[ResourceService] reconnected and room watched');
  } catch (err) {
    console.error('[ResourceService] watch after reconnect failed:', err);
  }
}
```

注意：`MAX_DELAY` 不建议继续使用 30 秒。自托管部署通常用户规模较小，30 秒会让网络恢复后的主观等待过长。建议用 15 秒作为体验和服务端保护的折中值，并加入 0.8-1.2 的随机 jitter，避免服务端重启后大量客户端按固定 2/4/8/15 秒节奏同时冲击 room-server。

`switchResource()` 成功初始化房间后兜底清除重连状态，避免 `connect` 时没有可用 `roomService` 或被正常切表流程接管后，UI 长时间停留在“重连中”。

```ts
async switchResource(params: { from?: string; to: string; resourceType: ResourceType; extra?: { [key: string]: any } }) {
  const { to, resourceType, extra } = params;
  const allowSwitchRoom = this.allowSwitchRoom();

  allowSwitchRoom && (await this.switchRoom(to));
  await this.fetchResource(to, resourceType, false, extra);
  this.createUndoManager(to);
  allowSwitchRoom && (await this.roomService.init(this.firstRoomInit));

  this.store.dispatch(StoreActions.setReconnecting(false));
  this.firstRoomInit = false;
}
```

## 修改 2：`RoomService.watch()` single-flight + 失效保护

文件：`packages/core/src/sync/room.ts`

新增字段：

```ts
private watchingPromise?: Promise<boolean>;
private watchEpoch = 0;
private disposed = false;
```

`watch()` 改为复用同一个 Promise：

```ts
@errorCapture<RoomService>()
async watch(): Promise<boolean> {
  if (this.watchingPromise) {
    return this.watchingPromise;
  }

  const epoch = this.watchEpoch;
  const promise = this.doWatch(epoch);

  this.watchingPromise = promise;

  promise.finally(() => {
    if (this.watchingPromise === promise) {
      this.watchingPromise = undefined;
    }
  });

  return promise;
}
```

注意：不能把 `promise.finally(...)` 的返回值赋给 `this.watchingPromise`。`finally()` 会返回新的 Promise 实例，后续 `this.watchingPromise === promise` 会永远不成立，导致 single-flight 缓存无法被清理。

新增内部方法，所有副作用前检查是否失效：

```ts
private isWatchStale(epoch: number) {
  return this.disposed || this.watchEpoch !== epoch;
}

private async doWatch(epoch: number): Promise<boolean> {
  const state = this.store.getState();
  const shareId = state.pageParams.shareId;
  const embedId = state.pageParams.embedId;

  const watchResponse = await this.io.watch<IWatchResponse, any>(this.roomId, shareId, embedId).catch((e) => {
    throw new EnhanceError(e);
  });

  if (this.isWatchStale(epoch) || !watchResponse) {
    return false;
  }

  const { resourceRevisions, collaborators } = watchResponse.data!;
  const collaEngine = this.collaEngineMap.get(this.roomId);
  if (!collaEngine) {
    return false;
  }

  await this.checkVersion(resourceRevisions);

  if (this.isWatchStale(epoch)) {
    return false;
  }

  const resourceType = collaEngine.resourceType;
  this.setConnected(true);
  this.store.dispatch(roomInfoSync(this.roomId, resourceType, collaborators || []));
  this.store.dispatch(setResourceConnect(this.roomId, resourceType));
  this.loadFieldPermissionMap();
  this.bindSocketMessage();
  this.setSendingWatcher();
  this.event.setRoomIOClear(true);
  this.nextSend();

  return true;
}
```

注意：重连成功后主动调用 `nextSend()` 是体验优化，用于立即发送断线期间积累在内存中的 `opBuffer/localPendingChangeset`。但仅加这一行不够：如果断线期间 `syncOperations()` 已经触发过发送，`sendUserChanges()` 会先把 `roomIOClear` 设为 `false`，再因 `!connected` 直接返回，导致后续 `nextSend()` 被 `getRoomIOClear()` 拦住。因此 watch 成功后需要先确认本轮 room 已恢复可发送状态，再调用 `nextSend()`。

同时建议修正断线发送短路分支，避免 `roomIOClear` 长时间卡在 `false`：

```ts
private sendUserChanges = (changesets: ILocalChangeset[]) => {
  console.log('Submission data: ', changesets, changesets.length);
  this.event.setRoomIOClear(false);
  this.event.setRoomLastSendTime();
  if (!this.connected) {
    this.event.setRoomIOClear(true);
    console.error("room has been destroy,can't send anything");
    return;
  }

  // 后续请求发送逻辑保持不变
};
```

发送失败路径也需要保证 `syncing` 状态被清理。现有代码在发送开始 500ms 后会 dispatch `changeResourceSyncingStatus(..., true)`，但失败 catch 中不一定会 dispatch `false`。如果请求失败或 fail-fast 退出，右上角网络图标可能残留在“同步中”。

建议在 `sendUserChanges().catch()` 中补充：

```ts
.catch(async (e) => {
  this.event.setRoomIOClear(true);
  clearTimeout(timer);
  this.store.dispatch(changeResourceSyncingStatus(this.roomId, resourceType, false));

  let errMsg = e;
  if (!('success' in errMsg)) {
    errMsg = {
      success: false,
      code: 0,
      message: t(Strings.exception_network_exception),
    };
  }
  await this.handleRejectCommit(errMsg);
  return Promise.reject();
});
```

`leaveRoom()` 使旧 watch 失效：

```ts
async leaveRoom() {
  this.disposed = true;
  this.watchEpoch++;
  this.setConnected(false);

  await this.unwatch().catch((e) => {
    console.warn('[RoomService] leaveRoom unwatch error (non-fatal):', e);
  });

  this.clearSendingWatcher();
  return this.collaEngineMap;
}
```

## 修改 3：`RoomService.init()` 失败策略

不要笼统吞掉 `waitPrepareComplete()` 错误后继续 `watch()`。更稳妥的处理是：准备失败时走错误通道并停止本次初始化。

```ts
async init(firstRoomInit = false) {
  const hasCollaEngine = () => this.collaEngineMap.has(this.roomId);

  do {
    await this.nextTick();
  } while (!hasCollaEngine());

  firstRoomInit && this.sendLocalChangesetWithInit();

  await Promise.all(
    Array.from(this.collaEngineMap.keys()).map((resourceId) => {
      const collaEngine = this.collaEngineMap.get(resourceId)!;
      return collaEngine.waitPrepareComplete();
    })
  );

  await this.watch();
}
```

如果确实要兼容“本地冲突已清理后继续进入房间”，需要先给 `prepare()` 错误分类，否则不要继续协同。

## 修改 4：`IO.unWatch()` 同步解绑 + 超时

文件：`packages/core/src/io/io.ts`

```ts
private static readonly LEAVE_ROOM_TIMEOUT = 3000;

unWatch() {
  this.abort = true;
  clearInterval(this.watchRoomRetryInterval);
  this.watchRoomRetryInterval = undefined;

  this.offAll();

  return new Promise((resolve) => {
    if (!this.socket.connected) {
      resolve(undefined);
      return;
    }

    let resolved = false;
    const timer = setTimeout(() => {
      if (resolved) {
        return;
      }
      resolved = true;
      console.warn('[IO] LEAVE_ROOM ACK timeout for', this.roomId);
      resolve(undefined);
    }, IO.LEAVE_ROOM_TIMEOUT);

    this.socket.emit(SyncRequestTypes.LEAVE_ROOM, { roomId: this.roomId }, (msg: any) => {
      if (resolved) {
        return;
      }
      resolved = true;
      clearTimeout(timer);
      console.log('[IO] unwatch confirmed by server:', this.roomId);
      resolve(msg);
    });
  });
}
```

## 修改 5：`Engine.waitPrepareComplete()`

文件：`packages/core/src/engine/engine.ts`

```ts
private prepared = false;
private prepareError: any = null;
private prepareResolvers: Array<{
  resolve: (v: boolean) => void;
  reject: (e: any) => void;
}> = [];
```

`prepare()` 成功或失败时释放等待者：

```ts
async prepare(checkVersion?: number) {
  this.prepared = false;
  this.prepareError = null;

  try {
    await this.checkLocalDiffChanges(checkVersion);
    this.prepared = true;
    this.prepareResolvers.forEach(({ resolve }) => resolve(true));
    this.prepareResolvers = [];
  } catch (error) {
    this.prepareError = error;
    this.prepareResolvers.forEach(({ reject }) => reject(error));
    this.prepareResolvers = [];

    Player.doTrigger(Events.app_error_logger, { error });
    this.bufferStorage.clearLocalPendingChangeset();
    this.bufferStorage.clearOpBuffer();

    this.event.onError?.({
      type: ErrorType.CollaError,
      code: ErrorCode.EngineCreateFailed,
      message: t(Strings.local_data_conflict),
      modalType: ModalType.Info,
    });
  }
}
```

注意：`prepared/prepareError` 必须在 `prepare()` 同步入口处重置。`prepare()` 可能在 engine 生命周期中被多次调用，如果保留上一轮错误，新的 `waitPrepareComplete()` 调用会被旧 `prepareError` 提前 reject，而不会等待当前这轮 prepare 的结果。

`waitPrepareComplete()`：

```ts
waitPrepareComplete() {
  if (this.prepared) {
    return Promise.resolve(true);
  }
  if (this.prepareError) {
    return Promise.reject(this.prepareError);
  }
  return new Promise<boolean>((resolve, reject) => {
    this.prepareResolvers.push({ resolve, reject });
  });
}
```

## 修改 6：网络状态图标与资源状态修正

文件：

- `packages/datasheet/src/pc/hooks/use_network.ts`
- `packages/core/src/modules/database/store/actions/resource/index.ts`
- `packages/core/src/modules/database/store/reducers/resource/*`

右上角网络状态图标不是只看 `space.connected`。当前 `useNetwork()` 读取的是资源级 `resourceNetwork.connected/syncing` 和 `space.reconnecting`：

```ts
const { syncing, connected } = useAppSelector((state) => {
  const resourceNetwork = Selectors.getResourceNetworking(state, resourceId, resourceType);
  return {
    syncing: resourceNetwork.syncing,
    connected: resourceNetwork.connected,
  };
});
const { reconnecting: IOConnecting } = useAppSelector((state) => state.space);
```

因此不能假设 `StoreActions.setConnected(false)` 会让右上角图标自动变成离线。资源级 reducer 目前只有 `*_CONNECTED` 置 `true` 的动作，没有通用置 `false` 的动作。

建议补充资源级连接状态 action，例如：

```ts
export const setResourceConnectedStatus = (
  resourceId: string,
  resourceType: ResourceType,
  connected: boolean,
) => {
  return {
    type: SET_RESOURCE_CONNECTED_STATUS,
    resourceId,
    resourceType,
    payload: connected,
  };
};
```

然后在对应 datasheet/dashboard/form/mirror reducer 中按 `resourceType + resourceId` 更新 `connected`。重连成功后仍可继续使用现有 `setResourceConnect(...)` 或统一迁移到新 action。

断开与离房时建议补充：

```ts
// socket disconnect 或 leaveRoom 时
this.store.dispatch(setResourceConnectedStatus(this.roomId, resourceType, false));
this.store.dispatch(changeResourceSyncingStatus(this.roomId, resourceType, false));
```

如果希望断网重连期间图标优先显示“正在重连”而不是“离线”，需要调整 `useNetwork()` 判断顺序，让 `IOConnecting` 优先于 `!connected`：

```ts
if (IOConnecting) {
  setStatus(Network.Loading);
  return;
}
if (!connected) {
  setStatus(Network.Offline);
  return;
}
if (syncing) {
  setStatus(Network.Sync);
  return;
}
setStatus(Network.Online);
```

这样状态含义更清晰：

- socket 断开且正在重连：Loading
- socket 不在重连且资源未连接：Offline
- 资源已连接但有本地发送中请求：Sync
- watch 成功且无发送中请求：Online

## 验证重点

新增单测应覆盖：

- 连续多次 `disconnect` 后一次 `connect`，`roomService.watch()` 只执行一次。
- 两个并发 `watch()` 返回同一个 single-flight 结果，不出现“第二个调用直接成功”。
- `leaveRoom()` 后旧 `watch()` ACK 返回，不会执行 `bindSocketMessage()`、`setSendingWatcher()`、`roomInfoSync()`。
- `IO.unWatch()` 在断线、ACK 正常、ACK 超时三种情况下都能 resolve。
- `prepare()` 失败后，`waitPrepareComplete()` 当前等待者和后续等待者都会 reject。
- `prepare()` 失败后再次启动新一轮 `prepare()`，新等待者不会被旧 `prepareError` 提前 reject。
- `connect` 事件发生时 `roomService` 尚不可用或被切表流程接管，`switchResource()` 完成后 `space.reconnecting` 会被清理。
- 高频快速切换多个 datasheet，旧 room 的 `unWatch` 超时和 `isWatchStale()` 都不会影响新 room 的 watch。
- 初始化 prepare 过程中断网，`init()` 能 reject 并走错误提示，不会无声卡死。
- 断线期间编辑产生本地缓冲操作，重连 watch 成功后无需等待 60 秒 force send，能够立即触发发送。
- 断线期间 `sendUserChanges()` 因 `!connected` 短路返回后，`roomIOClear` 不会残留为 `false`。
- `sendUserChanges()` 请求失败或 fail-fast 退出后，资源级 `syncing` 不会残留为 `true`。
- socket disconnect / leaveRoom 后资源级 `connected` 能置为 `false`，右上角图标不会因旧资源状态继续显示 Online。
- `space.reconnecting=true` 且资源级 `connected=false` 时，`useNetwork()` 按预期显示 Loading 或 Offline，取决于最终确认的产品语义。
- 大量客户端同时经历 `io server disconnect` 时，手动重连 delay 带 jitter，不会在固定时间点形成同步重连波峰。
