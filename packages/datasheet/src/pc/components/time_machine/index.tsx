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

import { useScroll } from 'ahooks';
import { message, Tabs } from 'antd';
import dayjs from 'dayjs';
import { difference } from 'lodash';
import Image from 'next/image';
import * as React from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Box, IconButton, Loading, Skeleton, Tooltip, Typography, TextButton } from '@apitable/components';
import {
  Api,
  CollaCommandName,
  DatasheetApi,
  fastCloneDeep,
  IChangesetPack,
  IMemberInfoInAddressList,
  IRemoteChangeset,
  PREVIEW_DATASHEET_ID,
  ResourceType,
  Selectors,
  StoreActions,
  Strings,
  t,
  ThemeName,
} from '@apitable/core';
import { CloseOutlined, QuestionCircleOutlined } from '@apitable/icons';
import { Avatar, Modal } from 'pc/components/common';
import { notify } from 'pc/components/common/notify';
import { NotifyKey } from 'pc/components/common/notify/notify.interface';
import { expandRecordIdNavigate } from 'pc/components/expand_record';
import { Portal } from 'pc/components/portal';
import { Beta } from 'pc/components/robot/robot_panel/robot_list_head';
import { useAppDispatch } from 'pc/hooks/use_app_dispatch';
import { resourceService } from 'pc/resource_service';
import { useAppSelector } from 'pc/store/react-redux';
import { copy2clipBoard } from 'pc/utils/dom';
import { getEnvVariables } from 'pc/utils/env';
import DataEmptyDark from 'static/icon/common/time_machine_empty_dark.png';
import DataEmptyLight from 'static/icon/common/time_machine_empty_light.png';
import { ITimeMachineRecordRef, TabPaneKeys } from './interface';
import { getForeignDatasheetIdsByOp, getOperationDetail } from './utils';
// @ts-ignore
import { getSocialWecomUnitName } from 'enterprise/home/social_platform/utils';
// @ts-ignore
import { Backup } from 'enterprise/time_machine/backup/backup';
import styles from './style.module.less';

const { TabPane } = Tabs;

const MAX_COUNT = Number.MAX_SAFE_INTEGER;
const DATEFORMAT = 'YYYY-MM-DD HH:mm:ss';
const MAX_VISIBLE_RECORD_REFS = 3;

const getRecordRefTooltip = (record: ITimeMachineRecordRef) => {
  const statusText = {
    exists: '点击标签打开记录，点击 recordId 复制',
    deleted: '记录已删除，点击 recordId 复制',
    unknown: '暂时无法定位该记录，点击 recordId 复制',
  }[record.status];
  return `${record.title ? `${record.title}\n` : ''}${record.recordId}\n${statusText}`;
};

const onOpenRecordRef = (event: React.MouseEvent, record: ITimeMachineRecordRef) => {
  event.stopPropagation();
  if (record.status !== 'exists') {
    message.info(record.status === 'deleted' ? '该记录已删除，可复制 recordId 后查看历史' : '暂时无法定位该记录');
    return;
  }
  expandRecordIdNavigate(record.recordId);
};

const onCopyRecordId = (event: React.MouseEvent, recordId: string) => {
  event.stopPropagation();
  copy2clipBoard(recordId, () => {
    message.success('已复制 recordId');
  });
};

const TimeMachineRecordRefs: React.FC<{ records: ITimeMachineRecordRef[] }> = ({ records }) => {
  if (!records.length) {
    return null;
  }
  const visibleRecords = records.slice(0, MAX_VISIBLE_RECORD_REFS);
  const hiddenCount = records.length - visibleRecords.length;
  return (
    <div className={styles.recordRefs}>
      {visibleRecords.map((record) => (
        <button
          type="button"
          aria-label={record.status === 'exists' ? `打开记录 ${record.recordId}` : `查看记录状态 ${record.recordId}`}
          className={styles.recordRef}
          data-status={record.status}
          key={record.recordId}
          onClick={(event) => onOpenRecordRef(event, record)}
          title={getRecordRefTooltip(record)}
        >
          {record.status === 'deleted' && <span className={styles.recordDeleted}>已删除</span>}
          {record.title && <span className={styles.recordTitle}>{record.title}</span>}
          <span className={styles.recordId} onClick={(event) => onCopyRecordId(event, record.recordId)}>
            {record.recordId}
          </span>
        </button>
      ))}
      {hiddenCount > 0 && <span className={styles.recordMore}>+ {hiddenCount} 条</span>}
    </div>
  );
};

