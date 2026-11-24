import { test, expect } from '@playwright/test';
import { CoreUiFixture, candidate, forbidBridgeApi, routeUrl } from './fixtures';

const persistent =
  'mtp:36:11111111-1111-4111-8111-11111111111136:22222222-2222-4222-8222-22222222222218:random-logical-key';
const physical = `HappyPC/${'測'.repeat(80)}`;
const longLogicalId = `logical-only-${'測'.repeat(80)}`;
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
      tracker: 'knee',
      displayName: 'HappyPC/device/left',
    },
    {
      ...candidate('logical-right'),
      publisher: 'HappyPC right',
      source: 'source-right',
      tracker: 'knee',
      displayName: 'HappyPC/device/right',
    },
    {
      ...candidate('no-physical-id'),
      source: null,
      tracker: longLogicalId,
      displayName: undefined,
    },
    {
      ...candidate('legacy-input', ['orientation']),
      origin: 'slime_orientation_stream',
      tracker: 'legacy-tracker-key',
      displayName: 'Legacy input',
    },
  ];
  return core;
}

test('logical labels, physical disclosure headers and exact selection IDs work at 375px', async ({
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
  const q = picker.getByRole('radio', {
    name: 'Q Source: random-logical-key',
    exact: true,
  });
  await expect(q).toBeEnabled();
  for (const side of ['left', 'right']) {
    const option = picker.getByRole('radio', {
      name: `Q Source: knee · HappyPC ${side}`,
      exact: true,
    });
    await expect(option).toBeEnabled();
    await expect(option.locator('xpath=ancestor::tr').locator('summary')).toHaveText(
      `▶ID : HappyPC/device/${side}`
    );
  }
  const bare = picker.getByRole('radio', {
    name: /^Q Source: logical-only-/,
  });
  const bareHeader = bare.locator('xpath=ancestor::tr').locator('th');
  const bareLabel = bareHeader.locator(':scope > span').first();
  await expect(bareHeader.locator('summary')).toHaveText('ID');
  await expect(bareLabel).not.toHaveText(longLogicalId);
  await expect(bareLabel).toHaveAttribute('title', longLogicalId);
  await expect(bareHeader.locator('pre')).toContainText('Identity: no-physical-id');
  const sourceHeader = q.locator('xpath=ancestor::tr').locator('th');
  const disclosure = sourceHeader.locator('details');
  const summary = disclosure.locator('summary');
  const details = disclosure.locator('pre');
  const physicalLabel = summary.getByText(physical, {
    exact: true,
  });
  await expect(summary).toHaveText(`▶ID : ${physical}`);
  await expect(physicalLabel).toBeVisible();
  await expect(physicalLabel).toHaveAttribute('title', physical);
  await expect(details).toBeHidden();
  const toggleBox = (await summary.locator(':scope > span').first().boundingBox())!;
  const physicalBox = (await physicalLabel.boundingBox())!;
  expect(physicalBox.x).toBeGreaterThanOrEqual(toggleBox.x + toggleBox.width);
  await summary.focus();
  await summary.press('Enter');
  await expect(physicalLabel).toBeVisible();
  await expect(details).toBeVisible();
  await expect(details).not.toContainText('Input device:');
  await expect(details).not.toContainText('Tracker:');
  await expect(details).toContainText(
    'Publisher: 11111111-1111-4111-8111-111111111111'
  );
  await expect(details).toContainText('Source: 22222222-2222-4222-8222-222222222222');
  await expect(details).not.toContainText('Identity:');
  await expect(sourceHeader).toHaveAttribute('title', /Tracker: random-logical-key/);
  await expect(sourceHeader).toHaveAttribute(
    'title',
    new RegExp(`Identity: ${persistent}`)
  );
  expect(
    await picker.evaluate((node) => node.scrollWidth <= node.clientWidth + 1)
  ).toBe(true);
  await summary.press('Enter');
  await expect(physicalLabel).toBeVisible();
  await expect(details).toBeHidden();
  const legacyDisclosure = picker
    .getByRole('radio', { name: 'Q Source: Legacy input', exact: true })
    .locator('xpath=ancestor::tr')
    .locator('details');
  await legacyDisclosure.locator('summary').click();
  const legacyDetails = legacyDisclosure.locator('pre');
  await expect(legacyDetails).toContainText('Tracker: legacy-tracker-key');
  await expect(legacyDetails).toContainText('Input device: Legacy input');
  await expect(legacyDetails).toContainText('Identity: legacy-input');
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
    new RegExp(`Identity: ${persistent}`)
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
    new RegExp(`Saved identity: ${persistent}`)
  );
  await expect(picker.locator('details pre')).toHaveText(
    `Saved identity: ${persistent}`
  );
  expect(core.saves).toHaveLength(1);
});

test('normal assignment picker and body summary show logical names and retain physical details', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1000, height: 1000 });
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
  await expect(summary).toContainText('random-logical-key');
  await expect(summary).toHaveAttribute('title', /Input device: HappyPC\//);
  expect((await summary.boundingBox())!.height).toBeLessThanOrEqual(34);
  await page.locator('.control#HIP').click();
  const picker = page.getByTestId('unified-assignment');
  await expect(picker).toBeVisible();
  const left = picker.getByRole('radio', {
    name: 'Q Source: knee · HappyPC left',
    exact: true,
  });
  const right = picker.getByRole('radio', {
    name: 'Q Source: knee · HappyPC right',
    exact: true,
  });
  await expect(left).toBeEnabled();
  await expect(right).toBeEnabled();
  await expect(picker.getByTestId('assignment-readback')).toContainText(
    'random-logical-key'
  );
  await expect(picker.getByTestId('assignment-readback')).toHaveAttribute(
    'title',
    new RegExp('random-logical-key')
  );
  expect(
    await picker.evaluate((node) => node.scrollWidth <= node.clientWidth + 1)
  ).toBe(true);
  const leftDetails = left.locator('xpath=ancestor::tr').locator('details');
  const leftHeader = leftDetails.locator('summary');
  await expect(leftHeader).toHaveText('▶ID : HappyPC/device/left');
  await expect(leftDetails.locator('pre')).toBeHidden();
  await leftHeader.click();
  await expect(leftHeader).toHaveText('▶ID : HappyPC/device/left');
  await expect(leftDetails.locator('pre')).toBeVisible();
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
