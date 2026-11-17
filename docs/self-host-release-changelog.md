# 自部署版本更新日志

本文只记录自部署发布、部署配置、`.env` 和 `docker-compose.yaml` 需要同步的变更。功能方案、代码实现细节仍放在对应专题文档中。

## 使用约定

每次发布 `selfhost-*` tag 前，先补充本文件：

- 新增、删除或改名的镜像。
- `.env` 新增、删除或默认值变化的变量。
- `docker-compose.yaml` 服务、依赖顺序、端口、卷、healthcheck 变化。
- 需要手动执行的数据修复或迁移验证命令。
- mirror upstream 镜像的 source tag/digest 变化。

## 未发布

### 开发分支回补发布配置（2026-11-17）

将 `codex/ghcr-selfhost-actions` 中已有的限额、运行配置和通用构建改进回补到 `codex/self-host-limits-permissions`，保留开发文档；GHCR 发布工作流仍由发布分支维护。

- 默认限额统一为席位 1000、文件节点 1 万、单表行数 10 万、空间总行数 1 亿，详见 `docs/self-host-community-limits.md`。
- `.env` 增加 `MYSQL_LOWER_CASE_TABLE_NAMES=0`，`docker-compose.yaml` 使用 `${MYSQL_LOWER_CASE_TABLE_NAMES:-2}` 传入 MySQL；已有数据库应通过部署环境保留初始化时使用的值。
- `.env.ghcr-local` 的 `SERVER_DOMAIN` 改为 `http://127.0.0.1:3000`，与网关端口和 `PUBLIC_URL` 一致。
- 本地配置中的 databus-server、imageproxy-server、init-appdata 镜像标签从 `selfhost-20260622-1` 同步到 `selfhost-20260625-2`；新增 `scripts/selfhost/set-image-tag.sh`，用于统一修改本地镜像标签。
- 回补 Docker 构建上下文排除规则、Gradle/pnpm 缓存、frozen lockfile 配置、锁文件声明元数据修正和 PM2 版本固定。

本次仅回补源码和配置，未重新发布镜像，也未执行构建或运行验证。

### 新增 init-selfhost-overrides

目的：

- 在 upstream `init-appdata` 执行后，作为自部署 fork 的最终数据库元数据覆盖层。
- 避免 fork 自定义 automation、feature flag、system config 等元数据被 upstream 初始化数据覆盖。

新增镜像：

```env
IMAGE_SELFHOST_OVERRIDES=apitable/init-selfhost-overrides:<selfhost-tag>
```

新增构建目标：

```text
docker-bake.hcl
  init-selfhost-overrides
```

`docker-compose.yaml` 顺序调整：

```text
mysql healthy
  -> init-db
    -> init-appdata
      -> init-selfhost-overrides
        -> backend-server / room-server / web-server / databus-server / gateway
```

当前覆盖内容：

- 恢复并固定自部署新增的企业微信自动化服务和动作类型：
  - `automation_service`: `asvWecom / wecom`
  - `automation_action_type`: `aatSendWecomMsg / sendWecomMsg`

部署配置需要同步：

```env
IMAGE_SELFHOST_OVERRIDES=apitable/init-selfhost-overrides:<selfhost-tag>
```

发布 workflow 后续需要同步：

- 构建并推送 `init-selfhost-overrides`。
- 发布摘要输出 `IMAGE_SELFHOST_OVERRIDES`。
- 正式 `.env` 模板加入 `IMAGE_SELFHOST_OVERRIDES`。

## selfhost-20260625-1

主要更新：

- 合入企业微信自动化动作支持。
- 修复浏览器长时间挂起返回后的重连卡死、重复监听和同步状态残留问题。
- 增强时光机操作记录、预览退出和记录定位体验。
- 启用自托管默认权限、本地 GHCR 配置与数据库变更脚本。

镜像 tag：

