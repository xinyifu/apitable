# 自部署 Fusion API 接口测试记录

本文记录 2026-07-01 对本地自部署维格表 Fusion API 的接口测试结果，便于后续定位 `fields` / `records` 读取异常。

代码层根因确认见:

```text
docs/self-host-fusion-api-datasheet-connected-root-cause.md
```

## 测试环境

- Base URL: `http://192.168.50.99:3000/fusion/v1`
- 测试时间: `2026-07-01`
- 测试 token: 从本地 MySQL `apitable_developer` 表读取已有 `usk` token，仅用于请求；文档不记录明文 token。
- 使用 token: 最新 token 掩码 `usk***RkSB`
- 测试方式: 先执行 GET/只读接口；后续在临时 datasheet 上补测写接口，并在测试结束后清理临时节点。
- 接口清单参考:
  - API 面板模板: `backend-server/application/src/main/resources/sysconfig/strings.json`
  - Fusion API 生成客户端: `backend-server/shared/starters/databus/src/main/java/com/apitable/starter/databus/client/api/FusionApiApi.java`

## 测试数据

销售空间:

```text
spaceId: spcD4rP8sFGL8
name: 销售
```

抽样 datasheet:

| Datasheet | 名称 | 已知 View |
| --- | --- | --- |
| `dstAWAYYukp4GnGdch` | 直营 | `viwVoWdmR2ErX` |
| `dstWs0CD9etZ8TGF25` | 代理 | 未指定 |
| `dstxZACQnjbKrA46M2` | 留言处理 | 未指定 |
| `dstAYeDD0N7wwLlUB9` | 来电记录 | 未指定 |

写接口测试使用空间:

```text
spaceId: spcMZ2kEh4aiv
name: sh station
```

说明: 当前 Fusion API 清单中没有创建空间站接口，只提供 `GET /spaces` 和 `POST /spaces/{spaceId}/datasheets`。因此写接口测试在已有空空间 `sh station` 中创建临时 datasheet 执行，测试后通过内部节点 API 清理。

## 总体结论

2026-07-01 重新使用当前源码构建并重启 Docker 服务后，原先失败的 `fields` / `records` 接口已经恢复。当前有效入口:

```text
Base URL: http://192.168.50.99:3000/fusion/v1
gateway: vika-local-backend-gateway-1, 0.0.0.0:3000->80
room-server image: ghcr.io/xinyifu/apitable/room-server:selfhost-20260701-local
```

复测结果:

| 接口 | HTTP | success | data keys / 结果 |
| --- | ---: | --- | --- |
| `GET /spaces` | 200 | true | `spaces` |
| `GET /datasheets/dstAWAYYukp4GnGdch/views` | 200 | true | `views` |
| `GET /datasheets/dstAWAYYukp4GnGdch/fields` | 200 | true | `fields` |
| `GET /datasheets/dstAWAYYukp4GnGdch/records?viewId=viwVoWdmR2ErX&fieldKey=id` | 200 | true | `total,records,pageNum,pageSize` |
| `GET /datasheets/dstAWAYYukp4GnGdch/records?pageSize=1&maxRecords=1` | 200 | true | `total,records,pageNum,pageSize` |

下面的历史测试结果保留，用于追踪修复前故障表现。

## 修复前总体结论

鉴权、空间列表、节点列表、节点详情、视图列表、附件预签名 URL、创建 datasheet、新增字段、上传附件均可用。

读取 datasheet 内容相关接口稳定失败:

```text
GET /fusion/v1/datasheets/{datasheetId}/fields
GET /fusion/v1/datasheets/{datasheetId}/records
```

失败在 4 张抽样业务表和新建临时表上均可复现，且错误消息一致。新增记录和删除字段也触发同一个异常。该问题不像单个 datasheetId、viewId 或 token 权限问题，更像服务端在连接/读取 datasheet 状态时触发 Redux reducer 异常。

## 只读接口测试结果

