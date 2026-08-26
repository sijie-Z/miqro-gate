import { defineConfig, devices } from '@playwright/test';

/**
 * Visual baseline (frontend-design.md §9): screenshots at the four required
 * viewports plus structural assertions (nav present, forbidden CSS absent).
 * The backend is not required: API routes are mocked in the spec so the shell
 * can render with fixture data.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  // Vite's cold start can race the module preload bridge on the first page
  // load; a single retry (warm server) is reliable.
  retries: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'off',
    // Force the full Chromium build (new headless): the headless shell breaks
    // Vite's module preload bridge ("预加载桥接不可用").
    channel: 'chromium',
  },
  projects: [
    {
      name: 'baseline',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev -- --port 5173 --strictPort',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
