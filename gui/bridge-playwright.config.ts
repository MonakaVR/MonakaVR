import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/bridge-browser',
  outputDir: './build/bridge-browser',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['json', { outputFile: './build/bridge-browser-result.json' }]],
  use: {
    channel: 'msedge',
    headless: true,
    viewport: { width: 1280, height: 1100 },
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
  },
});
