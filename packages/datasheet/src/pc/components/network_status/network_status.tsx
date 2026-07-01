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

import { FC, PropsWithChildren, useEffect, useRef } from 'react';
import { t, Strings } from '@apitable/core';
import ConnectingResourceAnimationJson from 'static/json/datasheet_icon_connecting_resource.json';
import LoadingAnimationJson from 'static/json/datasheet_icon_loading.json';
import OfflineAnimationJson from 'static/json/datasheet_icon_offline.json';
import OnlineAnimationJson from 'static/json/datasheet_icon_online.json';
import ReconnectingAnimationJson from 'static/json/datasheet_icon_reconnecting.json';
import SyncAnimationJson from 'static/json/datasheet_icon_sync.json';
import SyncingDataAnimationJson from 'static/json/datasheet_icon_syncing_data.json';
import { Tooltip } from '../common';
import styles from './style.module.less';

export enum Network {
  Online = 'online',
  Offline = 'offline',
  Sync = 'sync',
  Loading = 'loading',
  Reconnecting = 'reconnecting',
  ConnectingResource = 'connecting_resource',
  SyncingData = 'syncing_data',
}

export const NetworkTip = {
  [Network.Online]: t(Strings.network_icon_hover_connected),
  [Network.Offline]: t(Strings.network_icon_hover_disconnected),
  [Network.Sync]: t(Strings.network_icon_hover_data_synchronization),
  [Network.Loading]: t(Strings.network_icon_hover_reconnection),
  [Network.Reconnecting]: t(Strings.network_icon_hover_reconnection),
  [Network.ConnectingResource]: t(Strings.network_icon_hover_connecting_resource),
  [Network.SyncingData]: t(Strings.network_icon_hover_syncing_data),
};

export interface INetworkStatusProps {
  currentStatus?: Network;
}

export const NetworkStatus: FC<PropsWithChildren<INetworkStatusProps>> = (props) => {
  const { currentStatus = Network.Online } = props;
  const onlineRef = useRef<HTMLDivElement>(null);
  const offlineRef = useRef<HTMLDivElement>(null);
  const syncRef = useRef<HTMLDivElement>(null);
  const loadingRef = useRef<HTMLDivElement>(null);
  const reconnectingRef = useRef<HTMLDivElement>(null);
  const connectingResourceRef = useRef<HTMLDivElement>(null);
  const syncingDataRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let destroyed = false;
    const animations: Array<{ destroy: () => void }> = [];
    import('lottie-web/build/player/lottie_svg').then((module) => {
      if (destroyed) {
        return;
      }
      const lottie = module.default;
      const configs = [
        { container: onlineRef.current, loop: false, autoplay: false, animationData: OnlineAnimationJson },
        { container: offlineRef.current, loop: true, autoplay: true, animationData: OfflineAnimationJson },
        { container: syncRef.current, loop: true, autoplay: true, animationData: SyncAnimationJson },
        { container: loadingRef.current, loop: true, autoplay: true, animationData: LoadingAnimationJson },
        { container: reconnectingRef.current, loop: true, autoplay: true, animationData: ReconnectingAnimationJson },
        { container: connectingResourceRef.current, loop: true, autoplay: true, animationData: ConnectingResourceAnimationJson },
        { container: syncingDataRef.current, loop: true, autoplay: true, animationData: SyncingDataAnimationJson },
      ];

      configs.forEach(({ container, loop, autoplay, animationData }) => {
        if (!container) {
          return;
        }
        container.innerHTML = '';
        animations.push(lottie.loadAnimation({
          container,
          renderer: 'svg',
          loop,
          autoplay,
          animationData,
        }));
      });
    });

    return () => {
      destroyed = true;
      animations.forEach((animation) => animation.destroy());
    };
  }, []);

  return (
    <div className={styles.networkStatus}>
      <Tooltip title={NetworkTip[currentStatus]} placement="bottomRight">
        <div className={styles.network}>
          <div ref={onlineRef} style={{ display: currentStatus === Network.Online ? 'flex' : 'none' }} />
          <div ref={offlineRef} style={{ display: currentStatus === Network.Offline ? 'flex' : 'none' }} />
          <div ref={syncRef} style={{ display: currentStatus === Network.Sync ? 'flex' : 'none' }} />
          <div ref={loadingRef} style={{ display: currentStatus === Network.Loading ? 'flex' : 'none' }} />
          <div ref={reconnectingRef} style={{ display: currentStatus === Network.Reconnecting ? 'flex' : 'none' }} />
          <div ref={connectingResourceRef} style={{ display: currentStatus === Network.ConnectingResource ? 'flex' : 'none' }} />
          <div ref={syncingDataRef} style={{ display: currentStatus === Network.SyncingData ? 'flex' : 'none' }} />
        </div>
      </Tooltip>
    </div>
  );
};
