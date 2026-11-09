import { test, expect, _electron } from '@playwright/test';
import { resolve } from 'node:path';
import { CoreUiFixture, routeUrl } from './fixtures';
import {
  overridePaths,
  type OverrideRequest,
  type OverrideSelection,
} from '../../electron/tracking-override-types';

test('isolated Electron loads the real App with zero Bridge IPC', async () => {
  const app = await _electron.launch({
    args: [resolve('tests/monaka-input-route/electron-main.cjs')],
    env: {
      ...process.env,
      STAGE15_ELECTRON_PROFILE: resolve(
        `build/monaka-input-route/electron-${Date.now()}`
      ),
    },
  });
  try {
    const page = await app.firstWindow();
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    const fixture = new CoreUiFixture();
    fixture.snapshot.candidates = [];
    await fixture.install(page);
    await page.goto(
      `http://127.0.0.1:${process.env.MONAKA_INPUT_SMOKE_PORT ?? '4207'}` + routeUrl
    );
    await expect(page.getByLabel('Q Source', { exact: true })).toBeEnabled();
    await expect(page.locator('.settings-layout')).toBeVisible();
    await page.reload();
    await expect(page.getByLabel('Q Source', { exact: true })).toBeEnabled();
    expect(
      await app.evaluate(
        () =>
          (globalThis as typeof globalThis & { stage15BridgeCalls: number })
            .stage15BridgeCalls
      )
    ).toBe(0);
    expect(fixture.saves).toEqual([]);
    expect(errors).toEqual([]);
    await page.screenshot({
      path: 'build/monaka-input-route/after-electron.png',
      fullPage: true,
    });
  } finally {
    await app.close();
  }
});

