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

test('public physical labels keep exact selection IDs, full details and a bounded picker at 375px', async ({
  page,
}) => {
  await page.setViewportSize({ width: 375, height: 900 });
  const core = fixture();
  core.snapshot.bodies.push('LEFT_HAND');
  await forbidBridgeApi(page);
  await core.install(page);
  await page.goto(routeUrl);
  await page
    .locator('.control#LEFT_HAND')
    .getByText('Left hand', { exact: true })
    .click();
  const picker = page.getByTestId('unified-assignment');
  const q = picker.getByRole('radio', { name: /^Q Source: HappyPC\/測/ });
  await expect(q).toBeEnabled();
  await expect(
    picker.getByRole('radio', { name: 'Q Source: HappyPC/device/knee · HappyPC left' })
  ).toBeVisible();
  await expect(
    picker.getByRole('radio', { name: 'Q Source: HappyPC/device/knee · HappyPC right' })
  ).toBeVisible();
  const primary = await q.getAttribute('aria-label');
  expect(primary).not.toContain('random-logical-key');
  expect(primary).not.toContain('11111111-1111');
  await q.check();
  await page.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'Save input choices', exact: true })
  ).toBeDisabled();
  expect(core.saves[0]).toMatchObject({
    qSource: persistent,
    sixDofSource: persistent,
  });
  expect(core.saves[0]).not.toHaveProperty('displayName');
  expect(
    await picker.evaluate((node) => node.scrollWidth <= node.clientWidth + 1)
  ).toBe(true);
  await expect(picker.getByTestId('assignment-readback')).toHaveAttribute(
    'title',
    /random-logical-key/
  );
  core.snapshot.candidates = [];
  await page.reload();
  await page
    .locator('.control#LEFT_HAND')
    .getByText('Left hand', { exact: true })
    .click();
  await expect(picker.getByTestId('assignment-readback')).toContainText(
    'Saved source offline'
  );
  expect(await picker.getByTestId('assignment-readback').textContent()).not.toContain(
    'mtp:36:'
  );
  await expect(picker.getByTestId('assignment-readback')).toHaveAttribute(
    'title',
    /Saved identity: mtp:36:/
  );
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
