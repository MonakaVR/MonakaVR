import { contextBridge, ipcRenderer, IpcRendererEvent } from 'electron';
import { IElectronAPI, ServerStatusEvent } from './interface';
import { IPC_CHANNELS } from '../shared';
import type { BridgeConfiguration, BridgeHealthRead } from '../bridge-types';

ipcRenderer.on(IPC_CHANNELS.BRIDGE_OPEN, () => {
  window.location.hash = '/bridge';
});

contextBridge.exposeInMainWorld('electronAPI', {
  bridge: {
    load: (requestId) =>
      ipcRenderer.invoke(IPC_CHANNELS.BRIDGE_CONTROL, {
        operation: 'load',
        requestId,
      }) as Promise<BridgeConfiguration>,
    choose: (kind) =>
      ipcRenderer.invoke(IPC_CHANNELS.BRIDGE_CONTROL, {
        operation: 'choose',
        kind,
      }) as Promise<BridgeConfiguration | null>,
    health: () =>
      ipcRenderer.invoke(IPC_CHANNELS.BRIDGE_CONTROL, {
        operation: 'health',
      }) as Promise<BridgeHealthRead>,
    apply: (token, policy, requestId) =>
      ipcRenderer.invoke(IPC_CHANNELS.BRIDGE_CONTROL, {
        operation: 'apply',
        token,
        policy,
        requestId,
      }) as Promise<BridgeConfiguration>,
    cancel: (requestId) =>
      ipcRenderer.invoke(IPC_CHANNELS.BRIDGE_CONTROL, {
        operation: 'cancel',
        requestId,
      }) as Promise<void>,
  },
  onServerStatus: (callback) => {
    const subscription = (_event: IpcRendererEvent, value: ServerStatusEvent) =>
      callback(value);
    ipcRenderer.on(IPC_CHANNELS.SERVER_STATUS, subscription);
    return () => ipcRenderer.removeListener(IPC_CHANNELS.SERVER_STATUS, subscription);
  },
  openUrl: (url) => ipcRenderer.invoke(IPC_CHANNELS.OPEN_URL, url),
  osStats: () => ipcRenderer.invoke(IPC_CHANNELS.OS_STATS),
  close: () => ipcRenderer.invoke(IPC_CHANNELS.WINDOW_ACTIONS, 'close'),
  hide: () => ipcRenderer.invoke(IPC_CHANNELS.WINDOW_ACTIONS, 'hide'),
  minimize: () => ipcRenderer.invoke(IPC_CHANNELS.WINDOW_ACTIONS, 'minimize'),
  toggleMaximize: () =>
    ipcRenderer.invoke(IPC_CHANNELS.WINDOW_ACTIONS, 'toggle-maximize'),
  getStorage: async (type) => {
    return {
      get: (key) =>
        ipcRenderer.invoke(IPC_CHANNELS.STORAGE, { type, method: 'get', key }),
      set: (key, value) =>
        ipcRenderer.invoke(IPC_CHANNELS.STORAGE, { type, method: 'set', key, value }),
      delete: (key) =>
        ipcRenderer.invoke(IPC_CHANNELS.STORAGE, { type, method: 'delete', key }),
      save: () => ipcRenderer.invoke(IPC_CHANNELS.STORAGE, { type, method: 'save' }),
    };
  },
  log: (type, ...args) => ipcRenderer.invoke(IPC_CHANNELS.LOG, type, ...args),
  i18nOverride: async () => ipcRenderer.invoke(IPC_CHANNELS.I18N_OVERRIDE),
  showDecorations: () => {},
  setTranslations: () => {},
  openDialog: (options) => ipcRenderer.invoke(IPC_CHANNELS.OPEN_DIALOG, options),
  saveDialog: (options) => ipcRenderer.invoke(IPC_CHANNELS.SAVE_DIALOG, options),
  openConfigFolder: async () =>
    ipcRenderer.invoke(
      IPC_CHANNELS.OPEN_FILE,
      await ipcRenderer.invoke(IPC_CHANNELS.GET_FOLDER, 'config')
    ),
  openLogsFolder: async () =>
    ipcRenderer.invoke(
      IPC_CHANNELS.OPEN_FILE,
      await ipcRenderer.invoke(IPC_CHANNELS.GET_FOLDER, 'logs')
    ),
  openFile: (path) => ipcRenderer.invoke(IPC_CHANNELS.OPEN_FILE, path),
  ghGet: (req) => ipcRenderer.invoke(IPC_CHANNELS.GH_FETCH, req),
  setPresence: (options) => ipcRenderer.invoke(IPC_CHANNELS.DISCORD_PRESENCE, options),
  getInstallDir: () => ipcRenderer.invoke(IPC_CHANNELS.GET_FOLDER, 'exe'),
  isSteam: () => ipcRenderer.invoke(IPC_CHANNELS.IS_STEAM),
} satisfies IElectronAPI);
