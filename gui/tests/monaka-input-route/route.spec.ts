import { test, expect } from '@playwright/test';
import { CoreUiFixture, forbidBridgeApi, routeUrl } from './fixtures';
import { overridePaths } from '../../electron/tracking-override-types';

test('old settings bookmark redirects to the normal assignment and preserves its query', async ({
  page,
}) => {
  const core = new CoreUiFixture();
  core.snapshot.candidates = [];
  await forbidBridgeApi(page);
  await core.install(page);
  await page.goto(routeUrl + '?review=hand');
  await expect(page).toHaveURL(
    /\?ip=127\.0\.0\.1&port=29992#\/onboarding\/trackers-assign\?review=hand$/
  );
  await expect(page.getByRole('heading', { name: 'Assign trackers' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Home', exact: true })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: /Tracking Override$/ })).toHaveCount(
    2
  );
  await expect(
    page.getByRole('checkbox', { name: 'Left hand Tracking Override' })
  ).toBeDisabled();
  expect(core.saves).toEqual([]);
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  await expect(
    page.getByRole('link', { name: 'MVIP inputs', exact: true })
  ).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Bridge configuration' })).toHaveCount(
    0
  );
});

test('mobile settings selector has no standalone MVIP route and the alias still reaches hand labels', async ({
  page,
}) => {
  await page.setViewportSize({ width: 375, height: 950 });
  await forbidBridgeApi(page);
  const core = new CoreUiFixture();
  const handRow = (hand: 'left' | 'right', source: string) => ({
    ...overridePaths(hand),
    source,
    canEnable: true,
    calibrationCurrent: false,
    sourceStatus: 'OFF' as const,
    registeredDevicePath: null,
    saved: { enabled: hand === 'left', gripOffset: null, sameUniverseConfirmed: false },
  });
  core.snapshot.bodies.push('LEFT_HAND', 'RIGHT_HAND');
  core.snapshot.assignments = [
    {
      body: 'LEFT_HAND',
      qSource: null,
      sixDofSource: 'publisher-a',
      manualSix: true,
      mode: 'ik',
    },
    {
      body: 'RIGHT_HAND',
      qSource: null,
      sixDofSource: 'publisher-b',
      manualSix: true,
      mode: 'ik',
    },
  ];
  core.snapshot.trackingOverride = {
    driverReady: false,
    hands: {
      left: handRow('left', 'publisher-a'),
      right: handRow('right', 'publisher-b'),
    },
  };
  await core.install(page);
  await page.goto('/?ip=127.0.0.1&port=29992#/settings/trackers');
  await page.locator('[role="combobox"][aria-controls="__dropdownList-link"]').click();
  await expect(
    page.locator('[id="__dropdownList-link-item-/settings/monaka-input"]')
  ).toHaveCount(0);
  await page.goto(routeUrl);
  await expect(page.locator('.control#LEFT_HAND').getByRole('checkbox')).toBeVisible();
  await expect(page.locator('.control#RIGHT_HAND').getByRole('checkbox')).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Left hand Offset Settings' })
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Right hand Offset Settings' })
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth + 1
    )
  ).toBe(true);
  expect(core.saves).toEqual([]);
});

test('retired producer control remains unreachable from the actual assignment route', async ({
  page,
}) => {
  await forbidBridgeApi(page);
  const core = new CoreUiFixture();
  await core.install(page);
  await page.goto(routeUrl);
  await expect(page.getByRole('heading', { name: 'Assign trackers' })).toBeVisible();
  await expect(page.locator('a[href="#/bridge"]')).toHaveCount(0);
  await page.goto('/?ip=127.0.0.1&port=29992#/bridge');
  await expect(page.getByRole('heading', { name: 'Bridge configuration' })).toHaveCount(
    0
  );
  expect(core.saves).toEqual([]);
});
