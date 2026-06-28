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

import { FC, MouseEvent, PropsWithChildren, useCallback, useEffect, useRef, useState } from 'react';
import { Strings, t } from '@apitable/core';
import { Network } from 'pc/components/network_status';
import styles from './sync_recovering_overlay.module.less';

interface ISyncRecoveringOverlayProps {
  enabled: boolean;
  status: Network;
}

const RECOVERING_TEXT = {
  [Network.Reconnecting]: Strings.sync_recovering_reconnecting,
  [Network.ConnectingResource]: Strings.sync_recovering_connecting_resource,
  [Network.SyncingData]: Strings.sync_recovering_syncing_data,
};

const isRecoveringStatus = (status: Network): status is keyof typeof RECOVERING_TEXT => {
  return status === Network.Reconnecting || status === Network.ConnectingResource || status === Network.SyncingData;
};

export const SyncRecoveringOverlay: FC<PropsWithChildren<ISyncRecoveringOverlayProps>> = ({ enabled, status }) => {
  const overlayRef = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);
  const [showRefresh, setShowRefresh] = useState(false);
  const active = enabled && isRecoveringStatus(status);

  useEffect(() => {
    if (!active) {
      setVisible(false);
      setShowRefresh(false);
      return;
    }

    const visibleTimer = window.setTimeout(() => {
      setVisible(true);
    }, 500);
    const refreshTimer = window.setTimeout(() => {
      setShowRefresh(true);
    }, 15000);

    return () => {
      window.clearTimeout(visibleTimer);
      window.clearTimeout(refreshTimer);
    };
  }, [active, status]);

  useEffect(() => {
    if (!visible) {
      return;
    }
    overlayRef.current?.focus({ preventScroll: true });
  }, [visible]);

  const stopEvent = useCallback((event: MouseEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
  }, []);

  const handleRefresh = useCallback(() => {
    window.location.reload();
  }, []);

  if (!visible || !isRecoveringStatus(status)) {
    return null;
  }

  return (
    <div
      ref={overlayRef}
      className={styles.overlay}
      role="status"
      aria-live="polite"
      tabIndex={-1}
      onClick={stopEvent}
      onDoubleClick={stopEvent}
      onMouseDown={stopEvent}
    >
      <div className={styles.content}>
        <span className={styles.spinner} />
        <span className={styles.text}>{t(RECOVERING_TEXT[status])}</span>
        {showRefresh && (
          <button type="button" className={styles.refreshButton} onClick={handleRefresh}>
            {t(Strings.sync_recovering_manual_refresh)}
          </button>
        )}
      </div>
    </div>
  );
};
