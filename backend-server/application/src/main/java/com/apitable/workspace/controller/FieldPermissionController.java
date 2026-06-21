/*
 * APITable <https://github.com/apitable/apitable>
 * Copyright (C) 2022 APITable Ltd. <https://apitable.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.apitable.workspace.controller;

import static com.apitable.shared.listener.enums.FieldPermissionChangeEvent.FIELD_PERMISSION_CHANGE;
import static com.apitable.shared.listener.enums.FieldPermissionChangeEvent.FIELD_PERMISSION_ENABLE;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.apitable.control.infrastructure.ControlIdBuilder;
import com.apitable.control.infrastructure.ControlIdBuilder.ControlId;
import com.apitable.control.infrastructure.ControlTemplate;
import com.apitable.control.infrastructure.permission.NodePermission;
import com.apitable.control.service.IControlService;
import com.apitable.core.support.ResponseData;
import com.apitable.core.util.ExceptionUtil;
import com.apitable.organization.service.IMemberService;
import com.apitable.shared.cache.bean.UserSpaceDto;
import com.apitable.shared.cache.service.UserSpaceCacheService;
import com.apitable.shared.component.SocketBroadcastFactory;
import com.apitable.shared.component.scanner.annotation.ApiResource;
import com.apitable.shared.component.scanner.annotation.GetResource;
import com.apitable.shared.component.scanner.annotation.PostResource;
import com.apitable.shared.context.LoginContext;
import com.apitable.shared.context.SessionContext;
import com.apitable.shared.holder.SpaceHolder;
import com.apitable.shared.listener.enums.FieldPermissionChangeEvent;
import com.apitable.shared.listener.event.FieldPermissionEvent;
import com.apitable.shared.listener.event.FieldPermissionEvent.Arg;
import com.apitable.shared.util.page.PageInfo;
import com.apitable.shared.util.page.PageObjectParam;
import com.apitable.workspace.enums.PermissionException;
import com.apitable.workspace.ro.BatchFieldRoleDeleteRo;
import com.apitable.workspace.ro.BatchFieldRoleEditRo;
import com.apitable.workspace.ro.FieldControlProp;
import com.apitable.workspace.ro.FieldRoleCreateRo;
import com.apitable.workspace.ro.FieldRoleDeleteRo;
import com.apitable.workspace.ro.FieldRoleEditRo;
import com.apitable.workspace.service.IFieldRoleService;
import com.apitable.workspace.service.INodeService;
import com.apitable.workspace.service.INodeShareSettingService;
import com.apitable.workspace.vo.FieldCollaboratorVO;
import com.apitable.workspace.vo.FieldPermissionView;
import com.apitable.workspace.vo.FieldRoleMemberVo;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workbench - Field Permission Api.
 */
@Tag(name = "Workbench - Field Permission Api")
@RestController
@ApiResource(path = "/datasheet")
public class FieldPermissionController {

    private static final String ENABLE = "enable";

    private static final String DISABLE = "disable";

    @Resource
    private IFieldRoleService iFieldRoleService;

    @Resource
    private INodeService iNodeService;

    @Resource
    private INodeShareSettingService iNodeShareSettingService;

    @Resource
    private IMemberService iMemberService;

    @Resource
    private IControlService iControlService;

    @Resource
    private UserSpaceCacheService userSpaceCacheService;

    @Resource
    private ControlTemplate controlTemplate;

    @Resource
    private ApplicationContext applicationContext;

