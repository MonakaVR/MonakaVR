import { test, expect } from '@playwright/test';
import { source, snapshot } from './fixtures';

test('real Home catalog wrapper: absent producer, arbitrary publishers, dedup, conflict, loss and reconnect; snapshot requests only', async ({
  page,
}, testInfo) => {
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
  const details = panel.locator('[data-input-id="exact-a"] pre');
  await expect(details).toContainText('Publisher: unknown-publisher-one');
  await expect(details).toContainText('Source: source');
  await expect(details).not.toContainText('Identity:');
  await expect(details).not.toContainText('Tracker:');
  await expect(details).not.toContainText('Input device:');
  await page.screenshot({
    path: testInfo.outputPath('home-input-catalog-ja.png'),
    fullPage: true,
  });
  await page.evaluate(
    (value) => {
      window.homeInputTest.snapshot = value;
    },
    snapshot([a, { ...a, tracker: 'other-tracker', displayName: 'conflict' }, b])
  );
  await expect(panel.locator('[data-input-id="exact-a"]')).toContainText('入力競合');
  await expect(panel.locator('[data-input-id="exact-a"]')).toContainText(
    'Q 不明, 6DoF 不明'
  );
  await expect(details).toContainText('Tracker: tracker');
  await expect(details).toContainText('Tracker: other-tracker');
  await expect(details).toContainText('Input device: 同名の実機');
  await expect(details).toContainText('Input device: conflict');
  await expect(details).toContainText('Identity: exact-a');
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

test('Home headers keep compact IDs on the right as logical trackers are expanded and collapsed', async ({
  page,
}) => {
  await page.setViewportSize({ width: 375, height: 900 });
  await page.goto('/tests/home-input/index.html');
  const panel = page.getByRole('region');
  await expect(panel).toContainText('接続されている入力デバイスはありません。');

  const longLogicalId = `logical-tracker-${'l'.repeat(90)}-end`;
  const longPhysicalId = `physical-device-${'p'.repeat(90)}-end`;
  const physicalId = 'sensor-42';
  const input = snapshot([
    source('opaque-left', {
      tracker: 'logical-left',
      displayName: physicalId,
    }),
    source('opaque-right', {
      tracker: 'logical-right',
      displayName: physicalId,
    }),
    source('opaque-other-left', {
      publisher: 'arbitrary-b',
      tracker: 'logical-left',
      displayName: 'sensor-84',
    }),
    source('opaque-missing-physical', {
      tracker: 'logical-alone',
      displayName: null,
    }),
    source('opaque-missing-both', {
      tracker: undefined,
      displayName: '  ',
    }),
    source('opaque-missing-publisher', {
      publisher: null,
      tracker: 'logical-without-publisher',
    }),
    source('opaque-blank-source', {
      source: '  ',
      tracker: 'logical-without-source',
    }),
    source('opaque-long', {
      tracker: longLogicalId,
      displayName: longPhysicalId,
    }),
  ]);
  await page.evaluate((value) => {
    window.homeInputTest.snapshot = value;
  }, input);
  await expect(panel.locator('[data-input-id]')).toHaveCount(8);

  const header = (id: string) => panel.locator(`[data-input-id="${id}"] summary`);
  const left = header('opaque-left');
  const right = header('opaque-right');
  const otherLeft = header('opaque-other-left');
  const missing = header('opaque-missing-physical');
  const missingBoth = header('opaque-missing-both');
  await expect(left.locator('h2')).toHaveText('logical-left · arbitrary-a');
  await expect(right.locator('h2')).toHaveText('logical-right');
  await expect(otherLeft.locator('h2')).toHaveText('logical-left · arbitrary-b');
  await expect(missing.locator('h2')).toHaveText('logical-alone');
  await expect(missing.locator('h2 [title]')).toHaveAttribute(
    'title',
    'logical-alone'
  );
  await expect(missing.locator('[title^="ID : "]')).toHaveCount(0);
  await expect(missingBoth.locator('h2')).toHaveText('不明');
  await expect(missingBoth.locator('[title]')).toHaveCount(0);
  await expect(left.locator('[title^="ID : "]')).toHaveText(
    `▶ID : ${physicalId}`
  );
  await expect(right.locator('[title^="ID : "]')).toHaveText(
    `▶ID : ${physicalId}`
  );
  await expect(otherLeft.locator('[title^="ID : "]')).toHaveText(
    '▶ID : sensor-84'
  );
  for (const id of [
    'opaque-missing-both',
    'opaque-missing-publisher',
    'opaque-blank-source',
  ]) {
    await header(id).click();
    await expect(panel.locator(`[data-input-id="${id}"] pre`)).toContainText(
      `Identity: ${id}`
    );
    await header(id).click();
  }

  const longRow = panel.locator('[data-input-id="opaque-long"]');
  const longHeader = longRow.locator('summary');
  const longDevice = longHeader.locator('[title^="ID : "]');
  const arrow = longDevice.getByText('▶', { exact: true });
  await expect(longHeader.locator('h2')).toHaveText(/^logical-tracker-.*-end$/);
  await expect(longHeader.locator('h2 [title]')).toHaveAttribute(
    'title',
    longLogicalId
  );
  await expect(longDevice).toHaveAttribute('title', `ID : ${longPhysicalId}`);
  await expect(longDevice).toHaveText(`▶ID : ${longPhysicalId}`);
  await expect(longHeader).toHaveAttribute(
    'aria-label',
    new RegExp(`ID : ${longPhysicalId}`)
  );
  const expectLongIdsFit = async () => {
    const bounds = await longHeader.evaluate((summary) => {
      const heading = summary.querySelector('h2')!.getBoundingClientRect();
      const device = summary
        .querySelector('[title^="ID : "]')!
        .getBoundingClientRect();
      return {
        headingRight: heading.right,
        deviceLeft: device.left,
        deviceRight: device.right,
        summaryRight: summary.getBoundingClientRect().right,
        documentWidth: document.documentElement.scrollWidth,
        viewportWidth: document.documentElement.clientWidth,
      };
    });
    expect(bounds.headingRight).toBeLessThan(bounds.deviceLeft);
    expect(bounds.deviceRight).toBeLessThan(bounds.summaryRight);
    expect(bounds.documentWidth).toBe(bounds.viewportWidth);
  };
  await expect(longRow.locator('pre')).toBeHidden();
  await expect(arrow).toHaveCSS('transform', 'none');
  await expectLongIdsFit();
  await longHeader.focus();
  await longHeader.press('Enter');
  await expect(longRow.locator('pre')).toBeVisible();
  await expect(longRow.locator('pre')).not.toContainText('Identity:');
  await expect(longRow.locator('pre')).not.toContainText('Tracker:');
  await expect(longRow.locator('pre')).not.toContainText('Input device:');
  await expect(longDevice).toBeVisible();
  await expect(arrow).toHaveCSS('transform', 'matrix(0, 1, -1, 0, 0, 0)');
  await expectLongIdsFit();
  await longHeader.press('Space');
  await expect(longRow.locator('pre')).toBeHidden();
  await expect(longDevice).toBeVisible();
  await expect(arrow).toHaveCSS('transform', 'none');
});
