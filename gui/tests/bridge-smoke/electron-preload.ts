import { contextBridge, ipcRenderer } from 'electron';
import type {
  BridgeDesktopApi,
  BridgeDesktopRequest,
} from '../../electron/bridge-types';
import { IPC_CHANNELS } from '../../electron/shared';
import { BRIDGE_SMOKE_CHANNELS } from './electron-shared';

function requestBridge<T>(request: BridgeDesktopRequest): Promise<T> {
  return ipcRenderer.invoke(BRIDGE_SMOKE_CHANNELS.CONTROL, request);
}

ipcRenderer.on(BRIDGE_SMOKE_CHANNELS.OPEN, () => {
  window.location.hash = '/bridge';
});

const api: BridgeDesktopApi = {
  load: (requestId) => requestBridge({ operation: 'load', requestId }),
  choose: (kind) => requestBridge({ operation: 'choose', kind }),
  health: () => requestBridge({ operation: 'health' }),
  apply: (token, policy, requestId) =>
    requestBridge({ operation: 'apply', token, policy, requestId }),
  cancel: (requestId) => requestBridge({ operation: 'cancel', requestId }),
};

contextBridge.exposeInMainWorld('bridgeSmokeApi', api);
contextBridge.exposeInMainWorld('electronAPI', {
  hide: () => ipcRenderer.invoke(IPC_CHANNELS.WINDOW_ACTIONS, 'hide'),
});
