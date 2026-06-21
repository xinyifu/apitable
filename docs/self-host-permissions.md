# 本地自部署权限管理

本文记录 APITable/AITable 本地自部署场景下的节点权限、字段权限、权限接口、数据库表和本地修改说明。

权限内容最初记录在 `docs/self-host-community-limits.md`，现已从社区版限制文档中拆出，方便后续单独维护。

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

### 2026-06-21 字段权限前端修复记录

本地自部署 fork 仅针对自部署，不再把 SaaS/企业版计费能力作为字段权限前端操作的硬阻断。

本轮修复的前端问题：

1. 点击列权限时，`Selectors.getFieldPermissionMap` 可能为 `null`，直接读取 `fieldPermission[field.id]` 会触发 Next Runtime Error。
2. 在社区/自部署包里，`enterprise/billing` 可能不提供完整 `SubscribeUsageTipType`，直接读取 `SubscribeUsageTipType.Alert` 会触发 Runtime Error。
3. 字段权限接口返回 HTTP 403/500 或 axios reject 时，原组件没有捕获异常，前端会显示未处理错误遮罩，而不是给用户反馈。

已修改的前端文件：

```text
packages/datasheet/src/pc/components/field_permission/disabled_field_permission.tsx
packages/datasheet/src/pc/components/field_permission/enable_field_permission.tsx
packages/datasheet/src/pc/components/field_permission/enable_field_permission_plus.tsx
```

关键处理：

- 字段权限 map 改为可空读取：`fieldPermission?.[field.id]`。
- 自部署下计费用量提示改为可选调用：`triggerUsageAlert?.(...)`、`SubscribeUsageTipType?.Alert`。
- 字段权限新增、编辑、删除、批量编辑、恢复默认、开关表单写入权限等接口异常统一转成 `Message.warning`，避免开发环境 Runtime Error 遮罩。

浏览器验证：

```text
URL: http://127.0.0.1:3000/workbench/dstnbR4FlXGA8ik3uZ/viwPeX6vefMPh
fieldId: fldyM4uvWdxwR
fieldName: 平台
groupUnitId: 2068519346484842503
groupName: codex-row-20260621102017 station
```

验证结果：

1. 前端从表头菜单进入 `设置列权限 (Beta)`，弹窗正常打开。
2. 小组权限从 `editor` 切到 `reader`，页面显示 `操作成功`，接口 `listRole` 确认小组角色为 `reader`。
3. 重新从 `reader` 切回 `editor`，页面显示 `操作成功`，接口 `listRole` 确认小组角色为 `editor`。
4. 上述两次操作均未出现 `Unhandled Runtime Error`。
5. 第二次验证没有新增控制台错误；第一次验证只出现 Ant Design Tooltip deprecated warning，和权限逻辑无关。
