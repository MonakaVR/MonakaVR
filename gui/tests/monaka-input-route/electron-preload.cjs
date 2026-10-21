const { contextBridge, ipcRenderer } = require('electron');
const noop = () => {};
contextBridge.exposeInMainWorld('electronAPI', {
  isSteam: async () => false,
  osStats: async () => ({
    type: 'windows',
    architecture: 'x64',
    version: 'software fixture',
  }),
  log: noop,
  i18nOverride: async () => null,
  setTranslations: noop,
  setPresence: noop,
  onServerStatus: () => noop,
  getInstallDir: async () => 'SOFTWARE_FIXTURE_CONSUMER_INSTALL',
  ghGet: async () => null,
  getStorage: async (name) => ({
    get: async (key) =>
      localStorage.getItem(name === 'settings' ? key : name + '/' + key) ??
      undefined,
    set: async (key, value) =>
      localStorage.setItem(name === 'settings' ? key : name + '/' + key, value),
    delete: async (key) =>
      localStorage.removeItem(name === 'settings' ? key : name + '/' + key),
    save: async () => true,
  }),
  bridge: {
    load: () => ipcRenderer.invoke('stage15:bridge'),
    choose: () => ipcRenderer.invoke('stage15:bridge'),
    health: () => ipcRenderer.invoke('stage15:bridge'),
    apply: () => ipcRenderer.invoke('stage15:bridge'),
    cancel: () => ipcRenderer.invoke('stage15:bridge'),
  },
});
