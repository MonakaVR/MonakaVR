import { defineConfig } from '@playwright/test';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  testDir: '.',
  testMatch: 'browser.spec.ts',
  outputDir: '../../build/home-input-browser',
  workers: 1,
  retries: 0,
  reporter: [
    ['list'],
    ['json', { outputFile: '../../build/home-input-browser-result.json' }],
  ],
  use: {
    channel: 'msedge',
    headless: true,
    baseURL: 'http://127.0.0.1:4198',
    viewport: { width: 1280, height: 1100 },
    trace: 'retain-on-failure',
  },
  webServer: {
    cwd: fileURLToPath(new URL('../..', import.meta.url)),
    command:
      'node node_modules/vite/bin/vite.js --config tests/home-input/vite.config.ts',
    url: 'http://127.0.0.1:4198/tests/home-input/index.html',
    reuseExistingServer: false,
  },
});
