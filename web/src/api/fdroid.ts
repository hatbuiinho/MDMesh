import { apiClient } from './client';

// Server-side F-Droid catalogue proxy — see com.hmdm.rest.resource.FDroidResource.
//   GET /rest/private/fdroid/search?q=&limit=  -> FDroidApp[]
// The server fetches/caches the F-Droid index; the APK is deployed straight from
// f-droid.org's public URL (the agent downloads it directly).

export interface FDroidApp {
  packageName: string;
  name: string;
  summary?: string;
  iconUrl?: string | null;
  versionName?: string | null;
  versionCode: number;
  apkUrl: string;
  /** Hex SHA-256 from the F-Droid index. */
  sha256?: string | null;
  size?: number | null;
}

const searches = new Map<string, { expiresAt: number; result: Promise<FDroidApp[]> }>();
const SEARCH_TTL_MS = 5 * 60 * 1000;
const MAX_CACHED_SEARCHES = 100;

export function searchFdroid(q: string, limit = 50): Promise<FDroidApp[]> {
  const params = new URLSearchParams();
  if (q.trim()) params.set('q', q.trim().toLowerCase());
  params.set('limit', String(limit));
  const key = params.toString();
  const cached = searches.get(key);
  if (cached && cached.expiresAt > Date.now()) return cached.result;

  const entry = {
    expiresAt: Date.now() + SEARCH_TTL_MS,
    result: apiClient.get<FDroidApp[]>(`/private/fdroid/search?${key}`),
  };
  searches.delete(key);
  searches.set(key, entry);
  if (searches.size > MAX_CACHED_SEARCHES) searches.delete(searches.keys().next().value!);
  entry.result.then(() => {
    entry.expiresAt = Date.now() + SEARCH_TTL_MS;
  }, () => {
    if (searches.get(key) === entry) searches.delete(key);
  });
  return entry.result;
}
