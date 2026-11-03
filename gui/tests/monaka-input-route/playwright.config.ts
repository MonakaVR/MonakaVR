import { defineConfig } from '@playwright/test';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  testDir: '.',
  testMatch: ['route.spec.ts', 'electron.spec.ts'],
  outputDir: '../../build/monaka-input-route',
  workers: 1,
  retries: 0,
  timeout: 45000,
  reporter: [
    ['list'],
    ['json', { outputFile: '../../build/monaka-input-route-result.json' }],
  ],
  use: {
    channel: 'msedge',
    headless: true,
    viewport: { width: 1280, height: 1100 },
    baseURL: `http://127.0.0.1:${process.env.MONAKA_INPUT_SMOKE_PORT ?? '4207'}`,
    trace: 'retain-on-failure',
  },
  webServer: {
    cwd: fileURLToPath(new URL('../..', import.meta.url)),
    command: 'pnpm exec vite --config tests/monaka-input-route/vite.config.ts',
    url: `http://127.0.0.1:${process.env.MONAKA_INPUT_SMOKE_PORT ?? '4207'}`,
    reuseExistingServer: false,
    timeout: 90000,
  },
});
