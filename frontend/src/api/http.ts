/**
 * Thin fetch client for the Control Plane management API.
 *
 * - Session cookie is sent with `credentials: 'include'`.
 * - Every mutating request carries `X-CSRF-Token` read from the CSRF cookie
 *   (non-HttpOnly, per api-contract.md §3.3). GET /api/v1/auth/csrf is an
 *   alternative for servers that do not expose the cookie.
 * - Errors are RFC 9457 problem+json; a stable ApiError is thrown with the
 *   machine `code` and `requestId` for inline display.
 * - The plaintext Virtual Key must never be written to storage or logs here;
 *   it only passes through the create response.
 */

import type { ProblemDetails } from '@/types/api';

export const CSRF_COOKIE_NAME = 'MIQROKEY_CSRF';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;
  readonly fieldErrors?: Array<{ field: string; code: string }>;

  constructor(details: ProblemDetails) {
    super(details.detail ?? details.title);
    this.name = 'ApiError';
    this.status = details.status;
    this.code = details.code;
    this.requestId = details.requestId;
    this.fieldErrors = details.fieldErrors;
  }
}

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  for (const part of document.cookie.split(';')) {
    const trimmed = part.trim();
    if (trimmed.startsWith(prefix)) {
      return decodeURIComponent(trimmed.slice(prefix.length));
    }
  }
  return undefined;
}

async function parseError(response: Response): Promise<ApiError> {
  let details: ProblemDetails | undefined;
  try {
    const body = await response.json();
    if (body && typeof body.code === 'string' && typeof body.requestId === 'string') {
      details = body as ProblemDetails;
    }
  } catch {
    // Not JSON — fall back to a generic error below.
  }
  if (details) {
    return new ApiError(details);
  }
  return new ApiError({
    type: 'about:blank',
    title: `Request failed with status ${response.status}`,
    status: response.status,
    code: 'HTTP_ERROR',
    requestId: '',
  });
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** GET query parameters (values are stringified). */
  query?: Record<string, string | number | undefined>;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query } = options;

  let url = path;
  if (query) {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) {
        params.set(key, String(value));
      }
    }
    const qs = params.toString();
    if (qs) {
      url += `?${qs}`;
    }
  }

  const headers: Record<string, string> = { Accept: 'application/json' };
  const isMutation = method !== 'GET';
  if (isMutation) {
    const csrf = readCookie(CSRF_COOKIE_NAME);
    if (csrf) {
      headers['X-CSRF-Token'] = csrf;
    }
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  let response: Response;
  try {
    response = await fetch(url, {
      method,
      headers,
      credentials: 'include',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError({
      type: 'about:blank',
      title: 'Network error',
      status: 0,
      code: 'NETWORK_ERROR',
      detail: '无法连接到 MiQroKey 服务，请检查网络后重试。',
      requestId: '',
    });
  }

  if (!response.ok) {
    throw await parseError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function get<T>(path: string, query?: RequestOptions['query']): Promise<T> {
  return request<T>(path, { method: 'GET', query });
}

export function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, { method: 'POST', body });
}
