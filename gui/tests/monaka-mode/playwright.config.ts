import { defineConfig } from '@playwright/test';
import { fileURLToPath } from 'node:url';
export default defineConfig({
  testDir: '.',
  testMatch: 'component.spec.ts',
  workers: 1,
  outputDir: '../../build/monaka-mode-browser',
  reporter: 'list',
  use: {
    channel: 'msedge',
    headless: true,
    baseURL: 'http://127.0.0.1:4187',
    viewport: { width: 1280, height: 1200 },
  },
  webServer: {
    cwd: fileURLToPath(new URL('../..', import.meta.url)),
    command: 'pnpm exec vite --config tests/monaka-mode/vite.config.ts',
    url: 'http://127.0.0.1:4187',
    reuseExistingServer: false,
  },
});
