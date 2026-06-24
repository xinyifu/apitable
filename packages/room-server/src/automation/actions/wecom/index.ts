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

import fetch from 'node-fetch';
import { ResponseStatusCodeEnums } from '../enum/response.status.code.enums';
import { IActionResponse, IErrorResponse } from '../interface/action.response';

interface IWecomMsgRequest {
  type: 'text' | 'markdown';
  content: string;
  webhookUrl: string;
}

interface IWecomMsgResponse {
  errcode: number;
  errmsg: string;
}

const TEXT_CONTENT_BYTE_LIMIT = 2048;
const MARKDOWN_CONTENT_BYTE_LIMIT = 4096;

function getContentByteLimit(type: IWecomMsgRequest['type']) {
  return type === 'markdown' ? MARKDOWN_CONTENT_BYTE_LIMIT : TEXT_CONTENT_BYTE_LIMIT;
}

function createErrorResponse(message: string, code = ResponseStatusCodeEnums.ServerError): IActionResponse<any> {
  const res: IErrorResponse = {
    errors: [
      {
        message,
      },
    ],
  };
  return {
    success: false,
    data: res,
    code,
  };
}

export async function sendWecomMsg(request: IWecomMsgRequest): Promise<IActionResponse<any>> {
  const { type, content, webhookUrl } = request;
  const contentByteLimit = getContentByteLimit(type);
  if (Buffer.byteLength(content, 'utf8') > contentByteLimit) {
    return createErrorResponse(`WeCom ${type} message content must be no more than ${contentByteLimit} bytes`, ResponseStatusCodeEnums.ClientError);
  }

  const data = type === 'markdown'
    ? {
      msgtype: 'markdown',
      markdown: {
        content,
      },
    }
    : {
      msgtype: 'text',
      text: {
        content,
      },
    };

  try {
    const res = await fetch(webhookUrl.trim(), {
      body: JSON.stringify(data),
      method: 'POST',
      headers: {
        'content-type': 'application/json',
      },
    });
    const resp: IWecomMsgResponse = await res.json();
    if (resp.errcode === 0) {
      return {
        success: true,
        code: ResponseStatusCodeEnums.Success,
        data: {
          data: resp,
        },
      };
    }
    return {
      success: false,
      code: ResponseStatusCodeEnums.ClientError,
      data: {
        errors: [
          {
            message: resp.errmsg,
          },
        ],
      },
    };
  } catch (error: any) {
    return createErrorResponse(error.message);
  }
}
