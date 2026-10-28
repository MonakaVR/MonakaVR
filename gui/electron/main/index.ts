import {
  app,
  BrowserWindow,
  dialog,
  Menu,
  nativeImage,
  net,
  protocol,
  screen,
  shell,
  Tray,
} from 'electron';
import { IPC_CHANNELS } from '../shared';
import path, { dirname, join } from 'path';
import open from 'open';
import trayIcon from '../resources/icons/icon.png?asset';
import appleTrayIcon from '../resources/icons/Square30x30Logo.png?asset';
import { readFile, stat } from 'fs/promises';
import { pathToFileURL } from 'node:url';
import { getPlatform, handleIpc, isPortAvailable } from './utils';
import {
  instance,
  findServerJar,
  findSystemJRE,
  getExeFolder,
  getGuiDataFolder,
  getLogsFolder,
  getServerDataFolder,
  getWindowStateFile,
} from './paths';
import { initStores } from './store';
import { closeLogger, logger } from './logger';

import { spawn } from 'node:child_process';
import { discordPresence } from './presence';
import { options } from './cli';
import { ServerStatusEvent } from 'electron/preload/interface';
import { mkdir, writeFile } from 'node:fs/promises';
import { MenuItem } from 'electron/main';
import { MonakaBridgeService } from './monaka-bridge-service';
import { bridgeTrayEntry } from './monaka-bridge-tray';
import type { BridgeLocations } from '../bridge-types';
import { bridgeControlHandler } from './monaka-bridge-ipc';

type Stores = Awaited<ReturnType<typeof initStores>>;
let stores: Stores;

// Fixes colors looking washed on linux
// Might affect hdr
if (process.platform === 'linux') {
  app.commandLine.appendSwitch('disable-features', 'WaylandWpColorManagerV1');
  app.commandLine.appendSwitch('force-color-profile', 'srgb');
}

app.setPath('userData', getGuiDataFolder());
app.setPath('sessionData', join(getGuiDataFolder(), 'electron'));
if (!app.requestSingleInstanceLock()) app.exit(0);
app.on('second-instance', () => {
  if (mainWindow?.isMinimized()) mainWindow.restore();
  mainWindow?.show();
  mainWindow?.focus();
});

protocol.registerSchemesAsPrivileged([
  {
    scheme: 'app',
    privileges: {
      standard: true,
      secure: true,
      supportFetchAPI: true,
      corsEnabled: true,
      stream: true,
    },
  },
]);

let mainWindow: BrowserWindow | null = null;
let bridgeControl: MonakaBridgeService | null = null;
const bridgeHandler = bridgeControlHandler(
  () => mainWindow?.webContents.id ?? null,
  () => bridgeControl,
  async (kind) => {
    if (!mainWindow) throw new Error('Desktop window closed.');
    const result = await dialog.showOpenDialog(mainWindow, {
      title:
        kind === 'config'
          ? 'Choose the existing Bridge configuration'
          : 'Choose the tested monaka_bridge_config.exe',
      properties: ['openFile'],
      filters: [
        {
          name: kind === 'config' ? 'Bridge JSON' : 'Bridge validator',
          extensions: kind === 'config' ? ['json'] : ['exe'],
        },
      ],
    });
    return result.canceled ? null : (result.filePaths[0] ?? null);
  }
);
handleIpc(IPC_CHANNELS.BRIDGE_CONTROL, (event, request) =>
  bridgeHandler(event.sender.id, request)
);
handleIpc(IPC_CHANNELS.GH_FETCH, async (e, options) => {
  if (options.type === 'fw-releases') {
    return fetch(
      'https://api.github.com/repos/SlimeVR/SlimeVR-Tracker-ESP/releases'
    ).then((res) => res.json());
  }
  if (options.type === 'asset') {
    if (
      !options.url.startsWith(
        'https://github.com/SlimeVR/SlimeVR-Tracker-ESP/releases/download'
      )
    )
      return null;
    return fetch(options.url).then((res) => res.json());
  }
});

handleIpc(IPC_CHANNELS.OS_STATS, async () => {
  return {
    type: getPlatform(),
  };
});

handleIpc(IPC_CHANNELS.I18N_OVERRIDE, async () => {
  const overridefile = join(getServerDataFolder(), 'override.ftl');
  const exists = await stat(overridefile)
    .then(() => true)
    .catch(() => false);

  if (!exists) return false;
  return readFile(overridefile, { encoding: 'utf-8' });
});

