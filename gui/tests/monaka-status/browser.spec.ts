import { test, expect } from '@playwright/test';
import { fixtures, full, q, snapshot } from './fixtures';

const fixtureUrl = (name: string) =>
  `/tests/monaka-status/index.html?fixture=${encodeURIComponent(name)}`;
test('cached assignments on an initially disabled mount survive a later disconnect', async ({
  page,
}) => {
  await page.goto(fixtureUrl('disabled'));
  const panel = page.getByRole('region');
  await expect(panel).toContainText('Saved (offline)');
  await expect(panel).toContainText(full.id);
  await expect(panel).toContainText('6DoF usable now: Unknown');
  await page.evaluate(() => window.monakaStatusTest.update(null, 'offline'));
  await expect(panel).toContainText(full.id);
  await expect(panel).toContainText('Saved (offline)');
});
for (const fixture of fixtures) {
  test(`browser fixture: ${fixture.name}`, async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(fixtureUrl(fixture.name));
    const panel = page.getByRole('region', { name: 'Monaka input tracking status' });
    await expect(panel).toBeVisible();
    for (const text of fixture.expected) await expect(panel).toContainText(text);
    await expect(panel.locator('button,input,select,textarea,form')).toHaveCount(0);
    await expect(panel.getByRole('table')).toHaveCount(2);
    expect(errors).toEqual([]);
    if (
      ['Q-only', 'Pos+Q', 'saved offline', 'same-name/different-ID'].includes(
        fixture.name
      )
    )
      await page.screenshot({
        path: `build/monaka-status-${fixture.name.replace(/[^a-zA-Z0-9]/g, '-')}.png`,
        fullPage: true,
      });
  });
}
test('disconnect, malformed response and invalid reconnect preserve explicit selections', async ({
  page,
}) => {
  await page.goto(fixtureUrl('Pos+Q'));
  const panel = page.getByRole('region');
  await expect(panel).toContainText('6DoF usable now: Yes');
  await page.evaluate(() => window.monakaStatusTest.update(null, 'offline'));
  await expect(panel).toHaveAttribute('data-status', 'offline');
  await expect(panel).toContainText(full.id);
  await expect(panel).toContainText('Manual selection');
  await expect(panel).toContainText('6DoF usable now: Unknown');
  await page.evaluate(() => window.monakaStatusTest.update({} as never, 'connected'));
  await expect(panel.getByRole('alert')).toContainText('Invalid input snapshot');
  await expect(panel).toContainText(full.id);
  const reconnected = {
    ...fixtures[1].snapshot!,
    candidates: [{ ...full, qUsableNow: false, sixDofUsableNow: false }],
  };
  await page.evaluate(
    (value) => window.monakaStatusTest.update(value, 'connected'),
    reconnected
  );
  await expect(panel).toContainText('6DoF usable now: No');
  await expect(panel).toContainText('Position + orientation');
  await expect(panel).toContainText('Manual selection');
});
test('old generations and inconsistent selected IDs never revive a usable display', async ({
  page,
}) => {
  await page.goto(fixtureUrl('Pos+Q'));
  const panel = page.getByRole('region');
  await expect(panel).toContainText('6DoF usable now: Yes');
  const old = { ...fixtures[1].snapshot!, generation: '6' };
  await page.evaluate(
    (value) => window.monakaStatusTest.update(value, 'connected'),
    old
  );
  await expect(panel).toContainText('Old assignment generation rejected');
  await expect(panel).toContainText('6DoF usable now: Unknown');
  await page.evaluate(
    (value) => window.monakaStatusTest.update(value, 'connected'),
    snapshot([q])
  );
  await expect(panel.getByRole('alert')).toContainText('Selected IDs changed');
  await expect(panel).toContainText(full.id);
  await page.evaluate((value) => window.monakaStatusTest.update(value, 'connected'), {
    ...fixtures[1].snapshot!,
    generation: '8',
  });
  await expect(panel).toContainText('Assignment generation: 8');
  await expect(panel).toContainText('6DoF usable now: Yes');
});
test('no autonomous renders, application network calls or save requests', async ({
  page,
}) => {
  const requests: { method: string; url: string }[] = [];
  const sockets: string[] = [];
  page.on('request', (request) =>
    requests.push({ method: request.method(), url: request.url() })
  );
  page.on('websocket', (socket) => sockets.push(socket.url()));
  await page.goto(fixtureUrl('same-name/different-ID'));
  await expect(page.getByRole('region')).toContainText('publisher-b');
  const before = await page.evaluate(() => window.monakaStatusTest.commits());
  // An absence-of-background-work assertion, not a latency measurement.
  await page.waitForTimeout(600);
  expect(await page.evaluate(() => window.monakaStatusTest.commits())).toBe(before);
  await page.getByLabel('Fixture', { exact: true }).selectOption('saved offline');
  await expect(page.getByRole('region')).toContainText('Saved (offline)');
  expect(
    requests.every(
      (x) => x.method === 'GET' && x.url.startsWith('http://127.0.0.1:4197/')
    )
  ).toBe(true);
  // Vite's own HMR connection is the only WebSocket in this software harness.
  expect(sockets.every((url) => url.startsWith('ws://127.0.0.1:4197/'))).toBe(true);
});
test('memoized panel ignores parent rerenders with identical snapshot props', async ({
  page,
}) => {
  await page.goto(fixtureUrl('Pos+Q'));
  await expect(page.getByRole('region')).toContainText('6DoF usable now: Yes');
  await page.evaluate(() => {
    const panel = document.querySelector('section')!;
    const mutations: MutationRecord[] = [];
    const observer = new MutationObserver((records) => mutations.push(...records));
    observer.observe(panel, {
      attributes: true,
      childList: true,
      subtree: true,
      characterData: true,
    });
    Object.assign(window, { statusMutationProbe: { mutations, observer } });
    window.monakaStatusTest.repeat();
  });
  await page.waitForTimeout(150);
  const mutations = await page.evaluate(() => {
    const probe = (
      window as unknown as {
        statusMutationProbe: { mutations: unknown[]; observer: MutationObserver };
      }
    ).statusMutationProbe;
    probe.observer.disconnect();
    return probe.mutations.length;
  });
  expect(mutations).toBe(0);
});
