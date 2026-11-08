import { test, expect } from '@playwright/test';
import { CoreUiFixture, candidate, forbidBridgeApi, routeUrl } from './fixtures';

const persistent =
  'mtp:36:11111111-1111-4111-8111-11111111111136:22222222-2222-4222-8222-22222222222218:random-logical-key';
const physical = `HappyPC/${'測'.repeat(80)}`;
function fixture() {
  const core = new CoreUiFixture();
  core.snapshot.candidates = [
    {
      ...candidate(persistent),
      publisher: '11111111-1111-4111-8111-111111111111',
      source: '22222222-2222-4222-8222-222222222222',
      tracker: 'random-logical-key',
      displayName: physical,
    },
    {
      ...candidate('logical-left'),
      publisher: 'HappyPC left',
      source: 'source-left',
      displayName: 'HappyPC/device/knee',
    },
    {
      ...candidate('logical-right'),
      publisher: 'HappyPC right',
      source: 'source-right',
      displayName: 'HappyPC/device/knee',
    },
  ];
  return core;
}

test('public physical labels keep exact selection IDs, full details and bounded settings at 375px', async ({
  page,
}) => {
  await page.setViewportSize({ width: 375, height: 900 });
  const core = fixture();
  await forbidBridgeApi(page);
  await core.install(page);
  await page.goto(routeUrl);
  const q = page.getByLabel('Q Source', { exact: true });
  await expect(q).toBeEnabled();
  await expect(q.locator('option[value="logical-left"]')).toContainText(
    'HappyPC/device/knee · HappyPC left'
  );
  await expect(q.locator('option[value="logical-right"]')).toContainText(
    'HappyPC/device/knee · HappyPC right'
  );
  const primary = await q
    .locator('option')
    .filter({ hasText: 'HappyPC/測' })
    .textContent();
  expect(primary).not.toContain('random-logical-key');
  expect(primary).not.toContain('11111111-1111');
  await q.selectOption(persistent);
  await expect(q).toHaveAttribute('title', new RegExp('random-logical-key'));
  await page.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'Save input choices', exact: true })
  ).toBeDisabled();
  expect(core.saves[0]).toMatchObject({
    qSource: persistent,
    sixDofSource: persistent,
  });
  expect(core.saves[0]).not.toHaveProperty('displayName');
  const section = page.locator('section[aria-labelledby="monaka-input-title"]');
  expect(
    await section.evaluate((node) => node.scrollWidth <= node.clientWidth + 1)
  ).toBe(true);
  const sectionBox = await section.boundingBox();
  const selectBox = await q.boundingBox();
  expect(selectBox!.width).toBeLessThanOrEqual(sectionBox!.width);
  core.snapshot.candidates = [];
  await page.reload();
  await expect(q).toHaveValue(persistent);
  await expect(q.locator('option:checked')).toHaveText('Saved source (offline)');
  expect(await q.locator('option:checked').textContent()).not.toContain('mtp:36:');
  await expect(q).toHaveAttribute('title', `Saved identity: ${persistent}`);
  expect(core.saves).toHaveLength(1);
});

test('normal assignment picker and body summary share physical labels without expanding narrow layout', async ({
  page,
}) => {
  await page.setViewportSize({ width: 375, height: 1000 });
  const core = fixture();
  core.snapshot.assignments = [
    {
      body: 'HIP',
      qSource: persistent,
      sixDofSource: persistent,
      manualSix: true,
      mode: 'ik',
    },
  ];
  await forbidBridgeApi(page);
  await core.install(page);
  await page.addInitScript(() => {
    const config = JSON.parse(localStorage.getItem('config.json')!);
    localStorage.setItem(
      'config.json',
      JSON.stringify({ ...config, assignMode: 'all' })
    );
  });
  await page.goto('/?ip=127.0.0.1&port=29992#/onboarding/trackers-assign');
  const summary = page.getByTestId('assignment-summary-HIP');
  await expect(summary).toContainText('HappyPC/');
  expect(await summary.textContent()).not.toContain('random-logical-key');
  await expect(summary).toHaveAttribute('title', new RegExp('random-logical-key'));
  expect((await summary.boundingBox())!.height).toBeLessThanOrEqual(34);
  await page.locator('.control#HIP').click();
  const picker = page.getByTestId('unified-assignment');
  await expect(picker).toBeVisible();
  const left = picker.getByRole('radio', {
    name: 'Q Source: HappyPC/device/knee · HappyPC left',
    exact: true,
  });
  const right = picker.getByRole('radio', {
    name: 'Q Source: HappyPC/device/knee · HappyPC right',
    exact: true,
  });
  await expect(left).toBeEnabled();
  await expect(right).toBeEnabled();
  await expect(picker.getByTestId('assignment-readback')).toContainText('HappyPC/');
  await expect(picker.getByTestId('assignment-readback')).toHaveAttribute(
    'title',
    new RegExp('random-logical-key')
  );
  expect(
    await picker.evaluate((node) => node.scrollWidth <= node.clientWidth + 1)
  ).toBe(true);
  await left.check();
  await expect(left).toBeChecked();
  await expect(right).not.toBeChecked();
  await picker.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await expect(
    picker.getByRole('button', { name: 'Save input choices', exact: true })
  ).toBeDisabled();
  expect(core.saves[0]).toMatchObject({
    qSource: 'logical-left',
    sixDofSource: persistent,
  });
});
