import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/input-browser',
  outputDir: './build/input-browser',
  workers: 1,
  retries: 0,
  reporter: [['list'], ['json', { outputFile: './build/input-browser-result.json' }]],
  use: {
    channel: 'msedge',
    headless: true,
    viewport: { width: 1280, height: 1100 },
    baseURL: process.env.MONAKA_DESKTOP_SMOKE_URL,
    trace: 'retain-on-failure',
  },
});