    /**
     * Get field permission maps.
     */
    @GetResource(path = "/field/permission", requiredLogin = false,
        requiredPermission = false)
    @Operation(summary = "Get field permission maps")
    public ResponseData<List<FieldPermissionView>> getFieldPermissionMap(
        @RequestParam(name = "dstIds") String dstIds,
        @RequestParam(name = "shareId", required = false) String shareId,
        @RequestParam(name = "userId", required = false) String userId) {
        List<String> nodeIds = StrUtil.splitTrim(dstIds, StrUtil.COMMA);
        if (CollUtil.isEmpty(nodeIds)) {
            return ResponseData.success(Collections.emptyList());
        }
        List<String> existNodeIds = iNodeService.getExistNodeIdsBySelf(nodeIds);
        if (CollUtil.isEmpty(existNodeIds)) {
            return ResponseData.success(Collections.emptyList());
        }
        String spaceId = iNodeService.getSpaceIdByNodeIds(existNodeIds);
        Long memberId = getFieldPermissionMemberId(spaceId, shareId, userId);
        List<FieldPermissionView> views = new ArrayList<>();
        for (String nodeId : existNodeIds) {
            FieldPermissionView view =
                iFieldRoleService.getFieldPermissionView(memberId, nodeId, shareId);
            if (view != null) {
                views.add(view);
            }
        }
        return ResponseData.success(views);
    }

    /**
     * Open or close field permission.
     */
    @PostResource(path = "/{dstId}/field/{fieldId}/permission/{status}",
        requiredPermission = false)
    @Operation(summary = "Open or close field permission")
    public ResponseData<Void> setFieldPermissionStatus(
        @PathVariable("dstId") String dstId,
        @PathVariable("fieldId") String fieldId,
        @PathVariable("status") String status,
        @RequestBody(required = false) Map<String, Boolean> body) {
        Long userId = checkFieldPermissionManageable(dstId);
        ControlId controlId = ControlIdBuilder.fieldId(dstId, fieldId);
        if (ENABLE.equals(status)) {
            iFieldRoleService.checkFieldPermissionBeforeEnable(dstId, fieldId);
            if (!iFieldRoleService.getFieldRoleEnabledStatus(dstId, fieldId)) {
                boolean includeExtend =
                    body != null && Boolean.TRUE.equals(body.get("includeExtend"));
                iFieldRoleService.enableFieldRole(userId, dstId, fieldId, includeExtend);
                publishFieldEvent(dstId, fieldId, FIELD_PERMISSION_ENABLE, null, null, null,
                    includeExtend);
            }
            return ResponseData.success();
        }
        if (DISABLE.equals(status)) {
            List<String> existedControlIds =
                iControlService.getExistedControlId(controlId.getControlIds());
            if (CollUtil.isNotEmpty(existedControlIds)) {
                iControlService.removeControl(userId, existedControlIds, true);
                SocketBroadcastFactory.me().fieldBroadcast(getCurrentMemberName(dstId),
                    existedControlIds);
            }
            return ResponseData.success();
        }
        throw new IllegalArgumentException("unknown field permission status");
    }

    /**
     * Add field role.
     */
    @PostResource(path = "/{dstId}/field/{fieldId}/addRole", requiredPermission = false)
    @Operation(summary = "Add field role")
    public ResponseData<Void> addRole(
        @PathVariable("dstId") String dstId,
        @PathVariable("fieldId") String fieldId,
        @RequestBody @Valid FieldRoleCreateRo data) {
        addFieldRole(dstId, fieldId, data);
        return ResponseData.success();
    }

    /**
     * Edit field role.
     */
    @PostResource(path = "/{dstId}/field/{fieldId}/editRole", requiredPermission = false)
    @Operation(summary = "Edit field role")
    public ResponseData<Void> editRole(
        @PathVariable("dstId") String dstId,
        @PathVariable("fieldId") String fieldId,
        @RequestBody @Valid FieldRoleEditRo data) {
        Long userId = checkFieldRoleOperation(dstId, fieldId);
        String controlId = ControlIdBuilder.fieldId(dstId, fieldId).toString();
        List<Long> unitIds = Collections.singletonList(data.getUnitId());
        iFieldRoleService.editFieldRole(userId, controlId, unitIds, data.getRole());
        publishFieldEvent(dstId, fieldId, FIELD_PERMISSION_CHANGE, data.getRole(), unitIds,
            null, null);
        return ResponseData.success();
    }

