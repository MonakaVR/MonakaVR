import { test, expect } from '@playwright/test';
test('mock browser: English Fluent fallback, review, cancel, offline save and restart wait', async ({
  page,
}) => {
  await page.goto('/');
  for (const mode of ['IK', 'Direct', 'Hybrid'])
    await expect(page.getByRole('radio', { name: mode, exact: true })).toBeVisible();
  await expect(
    page.getByText('Hybrid background IK positional fallback is unavailable.', {
      exact: false,
    })
  ).toBeVisible();
  await page.getByRole('radio', { name: 'Direct', exact: true }).check();
  await page.getByRole('button', { name: 'Review mode change' }).click();
  await expect(page.getByText('Review HIP:', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Cancel draft' }).click();
  await expect(page.getByRole('radio', { name: 'IK', exact: true })).toBeChecked();
  await page.getByRole('radio', { name: 'Hybrid', exact: true }).check();
  await page.getByRole('button', { name: 'Review mode change' }).click();
  await page.getByRole('button', { name: 'Confirm and save configuration' }).click();
  await expect(page.getByText('Restart required.', { exact: false })).toBeVisible();
  await expect(page.getByRole('radio', { name: 'Direct', exact: true })).toBeDisabled();
  await expect(page.locator('dd').nth(0)).toHaveText('IK');
  await expect(page.locator('dd').nth(1)).toHaveText('Hybrid');
  await page.getByRole('button', { name: 'Reload saved and active modes' }).click();
  await expect(page.getByText('Restart required.', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Read fixture save count' }).click();
  await expect(page.locator('body')).toHaveAttribute('data-saves', '1');
  await page.screenshot({
    path: 'build/monaka-mode-browser/pending-restart.png',
    fullPage: true,
  });
});
test('mock browser: body drafts survive switching; disconnect blocks controls and reload recovers', async ({
  page,
}) => {
  await page.goto('/');
  await page.getByRole('radio', { name: 'Hybrid', exact: true }).check();
  await page.getByLabel('Mode body target').selectOption('LEFT_FOOT');
  await page.getByRole('radio', { name: 'Direct', exact: true }).check();
  await page.getByLabel('Mode body target').selectOption('HIP');
  await expect(page.getByRole('radio', { name: 'Hybrid', exact: true })).toBeChecked();
  await page.getByRole('button', { name: 'Simulate disconnect' }).click();
  await expect(page.getByRole('alert')).toContainText('disconnected');
  await expect(page.getByRole('radio', { name: 'Hybrid', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Reload saved and active modes' }).click();
  await expect(page.getByRole('radio', { name: 'IK', exact: true })).toBeEnabled();
});
test('mock browser: unknown modes are labelled and disabled', async ({ page }) => {
  await page.goto('/?unknown');
  await expect(page.locator('dd').nth(0)).toHaveText('Unknown');
  await expect(page.locator('dd').nth(1)).toHaveText('Unknown');
  await expect(page.getByRole('radio', { name: 'IK', exact: true })).toBeDisabled();
});
