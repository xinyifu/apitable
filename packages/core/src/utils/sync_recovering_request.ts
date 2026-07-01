/**
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

import axios from 'axios';
import type { IReduxState } from '../exports/store/interfaces';
import { ResourceType } from '../types/resource_types';

export function isRecoveringNetworkError(error: unknown): boolean {
  if (!axios.isAxiosError(error)) {
    return false;
  }
  const status = error.response?.status;
  return (
    !error.response ||
    status === 502 ||
    status === 503 ||
    status === 504 ||
    error.code === 'ECONNABORTED' ||
    error.code === 'ERR_NETWORK'
  );
}

// 内部辅助方法，直接访问 state 属性，规避循环依赖
function getDatasheetPackFromState(state: IReduxState, id?: string) {
  const datasheetId = id || state.pageParams?.datasheetId;
  if (!datasheetId) {
    return undefined;
  }
  return state.datasheetMap?.[datasheetId];
}

function getMirrorPackFromState(state: IReduxState, id?: string) {
  const mirrorId = id || state.pageParams?.mirrorId;
  if (!mirrorId) {
    return undefined;
  }
  return state.mirrorMap?.[mirrorId];
}

export function isSyncRecoveringState(state: IReduxState, resourceId?: string, resourceType?: ResourceType): boolean {
  if (state.space?.reconnecting) {
    return true;
  }
  if (resourceId) {
    if (resourceType === undefined || resourceType === ResourceType.Datasheet) {
      const pack = getDatasheetPackFromState(state, resourceId);
      if (pack && pack.connected === false) {
        return true;
      }
    }
    if (resourceType === ResourceType.Mirror) {
      const pack = getMirrorPackFromState(state, resourceId);
      if (pack && pack.connected === false) {
        return true;
      }
    }
  }
  return false;
}

export function shouldSuppressRecoveringRequestError(
  error: unknown,
  state: IReduxState,
  options?: {
    resourceId?: string;
    resourceType?: ResourceType;
    requireExistingDatasheet?: boolean;
  }
): boolean {
  const { resourceId, resourceType, requireExistingDatasheet } = options || {};

  if (!isRecoveringNetworkError(error)) {
    return false;
  }

  if (!isSyncRecoveringState(state, resourceId, resourceType)) {
    return false;
  }

  if (requireExistingDatasheet && resourceId) {
    const pack = getDatasheetPackFromState(state, resourceId);
    const datasheet = pack?.datasheet;
    if (!datasheet || datasheet.isPartOfData) {
      return false;
    }
  }

  return true;
}