| 接口 | 样本 | HTTP | success | data keys / 结果 |
| --- | --- | ---: | --- | --- |
| `GET /spaces` | 全局 | 200 | true | `spaces` |
| `GET /spaces/{spaceId}/nodes` | 销售 | 200 | true | `nodes` |
| `GET /spaces/{spaceId}/nodes` | 闫冬冬 | 200 | true | `nodes` |
| `GET /spaces/{spaceId}/nodes` | 何新歌 | 200 | true | `nodes` |
| `GET /spaces/{spaceId}/nodes` | sh station | 200 | true | `nodes` |
| `GET /nodes/{nodeId}` | 直营 | 200 | true | `id,name,type,icon,isFav,permission` |
| `GET /nodes/{nodeId}` | 代理 | 200 | true | `id,name,type,icon,isFav,permission` |
| `GET /nodes/{nodeId}` | 留言处理 | 200 | true | `id,name,type,icon,isFav,permission` |
| `GET /nodes/{nodeId}` | 来电记录 | 200 | true | `id,name,type,icon,isFav,permission` |
| `GET /datasheets/{datasheetId}/views` | 直营 | 200 | true | `views` |
| `GET /datasheets/{datasheetId}/views` | 代理 | 200 | true | `views` |
| `GET /datasheets/{datasheetId}/views` | 留言处理 | 200 | true | `views` |
| `GET /datasheets/{datasheetId}/views` | 来电记录 | 200 | true | `views` |
| `GET /datasheets/{datasheetId}/attachments/presignedUrl` | 直营 | 200 | true | `results` |
| `GET /datasheets/{datasheetId}/attachments/presignedUrl` | 代理 | 200 | true | `results` |
| `GET /datasheets/{datasheetId}/attachments/presignedUrl` | 留言处理 | 200 | true | `results` |
| `GET /datasheets/{datasheetId}/attachments/presignedUrl` | 来电记录 | 200 | true | `results` |
| `GET /datasheets/{datasheetId}/fields` | 直营 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/fields` | 代理 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/fields` | 留言处理 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/fields` | 来电记录 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/records?pageSize=1&maxRecords=1` | 直营 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/records?pageSize=1&maxRecords=1` | 代理 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/records?pageSize=1&maxRecords=1` | 留言处理 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/records?pageSize=1&maxRecords=1` | 来电记录 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/records?pageSize=1&maxRecords=1&fieldKey=id` | 直营 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/records?pageSize=1&maxRecords=1&fieldKey=id` | 代理 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/records?pageSize=1&maxRecords=1&fieldKey=id` | 留言处理 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/records?pageSize=1&maxRecords=1&fieldKey=id` | 来电记录 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |
| `GET /datasheets/{datasheetId}/records?viewId=viwVoWdmR2ErX&fieldKey=id&pageSize=1&maxRecords=1` | 直营 | 500 | false | `DATASHEET_CONNECTED` reducer 异常 |

## 写接口测试结果

临时 datasheet 1:

```text
spaceId: spcMZ2kEh4aiv
datasheetId: dstmE6pfqDEyxUlaDg
name: codex-fusion-api-test-20260701-094221
cleanup: 已通过 POST /api/v1/internal/spaces/spcMZ2kEh4aiv/nodes/dstmE6pfqDEyxUlaDg/delete 清理
cleanup verify: GET /fusion/v1/nodes/dstmE6pfqDEyxUlaDg 返回 code=301 找不到指定的表格
```

临时 datasheet 2:

```text
spaceId: spcMZ2kEh4aiv
datasheetId: dst0De04ZtPcy2h4SZ
name: codex-fusion-upload-test-20260701-094519
cleanup: 已通过 POST /api/v1/internal/spaces/spcMZ2kEh4aiv/nodes/dst0De04ZtPcy2h4SZ/delete 清理
```

| 接口 | 样本 | HTTP | success | code | 结果 |
| --- | --- | ---: | --- | ---: | --- |
| `POST /spaces/{spaceId}/datasheets` | 临时表 1 | 201 | true | 200 | 创建成功，返回 `dstmE6pfqDEyxUlaDg` 和默认字段 |
| `GET /datasheets/{datasheetId}/fields` | 临时表 1 | 500 | false | 500 | `DATASHEET_CONNECTED` reducer 异常 |
| `POST /spaces/{spaceId}/datasheets/{datasheetId}/fields` | `Text` 字段，带 `property:{}` | 200 | false | 400 | 业务校验: `Text not support set property` |
| `POST /spaces/{spaceId}/datasheets/{datasheetId}/fields` | `Text` 字段，不带 `property` | 201 | true | 200 | 创建成功，返回 `fldrLSG1lPUXL` |
| `POST /datasheets/{datasheetId}/records?fieldKey=name` | 临时表 1 | 500 | false | 500 | `DATASHEET_CONNECTED` reducer 异常，未能创建记录 |
| `GET /datasheets/{datasheetId}/records?pageSize=1&maxRecords=1&fieldKey=name` | 临时表 1 | 500 | false | 500 | `DATASHEET_CONNECTED` reducer 异常 |
| `PATCH /datasheets/{datasheetId}/records?fieldKey=name` | 使用不存在的 `recordId` | 200 | false | 400 | 入口可达，业务校验: `recordId 指定的记录不存在` |
| `PUT /datasheets/{datasheetId}/records?fieldKey=name` | 使用不存在的 `recordId` | 200 | false | 400 | 入口可达，业务校验: `recordId 指定的记录不存在` |
| `DELETE /datasheets/{datasheetId}/records?recordIds=recCODExNoSuch` | 使用不存在的 `recordId` | 200 | false | 400 | 入口可达，业务校验: `recordId 指定的记录不存在` |
| `DELETE /datasheets/{datasheetId}/records?recordIds[]=recCODExNoSuch` | 使用不存在的 `recordId` | 200 | false | 400 | 入口可达，业务校验: `recordId 指定的记录不存在` |
| `DELETE /spaces/{spaceId}/datasheets/{datasheetId}/fields/{fieldId}` | 删除刚创建的 `fldrLSG1lPUXL` | 500 | false | 500 | `DATASHEET_CONNECTED` reducer 异常 |
| `POST /datasheets/{datasheetId}/executeCommand` | 空 body | 200 | false | 212 | 业务失败: `修改数据失败` |
| `POST /spaces/{spaceId}/datasheets` | 临时表 2 | 201 | true | 200 | 创建成功，返回 `dst0De04ZtPcy2h4SZ` |
| `POST /datasheets/{datasheetId}/attachments` | 上传 41B 文本文件 | 201 | true | 200 | 上传成功，返回附件 `token/name/size/mimeType/url` |

由于 `POST /records` 无法成功创建记录，本轮无法拿到真实 `recordId` 来完成真实记录的 PATCH、PUT、DELETE 闭环。使用不存在的 `recordId` 测试显示 PATCH、PUT、DELETE 记录接口本身可进入业务校验，并非一进入就触发 reducer 异常。

## 失败响应

`fields` 与 `records` 返回的响应体一致:

```json
{
  "success": false,
  "code": 500,
  "message": "When called with an action of type \"DATASHEET_CONNECTED\", the slice reducer for key \"connected\" returned undefined. To ignore an action, you must explicitly return the previous state. If you want this reducer to hold no value, you can return null instead of undefined."
}
```

## 指定接口复测

2026-07-01 使用用户指定的接口信息复测，token 仅记录掩码 `usk***RkSB`:

```text
GET /fusion/v1/datasheets/dstAWAYYukp4GnGdch/records?viewId=viwVoWdmR2ErX&fieldKey=id
```

结果:

```text
HTTP 500
success: false
code: 500
message: When called with an action of type "DATASHEET_CONNECTED", the slice reducer for key "connected" returned undefined. To ignore an action, you must explicitly return the previous state. If you want this reducer to hold no value, you can return null instead of undefined.
```

## 未覆盖接口

| 接口能力 | 状态 |
| --- | --- |
| 创建空间站 | 当前 Fusion API 清单和生成客户端中未发现创建空间站接口；未测试 |
| 真实记录 PATCH/PUT/DELETE 闭环 | `POST /records` 因 reducer 500 无法创建记录，因此没有真实 `recordId` 可用于闭环测试 |

## 复测命令

可用外部 token 复测，避免从数据库读取凭据:

```powershell
$base = 'http://192.168.50.99:3000/fusion/v1'
$token = $env:FUSION_API_TOKEN
$headers = @{ Authorization = "Bearer $token" }
Invoke-WebRequest -Uri "$base/spaces" -Headers $headers -UseBasicParsing
Invoke-WebRequest -Uri "$base/datasheets/dstAWAYYukp4GnGdch/views" -Headers $headers -UseBasicParsing
Invoke-WebRequest -Uri "$base/datasheets/dstAWAYYukp4GnGdch/fields" -Headers $headers -UseBasicParsing
Invoke-WebRequest -Uri "$base/datasheets/dstAWAYYukp4GnGdch/records?pageSize=1&maxRecords=1&fieldKey=id" -Headers $headers -UseBasicParsing
```

## 后续排查建议

优先排查 `DATASHEET_CONNECTED` action 对应的 datasheet store/reducer 注册路径，特别是 `connected` reducer 是否在服务端 Fusion/API 读取链路中缺失默认返回值。

建议从 `fields` 与 `records` 的共同依赖开始定位，因为 `views` 和 `nodes` 可用，说明基础鉴权、节点权限和视图元数据读取不是主要故障点。
