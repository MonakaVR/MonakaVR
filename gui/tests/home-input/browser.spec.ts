import { test, expect } from '@playwright/test';
import { source, snapshot } from './fixtures';

test('real Home catalog wrapper: absent producer, arbitrary publishers, dedup, conflict, loss and reconnect; snapshot requests only', async ({
  page,
}) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/tests/home-input/index.html?ip=public-contract.invalid&port=21212');
  const panel = page.getByRole('region');
  await expect(panel).toContainText('接続されている入力デバイスはありません。');
  await expect(panel.getByRole('link')).toHaveAttribute(
    'href',
    '/onboarding/trackers-assign?ip=public-contract.invalid&port=21212'
  );
  const a = source('exact-a', {
    publisher: 'unknown-publisher-one',
    displayName: '同名の実機',
  });
  const b = source('exact-b', {
    publisher: 'unknown-publisher-two',
    displayName: '同名の実機',
  });
  await page.evaluate(
    (value) => {
      window.homeInputTest.snapshot = value;
    },
    snapshot([a, { ...a }, b])
  );
  await expect(panel.locator('[data-input-id]')).toHaveCount(2);
  await expect(panel).toContainText('左膝');
  await expect(panel).not.toContainText('LEFT_UPPER_LEG');
  await expect(panel.locator('[data-input-id="exact-a"]')).toContainText('位置 + 回転');
  await expect(panel.locator('[data-input-id="exact-a"]')).toContainText('現在有効');
  await panel.locator('[data-input-id="exact-a"] summary').click();
  await expect(panel.locator('[data-input-id="exact-a"] pre')).toContainText(
    'Identity: exact-a'
  );
  await page.screenshot({ path: 'build/home-input-catalog-ja.png', fullPage: true });
  await page.evaluate(
    (value) => {
      window.homeInputTest.snapshot = value;
    },
    snapshot([a, { ...a, displayName: 'conflict' }, b])
  );
  await expect(panel.locator('[data-input-id="exact-a"]')).toContainText(
    '識別情報が競合'
  );
  await expect(panel.locator('[data-input-id="exact-a"]')).toContainText(
    'Q 不明, 6DoF 不明'
  );
  await page.evaluate(() => {
    window.homeInputTest.pause = true;
    window.homeInputTest.disconnect();
  });
  await expect(panel).toContainText(
    '接続状態を確認できません。前回の一覧を表示しています。',
    { timeout: 10000 }
  );
  await expect(panel.locator('[data-input-id="exact-b"]')).toContainText(
    'Q 不明, 6DoF 不明'
  );
  await page.evaluate(
    (value) => {
      window.homeInputTest.snapshot = value;
      window.homeInputTest.pause = false;
      window.homeInputTest.disconnect();
    },
    snapshot([a, b])
  );
  await expect(panel.locator('[data-input-id="exact-a"]')).toContainText(
    'Q 現在有効, 6DoF 現在有効',
    { timeout: 10000 }
  );
  await expect(panel.locator('button,input,select,textarea,form')).toHaveCount(0);
  const transport = await page.evaluate(() => ({
    requests: window.homeInputTest.requests,
    urls: window.homeInputTest.urls,
  }));
  expect(
    transport.urls.every((url) => url === 'ws://public-contract.invalid:21212')
  ).toBeTruthy();
  expect(
    transport.requests.every(
      (request) =>
        request.action === 'snapshot' &&
        request.type === 'monaka_inputs_v1' &&
        Object.keys(request).sort().join(',') === 'action,id,type'
    )
  ).toBeTruthy();
  expect(errors).toEqual([]);
});