    /**
     * Delete field role.
     */
    @PostResource(path = "/{dstId}/field/{fieldId}/deleteRole",
        method = RequestMethod.DELETE, requiredPermission = false)
    @Operation(summary = "Delete field role")
    public ResponseData<Void> deleteRole(
        @PathVariable("dstId") String dstId,
        @PathVariable("fieldId") String fieldId,
        @RequestBody @Valid FieldRoleDeleteRo data) {
        checkFieldRoleOperation(dstId, fieldId);
        String controlId = ControlIdBuilder.fieldId(dstId, fieldId).toString();
        String role = iFieldRoleService.deleteFieldRole(controlId, dstId, data.getUnitId());
        publishFieldEvent(dstId, fieldId, FIELD_PERMISSION_CHANGE, role, null,
            Collections.singletonList(data.getUnitId()), null);
        return ResponseData.success();
    }

    /**
     * Batch edit field roles.
     */
    @PostResource(path = "/{dstId}/field/{fieldId}/batchEditRole",
        requiredPermission = false)
    @Operation(summary = "Batch edit field roles")
    public ResponseData<Void> batchEditRole(
        @PathVariable("dstId") String dstId,
        @PathVariable("fieldId") String fieldId,
        @RequestBody @Valid BatchFieldRoleEditRo data) {
        Long userId = checkFieldRoleOperation(dstId, fieldId);
        String controlId = ControlIdBuilder.fieldId(dstId, fieldId).toString();
        iFieldRoleService.editFieldRole(userId, controlId, data.getUnitIds(), data.getRole());
        publishFieldEvent(dstId, fieldId, FIELD_PERMISSION_CHANGE, data.getRole(),
            data.getUnitIds(), null, null);
        return ResponseData.success();
    }

    /**
     * Batch delete field roles.
     */
    @PostResource(path = "/{dstId}/field/{fieldId}/batchDeleteRole",
        method = RequestMethod.DELETE, requiredPermission = false)
    @Operation(summary = "Batch delete field roles")
    public ResponseData<Void> batchDeleteRole(
        @PathVariable("dstId") String dstId,
        @PathVariable("fieldId") String fieldId,
        @RequestBody @Valid BatchFieldRoleDeleteRo data) {
        checkFieldRoleOperation(dstId, fieldId);
        String controlId = ControlIdBuilder.fieldId(dstId, fieldId).toString();
        Map<String, List<Long>> roleToUnitIds =
            iFieldRoleService.deleteFieldRoles(controlId, data.getUnitIds());
        for (Map.Entry<String, List<Long>> entry : roleToUnitIds.entrySet()) {
            publishFieldEvent(dstId, fieldId, FIELD_PERMISSION_CHANGE, entry.getKey(), null,
                entry.getValue(), null);
        }
        return ResponseData.success();
    }

    /**
     * Update field role setting.
     */
    @PostResource(path = "/{dstId}/field/{fieldId}/updateRoleSetting",
        requiredPermission = false)
    @Operation(summary = "Update field role setting")
    public ResponseData<Void> updateRoleSetting(
        @PathVariable("dstId") String dstId,
        @PathVariable("fieldId") String fieldId,
        @RequestBody @Valid FieldControlProp data) {
        Long userId = checkFieldRoleOperation(dstId, fieldId);
        iFieldRoleService.updateFieldRoleProp(userId,
            ControlIdBuilder.fieldId(dstId, fieldId).toString(), data);
        return ResponseData.success();
    }

    /**
     * Query field role list.
     */
    @GetResource(path = "/{dstId}/field/{fieldId}/listRole", requiredPermission = false)
    @Operation(summary = "Query field role list")
    public ResponseData<FieldCollaboratorVO> listRole(
        @PathVariable("dstId") String dstId,
        @PathVariable("fieldId") String fieldId) {
        checkFieldPermissionManageable(dstId);
        return ResponseData.success(iFieldRoleService.getFieldRoles(dstId, fieldId));
    }

    /**
     * Query collaborator member page.
     */
    @GetResource(path = "/{dstId}/field/{fieldId}/collaborator/page",
        requiredPermission = false)
    @Operation(summary = "Query field collaborator member page")
    public ResponseData<PageInfo<FieldRoleMemberVo>> collaboratorPage(
        @PathVariable("dstId") String dstId,
        @PathVariable("fieldId") String fieldId,
        @PageObjectParam Page<FieldRoleMemberVo> page) {
        checkNodePermission(dstId, NodePermission.READ_NODE);
        return ResponseData.success(
            iFieldRoleService.getFieldRoleMembersPageInfo(page, dstId, fieldId));
    }

