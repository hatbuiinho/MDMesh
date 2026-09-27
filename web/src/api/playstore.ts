import { apiClient } from './client';

export interface PlayStatus {
  enabled: boolean;
  available: boolean;
  linked?: boolean;
  profile: string;
  message?: string;
  dispenserUrl?: string;
}

export interface PlayApp {
  packageName: string;
  name: string;
  summary?: string;
  iconUrl?: string | null;
  versionName?: string | null;
  versionCode?: number;
  paid?: boolean;
  compatible?: boolean;
}

export interface PlayImportResult {
  name: string;
  packageName: string;
  version?: string;
  versionCode: number;
  iconUrl?: string | null;
  parts: { url: string; sha256: string; name: string }[];
}

export const getPlayStatus = (): Promise<PlayStatus> =>
  apiClient.get('/private/play/status');

export function searchPlay(query: string, limit = 30, signal?: AbortSignal): Promise<PlayApp[]> {
  const params = new URLSearchParams({ q: query.trim(), limit: String(limit) });
  return apiClient.get(`/private/play/search?${params}`, signal);
}

export const importPlayApp = (packageName: string): Promise<PlayImportResult> =>
  apiClient.post('/private/play/import', { packageName });

export const pairPlayAccount = (code: string): Promise<{ linked: boolean; message?: string }> =>
  apiClient.post('/private/play/pair', { code });