export const TimeMachine: React.FC<React.PropsWithChildren<{ onClose: (_visible: boolean) => void }>> = ({ onClose }) => {
  const datasheetId = useAppSelector(Selectors.getActiveDatasheetId)!;
  const curDatasheet = useAppSelector((state) => Selectors.getDatasheet(state, datasheetId));
  const activeNodePrivate = useAppSelector((state) => Selectors.getActiveNodePrivate(state));
  const [curPreview, setCurPreview] = useState<number | string>();
  const [changesetList, setChangesetList] = useState<IRemoteChangeset[] | null>(null);
  const [fetching, setFetching] = useState(false);
  const [uuidMap, setUuidMap] = useState<Record<string, IMemberInfoInAddressList>>();
  const currentRevision = useAppSelector((state) => Selectors.getResourceRevision(state, datasheetId, ResourceType.Datasheet)!);
  const spaceInfo = useAppSelector((state) => state.space.curSpaceInfo);

  const uuids = useMemo(() => {
    const uuids =
      changesetList &&
      changesetList.map((item) => {
        return item.userId!;
      });
    return Array.from<string>(new Set(uuids || []));
  }, [changesetList]);

  const isEmpty = useMemo(() => {
    return Array.isArray(changesetList) && !changesetList.length;
  }, [changesetList]);

  const currentDatasheetIds = useAppSelector(Selectors.getDatasheetIds);
  const [rollbackIng, setRollbackIng] = useState(false);
  const dispatch = useAppDispatch();

  const theme = useAppSelector((state) => state.theme);
  const DataEmpty = theme === ThemeName.Light ? DataEmptyLight : DataEmptyDark;

  const fetchChangesets = (lastRevision: number) => {
    setFetching(true);
    const startRevision = lastRevision - 99 > 0 ? lastRevision - 99 : 1;
    DatasheetApi.fetchChangesets<IChangesetPack>(datasheetId, ResourceType.Datasheet, startRevision, lastRevision + 1)
      .then((res) => {
        // The returned data is from low to high, when displaying, you need to display the high version first
        const csl = res.data.data.reverse();
        const nextCsl = changesetList === null ? csl : changesetList.concat(csl);
        setChangesetList(nextCsl.filter((item) => item.operations.filter((op) => !op.cmd.startsWith('System')).length > 0));
      })
      .finally(() => {
        setFetching(false);
      });
  };

  const lastChangeset = changesetList && changesetList[changesetList.length - 1];
  const noMore = !lastChangeset || lastChangeset.revision === 1 || changesetList.length >= MAX_COUNT;
  const contentRef = useRef<HTMLDivElement>(null);
  const scrollInfo = useScroll(contentRef);

  useEffect(() => {
    // Load up to 500 most recent versions
    if (!contentRef.current || fetching || noMore) {
      return;
    }
    const offsetHeight = contentRef.current.offsetHeight;
    const scrollHeight = contentRef.current.scrollHeight;
    if (scrollInfo && offsetHeight + scrollInfo.top + 30 > scrollHeight) {
      fetchChangesets(lastChangeset.revision - 1);
    }
    // eslint-disable-next-line
  }, [scrollInfo]);

  useEffect(() => {
    fetchChangesets(currentRevision);
    // eslint-disable-next-line
  }, []);

  useEffect(() => {
    Promise.all(
      uuids.map((id) => {
        return Api.getMemberInfo({ uuid: id });
      }),
    ).then((results) => {
      const map = {};
      results.forEach((result, index) => {
        map[uuids[index]] = result.data.data;
      });
      setUuidMap(map);
    });
  }, [uuids]);

  const executeRollback = useCallback(
    (operations: any) => {
      try {
        resourceService.instance!.commandManager.execute({
          cmd: CollaCommandName.Rollback,
          datasheetId,
          data: {
            operations,
          },
        });
        notify.open({ message: t(Strings.rollback_tip), key: NotifyKey.Rollback });
      } catch (error) {
        Modal.confirm({
          title: t(Strings.rollback_fail_title),
          content: <div dangerouslySetInnerHTML={{ __html: t(Strings.rollback_fail_content, { url: '/help/manual-timemachine/' }) }} />,
        });
      }
      dispatch(StoreActions.resetDatasheet(PREVIEW_DATASHEET_ID));
    },
    [datasheetId, dispatch],
  );

  const executePreview = useCallback(
    (operations: any, index: any) => {
      if (!changesetList) return;
      const cloneDatasheet = fastCloneDeep(curDatasheet)!;
      const revision = `${changesetList[index].revision}`;
      setCurPreview(index);

      cloneDatasheet.id = PREVIEW_DATASHEET_ID;
      cloneDatasheet.snapshot.datasheetId = PREVIEW_DATASHEET_ID;
      // Proactively setting editable to false bypasses conflict detection and avoids pop-ups that automatically restore modal boxes
      cloneDatasheet.permissions = { ...cloneDatasheet.permissions, editable: false };
      // Identifies the current data as preview data and indicates the version of the preview
      cloneDatasheet.preview = revision;
      // Proactively setting editable to false bypasses conflict detection and avoids pop-ups that automatically restore modal boxes
      const previewSnapshot = cloneDatasheet.snapshot;
      try {
        dispatch(StoreActions.receiveDataPack({ snapshot: previewSnapshot, datasheet: cloneDatasheet }, { isPartOfData: false }));
      } catch (error) {
        console.log(error);
        Modal.error({
          title: t(Strings.preview_fail_title),
          content: <div dangerouslySetInnerHTML={{ __html: t(Strings.preview_fail_content, { url: '/help/manual-timemachine/' }) }} />,
        });
      }
    },
    [dispatch, changesetList, curDatasheet],
  );

  const execute = useCallback(
    (index: number, preview = false) => {
      if (!changesetList) return;
      const operations = changesetList
        .slice(0, index + 1)
        .map((cs) => {
          // op needs to be executed in reverse when rolling back, so the order should also be reversed first
          return cs.operations.filter((op) => !op.cmd.startsWith('System')).reverse();
        })
        .flat(1);
      const msgText = preview ? t(Strings.preview) : t(Strings.rollback);

      const foreignDatasheetIds = getForeignDatasheetIdsByOp(operations);
      const diff = difference(foreignDatasheetIds, currentDatasheetIds);
      const cmdExecute = () => {
        if (preview) {
          executePreview(operations, index);
        } else {
          executeRollback(operations);
        }
      };
      if (diff.length) {
        setRollbackIng(true);
        Promise.all(
          diff.map((dsId) => {
            return dispatch(StoreActions.fetchDatasheet(dsId));
          }),
        )
          .then(() => {
            cmdExecute();
          })
          .catch(() => {
            message.error(t(Strings.rollback_fail_tip, { type: msgText }));
          })
          .finally(() => {
            setRollbackIng(false);
          });
      } else {
        cmdExecute();
      }
    },
    [executePreview, executeRollback, currentDatasheetIds, dispatch, changesetList],
  );

  const onPreviewClick = useCallback(
    (index: number) => {
      execute(index, true);
    },
    [execute],
  );

  const onRollbackClick = useCallback((index: number) => {
    Modal.confirm({
      title: t(Strings.rollback_title, { revision: changesetList![index].revision }),
      width: 620,
      zIndex: 5001,
      content: <>
        <p>操作前阅读：</p>
        <p>点击确定后，会将数据从最新版本回滚到指定版本，目标版本以前的所有改动将会被撤销掉。 </p>
        <p>回滚不是直接删除修改，而是通过新的操作把原来的操作抵消掉，所以回滚操作(Rollback)也会出现在历史记录中。</p>
        <p>回滚过程中若遇到不可逆的操作，如：关联字段的关联表被删除，则不能恢复此操作相关的数据。</p>
        <p className={styles.danger}>请在回滚之前先预览要回滚的版本，以确保回退到正确的版本。</p>
        <p className={styles.danger}>回滚功能目前处于Beta版本，未经严格测试，具有一定风险性，请确认自己非常清楚正在做什么，否则有数据丢失的风险！</p>
      </>,
      okButtonProps: {
        color: 'danger',
      },
      onOk() {
        execute(index, false);
      },
    });
  }, [changesetList, execute]);

  return (
    <div className={styles.wrap}>
      <div className={styles.header}>
        <Typography variant="h6">{t(Strings.time_machine)}</Typography>
        <Tooltip content={t(Strings.robot_panel_help_tooltip)} placement="top-center">
          <Box display="flex" alignItems="center">
            <IconButton
              shape="square"
              icon={QuestionCircleOutlined}
              onClick={() => {
                window.open(t(Strings.timemachine_help_url));
              }}
            />
          </Box>
        </Tooltip>
        <Beta />
        <IconButton shape="square" onClick={() => onClose(false)} icon={CloseOutlined} style={{ position: 'absolute', right: 16 }} />
      </div>
      <Tabs
        className={styles.tabs}
        onChange={() => {
          dispatch(StoreActions.resetDatasheet(PREVIEW_DATASHEET_ID));
          setCurPreview(undefined);
        }}
      >
        <TabPane tab={t(Strings.time_machine_action_title)} key={TabPaneKeys.ACTION}>
          {!changesetList ? (
            <div className={'vk-px-2'}>
              <Skeleton width="38%" />
              <Skeleton count={2} />
              <Skeleton width="61%" />
            </div>
          ) : (
            <div className={styles.content} ref={contentRef}>
              {isEmpty ? (
                <div className={styles.noList}>
                  <Image src={DataEmpty} width={240} height={180} alt="" />
                  <p>{t(Strings.rollback_history_empty)}</p>
                </div>
              ) : (
                changesetList.map((item, index) => {
                  const memberInfo = uuidMap && uuidMap[item.userId!];
                  const operatorTitle = memberInfo?.memberName || memberInfo?.nickName || item.userId || '未知用户';
                  const operatorName =
                    getSocialWecomUnitName?.({
                      name: memberInfo?.memberName || memberInfo?.nickName,
                      isModified: memberInfo?.isMemberNameModified,
                      spaceInfo,
                    }) || operatorTitle;
                  const ops = item.operations.filter((op) => !op.cmd.startsWith('System'));
                  const detail = getOperationDetail(ops, curDatasheet?.snapshot);
                  return (
                    <section
                      className={styles.listItem}
                      key={`${item.messageId}-${item.revision}-${index}`}
                      data-active={index === curPreview}
                      onClick={() => {
                        onPreviewClick(index);
                      }}
                    >
                      <div style={{ display: 'flex', gap: '8px' }}>
                        <Avatar id={item.userId || ''} title={operatorTitle} size={24} src={memberInfo?.avatar} />
                        <div>
                          <div className={styles.title}>
                            <span className={styles.operatorName}>{operatorName}</span>
                            <span>{detail.summary}</span>
                          </div>
                          <TimeMachineRecordRefs records={detail.records} />
                          <div className={styles.timestamp}>
                            {dayjs.tz(item.createdAt).format(DATEFORMAT)}
                            {getEnvVariables().ENABLE_TIME_MACHINE_ROOLBACK && 
                            <TextButton size="x-small" color="danger" disabled={isEmpty} onClick={() => onRollbackClick(index)}>
                              {t(Strings.rollback_revision)}
                            </TextButton>
                            }
                          </div>
                        </div>
                      </div>
                    </section>
                  );
                })
              )}
              {!isEmpty && <div className={styles.bottomTip}>{noMore ? t(Strings.no_more) : t(Strings.data_loading)}</div>}
            </div>
          )}
        </TabPane>
        {Boolean(Backup) && !activeNodePrivate && (
          <TabPane tab={t(Strings.backup_title)} key={TabPaneKeys.BACKUP}>
            <Backup datasheetId={datasheetId} setCurPreview={setCurPreview} curPreview={curPreview!} />
          </TabPane>
        )}
      </Tabs>

      <Portal visible={rollbackIng} zIndex={2000}>
        <div className={styles.mask}>
          <i>
            <Loading />
          </i>
          <p>{t(Strings.rollbacking)}</p>
        </div>
      </Portal>
    </div>
  );
};
