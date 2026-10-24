// Isolated software main: no product main/service, producer, CLI or user prefs.
const { app, BrowserWindow, ipcMain } = require('electron');
const { resolve, join, sep } = require('node:path');
const root = resolve('build/monaka-input-route');
const profile = resolve(process.env.STAGE15_ELECTRON_PROFILE || '');
if (!profile.startsWith(root + sep))
  throw new Error('Owned fixture profile required');
app.setPath('userData', profile);
app.setPath('sessionData', join(profile, 'session'));
globalThis.stage15BridgeCalls = 0;
ipcMain.handle('stage15:bridge', () => {
  globalThis.stage15BridgeCalls++;
  throw new Error('No producer IPC is installed in this renderer smoke');
});
app.whenReady().then(() => {
  const window = new BrowserWindow({
    show: false,
    width: 1280,
    height: 1100,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: join(__dirname, 'electron-preload.cjs'),
    },
  });
  window.loadURL('about:blank');
});
app.on('window-all-closed', () => app.quit());
