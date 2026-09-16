import { beforeEach, describe, expect, it, vi } from 'vitest';
import { installChunkReload, isChunkLoadError, reloadForChunkError } from '@/utils/chunk-reload';

describe('chunk-reload (#663)', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it('recognises dynamic import failures across engines', () => {
    expect(
      isChunkLoadError(new TypeError('Failed to fetch dynamically imported module: /assets/x.js')),
    ).toBe(true);
    expect(isChunkLoadError(new TypeError('error loading dynamically imported module'))).toBe(true);
    expect(isChunkLoadError(new Error('Importing a module script failed.'))).toBe(true);
    expect(isChunkLoadError(new Error('boom'))).toBe(false);
    expect(isChunkLoadError(undefined)).toBe(false);
  });

  it('reloads once, then holds off inside the cooldown and fires again after it', () => {
    const reload = vi.fn();
    expect(reloadForChunkError(1_000_000, reload)).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(reloadForChunkError(1_009_999, reload)).toBe(false);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(reloadForChunkError(1_010_000, reload)).toBe(true);
    expect(reload).toHaveBeenCalledTimes(2);
  });

  it('leaves the location untouched when sessionStorage is unavailable', () => {
    const reload = vi.fn();
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });
    expect(reloadForChunkError(Date.now(), reload)).toBe(false);
    expect(reload).not.toHaveBeenCalled();
    getItem.mockRestore();
  });

  it('reloads on vite:preloadError and suppresses the error', () => {
    const reload = vi.fn();
    const uninstall = installChunkReload(reload);

    const first = new Event('vite:preloadError', { cancelable: true });
    window.dispatchEvent(first);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(first.defaultPrevented).toBe(true);

    // Second failure inside the cooldown: no reload, the error surfaces.
    const second = new Event('vite:preloadError', { cancelable: true });
    window.dispatchEvent(second);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(second.defaultPrevented).toBe(false);

    uninstall();
  });
});
