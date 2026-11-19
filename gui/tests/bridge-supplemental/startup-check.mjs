import assert from 'node:assert/strict';
import { _electron as electron, expect } from '@playwright/test';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';

const output = process.env.MONAKA_SUPPLEMENT_ROOT;
const executable = process.env.MONAKA_STARTUP_EXE;
const mode = process.env.MONAKA_STARTUP_MODE ?? 'unpackaged-production';
assert.ok(output && executable, 'Explicit task-owned paths required');
const root = await mkdtemp(join(output, `startup-${mode}-`));
const settingsRoot = join(root, 'app-data/dev.slimevr.SlimeVR');
await mkdir(settingsRoot, { recursive: true });
const config = join(root, 'bridge.json');
await writeFile(
  config,
  JSON.stringify({
    namespace: 'monaka.bridge',
    version: 2,
    bridge_id: 'isolated-production-startup-fixture',
    mapping_revision: 10,
    policy: 'steamvr',
    pose_timeout_ms: 500,
    profiles: {},
    mappings: [],
  })
);
await writeFile(
  join(settingsRoot, 'gui-settings.dat'),
  JSON.stringify({
    monakaBridge: {
      configPath: config,
      validatorPath: process.env.MONAKA_LEGACY_CONFIG,
    },
    'config.json': JSON.stringify({
      uuid: '00000000-0000-4000-8000-000000000001',
      errorTracking: false,
      discordPresence: false,
    }),
  })
);
const env = {};
for (const [key, value] of Object.entries(process.env))
  if (
    value !== undefined &&
    ![
      'ELECTRON_RUN_AS_NODE',
      'NODE_OPTIONS',
      'ELECTRON_RENDERER_URL',
      'APPDIR',
      '_JAVA_OPTIONS',
      'JAVA_TOOL_OPTIONS',
      'JDK_JAVA_OPTIONS',
    ].includes(key)
  )
    env[key] = value;
env.MONAKA_STARTUP_ROOT = root;
if (mode === 'unpackaged-production')
  env.MONAKA_STARTUP_PRODUCT = resolve('out/main/index.js');
else delete env.MONAKA_STARTUP_PRODUCT;
const wrapper = resolve(
  'tests/bridge-supplemental/isolated-production-main.mjs'
);
const app = await electron.launch({
  executablePath: executable,
  args: mode === 'unpackaged-production' ? [wrapper] : [],
  env,
  timeout: 20000,
});
let guard,
  pageErrors = [],
  stderr = '',
  stdout = '',
  shutdown = 'NOT_RUN';
app.process().stderr.on('data', (chunk) => {
  stderr += chunk;
});
app.process().stdout.on('data', (chunk) => {
  stdout += chunk;
});
try {
  const page = await app.firstWindow({ timeout: 15000 });
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.waitForURL('app://./index.html', { timeout: 10000 });
  await expect(page.locator('body')).not.toHaveText('', { timeout: 10000 });
  const initialUrl = page.url();
  const tray = await app.evaluate(() => {
    const menu = globalThis.monakaStartupGuard.trayMenus.find((items) =>
      items.some(({ role }) => role === 'quit')
    );
    if (!menu) throw new Error('Actual production tray menu not created');
    return menu;
  });
  assert.deepEqual(tray.map(({ label, role }) => role || label), [
    'Show',
    'Hide',
    'quit',
  ]);
  assert.equal(
    await page.evaluate(() => {
      const api = window.electronAPI;
      return typeof api?.onServerStatus === 'function' && !('bridge' in api);
    }),
    true,
    'The real preload must expose the normal API without a producer API'
  );
  await page.evaluate(
    () =>
      new Promise((resolve) => {
        window.addEventListener(
          'hashchange',
          () => requestAnimationFrame(() => requestAnimationFrame(resolve)),
          { once: true }
        );
        window.location.hash = '/bridge';
      })
  );
  await page.waitForURL('**#/bridge');
  await expect(page.locator('#root > .text-standard')).toBeVisible();
  await expect(page.locator('#bridge-policy')).toHaveCount(0);
  await expect(
    page.locator(
      'a[href*="#/bridge"], a[href*="Start_MonakaBridge"], a[href*="monaka_bridge_config"]'
    )
  ).toHaveCount(0);
  await page.evaluate(() => {
    const label = document.createElement('p');
    label.textContent =
      'ISOLATED PRODUCTION STARTUP TEST - guard enabled, temporary data, no Core or output';
    label.style.cssText =
      'padding:12px;background:#831843;color:white;font-weight:bold';
    document.body.prepend(label);
  });
  await page.screenshot({ path: join(output, `${mode}.png`), fullPage: true });
  guard = await app.evaluate(({ app }) => {
    const value = globalThis.monakaStartupGuard;
    return {
      ...value,
      paths: Object.fromEntries(
        ['appData', 'userData', 'sessionData', 'crashDumps'].map((name) => [
          name,
          app.getPath(name),
        ])
      ),
    };
  });
  assert.equal(guard.packaged, mode !== 'unpackaged-production');
  assert.ok(guard.ipcChannels.includes('window-actions'));
  assert.ok(!guard.ipcChannels.some((channel) => channel.startsWith('monaka-bridge-')));
  assert.deepEqual(guard.deniedChildren, []);
  assert.deepEqual(guard.deniedSockets, []);
  assert.ok(
    guard.blockedRequests.some((item) =>
      item.url.startsWith('ws://localhost:21110')
    ),
    'The real renderer attempted its default socket and the guard blocked it'
  );
  for (const value of Object.values(guard.paths))
    assert.ok(value.startsWith(root), `Unisolated app path ${value}`);
  assert.deepEqual(pageErrors, []);
  assert.equal(JSON.parse(await readFile(config, 'utf8')).policy, 'steamvr');
  await writeFile(
    join(output, `${mode}-result.json`),
    JSON.stringify(
      {
        mode,
        result: 'PASS_GUARDED_STARTUP',
        root,
        initialUrl,
        tray,
        guard,
        pageErrors,
        productionMainSha256: createHash('sha256')
          .update(await readFile(resolve('out/main/index.js')))
          .digest('hex'),
        limits:
          'Guarded test entry imports unchanged production code. Not unrestricted normal/release launch; no manual OS click or Core/HIL/output validation.',
      },
      null,
      2
    )
  );
} finally {
  let timer;
  try {
    await Promise.race([
      app.close(),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error('shutdown timeout')), 10000);
      }),
    ]);
    shutdown = 'PASS';
  } catch (error) {
    shutdown = error.message;
    app.process().kill();
  } finally {
    clearTimeout(timer);
  }
  await writeFile(join(output, `${mode}-stdout.log`), stdout);
  await writeFile(join(output, `${mode}-stderr.log`), stderr);
  await writeFile(
    join(output, `${mode}-shutdown.json`),
    JSON.stringify({ shutdown, pageErrors }, null, 2)
  );
}
assert.equal(shutdown, 'PASS');
console.log(
  JSON.stringify({
    mode,
    result: 'PASS_GUARDED_STARTUP',
    shutdown,
    blockedRequests: guard.blockedRequests.length,
  })
);
