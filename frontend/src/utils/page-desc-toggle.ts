/**
 * Page-description toggle (#830): clicking a page title collapses/expands the
 * description line under it (`.ui-page-desc`) by flipping the `showPageDesc`
 * preference. The behaviour is global UI policy, so it is installed once per
 * page (mirroring installDomI18n) instead of per shell mount — a per-mount
 * listener double-toggled when the shell remounted and in tests that mount it
 * more than once.
 */
import { preferences, setPreference } from '@/preferences';

let installed = false;

export function installPageDescToggle(): void {
  if (installed || typeof window === 'undefined') return;
  installed = true;
  window.addEventListener('click', (event) => {
    const target = event.target as Element | null;
    const title = target?.closest('.ui-page-title');
    if (!title) return;
    // Only headers that actually carry a description line react — a title
    // without one must not flip a preference that affects other pages.
    const header = title.closest('.ui-page-header');
    if (!header?.querySelector('.ui-page-desc')) return;
    setPreference('showPageDesc', !preferences.showPageDesc);
  });
}
