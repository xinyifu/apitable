# Fusion API DATASHEET_CONNECTED 500 根因确认

本文记录 2026-07-01 对自部署 Fusion API `DATASHEET_CONNECTED` 500 的代码层排查结论。

关联测试记录:

```text
docs/self-host-fusion-api-test-results.md
```

## 结论

修复前运行中的 `room-server` 镜像没有包含 `e81864051` 中对 datasheet 连接状态的修复，导致服务端 Redux store 在处理 `DATASHEET_CONNECTED` action 时，`datasheetPack.connected` 子 reducer 返回 `undefined`，Redux `combineReducers` 因而抛出异常。

这与接口返回的错误完全一致:

```text
When called with an action of type "DATASHEET_CONNECTED", the slice reducer for key "connected" returned undefined.
```

2026-07-01 已使用当前源码重新构建并重启服务。当前运行的 `room-server` 镜像为:

```text
ghcr.io/xinyifu/apitable/room-server:selfhost-20260701-local
```

容器内编译产物已确认包含修复:

```text
setDatasheetConnected() 包含 payload: true
connected reducer 返回 action.payload ?? true
```

复测结果: `GET /fusion/v1/datasheets/dstAWAYYukp4GnGdch/fields` 和 `GET /fusion/v1/datasheets/dstAWAYYukp4GnGdch/records?viewId=viwVoWdmR2ErX&fieldKey=id` 均已返回 HTTP 200。

## 触发链路

失败接口:

```text
GET /fusion/v1/datasheets/{datasheetId}/fields
GET /fusion/v1/datasheets/{datasheetId}/records
POST /fusion/v1/datasheets/{datasheetId}/records
DELETE /fusion/v1/spaces/{spaceId}/datasheets/{datasheetId}/fields/{fieldId}
```

这些接口都会通过 `DataBusService.getDatasheet()` 构建 datasheet 的服务端 store:

```text
packages/room-server/src/fusion/services/fusion.api.service.ts
packages/room-server/src/fusion/services/databus/databus.service.ts
packages/room-server/src/fusion/services/databus/server.store.provider.ts
packages/room-server/src/database/command/services/command.service.ts
```

`ServerStoreProvider.createDatasheetStore()` 调用:

```text
commandService.fullFillStore(datasheetPack)
```

`fullFillStore()` 中会 dispatch:

```text
StoreActions.setDatasheetConnected(datasheetPack.datasheet.id)
```

在旧版编译产物中，`setDatasheetConnected()` 不带 `payload`:

```js
const setDatasheetConnected = (datasheetId) => {
    return {
        type: DATASHEET_CONNECTED,
        datasheetId,
    };
};
```

同时旧版 reducer 直接返回 `action.payload`:

```js
connected: (state = false, action) => {
    if (action.type === actions.DATASHEET_CONNECTED) {
        return action.payload;
    }
    return state;
}
```

因此 `DATASHEET_CONNECTED` 被 dispatch 后，`connected` reducer 返回 `undefined`，触发 Redux 的 reducer 校验错误。

## 源码状态

当前工作区源码已经包含修复。

`packages/core/src/modules/database/store/actions/resource/datasheet/datasheet.ts`:

```ts
export const setDatasheetConnected = (datasheetId: string) => {
  return {
    type: DATASHEET_CONNECTED,
    payload: true,
    datasheetId,
  };
};
```

`packages/core/src/modules/database/store/reducers/resource/datasheet/datasheet.ts`:

```ts
connected: (state = false, action) => {
  if (action.type === actions.DATASHEET_CONNECTED) {
    return action.payload ?? true;
  }
  return state;
},
```

上述两处修复来自提交:

```text
e81864051 Add self-host overrides init image
```

相对上一版本 `daec139c8` 的关键 diff:

```diff
-      return action.payload;
+      return action.payload ?? true;
```

同时 `setDatasheetConnected()` 增加:

```diff
+    payload: true,
```

## 运行环境证据

修复前运行容器:

```text
vika-local-backend-room-server-1
image: ghcr.io/xinyifu/apitable/room-server:selfhost-20260622-1
```

该镜像创建时间早于修复提交 `e81864051`。

容器内编译产物仍是旧逻辑:

```text
/app/packages/core/dist/modules/database/store/actions/resource/datasheet/datasheet.js
  setDatasheetConnected() 未包含 payload

/app/packages/core/dist/modules/database/store/reducers/resource/datasheet/datasheet.js
  connected reducer 返回 action.payload
```

这说明问题不是当前源码缺失修复，而是当前部署运行的 `room-server` 镜像没有包含当前源码中的修复。

修复后运行容器:

```text
vika-local-backend-room-server-1
image: ghcr.io/xinyifu/apitable/room-server:selfhost-20260701-local
```

同时重新构建并运行的本地镜像:

```text
ghcr.io/xinyifu/apitable/backend-server:selfhost-20260701-local
ghcr.io/xinyifu/apitable/openresty:selfhost-20260701-local
ghcr.io/xinyifu/apitable/init-db:selfhost-20260701-local
ghcr.io/xinyifu/apitable/init-selfhost-overrides:selfhost-20260701-local
ghcr.io/xinyifu/apitable/room-server:selfhost-20260701-local
ghcr.io/xinyifu/apitable/web-server:selfhost-20260701-local
```

以下辅助镜像在当前仓库 bake 清单中没有对应构建目标，仍使用上一版镜像:

```text
ghcr.io/xinyifu/apitable/databus-server:selfhost-20260622-1
ghcr.io/xinyifu/apitable/imageproxy-server:selfhost-20260622-1
ghcr.io/xinyifu/apitable/init-appdata:selfhost-20260622-1
```

构建过程中还修复了两个 Windows 工作区换行导致的镜像执行问题:

```text
packaging/Dockerfile.backend-server: 规范化 backend-server/gradlew 的 CRLF
init-selfhost-overrides/Dockerfile: 规范化 apply-overrides.sh 的 CRLF
```

## 为什么部分接口正常

以下接口不走 `DataBusService.getDatasheet()` 构建 datasheet store，因此不会触发该 reducer:

```text
GET /fusion/v1/spaces
GET /fusion/v1/spaces/{spaceId}/nodes
GET /fusion/v1/nodes/{nodeId}
GET /fusion/v1/datasheets/{datasheetId}/views
GET /fusion/v1/datasheets/{datasheetId}/attachments/presignedUrl
POST /fusion/v1/spaces/{spaceId}/datasheets
POST /fusion/v1/datasheets/{datasheetId}/attachments
```

例如 `getViewList()` 直接读取 `DatasheetMetaService.getMetaDataMaybeNull()`，不构建 databus store，所以视图接口成功。

## 修复建议

重新构建并部署包含 `e81864051` 或更新源码的 `room-server` 镜像。

部署后重点复测:

```text
GET /fusion/v1/datasheets/dstAWAYYukp4GnGdch/fields
GET /fusion/v1/datasheets/dstAWAYYukp4GnGdch/records?viewId=viwVoWdmR2ErX&fieldKey=id
POST /fusion/v1/datasheets/{testDatasheetId}/records
DELETE /fusion/v1/spaces/{spaceId}/datasheets/{testDatasheetId}/fields/{fieldId}
```

如果短期只需要验证，可以先启动包含当前源码构建产物的 `room-server:latest` 或新 tag；但正式自部署发布应重新发布明确版本 tag，避免继续使用 `selfhost-20260622-1`。
