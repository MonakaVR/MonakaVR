// TEST ENTRY ONLY. The imported production main/preload/renderer stay unchanged.
// This is a guarded startup check, not an unrestricted normal/release launch.
import electron from 'electron';
import childProcess from 'node:child_process';
import net from 'node:net';
import { syncBuiltinESMExports } from 'node:module';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const { app, Menu, session } = electron;
const root = process.env.MONAKA_STARTUP_ROOT;
if (!root || !path.isAbsolute(root))
  throw new Error('An isolated absolute data root is required.');
const data = path.join(root, 'app-data');
for (const name of [
  'app-data',
  'initial-user',
  'initial-session',
  'crash-dumps',
  'empty-core',
])
  mkdirSync(path.join(root, name), { recursive: true });
app.setPath('appData', data);
app.setPath('userData', path.join(root, 'initial-user'));
app.setPath('sessionData', path.join(root, 'initial-session'));
app.setPath('crashDumps', path.join(root, 'crash-dumps'));
const evidence = {
  packaged: app.isPackaged,
  blockedRequests: [],
  deniedChildren: [],
  deniedSockets: [],
  menuLabels: [],
  paths: {},
  menus: [],
};
globalThis.monakaStartupGuard = evidence;
const denyChild =
  (name) =>
  (...args) => {
    evidence.deniedChildren.push({ name, executable: String(args[0]) });
    throw new Error(`ISOLATED STARTUP: ${name} denied`);
  };
for (const name of [
  'spawn',
  'spawnSync',
  'exec',
  'execSync',
  'execFile',
  'execFileSync',
  'fork',
])
  childProcess[name] = denyChild(name);
net.Socket.prototype.connect = function (...args) {
  evidence.deniedSockets.push(String(args[0]));
  throw new Error('ISOLATED STARTUP: Node socket/pipe denied');
};
syncBuiltinESMExports();
globalThis.fetch = async (url) => {
  evidence.blockedRequests.push({ url: String(url), layer: 'node-fetch' });
  throw new Error('ISOLATED STARTUP: Node fetch denied');
};
const buildMenu = Menu.buildFromTemplate.bind(Menu);
Menu.buildFromTemplate = (template) => {
  const menu = buildMenu(template);
  if (template.some((item) => item.label?.startsWith('Monaka Bridge'))) {
    evidence.menuLabels = menu.items.map((item) => item.label || item.role);
    evidence.menus.push(menu);
  }
  return menu;
};
app.on('ready', () => {
  session.defaultSession.webRequest.onBeforeRequest(
    { urls: ['<all_urls>'] },
    (details, done) => {
      const scheme = new URL(details.url).protocol;
      if (
        scheme === 'app:' ||
        scheme === 'file:' ||
        scheme === 'devtools:' ||
        scheme === 'data:'
      )
        return done({ cancel: false });
      evidence.blockedRequests.push({
        url: details.url,
        layer: details.resourceType,
      });
      done({ cancel: true });
    }
  );
});
app.on('before-quit', () => {
  evidence.paths = Object.fromEntries(
    ['appData', 'userData', 'sessionData', 'logs', 'crashDumps'].map((name) => [
      name,
      app.getPath(name),
    ])
  );
  writeFileSync(
    path.join(root, 'guard-exit.json'),
    JSON.stringify({ ...evidence, menus: undefined }, null, 2)
  );
});
// Existing --path selects an empty task directory. The child-process guard also
// fails closed if any fallback path unexpectedly contains a Core JAR.
const here = path.dirname(fileURLToPath(import.meta.url));
const entry =
  process.env.MONAKA_STARTUP_PRODUCT ?? path.join(here, 'out/main/index.js');
process.argv = [
  process.execPath,
  entry,
  '--path',
  path.join(root, 'empty-core'),
];
delete process.env.APPDIR;
await import(pathToFileURL(entry).href);
