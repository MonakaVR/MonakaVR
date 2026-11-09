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

test('Grip offset edits preview live in millimetres and XYZ degrees', async () => {
  const app = await _electron.launch({
    args: [resolve('tests/monaka-input-route/electron-main.cjs')],
    env: {
      ...process.env,
      STAGE15_OVERRIDE_TEST: '1',
      STAGE15_ELECTRON_PROFILE: resolve(
        'build/monaka-input-route/grip-offset-' + Date.now()
      ),
    },
  });
  try {
    type OffsetSave = Extract<OverrideRequest, { action: 'save' }>;
    type Preview = Extract<OverrideRequest, { action: 'preview' }>;
    type OffsetFixture = {
      offsetSaves: OffsetSave[];
      previews: Preview[];
      blockNextPreview: boolean;
      releasePreview?: () => void;
    };
    await app.evaluate(({ ipcMain }) => {
      const store = globalThis as typeof globalThis & OffsetFixture;
      store.offsetSaves = [];
      store.previews = [];
      store.blockNextPreview = true;
      ipcMain.handle(
        'stage15:override',
        async (_event, request: OverrideRequest) => {
          if (request.action === 'preview') {
            store.previews.push(request);
            if (request.gripOffset && store.blockNextPreview) {
              store.blockNextPreview = false;
              await new Promise<void>((resolve) => {
                store.releasePreview = resolve;
              });
            }
            return { type: 'preview' };
          }
          if (request.action === 'save') store.offsetSaves.push(request);
          return { type: 'core' };
        }
      );
    });
    const requests = () =>
      app.evaluate(
        () => (globalThis as typeof globalThis & OffsetFixture).offsetSaves
      );
    const previews = () =>
      app.evaluate(
        () => (globalThis as typeof globalThis & OffsetFixture).previews
      );
    const release = () =>
      app.evaluate(() => {
        const store = globalThis as typeof globalThis & OffsetFixture;
        store.releasePreview?.();
        store.releasePreview = undefined;
      });
    const fixture = new CoreUiFixture();
    fixture.snapshot.bodies.push('LEFT_HAND');
    fixture.snapshot.assignments.push({
      body: 'LEFT_HAND',
      qSource: null,
      sixDofSource: 'publisher-a',
      manualSix: true,
      mode: 'ik',
    });
    const savedHalfAngle = (90.123456 * Math.PI) / 360;
    const savedGrip = {
      positionM: [0.01, -0.0000208491, 0.0000245491],
      rotationWxyz: [Math.cos(savedHalfAngle), 0, 0, Math.sin(savedHalfAngle)],
    } satisfies NonNullable<OverrideSelection['gripOffset']>;
    const saved = {
      enabled: true,
      sameUniverseConfirmed: true,
      gripOffset: savedGrip,
    };
    const left: OverrideOutputSnapshot['hands']['left'] = {
      ...overridePaths('left'),
      saved,
      source: 'publisher-a',
      canEnable: true,
      calibrationCurrent: true,
      sourceStatus: 'FULL',
      registeredDevicePath: null,
    };
    fixture.snapshot.readbackToken = 'core-review';
    fixture.snapshot.trackingOverride = {
      driverReady: false,
      hands: {
        left,
        right: {
          ...overridePaths('right'),
          saved: empty,
          source: null,
          canEnable: false,
          calibrationCurrent: false,
          sourceStatus: 'OFF',
          registeredDevicePath: null,
        },
      },
    };
    const page = await app.firstWindow();
    await fixture.install(page);
    await page.goto(baseURL + routeUrl);
    await page
      .getByRole('button', { name: 'Left hand Offset Settings' })
      .click();
    const detail = page.getByRole('dialog', {
      name: 'Left hand Tracking Override',
    });
    const field = (name: string) => detail.getByRole('spinbutton', { name });
    const saveOffset = detail.getByRole('button', {
      name: 'Save offset settings',
    });

    await expect(field('X (mm)')).toHaveValue('10');
    await expect(field('Y (mm)')).toHaveValue('-0.0208');
    await expect(field('Z (mm)')).toHaveValue('0.0245');
    await expect(field('X (°)')).toHaveValue('0');
    await expect(field('Y (°)')).toHaveValue('0');
    await expect(field('Z (°)')).toHaveValue('90.1235');
    await saveOffset.click();
    await expect.poll(async () => (await requests())[0]?.selection).toEqual(saved);
    await expect(field('X (mm)')).toBeEnabled();
    expect(await previews()).toEqual([]);
    await field('X (mm)').press('ArrowUp');
    await expect(field('X (mm)')).toHaveValue('11');
    await expect.poll(async () => (await previews()).length).toBe(1);
    await field('X (mm)').fill('12');
    await field('X (mm)').fill('13');
    await field('X (mm)').fill('14.00015');
    await field('X (mm)').fill('');
    await expect(saveOffset).toBeDisabled();
    await field('X (mm)').fill('1e308');
    await expect(saveOffset).toBeDisabled();
    expect(await requests()).toHaveLength(1);
    expect(
      (await previews()).map((request) => request.gripOffset?.positionM[0])
    ).toEqual([0.011]);
    await release();
    await expect
      .poll(async () =>
        (await previews())
          .slice(0, 3)
          .map((request) => request.gripOffset?.positionM[0])
      )
      .toEqual([0.011, 14.0002 / 1000, 14.0002 / 1000]);
    const confirmed = detail.getByLabel(
      'I verified the source world aligns with the SteamVR driver universe'
    );
    await confirmed.uncheck();
    await expect
      .poll(async () => (await previews()).at(-1)?.gripOffset)
      .toBeNull();
    await confirmed.check();
    const editedGrip = {
      ...savedGrip,
      positionM: [11.2345 / 1000, savedGrip.positionM[1], savedGrip.positionM[2]],
    };
    await field('X (mm)').fill('11.23445');
    await expect
      .poll(async () => (await previews()).at(-1)?.gripOffset)
      .toEqual(editedGrip);
    await expect(field('X (mm)')).toHaveValue('11.23445');
    // A save can run while the edited field still has focus.
    await saveOffset.evaluate((button: HTMLButtonElement) => button.click());
    await expect
      .poll(async () => (await requests())[1]?.selection)
      .toEqual({ ...saved, gripOffset: editedGrip });

    await field('X (°)').fill('1e308');
    await expect(saveOffset).toBeEnabled();

    await field('X (°)').fill('179');
    await field('X (°)').press('ArrowUp');
    await expect(field('X (°)')).toHaveValue('180');
    await field('Y (°)').fill('90');
    await field('Z (°)').fill('-89.99997');
    await field('Z (°)').press('Tab');
    await expect(field('Z (°)')).toHaveValue('-90');
    await saveOffset.click();
    await expect.poll(async () => (await requests()).length).toBe(3);
    const rotationSave = (await requests())[2].selection;
    const quaternion = rotationSave.gripOffset?.rotationWxyz;
    if (!quaternion) throw new Error('Expected rotation offset save');
    expect(Math.hypot(...quaternion)).toBeCloseTo(1, 12);
    // Intrinsic XYZ (180°, 90°, -90°) and (90°, 90°, 0°) encode the same rotation.
    expect(
      Math.abs(quaternion.reduce((dot, component) => dot + component * 0.5, 0))
    ).toBeCloseTo(1, 12);
    for (const component of quaternion) {
      expect(Math.abs(component)).toBeCloseTo(0.5, 12);
    }
    left.saved = rotationSave;
    await expect(field('X (°)')).toHaveValue('90');
    await expect(field('Y (°)')).toHaveValue('90');
    await expect(field('Z (°)')).toHaveValue('0');

    left.saved = {
      ...saved,
      gripOffset: { ...savedGrip, rotationWxyz: [1, 1e-14, 0, 0] },
    };
    await expect(field('X (°)')).toHaveValue('0');
    await detail
      .getByRole('button', { name: 'Set identity grip offset' })
      .click();
    await field('X (mm)').fill('12');
    await saveOffset.click();
    await expect
      .poll(async () => (await requests())[3]?.selection.gripOffset)
      .toEqual({
        positionM: [0.012, 0, 0],
        rotationWxyz: [1, 0, 0, 0],
      });

    await app.evaluate(() => {
      (globalThis as typeof globalThis & OffsetFixture).blockNextPreview = true;
    });
    await field('X (mm)').fill('15');
    await expect
      .poll(async () => (await previews()).at(-1)?.gripOffset?.positionM[0])
      .toBe(0.015);
    await field('X (mm)').fill('16');
    await detail.getByRole('button', { name: 'Close', exact: true }).click();
    await expect(detail).toBeHidden();
    await release();
    await expect
      .poll(async () => (await previews()).at(-1)?.gripOffset)
      .toBeNull();
    expect(
      (await previews()).some(
        (request) => request.gripOffset?.positionM[0] === 0.016
      )
    ).toBe(false);
  } finally {
    await app.close();
  }
});
