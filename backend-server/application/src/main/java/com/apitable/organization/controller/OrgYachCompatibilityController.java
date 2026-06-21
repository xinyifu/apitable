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

package com.apitable.organization.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.apitable.control.infrastructure.ControlTemplate;
import com.apitable.control.infrastructure.permission.NodePermission;
import com.apitable.core.support.ResponseData;
import com.apitable.core.util.ExceptionUtil;
import com.apitable.organization.entity.TagEntity;
import com.apitable.organization.entity.UnitEntity;
import com.apitable.organization.enums.UnitType;
import com.apitable.organization.mapper.TagMapper;
import com.apitable.organization.mapper.UnitMapper;
import com.apitable.organization.vo.YachGroupVo;
import com.apitable.shared.cache.service.UserSpaceCacheService;
import com.apitable.shared.component.scanner.annotation.ApiResource;
import com.apitable.shared.component.scanner.annotation.GetResource;
import com.apitable.shared.component.scanner.annotation.PostResource;
import com.apitable.shared.context.LoginContext;
import com.apitable.shared.context.SessionContext;
import com.apitable.shared.holder.SpaceHolder;
import com.apitable.shared.util.page.PageHelper;
import com.apitable.shared.util.page.PageInfo;
import com.apitable.shared.util.page.PageObjectParam;
import com.apitable.workspace.controller.FieldPermissionController;
import com.apitable.workspace.controller.NodeRoleController;
import com.apitable.workspace.enums.PermissionException;
import com.apitable.workspace.ro.AddNodeRoleRo;
import com.apitable.workspace.ro.FieldRoleCreateRo;
import com.apitable.workspace.service.INodeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Yach compatibility api for local self-hosted org tags.
 */
@Tag(name = "Yach Compatibility Api")
@RestController
@ApiResource(path = "/org/yach")
public class OrgYachCompatibilityController {

    @Resource
    private TagMapper tagMapper;

    @Resource
    private UnitMapper unitMapper;

    @Resource
    private INodeService iNodeService;

    @Resource
    private UserSpaceCacheService userSpaceCacheService;

    @Resource
    private ControlTemplate controlTemplate;

    @Resource
    private NodeRoleController nodeRoleController;

    @Resource
    private FieldPermissionController fieldPermissionController;

    /**
     * Query local tag list as yach groups.
     */
    @GetResource(path = "/group/page", requiredPermission = false)
    @Operation(summary = "Query yach group page")
    public ResponseData<PageInfo<YachGroupVo>> groupPage(
        @RequestParam(name = "groupName", required = false) String groupName,
        @PageObjectParam Page<TagEntity> page) {
        String spaceId = LoginContext.me().getSpaceId();
        LambdaQueryWrapper<TagEntity> wrapper = new LambdaQueryWrapper<TagEntity>()
            .eq(TagEntity::getSpaceId, spaceId)
            .like(StrUtil.isNotBlank(groupName), TagEntity::getTagName, groupName)
            .orderByAsc(TagEntity::getSequence)
            .orderByDesc(TagEntity::getCreatedAt);
        Page<TagEntity> result = tagMapper.selectPage(page, wrapper);
        List<YachGroupVo> records = toGroupVos(spaceId, result.getRecords());
        return ResponseData.success(PageHelper.build(result.getCurrent(), result.getSize(),
            result.getTotal(), records));
    }

    /**
     * Add local tag units to node roles.
     */
    @PostResource(path = "/node/addRole", requiredPermission = false)
    @Operation(summary = "Add yach group node role")
    public ResponseData<Void> addNodeRole(@RequestBody @Valid AddNodeRoleRo data) {
        checkNodePermission(data.getNodeId(), NodePermission.ASSIGN_NODE_ROLE);
        nodeRoleController.addRole(data);
        return ResponseData.success();
    }

    /**
     * Add local tag units to field roles.
     */
    @PostResource(path = "/datasheet/{dstId}/field/{fieldId}/addRole",
        requiredPermission = false)
    @Operation(summary = "Add yach group field role")
    public ResponseData<Void> addFieldRole(
        @PathVariable("dstId") String dstId,
        @PathVariable("fieldId") String fieldId,
        @RequestBody @Valid FieldRoleCreateRo data) {
        fieldPermissionController.addFieldRole(dstId, fieldId, data);
        return ResponseData.success();
    }

    private List<YachGroupVo> toGroupVos(String spaceId, List<TagEntity> tags) {
        if (CollUtil.isEmpty(tags)) {
            return Collections.emptyList();
        }
        List<Long> tagIds = tags.stream().map(TagEntity::getId).collect(Collectors.toList());
        List<UnitEntity> units = unitMapper.selectByRefIds(tagIds).stream()
            .filter(unit -> spaceId.equals(unit.getSpaceId()))
            .filter(unit -> UnitType.TAG.getType().equals(unit.getUnitType()))
            .collect(Collectors.toList());
        Map<Long, UnitEntity> tagIdToUnit = units.stream()
            .collect(Collectors.toMap(UnitEntity::getUnitRefId, Function.identity()));
        return tags.stream()
            .filter(tag -> tagIdToUnit.containsKey(tag.getId()))
            .map(tag -> new YachGroupVo(tag.getTagName(), tagIdToUnit.get(tag.getId()).getId()))
            .collect(Collectors.toList());
    }

    private void checkNodePermission(String nodeId, NodePermission nodePermission) {
        String spaceId = iNodeService.getSpaceIdByNodeId(nodeId);
        SpaceHolder.set(spaceId);
        Long userId = SessionContext.getUserId();
        Long memberId = userSpaceCacheService.getMemberId(userId, spaceId);
        boolean permitted = controlTemplate.hasNodePermission(memberId, nodeId, nodePermission);
        ExceptionUtil.isTrue(permitted, PermissionException.NODE_OPERATION_DENIED);
    }
}
