import { test, expect, type Page } from '@playwright/test';
import { CoreUiFixture, forbidBridgeApi } from './fixtures';

async function open(page: Page, fixture: CoreUiFixture, language = 'en') {
  await fixture.install(page, language);
  await forbidBridgeApi(page);
  await page.addInitScript(() => {
    const config = JSON.parse(localStorage.getItem('config.json')!);
    localStorage.setItem(
      'config.json',
      JSON.stringify({ ...config, assignMode: 'all' })
    );
  });
  await page.goto('/?ip=127.0.0.1&port=29992#/onboarding/trackers-assign');
  await expect.poll(() => fixture.requests.length).toBeGreaterThan(0);
  await page.locator('.control#HIP').click();
  await expect(page.getByTestId('unified-assignment')).toBeVisible();
}
const qName = 'Q Source: publisher-a / opaque-source / same-name';
const sixName = '6DoF Source: publisher-a / opaque-source / same-name';

test('normal body picker saves Pos+Q and renders the actual body summary without a settings page', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await open(page, fixture);
  await page.getByRole('radio', { name: qName, exact: true }).check();
  await expect(page.getByRole('radio', { name: sixName, exact: true })).toBeChecked();
  await page.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await expect(page.getByTestId('assignment-readback')).toContainText('publisher-a');
  expect(fixture.saves).toHaveLength(1);
  await page.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(page.getByTestId('assignment-summary-HIP')).toContainText('publisher-a');
  expect(page.url()).toContain('/onboarding/trackers-assign');
  expect(errors).toEqual([]);
});

test('Q-only and old-Core Slime association fail closed while manual clear survives metadata reload', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  await open(page, fixture);
  await expect(
    page.getByRole('radio', {
      name: 'Q Source: slime-q / opaque-source / same-name',
      exact: true,
    })
  ).toBeDisabled();
  await page
    .getByRole('radio', {
      name: 'Q Source: q-only / opaque-source / same-name',
      exact: true,
    })
    .check();
  await expect(
    page.getByRole('radio', {
      name: '6DoF Source: q-only / opaque-source / same-name',
      exact: true,
    })
  ).toBeDisabled();
  await page.getByRole('radio', { name: sixName, exact: true }).check();
  await page
    .getByRole('radio', { name: '6DoF Source: Unassigned', exact: true })
    .check();
  await page.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await page.getByRole('button', { name: 'Reload input choices', exact: true }).click();
  await expect(
    page.getByRole('radio', { name: '6DoF Source: Unassigned', exact: true })
  ).toBeChecked();
  expect(fixture.snapshot.assignments[0].manualSix).toBe(true);
});

test('full reset requires explicit confirmation and stale generations never show success', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  fixture.snapshot.assignments = [
    {
      body: 'HIP',
      qSource: 'publisher-a',
      sixDofSource: 'publisher-a',
      manualSix: false,
      mode: 'ik',
    },
  ];
  await open(page, fixture);
  await page.getByRole('radio', { name: 'Q Source: Unassigned', exact: true }).check();
  await page
    .getByRole('radio', { name: '6DoF Source: Unassigned', exact: true })
    .check();
  await page.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await expect(page.getByRole('alertdialog')).toContainText(
    'resets its selection history'
  );
  expect(fixture.saves).toHaveLength(0);
  fixture.snapshot.generation = '2';
  await page.getByRole('button', { name: 'Confirm and save', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('generation');
  await expect(page.getByRole('status')).toContainText('No physical body write');
  expect(fixture.snapshot.assignments[0].qSource).toBe('publisher-a');
});

test('Japanese normal picker remains operable at narrow width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = new CoreUiFixture();
  await open(page, fixture, 'ja');
  await page
    .getByRole('radio', {
      name: 'Q入力元: publisher-a / opaque-source / same-name',
      exact: true,
    })
    .check();
  await page.getByRole('button', { name: '入力の選択を保存', exact: true }).click();
  await expect(page.getByTestId('assignment-readback')).toContainText('publisher-a');
  await page.screenshot({ path: 'build/r4-unified-mobile.png', fullPage: true });
});
