import { test, expect, _electron } from '@playwright/test';
import { resolve } from 'node:path';
import { CoreUiFixture, routeUrl } from './fixtures';
import {
  overridePaths,
  type OverrideOutputSnapshot,
  type OverrideRequest,
  type OverrideSelection,
} from '../../electron/tracking-override-types';

const baseURL = 'http://127.0.0.1:' + (process.env.MONAKA_INPUT_SMOKE_PORT ?? '4207');
const empty: OverrideSelection = {
  enabled: false,
  gripOffset: null,
  sameUniverseConfirmed: false,
};

test('isolated Electron opens the canonical assignment without producer IPC', async () => {
  const app = await _electron.launch({
    args: [resolve('tests/monaka-input-route/electron-main.cjs')],
    env: {
      ...process.env,
      STAGE15_ELECTRON_PROFILE: resolve(
        'build/monaka-input-route/electron-' + Date.now()
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
    await page.goto(baseURL + routeUrl);
    await expect(page.getByRole('heading', { name: 'Assign trackers' })).toBeVisible();
    await expect(page).toHaveURL(/#\/onboarding\/trackers-assign/);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Assign trackers' })).toBeVisible();
    expect(
      await app.evaluate(
        () =>
          (globalThis as typeof globalThis & { stage15BridgeCalls: number })
            .stage15BridgeCalls
      )
    ).toBe(0);
    expect(fixture.saves).toEqual([]);
    expect(errors).toEqual([]);
  } finally {
    await app.close();
  }
});

test('hand Override starts before the normal chooser and its mapping remains recoverable without Core', async () => {
  test.setTimeout(120000);
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
    fixture.snapshot.bodies.push('LEFT_HAND', 'RIGHT_HAND');
    const row = (hand: 'left' | 'right') => ({
      ...overridePaths(hand),
      saved: empty,
      source: null,
      canEnable: false,
      calibrationCurrent: false,
      sourceStatus: 'OFF' as const,
      registeredDevicePath: null,
    });
    const output: OverrideOutputSnapshot = {
      driverReady: false,
      hands: { left: row('left'), right: row('right') },
    };
    fixture.snapshot.readbackToken = 'core-review';
    fixture.snapshot.trackingOverride = output;
    fixture.afterSave = () => {
      for (const hand of ['left', 'right'] as const) {
        const source =
          fixture.snapshot.assignments.find(
            (a) => a.body === hand.toUpperCase() + '_HAND'
          )?.sixDofSource ?? null;
        output.hands[hand].source = source;
        output.hands[hand].canEnable = !!source;
      }
      return false;
    };
    await fixture.install(page);
    await page.goto(baseURL + routeUrl);
    const left = page.locator('.control#LEFT_HAND');
    const right = page.locator('.control#RIGHT_HAND');
    const leftToggle = left.getByRole('checkbox', {
      name: 'Left hand Tracking Override',
    });
    const rightToggle = right.getByRole('checkbox', {
      name: 'Right hand Tracking Override',
    });
    const leftOffset = left.getByRole('button', { name: 'Left hand Offset Settings' });
    const rightOffset = right.getByRole('button', {
      name: 'Right hand Offset Settings',
    });
    await expect(
      page.getByRole('checkbox', { name: /Tracking Override$/ })
    ).toHaveCount(2);
    await expect(leftOffset).toHaveCount(0);
    await expect(rightOffset).toHaveCount(0);
    await leftToggle.check();
    await expect(leftToggle).toBeChecked();
    await expect(rightToggle).not.toBeChecked();
    await expect(page.getByTestId('unified-assignment')).toHaveCount(0);
    expect((await requests()).filter((x) => x.action === 'save')).toEqual([]);

    const assign = async (hand: 'left' | 'right', publisher: string) => {
      const card = hand === 'left' ? left : right;
      await card
        .getByText(hand === 'left' ? 'Left hand' : 'Right hand', { exact: true })
        .click();
      const chooser = page.getByTestId('unified-assignment');
      await expect(chooser).toBeVisible();
      await chooser
        .getByRole('radio', { name: '6DoF Source: device-' + publisher, exact: true })
        .check();
      await chooser
        .getByRole('button', { name: 'Save input choices', exact: true })
        .click();
      await expect(chooser.getByTestId('assignment-readback')).toContainText(publisher);
      await chooser.getByRole('button', { name: 'Close', exact: true }).click();
    };
    await assign('left', 'publisher-a');
    await expect(leftOffset).toBeVisible();
    await expect(rightOffset).toHaveCount(0);
    await assign('right', 'publisher-b');
    await expect(rightOffset).toBeVisible();
    expect(fixture.saves).toEqual([
      expect.objectContaining({ body: 'LEFT_HAND', sixDofSource: 'publisher-a' }),
      expect.objectContaining({ body: 'RIGHT_HAND', sixDofSource: 'publisher-b' }),
    ]);

    await page.screenshot({
      path: 'build/monaka-input-route/tracking-override-avatar.png',
      fullPage: true,
    });
    const initial = await left.boundingBox();
    await page.getByText('Mirror view', { exact: true }).click();
    await expect.poll(async () => (await left.boundingBox())?.x).not.toBe(initial?.x);
    await expect(left.getByTestId('assignment-summary-LEFT_HAND')).toContainText(
      'publisher-a'
    );
    await expect(right.getByTestId('assignment-summary-RIGHT_HAND')).toContainText(
      'publisher-b'
    );
    await expect(leftToggle).toBeChecked();
    await expect(rightToggle).not.toBeChecked();

    await leftOffset.click();
    const detail = page.getByRole('dialog', { name: 'Left hand Tracking Override' });
    await expect(detail).toContainText('device-publisher-a');
    await expect(detail.getByRole('combobox')).toHaveCount(0);
    await detail.getByRole('button', { name: 'Set identity grip offset' }).click();
    await detail
      .getByLabel('I verified the source world aligns with the SteamVR driver universe')
      .check();
    await detail.getByRole('button', { name: 'Save offset settings' }).click();
    await expect
      .poll(async () => (await requests()).filter((x) => x.action === 'save').length)
      .toBe(1);
    const save = (await requests()).find((x) => x.action === 'save');
    if (!save || save.action !== 'save') throw new Error('Expected offset save');
    expect(save.selection).toEqual({
      enabled: true,
      sameUniverseConfirmed: true,
      gripOffset: { positionM: [0, 0, 0], rotationWxyz: [1, 0, 0, 0] },
    });
    output.hands.left.saved = save.selection;
    output.hands.left.calibrationCurrent = true;
    output.hands.left.sourceStatus = 'FULL';
    const apply = detail.getByRole('button', { name: 'Apply to SteamVR' });
    await expect(apply).toBeDisabled();
    output.hands.left.registeredDevicePath = overridePaths('left').devicePath;
    await apply.click();
    await expect(detail).toContainText('MonakaVR mapping saved');
    await expect(detail).toContainText('SteamVR restart required');
    await detail.getByRole('button', { name: 'Close', exact: true }).click();
    await page.reload();
    await expect(leftToggle).toBeChecked();
    await expect(rightToggle).not.toBeChecked();

    fixture.disconnect();
    await expect(page.getByText(/Core is unavailable/)).toBeVisible({ timeout: 10000 });
    await page.evaluate(() => {
      window.location.hash = '#/';
    });
    await page
      .getByRole('link', {
        name: 'Open Tracking Override settings to remove a SteamVR mapping',
      })
      .click();
    await page.getByRole('button', { name: 'Release SteamVR mapping' }).click();
    await page.getByRole('button', { name: 'Remove SteamVR mapping' }).click();
    await expect(page.getByRole('dialog')).toContainText('No MonakaVR mapping');
    expect(
      (await requests()).filter((x) => x.action === 'apply' || x.action === 'remove')
    ).toEqual([
      { action: 'apply', hand: 'left', token: 'steam-review' },
      { action: 'remove', hand: 'left', token: 'steam-review' },
    ]);
    expect(errors).toEqual([]);
  } finally {
    await app.close();
  }
});