handleIpc(IPC_CHANNELS.LOG, (e, type, ...args) => {
  let payload: Record<string, unknown> = {};
  const messageParts: unknown[] = [];

  args.forEach((arg) => {
    if (arg instanceof Error) {
      payload.err = arg;
    } else if (typeof arg === 'object' && arg !== null) {
      payload = { ...payload, ...arg };
    } else {
      messageParts.push(arg);
    }
  });

  const msg = messageParts.join(' ');

  switch (type) {
    case 'error':
      logger.error(payload, msg);
      break;
    case 'warn':
      logger.warn(payload, msg);
      break;
    default:
      logger.info(payload, msg);
  }
});

handleIpc(IPC_CHANNELS.OPEN_URL, (e, url) => {
  const allowedUrls = [
    /^steam:\/\//,
    /^ms-settings:network$/,
    /^https:\/\/(?:.+\.)?slimevr\.dev(?:\/.+)?$/,
    /^https:\/\/github\.com\/SlimeVR(?:\/.+)?$/,
    /^https:\/\/discord\.gg\/slimevr$/,
  ];
  if (allowedUrls.find((a) => url.match(a))) open(url);
  else logger.error({ url }, 'attempted to open non-whitelisted URL');
});

handleIpc(IPC_CHANNELS.STORAGE, async (e, { type, method, key, value }) => {
  const store = stores[type];
  if (!store) throw new Error(`Storage type ${type} not found`);

  switch (method) {
    case 'get':
      return store.get(key!);
    case 'set':
      return store.set(key!, value);
    case 'delete':
      return store.delete(key!);
    case 'save':
      return store.save();
  }
});

handleIpc(IPC_CHANNELS.DISCORD_PRESENCE, async (e, options) => {
  if (options.enable) {
    if (!discordPresence.state.ready) await discordPresence.connect();
    discordPresence.updateActivity(options.activity, options.iconText);
  } else if (discordPresence.state.ready) {
    discordPresence.destroy();
  }
});

handleIpc(IPC_CHANNELS.OPEN_FILE, (e, folder) => {
  const requestedPath = path.resolve(folder);

  const isAllowed = [getServerDataFolder(), getGuiDataFolder(), getLogsFolder()].some(
    (parent) => {
      const absoluteParent = path.resolve(parent);
      const relative = path.relative(absoluteParent, requestedPath);
      return !relative.includes('..') && !path.isAbsolute(relative);
    }
  );

  if (isAllowed) {
    shell.openPath(requestedPath);
  } else {
    logger.error({ path: requestedPath }, 'Blocked unauthorized path');
  }
});

handleIpc(IPC_CHANNELS.GET_FOLDER, (e, folder) => {
  switch (folder) {
    case 'config':
      return getGuiDataFolder();
    case 'logs':
      return getLogsFolder();
    case 'exe':
      return getExeFolder();
  }
});

handleIpc(IPC_CHANNELS.IS_STEAM, () => {
  return options.steam;
});

const defaultWindowState: {
  width: number;
  height: number;
  x?: number;
  y?: number;
} = {
  width: 1289.0,
  height: 709.0,
  x: undefined,
  y: undefined,
};

const windowState = await readFile(getWindowStateFile(), {
  encoding: 'utf-8',
})
  .then((data) => JSON.parse(data))
  .catch(() => {
    logger.error('Failed to load window state, using defaults');
    return defaultWindowState;
  });

const MIN_WIDTH = 393;
const MIN_HEIGHT = 667;

function validateWindowState(state: typeof defaultWindowState) {
  if (state.x === undefined || state.y === undefined) {
    return state;
  }

  const displays = screen.getAllDisplays();

  const isVisible = displays.some((display) => {
    return (
      state.x! >= display.bounds.x &&
      state.y! >= display.bounds.y &&
      state.x! + state.width <= display.bounds.x + display.bounds.width &&
      state.y! + state.height <= display.bounds.y + display.bounds.height
    );
  });

  const minWidth = MIN_WIDTH;
  const minHeight = MIN_HEIGHT;

  if (!isVisible || state.width < minWidth || state.height < minHeight) {
    return defaultWindowState;
  }

  return state;
}

const saveWindowState = async () => {
  await mkdir(dirname(getWindowStateFile()), { recursive: true });
  await writeFile(getWindowStateFile(), JSON.stringify(windowState), {
    encoding: 'utf-8',
  });
};

