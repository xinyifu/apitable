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

import cn.hutool.core.collection.CollUtil;
import com.apitable.control.infrastructure.ControlTemplate;
import com.apitable.control.infrastructure.permission.NodePermission;
import com.apitable.core.support.ResponseData;
import com.apitable.core.util.ExceptionUtil;
import com.apitable.organization.service.IOrganizationService;
import com.apitable.organization.vo.UnitMemberVo;
import com.apitable.shared.cache.service.UserSpaceCacheService;
import com.apitable.shared.component.scanner.annotation.ApiResource;
import com.apitable.shared.component.scanner.annotation.GetResource;
import com.apitable.shared.component.scanner.annotation.PostResource;
import com.apitable.shared.context.SessionContext;
import com.apitable.shared.holder.SpaceHolder;
import com.apitable.shared.util.page.PageInfo;
import com.apitable.shared.util.page.PageObjectParam;
import com.apitable.space.service.ISpaceRoleService;
import com.apitable.workspace.enums.PermissionException;
import com.apitable.workspace.ro.AddNodeRoleRo;
import com.apitable.workspace.ro.BatchDeleteNodeRoleRo;
import com.apitable.workspace.ro.BatchModifyNodeRoleRo;
import com.apitable.workspace.ro.DeleteNodeRoleRo;
import com.apitable.workspace.ro.ModifyNodeRoleRo;
import com.apitable.workspace.service.INodeRoleService;
import com.apitable.workspace.service.INodeService;
import com.apitable.workspace.vo.NodeCollaboratorsVo;
import com.apitable.workspace.vo.NodeRoleMemberVo;
import com.apitable.workspace.vo.NodeRoleUnit;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workbench - Node Role Api.
 */
@Tag(name = "Workbench - Node Role Api")
@RestController
@ApiResource(path = "/node")
public class NodeRoleController {

    @Resource
    private INodeRoleService iNodeRoleService;

    @Resource
    private INodeService iNodeService;

    @Resource
    private ISpaceRoleService iSpaceRoleService;

    @Resource
    private IOrganizationService iOrganizationService;

    @Resource
    private UserSpaceCacheService userSpaceCacheService;

    @Resource
    private ControlTemplate controlTemplate;

    /**
     * Query node role list.
     */
    @GetResource(path = "/listRole", requiredPermission = false)
    @Operation(summary = "Query node role list")
    public ResponseData<NodeCollaboratorsVo> listRole(
        @RequestParam(name = "nodeId") String nodeId,
        @RequestParam(name = "includeAdmin", required = false) Boolean includeAdmin,
        @RequestParam(name = "includeExtend", required = false) Boolean includeExtend,
        @RequestParam(name = "includeSelf", required = false) String includeSelf) {
        String spaceId = iNodeService.getSpaceIdByNodeId(nodeId);
        SpaceHolder.set(spaceId);
        Long userId = SessionContext.getUserId();
        Long memberId = userSpaceCacheService.getMemberId(userId, spaceId);
        checkNodePermission(memberId, nodeId, NodePermission.READ_NODE);

        boolean assignMode = iNodeRoleService.getNodeRoleIfEnabled(nodeId);
        String controlNodeId = assignMode ? nodeId : iNodeRoleService.getNodeExtendNodeId(nodeId);

        NodeCollaboratorsVo vo = new NodeCollaboratorsVo();
        vo.setExtend(!assignMode);
        vo.setAdmins(getAdmins(spaceId, includeAdmin));
        vo.setOwner(controlNodeId == null ? null : iNodeRoleService.getNodeOwner(controlNodeId));
        vo.setSelf(getSelf(spaceId, memberId, includeSelf));
        vo.setRoleUnits(getRoleUnits(spaceId, controlNodeId, assignMode, includeExtend));
        vo.setMembers(Collections.emptyList());
        vo.setExtendNodeName(
            !assignMode && controlNodeId != null ? iNodeService.getNodeNameByNodeId(controlNodeId)
                : null);
        vo.setBelongRootFolder(isBelongRootFolder(spaceId, nodeId));
        return ResponseData.success(vo);
    }

    /**
     * Query collaborator member page.
     */
    @GetResource(path = "/collaborator/page", requiredPermission = false)
    @Operation(summary = "Query collaborator member page")
    public ResponseData<PageInfo<NodeRoleMemberVo>> collaboratorPage(
        @RequestParam(name = "nodeId") String nodeId,
        @PageObjectParam Page<NodeRoleMemberVo> page) {
        String spaceId = iNodeService.getSpaceIdByNodeId(nodeId);
        SpaceHolder.set(spaceId);
        Long userId = SessionContext.getUserId();
        Long memberId = userSpaceCacheService.getMemberId(userId, spaceId);
        checkNodePermission(memberId, nodeId, NodePermission.READ_NODE);
        return ResponseData.success(iNodeRoleService.getNodeRoleMembersPageInfo(page, nodeId));
    }

    /**
     * Turn on specified node role mode.
     */
    @PostResource(path = "/disableRoleExtend", requiredPermission = false)
    @Operation(summary = "Turn on specified node role mode")
    public ResponseData<Void> disableRoleExtend(
        @RequestParam(name = "nodeId") String nodeId,
        @RequestBody(required = false) Map<String, Boolean> body) {
        String spaceId = iNodeService.getSpaceIdByNodeId(nodeId);
        Long userId = checkNodeRoleAssignable(spaceId, nodeId);
        boolean includeExtend = body != null && Boolean.TRUE.equals(body.get("includeExtend"));
        iNodeRoleService.enableNodeRole(userId, spaceId, nodeId, includeExtend);
        return ResponseData.success();
    }

