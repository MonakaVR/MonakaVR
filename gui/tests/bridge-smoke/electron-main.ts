// TEST ENTRY ONLY. Deliberately never imports the production main/paths/logger,
// starts Core or touches user preferences. Built separately from product inputs.
import { app, BrowserWindow, ipcMain, Menu, Tray, nativeImage } from 'electron';
import { resolve } from 'node:path';
import { bridgeControlHandler } from '../../electron/main/monaka-bridge-ipc';
import { MonakaBridgeService } from '../../electron/main/monaka-bridge-service';
import { bridgeTrayEntry } from '../../electron/main/monaka-bridge-tray';
import { IPC_CHANNELS } from '../../electron/shared';

const fixtureRoot = process.env.MONAKA_DESKTOP_E2E_ROOT;
const validator = process.env.MONAKA_BRIDGE_TEST_P2R_VALIDATOR;
if (!fixtureRoot || !validator)
  throw new Error('Explicit isolated E2E paths required.');
app.setPath('userData', resolve(fixtureRoot, 'electron-user-data'));
app.setPath('sessionData', resolve(fixtureRoot, 'electron-session-data'));
app.whenReady().then(async () => {
  const configPath = resolve(fixtureRoot, 'bridge.json');
  const control = new MonakaBridgeService({
    read: async () => ({ configPath, validatorPath: validator }),
    write: async () => {
      throw new Error('File dialogs are outside this smoke fixture.');
    },
  });
  const window = new BrowserWindow({
    show: false,
    width: 1280,
    height: 1100,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: resolve('out/preload/index.js'),
    },
  });
  const handle = bridgeControlHandler(
    () => window.webContents.id,
    () => control,
    async () => null
  );
  ipcMain.handle(IPC_CHANNELS.BRIDGE_CONTROL, (event, request) =>
    handle(event.sender.id, request)
  );
  ipcMain.handle(IPC_CHANNELS.WINDOW_ACTIONS, (_event, action) => {
    if (action === 'hide') window.hide();
  });
  const menu = Menu.buildFromTemplate([
    bridgeTrayEntry(
      () => window.show(),
      () => window.webContents.send(IPC_CHANNELS.BRIDGE_OPEN)
    ),
  ]);
  const tray = new Tray(
    nativeImage.createFromPath(resolve('electron/resources/icons/icon.png'))
  );
  tray.setContextMenu(menu);
  tray.setToolTip('Monaka GUI SOFTWARE TEST MODE');
  Object.assign(globalThis, { bridgeSmoke: { menu, tray } });
  window.on('closed', () => control.dispose());
  app.on('before-quit', () => {
    control.dispose();
    tray.destroy();
  });
  await window.loadURL('http://127.0.0.1:4173/tests/bridge-smoke/native.html');
});