function createWindow() {
  const validatedState = validateWindowState(windowState);

  mainWindow = new BrowserWindow({
    width: validatedState.width,
    height: validatedState.height,
    x: validatedState.x,
    y: validatedState.y,
    minHeight: MIN_HEIGHT,
    minWidth: MIN_WIDTH,
    movable: true,
    frame: false,
    roundedCorners: true,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      nodeIntegration: false,
      contextIsolation: true,
      devTools: true,
    },
  });

  if (process.env.ELECTRON_RENDERER_URL) {
    mainWindow.loadURL(process.env.ELECTRON_RENDERER_URL);
    mainWindow.webContents.openDevTools();
  } else {
    mainWindow.loadURL(`app://./index.html?port=${instance.port}`);
  }

  mainWindow.on('closed', () => {
    bridgeControl?.dispose();
    mainWindow = null;
  });

  handleIpc('window-actions', (e, action) => {
    if (mainWindow === null) return;
    switch (action) {
      case 'close':
        mainWindow.close();
        break;
      case 'hide':
        mainWindow.hide();
        break;
      case 'minimize':
        mainWindow.minimize();
        break;
      case 'toggle-maximize':
        if (mainWindow.isMaximized()) mainWindow.unmaximize();
        else mainWindow.maximize();
        break;
    }
  });

  handleIpc('open-dialog', (e, options) => dialog.showOpenDialog(options));
  handleIpc('save-dialog', (e, options) => dialog.showSaveDialog(options));

  const icon = nativeImage.createFromPath(
    getPlatform() === 'macos' ? appleTrayIcon : trayIcon
  );
  const tray = new Tray(icon);
  tray.setToolTip('SlimeVR');
  tray.on('click', () => {
    mainWindow?.show();
  });
  const contextMenu = Menu.buildFromTemplate([
    bridgeTrayEntry(
      () => mainWindow?.show(),
      () => mainWindow?.webContents.send(IPC_CHANNELS.BRIDGE_OPEN)
    ),
    {
      label: 'Show',
      click: () => {
        mainWindow?.show();
      },
    },
    {
      label: 'Hide',
      click: () => {
        mainWindow?.hide();
      },
    },
    { role: 'quit' },
  ]);
  tray.setContextMenu(contextMenu);

  const updateWindowState = () => {
    if (!mainWindow) return;

    windowState.minimized = mainWindow.isMinimized();
    if (!mainWindow.isMinimized() && !mainWindow.isMaximized()) {
      const bounds = mainWindow.getBounds();
      windowState.width = bounds.width;
      windowState.height = bounds.height;
      windowState.x = bounds.x;
      windowState.y = bounds.y;
    }
  };

  mainWindow.on('move', updateWindowState);
  mainWindow.on('resize', updateWindowState);
  mainWindow.on('minimize', updateWindowState);
  mainWindow.on('maximize', updateWindowState);

  mainWindow.webContents.on('context-menu', (event, params) => {
    const menu = new Menu();

    menu.append(
      new MenuItem({
        label: 'Inspect Element',
        click: () => {
          mainWindow?.webContents.inspectElement(params.x, params.y);
        },
      })
    );

    menu.append(new MenuItem({ type: 'separator' }));
    menu.append(new MenuItem({ label: 'Copy', role: 'copy' }));
    menu.append(new MenuItem({ label: 'Paste', role: 'paste' }));

    if (mainWindow) menu.popup({ window: mainWindow });
  });
}

const checkEnvironmentVariables = () => {
  const disallowedVars = ['_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS'];

  const set = disallowedVars.filter((env) => !!process.env[env]);
  if (set.length > 0) {
    dialog.showErrorBox(
      'SlimeVR',
      `You have environment variables ${set.join(', ')} set, which may cause the SlimeVR Server to fail to launch properly.`
    );
    app.quit();
  }
};

const isServerRunning = async () => !(await isPortAvailable(instance.port));