    /**
     * Restore inherited node role mode.
     */
    @PostResource(path = "/enableRoleExtend", requiredPermission = false)
    @Operation(summary = "Restore inherited node role mode")
    public ResponseData<Void> enableRoleExtend(@RequestParam(name = "nodeId") String nodeId) {
        String spaceId = iNodeService.getSpaceIdByNodeId(nodeId);
        Long userId = checkNodeRoleAssignable(spaceId, nodeId);
        iNodeRoleService.disableNodeRole(userId, nodeId);
        return ResponseData.success();
    }

    /**
     * Add node role.
     */
    @PostResource(path = "/addRole", requiredPermission = false)
    @Operation(summary = "Add node role")
    public ResponseData<Void> addRole(@RequestBody @Valid AddNodeRoleRo data) {
        String spaceId = iNodeService.getSpaceIdByNodeId(data.getNodeId());
        Long userId = checkNodeRoleAssignable(spaceId, data.getNodeId());
        iNodeRoleService.addNodeRole(userId, data.getNodeId(), data.getRole(), data.getUnitIds());
        return ResponseData.success();
    }

    /**
     * Edit node role.
     */
    @PostResource(path = "/editRole", requiredPermission = false)
    @Operation(summary = "Edit node role")
    public ResponseData<Void> editRole(@RequestBody @Valid ModifyNodeRoleRo data) {
        String spaceId = iNodeService.getSpaceIdByNodeId(data.getNodeId());
        Long userId = checkNodeRoleAssignable(spaceId, data.getNodeId());
        iNodeRoleService.updateNodeRole(userId, data.getNodeId(), data.getRole(),
            Collections.singletonList(data.getUnitId()));
        return ResponseData.success();
    }

    /**
     * Batch edit node role.
     */
    @PostResource(path = "/batchEditRole", requiredPermission = false)
    @Operation(summary = "Batch edit node role")
    public ResponseData<Void> batchEditRole(@RequestBody @Valid BatchModifyNodeRoleRo data) {
        String spaceId = iNodeService.getSpaceIdByNodeId(data.getNodeId());
        Long userId = checkNodeRoleAssignable(spaceId, data.getNodeId());
        iNodeRoleService.updateNodeRole(userId, data.getNodeId(), data.getRole(),
            data.getUnitIds());
        return ResponseData.success();
    }

    /**
     * Delete node role.
     */
    @PostResource(path = "/deleteRole", method = RequestMethod.DELETE, requiredPermission = false)
    @Operation(summary = "Delete node role")
    public ResponseData<Void> deleteRole(@RequestBody @Valid DeleteNodeRoleRo data) {
        String spaceId = iNodeService.getSpaceIdByNodeId(data.getNodeId());
        Long userId = checkNodeRoleAssignable(spaceId, data.getNodeId());
        iNodeRoleService.deleteNodeRole(userId, data.getNodeId(), data.getUnitId());
        return ResponseData.success();
    }

    /**
     * Batch delete node role.
     */
    @PostResource(path = "/batchDeleteRole", method = RequestMethod.DELETE,
        requiredPermission = false)
    @Operation(summary = "Batch delete node role")
    public ResponseData<Void> batchDeleteRole(@RequestBody @Valid BatchDeleteNodeRoleRo data) {
        String spaceId = iNodeService.getSpaceIdByNodeId(data.getNodeId());
        checkNodeRoleAssignable(spaceId, data.getNodeId());
        iNodeRoleService.deleteNodeRoles(data.getNodeId(), data.getUnitIds());
        return ResponseData.success();
    }

    private List<UnitMemberVo> getAdmins(String spaceId, Boolean includeAdmin) {
        if (Boolean.FALSE.equals(includeAdmin)) {
            return Collections.emptyList();
        }
        List<Long> adminMemberIds = iSpaceRoleService.getSpaceAdminsWithWorkbenchManage(spaceId);
        return iOrganizationService.findAdminsVo(adminMemberIds, spaceId);
    }

    private UnitMemberVo getSelf(String spaceId, Long memberId, String includeSelf) {
        if (!Boolean.parseBoolean(includeSelf)) {
            return null;
        }
        List<UnitMemberVo> members =
            iOrganizationService.findAdminsVo(Collections.singletonList(memberId), spaceId);
        return CollUtil.isNotEmpty(members) ? CollUtil.getFirst(members) : null;
    }

    private List<NodeRoleUnit> getRoleUnits(String spaceId, String controlNodeId,
                                            boolean assignMode, Boolean includeExtend) {
        if (!assignMode && Boolean.FALSE.equals(includeExtend)) {
            return Collections.emptyList();
        }
        if (controlNodeId == null) {
            return Collections.singletonList(iNodeRoleService.getRootNodeRoleUnit(spaceId));
        }
        return iNodeRoleService.getNodeRoleUnitList(controlNodeId);
    }

    private boolean isBelongRootFolder(String spaceId, String nodeId) {
        String rootNodeId = iNodeService.getRootNodeIdBySpaceId(spaceId);
        return nodeId.equals(rootNodeId) || rootNodeId.equals(iNodeService.getParentIdByNodeId(
            nodeId));
    }

    private Long checkNodeRoleAssignable(String spaceId, String nodeId) {
        SpaceHolder.set(spaceId);
        Long userId = SessionContext.getUserId();
        Long memberId = userSpaceCacheService.getMemberId(userId, spaceId);
        checkNodePermission(memberId, nodeId, NodePermission.ASSIGN_NODE_ROLE);
        return userId;
    }

    private void checkNodePermission(Long memberId, String nodeId, NodePermission nodePermission) {
        controlTemplate.checkNodePermission(memberId, nodeId, nodePermission,
            status -> ExceptionUtil.isTrue(status, PermissionException.NODE_OPERATION_DENIED));
    }
}
