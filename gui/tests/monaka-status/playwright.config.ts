import { defineConfig } from '@playwright/test';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  testDir: '.',
  testMatch: 'browser.spec.ts',
  outputDir: '../../build/monaka-status-browser',
  workers: 1,
  retries: 0,
  reporter: [
    ['list'],
    ['json', { outputFile: '../../build/monaka-status-browser-result.json' }],
  ],
  use: {
    channel: 'msedge',
    headless: true,
    baseURL: 'http://127.0.0.1:4197',
    viewport: { width: 1280, height: 1100 },
    trace: 'retain-on-failure',
  },
  webServer: {
    cwd: fileURLToPath(new URL('../..', import.meta.url)),
    command: 'pnpm exec vite --config tests/monaka-status/vite.config.ts',
    url: 'http://127.0.0.1:4197/tests/monaka-status/index.html',
    reuseExistingServer: false,
  },
});
