import { test, expect, _electron as electron } from '@playwright/test';
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { basename, dirname, resolve } from 'node:path';

test('isolated Electron: actual preload/IPC/native validator and real tray menu entry', async () => {
  const executablePath = process.env.MONAKA_DESKTOP_TEST_ELECTRON;
  const validator = process.env.MONAKA_BRIDGE_TEST_P2R_VALIDATOR;
  if (!executablePath || !validator)
    throw new Error(
      'Set explicit pinned Electron and P2R validator paths; no native smoke is skipped.'
    );
  const parent = resolve('build');
  const root = await mkdtemp(resolve(parent, 'desktop-native-test-'));
  await mkdir(resolve(root, 'electron-user-data'));
  await mkdir(resolve(root, 'electron-session-data'));
  const configPath = resolve(root, 'bridge.json');
  const original = {
    namespace: 'monaka.bridge',
    version: 2,
    bridge_id: 'native-desktop-fixture',
    mapping_revision: 10,
    policy: 'steamvr',
    pose_timeout_ms: 500,
    profiles: {},
    mappings: [],
    test_extension: { preserve: 'software fixture only' },
  };
  await writeFile(configPath, JSON.stringify(original));
  const env: Record<string, string> = { MONAKA_DESKTOP_E2E_ROOT: root };
  for (const [key, value] of Object.entries(process.env))
    if (value !== undefined && key !== 'ELECTRON_RUN_AS_NODE') env[key] = value;
  const application = await electron.launch({
    executablePath,
    args: [resolve('build/bridge-native/main.cjs')],
    env,
  });
  try {
    const page = await application.firstWindow();
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    const policy = page.getByLabel('Selected output policy', { exact: true });
    await expect(policy).toHaveValue('steamvr');
    await page.getByRole('button', { name: 'Choose configuration' }).click();
    await expect(policy).toBeEnabled();
    await policy.selectOption('monaka');
    await page.getByRole('checkbox').check();
    await page.getByRole('button', { name: 'Apply output policy' }).click();
    await expect(page.getByRole('status')).toContainText('Saved monaka');
    const saved = JSON.parse(await readFile(configPath, 'utf8'));
    expect(saved).toEqual({ ...original, policy: 'monaka', mapping_revision: 11 });
    const backups = (await readdir(root)).filter((name) => name.includes('.backup-'));
    expect(backups).toHaveLength(1);
    expect(JSON.parse(await readFile(resolve(root, backups[0]), 'utf8'))).toEqual(
      original
    );
    // Real Electron MenuItem, invoked programmatically. Manual OS tray click is
    // deliberately a separately reported, unverified platform interaction.
    const menu = await application.evaluate(() => {
      const harness = (
        globalThis as unknown as {
          bridgeSmoke: {
            menu: { items: { label: string; submenu?: unknown; click: () => void }[] };
            tray: { isDestroyed: () => boolean };
          };
        }
      ).bridgeSmoke;
      const item = harness.menu.items[0];
      item.click();
      return {
        count: harness.menu.items.length,
        label: item.label,
        submenu: !!item.submenu,
        trayAlive: !harness.tray.isDestroyed(),
      };
    });
    expect(menu).toEqual({
      count: 1,
      label: 'Monaka Bridge…',
      submenu: false,
      trayAlive: true,
    });
    await expect.poll(() => page.evaluate(() => window.location.hash)).toBe('#/bridge');
    expect(JSON.parse(await readFile(configPath, 'utf8'))).toEqual(saved);
    await page.screenshot({
      path: 'build/gui-validation/desktop-native-ipc-tray.png',
      fullPage: true,
    });
    await page.getByRole('button', { name: 'Hide Desktop window' }).click();
    expect(errors).toEqual([]);
  } finally {
    await application.close();
    if (dirname(root) !== parent || !basename(root).startsWith('desktop-native-test-'))
      throw new Error('Unsafe native smoke cleanup path');
    await rm(root, { recursive: true, force: true });
  }
});
