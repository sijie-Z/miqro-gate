import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, get, post } from '@/api/http';
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
    stubFetch(async (url, init) => {
      seen = init;
      return jsonResponse(200, []);
    });

    await get('/api/v1/me/virtual-keys');
    expect(seen?.headers).not.toHaveProperty('X-CSRF-Token');
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

    const error = await get('/api/v1/me/virtual-keys/abc').catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('VIRTUAL_KEY_NOT_FOUND');
    expect(error.requestId).toBe('0190...');
    expect(error.status).toBe(404);
  });

  it('throws a generic ApiError when the body is not problem+json', async () => {
    stubFetch(async () => new Response('upstream exploded', { status: 502 }));

    const error = await get('/api/v1/me/usage/summary').catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('HTTP_ERROR');
    expect(error.status).toBe(502);
  });

  it('maps network failures to a NETWORK_ERROR ApiError', async () => {
    stubFetch(async () => {
      throw new TypeError('Failed to fetch');
    });

    const error = await get('/api/v1/auth/me').catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('NETWORK_ERROR');
  });
});
