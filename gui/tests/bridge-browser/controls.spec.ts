import { test, expect, type Page } from '@playwright/test';

const path = '/tests/bridge-smoke/index.html';
const policy = (page: Page) =>
  page.getByLabel('Selected output policy', { exact: true });
const apply = (page: Page) => page.getByRole('button', { name: 'Apply output policy' });
const cancel = (page: Page) =>
  page.getByRole('button', { name: 'Cancel / restore selection' });
async function review(page: Page, value: string) {
  await policy(page).selectOption(value);
  await page.getByRole('checkbox').check();
  await expect(apply(page)).toBeEnabled();
}

test('real renderer: review, duplicate clicks, cancellation, failure/retry, restoration, reload', async ({
  page,
}) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(path);
  await expect(page.getByText('SOFTWARE TEST MODE', { exact: false })).toBeVisible();
  await expect(policy(page)).toHaveValue('steamvr');
  await expect(apply(page)).toBeDisabled();
  await policy(page).selectOption('both');
  await expect(apply(page)).toBeDisabled();
  await cancel(page).click();
  await expect(policy(page)).toHaveValue('steamvr');
  await review(page, 'both');
  // Send multiple actual DOM click events in one turn, before React can rerender.
  await apply(page).evaluate((button: HTMLButtonElement) => {
    for (let index = 0; index < 50; ++index) button.click();
  });
  await expect(policy(page)).toBeDisabled();
  await expect(page.getByRole('status')).toContainText('Validating and saving both');
  await cancel(page).click();
  await expect(page.getByRole('status')).toContainText('Cancelled before save', {
    timeout: 8000,
  });
  await expect(policy(page)).toHaveValue('steamvr');
  await page.getByRole('button', { name: 'Next save fails' }).click();
  await expect(
    page.getByText('Fixture save attempts: 1', { exact: false })
  ).toBeVisible();
  await review(page, 'monaka');
  await apply(page).click();
  await expect(page.getByRole('status')).toContainText('Injected validator failure', {
    timeout: 8000,
  });
  await expect(policy(page)).toHaveValue('steamvr');
  await expect(page.getByRole('checkbox')).not.toBeChecked();
  await review(page, 'disabled');
  await apply(page).click();
  await expect(page.getByRole('status')).toContainText('Saved disabled', {
    timeout: 8000,
  });
  await expect(page.getByText('Saved (last load):', { exact: false })).toContainText(
    'disabled'
  );
  await page.getByRole('button', { name: 'Reload saved settings' }).click();
  await expect(policy(page)).toHaveValue('disabled');
  await expect(page.getByRole('status')).toContainText('Saved settings loaded');
  await page.screenshot({
    path: 'build/gui-validation/desktop-controls-saved.png',
    fullPage: true,
  });
  expect(errors).toEqual([]);
});

test('real renderer: independent validity, identity selection, old/future updates, disconnect and reconnect', async ({
  page,
}) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(path);
  const table = page.getByRole('table');
  await expect(table.getByText('degraded', { exact: true })).toBeVisible();
  await expect(table.getByText('valid', { exact: true })).toBeVisible();
  await expect(table.getByText('invalid', { exact: true })).toBeVisible();
  await expect(table.getByText('fresh', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'fixture-source / fixture-device' }).click();
  await expect(page.getByText('Selected identity:', { exact: false })).toContainText(
    'fixture-device'
  );
  await expect(page.getByText('unknown - not reported', { exact: true })).toHaveCount(
    2
  );
  await page.getByRole('button', { name: 'Old diagnostic update' }).click();
  await expect(
    page.getByText('Older diagnostic update ignored.', { exact: true })
  ).toBeVisible();
  await expect(table.getByText('historical', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Reconnect diagnostics' }).click();
  await expect(table.getByText('fresh', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Future diagnostic update' }).click();
  await expect(
    page.getByText('Diagnostic timestamp is missing or in the future.', { exact: true })
  ).toBeVisible();
  await page.getByRole('button', { name: 'Disconnect diagnostics' }).click();
  await expect(
    page.getByText('Injected disconnected diagnostic source.', { exact: true })
  ).toBeVisible();
  await expect(table.getByText('historical', { exact: true })).toBeVisible();
  await page.screenshot({
    path: 'build/gui-validation/desktop-controls-historical.png',
    fullPage: true,
  });
  await page.getByRole('button', { name: 'Reconnect diagnostics' }).click();
  await expect(table.getByText('fresh', { exact: true })).toBeVisible();
  await expect(page.getByText('Selected identity:', { exact: false })).toContainText(
    'fixture-device'
  );
  await page.screenshot({
    path: 'build/gui-validation/desktop-controls-current.png',
    fullPage: true,
  });
  await page.close();
  expect(errors).toEqual([]);
});
