import { test, expect, _electron } from '@playwright/test';
import { resolve } from 'node:path';
import { CoreUiFixture, routeUrl } from './fixtures';

test('isolated Electron loads the real App with zero Bridge IPC', async () => {
  const app = await _electron.launch({
    args: [resolve('tests/monaka-input-route/electron-main.cjs')],
    env: {
      ...process.env,
      STAGE15_ELECTRON_PROFILE: resolve(
        `build/monaka-input-route/electron-${Date.now()}`
      ),
    },
  });
  try {
    const page = await app.firstWindow();
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    const fixture = new CoreUiFixture();
    fixture.snapshot.candidates = [];
    await fixture.install(page);
    await page.goto('http://127.0.0.1:4207' + routeUrl);
    await expect(page.getByLabel('Q Source', { exact: true })).toBeEnabled();
    await expect(page.locator('.settings-layout')).toBeVisible();
    await page.reload();
    await expect(page.getByLabel('Q Source', { exact: true })).toBeEnabled();
    expect(
      await app.evaluate(
        () =>
          (globalThis as typeof globalThis & { stage15BridgeCalls: number })
            .stage15BridgeCalls
      )
    ).toBe(0);
    expect(fixture.saves).toEqual([]);
    expect(errors).toEqual([]);
    await page.screenshot({
      path: 'build/monaka-input-route/after-electron.png',
      fullPage: true,
    });
  } finally {
    await app.close();
  }
});