```env
IMAGE_BACKEND_SERVER=apitable/backend-server:selfhost-20260625-1
IMAGE_GATEWAY=apitable/openresty:selfhost-20260625-1
IMAGE_INIT_DB=apitable/init-db:selfhost-20260625-1
IMAGE_ROOM_SERVER=apitable/room-server:selfhost-20260625-1
IMAGE_WEB_SERVER=apitable/web-server:selfhost-20260625-1
IMAGE_DATABUS_SERVER=apitable/databus-server:selfhost-20260625-1
IMAGE_IMAGEPROXY_SERVER=apitable/imageproxy-server:selfhost-20260625-1
IMAGE_INIT_APPDATA=apitable/init-appdata:selfhost-20260625-1
```

`.env` 新增或需要确认的变量：

```env
FIELD_PERMISSION_VISIBLE=true
FILE_PERMISSION_VISIBLE=true
SPACE_PERMISSION_OVERVIEW_VISIBLE=true
SPACE_ROLE_VISIBLE=true
CONTACTS_MODAL_BULK_IMPORT_VISIBLE=true
REGENERATE_API_TOKEN_VISIBLE=true
CHANGE_SPACE_ADMIN_VISIBLE=true
TIME_MACHINE_VISIBLE=true
MYSQL_LOWER_CASE_TABLE_NAMES=2
```

`docker-compose.yaml` 变化：

- MySQL 启动参数改为可配置：

```yaml
--lower_case_table_names=${MYSQL_LOWER_CASE_TABLE_NAMES:-2}
```

部署注意：

- `init-db:selfhost-20260625-1` 包含企业微信动作 Liquibase 脚本。
- 如果数据库里已有软删除企业微信记录，旧 changeset 不会自动恢复，需要 `init-selfhost-overrides` 或手动 SQL 恢复。

手动恢复 SQL：

```sql
UPDATE apitable_automation_service
SET is_deleted = 0
WHERE service_id = 'asvWecom' OR slug = 'wecom';

UPDATE apitable_automation_action_type
SET is_deleted = 0
WHERE action_type_id = 'aatSendWecomMsg' OR endpoint = 'sendWecomMsg';
```

## selfhost-20260622-1

主要更新：

- 新增自部署 GHCR 镜像发布流程。
- 调整社区版自部署默认限制和部分权限入口。
- 生成第一版自部署 GHCR `.env` / compose 配置基础。

构建镜像：

```text
backend-server
room-server
web-server
init-db
openresty
```

mirror upstream 镜像：

```text
databus-server
imageproxy-server
init-appdata
```

风险说明：

- `init-appdata` 来源是 upstream runtime 镜像，不是 fork 构建产物。
- 它会加载初始化数据，可能覆盖 fork 自定义元数据。
- 后续用 `init-selfhost-overrides` 作为最终覆盖层处理。

## 后续版本模板

## selfhost-YYYYMMDD-N

主要更新：

- 

镜像 tag：

```env
IMAGE_BACKEND_SERVER=apitable/backend-server:selfhost-YYYYMMDD-N
IMAGE_GATEWAY=apitable/openresty:selfhost-YYYYMMDD-N
IMAGE_INIT_DB=apitable/init-db:selfhost-YYYYMMDD-N
IMAGE_SELFHOST_OVERRIDES=apitable/init-selfhost-overrides:selfhost-YYYYMMDD-N
IMAGE_ROOM_SERVER=apitable/room-server:selfhost-YYYYMMDD-N
IMAGE_WEB_SERVER=apitable/web-server:selfhost-YYYYMMDD-N
IMAGE_DATABUS_SERVER=apitable/databus-server:selfhost-YYYYMMDD-N
IMAGE_IMAGEPROXY_SERVER=apitable/imageproxy-server:selfhost-YYYYMMDD-N
IMAGE_INIT_APPDATA=apitable/init-appdata:selfhost-YYYYMMDD-N
```

`.env` 变化：

```env
# ADD / CHANGE / REMOVE
```

`docker-compose.yaml` 变化：

```text
# services / depends_on / volumes / ports / healthcheck
```

数据库与初始化：

```text
# init-db changelog
# init-appdata 注意事项
# init-selfhost-overrides SQL
```

验证命令：

```powershell
docker compose --env-file .env -f docker-compose.yaml config
docker compose --env-file .env -f docker-compose.yaml run --rm init-selfhost-overrides
```
