import { defineConfig, devices } from '@playwright/test';

/**
 * Visual baseline (frontend-design.md §9): screenshots at the four required
 * viewports plus structural assertions (nav present, forbidden CSS absent).
 * The backend is not required: API routes are mocked in the spec so the shell
 * can render with fixture data.
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  // Vite's cold start can race the module preload bridge on the first page
  // load; a single retry (warm server) is reliable.
  retries: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'off',
  },
  projects: [
    {
      name: 'baseline',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  // Serve the production build (vite preview): no dev-server module graph to
  // race, so the "预加载桥接不可用" cold-start problem is structurally gone.
  webServer: {
    command: 'npm run build-only && npm run preview -- --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
