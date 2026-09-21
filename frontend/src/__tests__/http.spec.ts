import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, get, getList, post } from '@/api/http';
import type { ProblemDetails } from '@/types/api';

const CSRF = 'csrf-token-value';

function stubFetch(handler: (url: string, init: RequestInit) => Promise<Response>) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    return handler(url, init ?? {});
  });
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('http client', () => {
  beforeEach(() => {
    document.cookie = `${encodeURIComponent('MIQROKEY_CSRF')}=${CSRF}; path=/`;
  });

  afterEach(() => {
    vi.restoreAllMocks();
    document.cookie = `${encodeURIComponent('MIQROKEY_CSRF')}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  });

  it('sends credentials and the CSRF token on mutating requests', async () => {
    const fetchMock = stubFetch(async (url, init) => {
      expect(url).toBe('/api/v1/me/virtual-keys');
      expect(init.method).toBe('POST');
      expect(init.credentials).toBe('include');
      const headers = init.headers as Record<string, string>;
      expect(headers['X-CSRF-Token']).toBe(CSRF);
      expect(headers['Content-Type']).toBe('application/json');
      return jsonResponse(201, { id: 'key-1' });
    });

    await post('/api/v1/me/virtual-keys', { name: 'k' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not attach the CSRF token to GET requests', async () => {
    let seen: RequestInit | null = null;
    stubFetch(async (_url, init) => {
      seen = init;
      return jsonResponse(200, []);
    });

    await get('/api/v1/me/virtual-keys');
    expect((seen as RequestInit | null)?.headers).not.toHaveProperty('X-CSRF-Token');
  });

  it('serializes GET query parameters, skipping undefined values', async () => {
    let seenUrl = '';
    stubFetch(async (url) => {
      seenUrl = url;
      return jsonResponse(200, { items: [], page: 1, size: 50, total: 0 });
    });

    await get('/api/v1/me/usage/records', { page: 2, size: 50, from: undefined });
    expect(seenUrl).toBe('/api/v1/me/usage/records?page=2&size=50');
  });

  it('throws ApiError with code and requestId on problem+json responses', async () => {
    const details: ProblemDetails = {
      type: 'about:blank',
      title: 'Virtual key not found',
      status: 404,
      code: 'VIRTUAL_KEY_NOT_FOUND',
      detail: 'The requested virtual key does not exist or is not visible.',
      requestId: '0190...',
    };
    stubFetch(async () => jsonResponse(404, details));

    const error = (await get('/api/v1/me/virtual-keys/abc').catch((e) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('VIRTUAL_KEY_NOT_FOUND');
    expect(error.requestId).toBe('0190...');
    expect(error.status).toBe(404);
  });

  it('throws a generic ApiError when the body is not problem+json', async () => {
    stubFetch(async () => new Response('upstream exploded', { status: 502 }));

    const error = (await get('/api/v1/me/usage/summary').catch((e) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('HTTP_ERROR');
    expect(error.status).toBe(502);
  });

  it('maps network failures to a NETWORK_ERROR ApiError', async () => {
    stubFetch(async () => {
      throw new TypeError('Failed to fetch');
    });

    const error = (await get('/api/v1/auth/me').catch((e) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('NETWORK_ERROR');
  });

  it('aborts a stalled request with a TIMEOUT ApiError after the client cap (#583)', async () => {
    vi.useFakeTimers();
    try {
      stubFetch(
        (_url, init) =>
          new Promise<Response>((_resolve, reject) => {
            init.signal?.addEventListener('abort', () => {
              reject(new DOMException('aborted', 'AbortError'));
            });
          }),
      );

      const pending = get('/api/v1/me/usage/summary').catch((e) => e);
      await vi.advanceTimersByTimeAsync(60_000);

      const error = (await pending) as ApiError;
      expect(error).toBeInstanceOf(ApiError);
      expect(error.code).toBe('TIMEOUT');
      expect(error.message).toContain('超时');
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('getList', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  // 正向对照：真数组必须原样透传 —— 只喂坏数据的测试无法发现「无条件返回 []」这种回归
  it('passes a real array through untouched', async () => {
    stubFetch(async () => jsonResponse(200, [{ id: 'a' }, { id: 'b' }]));
    await expect(getList<{ id: string }>('/api/v1/me/virtual-keys')).resolves.toEqual([
      { id: 'a' },
      { id: 'b' },
    ]);
  });

  it('normalises a null body to an empty array', async () => {
    stubFetch(
      async () =>
        new Response('null', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    await expect(getList('/api/v1/me/virtual-keys')).resolves.toEqual([]);
  });

  it('normalises a 204 / empty body to an empty array', async () => {
    stubFetch(async () => new Response(null, { status: 204 }));
    await expect(getList('/api/v1/me/virtual-keys')).resolves.toEqual([]);
  });
});
