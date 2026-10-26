// Thin fetch wrapper around the Headwind MDM REST API.
//
// The server (see com.hmdm.rest.json.Response) wraps every response in an
// envelope: { status: "OK" | "ERROR" | ..., message: string | null, data: T }.
// Authentication is *session based*: AuthResource#login stores the user in the
// HttpSession and the browser receives a JSESSIONID cookie. There is no bearer
// token to attach. Every request sends credentials; a separate HttpOnly refresh
// cookie renews an expired access session.

export const API_BASE: string =
  (import.meta.env.VITE_API_BASE ?? '/rest').replace(/\/$/, '');

export type ResponseStatus = 'OK' | 'ERROR' | string;

export interface ApiEnvelope<T> {
  status: ResponseStatus;
  message: string | null;
  data: T;
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: ResponseStatus,
    readonly httpStatus: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Optional cancellation, forwarded to fetch — lets callers abort long polls. */
  signal?: AbortSignal;
  // Some endpoints (e.g. /devices/search) consume a raw JSON string rather than
  // an object. We always JSON.stringify, which is correct for both.
  /** Send `body` verbatim as text/plain instead of JSON — for endpoints that read
   *  a raw String (e.g. POST /devices/{id}/description). */
  text?: boolean;
}

let refreshing: Promise<boolean> | null = null;

async function mutateWebSession(action: 'register' | 'refresh' | 'revoke'): Promise<boolean> {
  const res = await fetch(`${API_BASE}/public/web-session/${action}`, {
    method: 'POST', credentials: 'include',
    headers: { 'X-MDMesh-Session': '1', Accept: 'application/json' },
  });
  if (!res.ok) {
    if (action === 'refresh' && res.status === 401) window.dispatchEvent(new Event('mdmesh-session-expired'));
    return false;
  }
  const envelope = await res.json() as ApiEnvelope<unknown>;
  return envelope.status === 'OK';
}

export async function webSession(action: 'register' | 'refresh' | 'revoke'): Promise<boolean> {
  if (refreshing) await refreshing.catch(() => false);
  return navigator.locks
    ? await navigator.locks.request('mdmesh-web-refresh', async () => mutateWebSession(action))
    : mutateWebSession(action);
}

function renewSession(): Promise<boolean> {
  if (!refreshing) {
    const renew = async () => {
      const status = await fetch(`${API_BASE}/public/web-session/status`, { credentials: 'include', cache: 'no-store' });
      return status.ok || mutateWebSession('refresh');
    };
    // Coordinate rotation across tabs as well as concurrent requests in this tab.
    refreshing = (async (): Promise<boolean> => {
      if (navigator.locks) return await navigator.locks.request('mdmesh-web-refresh', renew);
      return renew();
    })().finally(() => { refreshing = null; });
  }
  return refreshing;
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const url = `${API_BASE}${path}`;
  const init: RequestInit = {
    method: opts.method ?? 'GET',
    // Always send the session cookie. Works same-origin and (with proper CORS)
    // cross-origin.
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
    signal: opts.signal,
  };

  if (opts.body !== undefined) {
    if (opts.body instanceof FormData) {
      // Let the browser set the multipart boundary; don't force a Content-Type.
      init.body = opts.body;
    } else if (opts.text) {
      // Raw text body for endpoints that read a String (not JSON) — sending it
      // JSON-encoded would store the surrounding quotes.
      init.headers = { ...init.headers, 'Content-Type': 'text/plain' };
      init.body = String(opts.body);
    } else {
      init.headers = {
        ...init.headers,
        'Content-Type': 'application/json',
      };
      init.body = JSON.stringify(opts.body);
    }
  }

  let res: globalThis.Response;
  try {
    res = await fetch(url, init);
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new ApiError(
      `Network error contacting the server: ${(e as Error).message}`,
      'ERROR',
      0,
    );
  }

  if ((res.status === 401 || res.status === 403) && path.startsWith('/private/')) {
    if (await renewSession()) res = await fetch(url, init);
  }

  if (res.status === 401 || res.status === 403) {
    throw new ApiError('Not authenticated', 'ERROR', res.status);
  }

  if (!res.ok) {
    throw new ApiError(
      `Request failed with HTTP ${res.status}`,
      'ERROR',
      res.status,
    );
  }

  // Most endpoints return the JSON envelope. Some (logout) return no body.
  const text = await res.text();
  if (!text) {
    return undefined as unknown as T;
  }

  let envelope: ApiEnvelope<T>;
  try {
    envelope = JSON.parse(text) as ApiEnvelope<T>;
  } catch {
    // e.g. an HTML error page from a proxy — surface it as an API error, not a crash.
    throw new ApiError('Unexpected non-JSON response from server', 'ERROR', res.status);
  }
  if (envelope.status && envelope.status !== 'OK') {
    throw new ApiError(
      envelope.message ?? 'The server returned an error',
      envelope.status,
      res.status,
    );
  }
  return envelope.data;
}

export const apiClient = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown, signal?: AbortSignal) =>
    request<T>(path, { method: 'POST', body, signal }),
  postText: <T>(path: string, body: string, signal?: AbortSignal) =>
    request<T>(path, { method: 'POST', body, text: true, signal }),
  put: <T>(path: string, body?: unknown, signal?: AbortSignal) =>
    request<T>(path, { method: 'PUT', body, signal }),
  del: <T>(path: string, signal?: AbortSignal) =>
    request<T>(path, { method: 'DELETE', signal }),
  postForm: <T>(path: string, form: FormData, signal?: AbortSignal) =>
    request<T>(path, { method: 'POST', body: form, signal }),
};
