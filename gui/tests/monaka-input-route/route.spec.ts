import { test, expect } from '@playwright/test';
import { CoreUiFixture, forbidBridgeApi, routeUrl } from './fixtures';

test('real App settings opens with no producer or Electron API and retains normal navigation', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  fixture.snapshot.candidates = [];
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await forbidBridgeApi(page);
  await fixture.install(page);
  await page.goto(routeUrl);
  await expect(page.getByLabel('Q Source', { exact: true })).toBeEnabled();
  await expect(page.locator('.settings-layout')).toBeVisible();
  await expect(
    page.getByRole('link', { name: 'MVIP inputs', exact: true })
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Bridge configuration' })).toHaveCount(
    0
  );
  expect(await page.evaluate(() => typeof Reflect.get(window, 'electronAPI'))).toBe(
    'undefined'
  );
  await page.screenshot({
    path: 'build/monaka-input-route/after-desktop.png',
    fullPage: true,
  });
  await page.locator('a[href="#/"]').first().click();
  await expect(page.getByLabel('Q Source', { exact: true })).toHaveCount(0);
  await page.locator('a[href="#/settings/trackers"]').first().click();
  await expect(page.locator('.settings-layout')).toBeVisible();
  await page.getByRole('link', { name: 'MVIP inputs', exact: true }).click();
  await expect(page.getByLabel('Q Source', { exact: true })).toBeEnabled();
  await page.reload();
  await expect(page.getByLabel('Q Source', { exact: true })).toBeEnabled();
  expect(fixture.saves).toEqual([]);
  expect(errors).toEqual([]);
});

test('existing Q/6DoF widget keeps capability selection, opaque IDs, cancel and manual choices', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  await fixture.install(page);
  await page.goto(routeUrl);
  const q = page.getByLabel('Q Source', { exact: true });
  const six = page.getByLabel('6DoF Source', { exact: true });
  const save = page.getByRole('button', { name: 'Save input choices', exact: true });
  const cancel = page.getByRole('button', {
    name: 'Cancel input changes',
    exact: true,
  });
  await expect(q).toBeEnabled();
  await expect(q.locator('option[value="unknown"]')).toHaveJSProperty('disabled', true);
  await q.selectOption('q-only');
  await expect(six).toHaveValue('');
  await expect(six.locator('option[value="q-only"]')).toHaveJSProperty(
    'disabled',
    true
  );
  await cancel.click();
  await q.selectOption('publisher-a');
  await expect(six).toHaveValue('publisher-a');
  await expect(page.getByLabel('Current input usability')).toContainText('invalid now');
  await six.selectOption('publisher-b');
  await save.click();
  await expect(save).toBeDisabled();
  expect(fixture.saves[0]).toMatchObject({
    qSource: 'publisher-a',
    sixDofSource: 'publisher-b',
    manualSix: true,
  });
  await page.reload();
  await expect(q).toHaveValue('publisher-a');
  await expect(six).toHaveValue('publisher-b');
  await six.selectOption('');
  await save.click();
  await expect(save).toBeDisabled();
  await q.selectOption('publisher-b');
  await expect(six).toHaveValue('');
  await cancel.click();
  await page.getByLabel('Input body target').selectOption('CHEST');
  await q.selectOption('slime-q');
  await expect(six).toHaveValue('');
  await six.selectOption('publisher-b');
  await save.click();
  await expect(save).toBeDisabled();
  expect(
    fixture.snapshot.assignments.find((row) => row.body === 'CHEST')
  ).toMatchObject({ qSource: 'slime-q', sixDofSource: 'publisher-b' });
  expect(
    fixture.requests.every((request) =>
      ['snapshot', 'save'].includes(String(request.action))
    )
  ).toBe(true);
});

test('generation conflict and pending saved mode fail closed without changing the writer contract', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  await fixture.install(page);
  await page.goto(routeUrl);
  const q = page.getByLabel('Q Source', { exact: true });
  await expect(q).toBeEnabled();
  await q.selectOption('q-only');
  fixture.snapshot.generation = '1';
  fixture.snapshot.assignments = [
    {
      body: 'HIP',
      qSource: 'publisher-b',
      sixDofSource: null,
      manualSix: true,
      mode: 'ik',
    },
  ];
  await page.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Reload');
  expect(fixture.snapshot.assignments[0].qSource).toBe('publisher-b');
  await page.getByRole('button', { name: 'Reload input choices', exact: true }).click();
  await expect(q).toHaveValue('publisher-b');
  fixture.restartRequired = true;
  Object.assign(fixture.snapshot.assignments[0], {
    activeMode: 'ik',
    savedMode: 'hybrid',
    restartRequired: true,
  });
  await q.selectOption('q-only');
  await page.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('restart');
  expect(fixture.snapshot.assignments[0]).toMatchObject({
    qSource: 'publisher-b',
    activeMode: 'ik',
    savedMode: 'hybrid',
    restartRequired: true,
  });
  expect(fixture.saves.every((request) => !('mode' in request))).toBe(true);
});

test('producer loss/validity and Core disconnect/reconnect retain saved identities', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  fixture.snapshot.assignments = [
    {
      body: 'HIP',
      qSource: 'publisher-a',
      sixDofSource: 'publisher-b',
      manualSix: true,
      mode: 'ik',
    },
  ];
  await fixture.install(page);
  await page.goto(routeUrl);
  const q = page.getByLabel('Q Source', { exact: true });
  const six = page.getByLabel('6DoF Source', { exact: true });
  await expect(q).toHaveValue('publisher-a');
  fixture.snapshot.candidates.forEach((row) => {
    row.qUsableNow = true;
    row.sixDofUsableNow = row.sixDofSelectable;
  });
  await expect(page.getByLabel('Current input usability')).toContainText('valid now');
  fixture.snapshot.candidates = [];
  await expect(q.locator('option[value="publisher-a"]')).toContainText(
    'Saved source (offline)'
  );
  await expect(six).toHaveValue('publisher-b');
  fixture.disconnect();
  await expect(page.locator('.settings-layout')).toHaveCount(0);
  fixture.reconnect();
  await expect(q).toHaveValue('publisher-a', { timeout: 15000 });
  await expect(six).toHaveValue('publisher-b');
  expect(fixture.saves).toEqual([]);
});

test('mobile settings selector has an independent input route', async ({ page }) => {
  const fixture = new CoreUiFixture();
  await fixture.install(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/?ip=127.0.0.1&port=29992#/settings/trackers');
  await expect(page.locator('.settings-layout')).toBeVisible();
  await page.locator('[role="combobox"][aria-controls="__dropdownList-link"]').click();
  await page.locator('[id="__dropdownList-link-item-/settings/monaka-input"]').click();
  await expect(page.getByLabel('Q Source', { exact: true })).toBeEnabled();
  await page.screenshot({
    path: 'build/monaka-input-route/after-mobile.png',
    fullPage: true,
  });
  expect(fixture.saves).toEqual([]);
});

test('legacy Bridge screen remains accessible without embedding consumer inputs', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  await fixture.install(page);
  await page.goto('/?ip=127.0.0.1&port=29992#/bridge');
  await expect(
    page.getByRole('heading', { name: 'Monaka Bridge', exact: true })
  ).toBeVisible();
  await expect(page.getByLabel('Q Source', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Return to MonakaVR' })).toBeVisible();
  expect(fixture.requests).toEqual([]);
});