    /**
     * Add field role for normal and yach-compatible paths.
     */
    public void addFieldRole(String dstId, String fieldId, FieldRoleCreateRo data) {
        Long userId = checkFieldRoleOperation(dstId, fieldId);
        String controlId = ControlIdBuilder.fieldId(dstId, fieldId).toString();
        iFieldRoleService.addFieldRole(userId, controlId, data.getUnitIds(), data.getRole());
        publishFieldEvent(dstId, fieldId, FIELD_PERMISSION_CHANGE, data.getRole(),
            data.getUnitIds(), null, null);
    }

    private Long getFieldPermissionMemberId(String spaceId, String shareId, String userId) {
        if (StrUtil.isBlank(userId)) {
            userId = StrUtil.isNotBlank(shareId)
                ? StrUtil.toString(iNodeShareSettingService.getUpdatedByByShareId(shareId))
                : SessionContext.getUserId().toString();
        }
        return iMemberService.getMemberIdByUserIdAndSpaceId(Long.parseLong(userId), spaceId);
    }

    private Long checkFieldRoleOperation(String dstId, String fieldId) {
        Long userId = checkFieldPermissionManageable(dstId);
        Long memberId = userSpaceCacheService.getMemberId(userId, iNodeService.getSpaceIdByNodeId(
            dstId));
        iFieldRoleService.checkFieldHasOperation(
            ControlIdBuilder.fieldId(dstId, fieldId).toString(), memberId);
        return userId;
    }

    private Long checkFieldPermissionManageable(String dstId) {
        String spaceId = iNodeService.getSpaceIdByNodeId(dstId);
        SpaceHolder.set(spaceId);
        Long userId = SessionContext.getUserId();
        Long memberId = userSpaceCacheService.getMemberId(userId, spaceId);
        checkNodePermission(memberId, dstId, NodePermission.MANAGE_FIELD_PERMISSION);
        return userId;
    }

    private void checkNodePermission(String nodeId, NodePermission nodePermission) {
        String spaceId = iNodeService.getSpaceIdByNodeId(nodeId);
        Long userId = SessionContext.getUserId();
        Long memberId = userSpaceCacheService.getMemberId(userId, spaceId);
        checkNodePermission(memberId, nodeId, nodePermission);
    }

    private void checkNodePermission(Long memberId, String nodeId, NodePermission nodePermission) {
        controlTemplate.checkNodePermission(memberId, nodeId, nodePermission,
            status -> ExceptionUtil.isTrue(status, PermissionException.NODE_OPERATION_DENIED));
    }

    private String getCurrentMemberName(String dstId) {
        String spaceId = iNodeService.getSpaceIdByNodeId(dstId);
        UserSpaceDto userSpaceDto =
            userSpaceCacheService.getUserSpace(SessionContext.getUserId(), spaceId);
        return userSpaceDto.getMemberName();
    }

    private void publishFieldEvent(String dstId, String fieldId,
                                   FieldPermissionChangeEvent event, String role,
                                   List<Long> changedUnitIds, List<Long> delUnitIds,
                                   Boolean includeExtend) {
        UserSpaceDto userSpaceDto =
            userSpaceCacheService.getUserSpace(SessionContext.getUserId(),
                iNodeService.getSpaceIdByNodeId(dstId));
        Arg arg = Arg.builder()
            .event(event)
            .datasheetId(dstId)
            .fieldId(fieldId)
            .uuid(LoginContext.me().getLoginUser().getUuid())
            .operator(userSpaceDto.getMemberName())
            .role(role)
            .changedUnitIds(changedUnitIds)
            .delUnitIds(delUnitIds)
            .includeExtend(includeExtend)
            .build();
        applicationContext.publishEvent(new FieldPermissionEvent(this, arg));
    }
}
