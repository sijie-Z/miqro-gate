import { chromium } from '@playwright/test';

/**
 * Warms the Vite dev server before any test navigates: executing the page in a
 * real browser completes esbuild's dependency pre-bundling, which otherwise
 * races the first test navigation ("预加载桥接不可用").
 */
export default async function globalSetup(): Promise<void> {
  const browser = await chromium.launch({ channel: 'chromium' });
  try {
    const page = await browser.newPage();
    await page
      .goto('http://localhost:5173/login', { waitUntil: 'networkidle' })
      .catch(() => undefined);
  } finally {
    await browser.close();
  }
}