const spawnServer = async () => {
  if (options.skipServerIfRunning && (await isServerRunning())) {
    logger.info(
      { skipServerIfRunning: options.skipServerIfRunning },
      'Server is already running, skipping server start'
    );
    return;
  }

  const serverJar = findServerJar();
  if (!serverJar) {
    logger.info('server jar not found, skipping');
    return;
  }
  const sharedDir = dirname(serverJar);
  const javaBin = await findSystemJRE(sharedDir);
  if (!javaBin) {
    dialog.showErrorBox(
      'SlimeVR',
      'Unable to find a compatible Java version, please download Java 17 or higher'
    );
    app.quit();
    return;
  }

  logger.info({ javaBin, serverJar }, 'Found Java and server jar');
  const platform = getPlatform();

  const serverArgs = ['-Xmx128M', '-jar', serverJar];
  if (options.steam) serverArgs.push('--steam');
  if (options.install) serverArgs.push('--install');
  if (options.noUdev) serverArgs.push('--no-udev');
  if (options.monakaMtp) serverArgs.push('--monaka-mtp');

  serverArgs.push('run');

  const serverProcess = spawn(javaBin, serverArgs, {
    cwd: sharedDir,
    shell: false,
    env: {
      ...process.env,
      MONAKA_PROFILE_DIRECTORY: instance.profile,
      MONAKA_NETWORK_ONLY: String(instance.networkOnly),
      MONAKA_WEBSOCKET_PORT: String(instance.port),
      ...(platform === 'windows'
        ? {
            APPDATA: instance.profile
              ? path.join(instance.profile, 'roaming')
              : app.getPath('appData'),
            LOCALAPPDATA: instance.profile
              ? path.join(instance.profile, 'local')
              : process.env['USERPROFILE']
                ? path.join(process.env['USERPROFILE'], 'AppData', 'Local')
                : undefined,
          }
        : {}),
    },
  });

  const sendToWindow = (event: ServerStatusEvent) => {
    if (mainWindow && !mainWindow.webContents.isDestroyed()) {
      mainWindow.webContents.send(IPC_CHANNELS.SERVER_STATUS, event);
    }
  };

  serverProcess.stdout?.on('data', (message) => {
    sendToWindow({ message: message.toString(), type: 'stdout' });
  });

  serverProcess.stderr?.on('data', (message) => {
    sendToWindow({ message: message.toString(), type: 'stderr' });
  });

  serverProcess.on('error', (err) => {
    logger.info({ err }, 'Error launching the java server');
    if (!isQuitting) app.quit();
  });

  serverProcess.on('exit', () => {
    logger.info('Server process exiting');
  });

  const exited = new Promise<void>((resolve) => serverProcess.once('exit', resolve));

  return {
    process: serverProcess,
    close: () => {
      // Graceful request targets only this retained child. Bound the fallback.
      serverProcess.stdin?.write('exit\n');
      const deadline = setTimeout(() => {
        if (serverProcess.exitCode === null) serverProcess.kill();
      }, 5000);
      serverProcess.once('exit', () => clearTimeout(deadline));
    },
    waitForExit: () => exited,
  };
};

const createFolders = async () => {
  await mkdir(getServerDataFolder(), { recursive: true });
  await mkdir(getGuiDataFolder(), { recursive: true });
};

let isQuitting = false;

app.whenReady().then(async () => {
  protocol.handle('app', (request) => {
    const { pathname } = new URL(request.url);
    const filePath = path.normalize(join(__dirname, '../renderer', pathname));
    return net.fetch(pathToFileURL(filePath).toString(), { headers: request.headers });
  });

  try {
    await createFolders();
  } catch (err) {
    logger.error(err, 'Failed to initialize stores');
    dialog.showErrorBox(
      'SlimeVR',
      'Failed to initialize application storage. Please make sure the application has write permissions to its data folder.'
    );
    app.quit();
    return;
  }

  stores = await initStores();
  bridgeControl = new MonakaBridgeService({
    read: async () => {
      const saved = stores.settings.get<Partial<BridgeLocations>>('monakaBridge');
      return {
        configPath: typeof saved?.configPath === 'string' ? saved.configPath : null,
        validatorPath:
          typeof saved?.validatorPath === 'string' ? saved.validatorPath : null,
      };
    },
    write: async (value) => {
      const previous = stores.settings.get('monakaBridge');
      stores.settings.set('monakaBridge', value);
      if (!(await stores.settings.save())) {
        stores.settings.set('monakaBridge', previous);
        throw new Error('Desktop could not save the Bridge file selection.');
      }
    },
  });
  checkEnvironmentVariables();
  const server = await spawnServer();

  createWindow();

  logger.info('SlimeVR started!');

  app.on('window-all-closed', () => {
    app.quit();
  });

  app.on('before-quit', async (event) => {
    if (isQuitting) return;
    isQuitting = true;
    event.preventDefault();
    logger.info('App quitting, saving...');
    bridgeControl?.dispose();
    server?.close();
    await server?.waitForExit();
    await stores.settings.save();
    await stores.cache.save();
    discordPresence.destroy();
    await saveWindowState();
    await closeLogger();
    app.exit(0);
  });
});