test('Tracking Override in the real App keeps hand choices separate and can remove without Core', async () => {
  test.setTimeout(90000);
  const app = await _electron.launch({
    args: [resolve('tests/monaka-input-route/electron-main.cjs')],
    env: {
      ...process.env,
      STAGE15_OVERRIDE_TEST: '1',
      STAGE15_ELECTRON_PROFILE: resolve(
        'build/monaka-input-route/override-' + Date.now()
      ),
    },
  });
  try {
    await app.evaluate(({ ipcMain }) => {
      const store = globalThis as typeof globalThis & {
        overrideRequests: OverrideRequest[];
      };
      store.overrideRequests = [];
      const hands = {
        left: { owned: false, conflict: false },
        right: { owned: false, conflict: false },
      };
      ipcMain.handle('stage15:override', (_event, request: OverrideRequest) => {
        store.overrideRequests.push(request);
        if (request.action === 'save') return { type: 'core' };
        if (request.action !== 'snapshot')
          hands[request.hand].owned = request.action === 'apply';
        return { type: 'steam', settings: { token: 'steam-review', hands } };
      });
    });
    const requests = () =>
      app.evaluate(
        () =>
          (globalThis as typeof globalThis & { overrideRequests: OverrideRequest[] })
            .overrideRequests
      );
    const page = await app.firstWindow();
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    const fixture = new CoreUiFixture();
    const initial: OverrideSelection = {
      enabled: false,
      source: null,
      gripOffset: null,
      sameUniverseConfirmed: false,
    };
    const output = {
      driverReady: false,
      hands: {
        left: {
          ...overridePaths('left'),
          saved: initial,
          sourceStatus: null,
          registeredDevicePath: null as string | null,
        },
        right: {
          ...overridePaths('right'),
          saved: initial,
          sourceStatus: null,
          registeredDevicePath: null as string | null,
        },
      },
    };
    fixture.snapshot.readbackToken = 'core-review';
    fixture.snapshot.trackingOverride = output;
    await fixture.install(page);
    const baseURL =
      'http://127.0.0.1:' + (process.env.MONAKA_INPUT_SMOKE_PORT ?? '4207');
    await page.goto(baseURL + routeUrl);
    const left = page.getByRole('group', { name: 'Left hand', exact: true });
    const right = page.getByRole('group', { name: 'Right hand', exact: true });
    const apply = 'Apply to SteamVR';
    const remove = 'Remove SteamVR mapping';
    const saveHand = async (hand: 'left' | 'right', source: string) => {
      const form = hand === 'left' ? left : right;
      const label = hand === 'left' ? 'Left hand' : 'Right hand';
      await form.getByLabel(label + ' 6DoF Source').selectOption(source);
      await form.getByRole('button', { name: 'Set identity grip offset' }).click();
      await form
        .getByLabel(
          'I verified the source world aligns with the SteamVR driver universe'
        )
        .check();
      await form.getByLabel(label + ' Enable pose source').check();
      await form.getByRole('button', { name: 'Save pose source' }).click();
      await expect
        .poll(async () =>
          (await requests()).some((x) => x.action === 'save' && x.hand === hand)
        )
        .toBe(true);
      const save = (await requests()).filter((x) => x.action === 'save').at(-1);
      if (!save || save.action !== 'save') throw new Error('Expected save');
      expect(save.selection).toEqual({
        enabled: true,
        source,
        sameUniverseConfirmed: true,
        gripOffset: { positionM: [0, 0, 0], rotationWxyz: [1, 0, 0, 0] },
      });
      output.hands[hand].saved = save.selection;
      await expect(form).toContainText('Saved MonakaVR source ON');
      await expect(form.getByRole('button', { name: apply })).toBeDisabled();
      output.hands[hand].registeredDevicePath = overridePaths(hand).devicePath;
      await form.getByRole('button', { name: apply }).click();
      await expect(form).toContainText('MonakaVR mapping saved');
    };
    await expect(left).toContainText('Saved MonakaVR source OFF');
    await expect(right).toContainText('Saved MonakaVR source OFF');
    await expect(left.locator('option[value="q-only"]')).toHaveJSProperty(
      'disabled',
      true
    );
    await expect(left.locator('option[value="unknown"]')).toHaveJSProperty(
      'disabled',
      true
    );
    await saveHand('left', 'publisher-a');
    await expect(right).toContainText('Saved MonakaVR source OFF');
    await saveHand('right', 'publisher-b');
    await expect(left).toContainText('/user/hand/left');
    await expect(right).toContainText('/user/hand/right');
    await expect(
      page.getByRole('status').filter({ hasText: 'SteamVR restart required' })
    ).toBeVisible();
    await page.reload();
    await expect(left.getByLabel('Left hand 6DoF Source')).toHaveValue('publisher-a');
    await expect(right.getByLabel('Right hand 6DoF Source')).toHaveValue('publisher-b');
    await expect(left).toContainText(
      'Active SteamVR mapping requires runtime verification'
    );
    fixture.disconnect();
    await expect(left.getByLabel('Left hand 6DoF Source')).toBeDisabled();
    await page.evaluate(() => {
      window.location.hash = '#/';
    });
    await page
      .getByRole('link', {
        name: 'Open Tracking Override settings to remove a SteamVR mapping',
      })
      .click();
    await expect(left.getByLabel('Left hand 6DoF Source')).toBeDisabled();
    await left.getByRole('button', { name: remove }).click();
    await expect(left).toContainText('No MonakaVR mapping');
    await expect(right).toContainText('MonakaVR mapping saved');
    fixture.reconnect();
    await left.getByRole('button', { name: apply }).click();
    await expect(left).toContainText('MonakaVR mapping saved');
    expect(
      (await requests()).filter((x) => x.action === 'apply' || x.action === 'remove')
    ).toEqual([
      { action: 'apply', hand: 'left', token: 'steam-review' },
      { action: 'apply', hand: 'right', token: 'steam-review' },
      { action: 'remove', hand: 'left', token: 'steam-review' },
      { action: 'apply', hand: 'left', token: 'steam-review' },
    ]);
    expect(errors).toEqual([]);
    await page.screenshot({
      path: 'build/monaka-input-route/tracking-override.png',
      fullPage: true,
    });
  } finally {
    await app.close();
  }
});
