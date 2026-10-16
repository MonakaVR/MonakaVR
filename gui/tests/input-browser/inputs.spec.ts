import { test, expect, Page } from '@playwright/test';

async function control(page: Page, op: string, fields: Record<string, unknown> = {}) {
  return page.evaluate(
    ({ url, op, fields }) =>
      new Promise<any>((resolve, reject) => {
        const ws = new WebSocket(url!);
        const timer = setTimeout(() => {
          ws.close();
          reject(new Error('Owned control timeout'));
        }, 10000);
        ws.onopen = () =>
          ws.send(JSON.stringify({ type: 'probe_control', op, ...fields }));
        ws.onmessage = (event) => {
          clearTimeout(timer);
          const value = JSON.parse(event.data);
          ws.close();
          value.ok ? resolve(value.proof) : reject(new Error(value.error));
        };
        ws.onerror = () => reject(new Error('Owned control connection failed'));
      }),
    { url: process.env.MONAKA_INPUT_PROBE_WS, op, fields }
  );
}

test('real input widget preserves explicit choices through save, failure and reconnect', async ({
  page,
}) => {
  test.setTimeout(120000);
  const port = new URL(process.env.MONAKA_INPUT_PROBE_WS!).port;
  await page.goto(`/tests/input-smoke/index.html?ip=127.0.0.1&port=${port}`);
  const q = page.getByLabel('Q Source', { exact: true });
  const six = page.getByLabel('6DoF Source', { exact: true });
  const save = page.getByRole('button', { name: 'Save input choices', exact: true });
  const cancel = page.getByRole('button', {
    name: 'Cancel input changes',
    exact: true,
  });
  const reload = page.getByRole('button', {
    name: 'Reload input choices',
    exact: true,
  });
  await expect(q).toBeEnabled();
  const source = async (publisher: string) => {
    const option = q.locator('option').filter({ hasText: publisher });
    await expect(option).toHaveCount(1);
    return (await option.getAttribute('value'))!;
  };
  const a = await source('ui-bridge-a');
  const b = await source('ui-bridge-b');
  const onlyQ = await source('ui-bridge-q');
  expect(a).not.toBe(b);
  const initial = await control(page, 'proof');
  await q.selectOption(onlyQ);
  await expect(six).toHaveValue('');
  await expect(six.locator(`option[value="${onlyQ}"]`)).toHaveJSProperty(
    'disabled',
    true
  );
  await cancel.click();
  expect((await control(page, 'proof')).config_sha256).toBe(initial.config_sha256);
  await q.selectOption(a);
  await expect(six).toHaveValue(a);
  await save.click();
  await expect(save).toBeDisabled();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await reload.click();
  await expect(q).toHaveValue(a);
  await expect(six).toHaveValue(a);
  await six.selectOption(b);
  await save.click();
  await expect(save).toBeDisabled();
  const persisted = await control(page, 'proof');
  await control(page, 'hold-lock');
  await six.selectOption('');
  await save.click();
  await expect(page.getByRole('alert')).toContainText('Reload');
  const failed = await control(page, 'proof');
  expect(failed.config_sha256).toBe(persisted.config_sha256);
  expect(failed.generation).toBe(persisted.generation);
  await control(page, 'release-lock');
  await reload.click();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(six).toHaveValue(b);
  await control(page, 'disconnect');
  await expect(q).toHaveValue(a);
  await expect(six).toHaveValue(b);
  await expect(async () => {
    await reload.click();
    await expect(page.getByRole('alert')).toHaveCount(0);
  }).toPass({ timeout: 15000 });
  expect((await control(page, 'proof')).config_sha256).toBe(persisted.config_sha256);
  await six.selectOption('');
  await save.click();
  await expect(save).toBeDisabled();
  await reload.click();
  await expect(q).toHaveValue(a);
  await expect(six).toHaveValue('');
  await six.selectOption(b);
  await q.selectOption('');
  await save.click();
  await expect(save).toBeDisabled();
  await reload.click();
  await expect(q).toHaveValue('');
  await expect(six).toHaveValue(b);
  await page.getByLabel('Input body target').selectOption('CHEST');
  const slime = await source('software-legacy-q');
  await q.selectOption(slime);
  await expect(six).toHaveValue('');
  await cancel.click();
  await page.screenshot({ path: 'build/input-browser-proof.png', fullPage: true });
});
