import { queueCommand, getCommand, type CommandHistoryItem, type QueueCommandRequest } from './commands';
import { apiClient } from './client';

// Device app inventory for the kiosk picker. The agent answers `apps.scan` / `apps.icons`
// commands and returns the data as JSON in the command-result `detail`; we read it back from
// the command-history endpoint we already poll — so there is no dedicated server API.

export interface AppInfo {
  pkg: string;
  label: string;
  system?: boolean;
  launchable?: boolean;
  versionName?: string;
  versionCode?: number;
}

const sleep = (ms: number, signal?: AbortSignal) => new Promise<void>((resolve, reject) => {
  if (signal?.aborted) { reject(new DOMException('Cancelled', 'AbortError')); return; }
  const onAbort = () => { window.clearTimeout(timer); reject(new DOMException('Cancelled', 'AbortError')); };
  const timer = window.setTimeout(() => { signal?.removeEventListener('abort', onAbort); resolve(); }, ms);
  signal?.addEventListener('abort', onAbort, { once: true });
});

/** Queue a command and poll only that command until it completes, returning its result `detail`. */
async function runForResult(
  deviceId: number | string,
  req: QueueCommandRequest,
  timeoutMs = 75000,
  signal?: AbortSignal,
): Promise<string> {
  const queued = await queueCommand(deviceId, req, signal);
  const id = queued?.id;
  if (id == null) throw new Error('Command was not queued');
  const deadline = Date.now() + timeoutMs;
  let delay = 750;
  while (Date.now() < deadline) {
    if (signal?.aborted) throw new Error('Cancelled');
    await sleep(delay, signal);
    if (signal?.aborted) throw new Error('Cancelled');
    const cmd = await getCommand(deviceId, id, signal);
    if (cmd.status === 'done') return cmd.detail ?? '';
    if (cmd.status === 'failed' || cmd.status === 'unsupported' || cmd.status === 'expired') {
      throw new Error(cmd.detail || `Device returned ${cmd.status}`);
    }
    delay = Math.min(Math.round(delay * 1.6), 4000);
  }
  throw new Error('Timed out waiting for the device (is it online?)');
}

/** Scan the device for installed packages (metadata only — no icons). */
export async function scanApps(deviceId: number | string, signal?: AbortSignal): Promise<AppInfo[]> {
  const detail = await runForResult(deviceId, { type: 'apps.scan' }, 75000, signal);
  const parsed = JSON.parse(detail || '{}') as { apps?: AppInfo[] };
  const apps = parsed.apps ?? [];
  snapshotCache.set(String(deviceId), { value: { apps, scannedAt: Date.now() }, cachedAt: Date.now() });
  return apps;
}

export interface ScanSnapshot {
  apps: AppInfo[];
  scannedAt?: number;
}

const SNAPSHOT_CACHE_MS = 60_000;
const snapshotCache = new Map<string, { value: ScanSnapshot | null; cachedAt: number }>();

/**
 * The most recent saved scan, fetched through the server's indexed latest-snapshot lookup.
 * A short in-memory cache makes tab switches free. Returns null if the device was never scanned.
 */
export async function getLatestScan(deviceId: number | string): Promise<ScanSnapshot | null> {
  const key = String(deviceId), cached = snapshotCache.get(key);
  if (cached && Date.now() - cached.cachedAt < SNAPSHOT_CACHE_MS) return cached.value;
  const scan = await apiClient.get<CommandHistoryItem | null>(
    `/private/agent/v1/devices/${encodeURIComponent(key)}/apps/latest`,
  ).catch(() => null);
  if (!scan?.detail) {
    snapshotCache.set(key, { value: null, cachedAt: Date.now() });
    return null;
  }
  try {
    const apps = (JSON.parse(scan.detail).apps ?? []) as AppInfo[];
    const value = apps.length ? { apps, scannedAt: scan.completedAt } : null;
    snapshotCache.set(key, { value, cachedAt: Date.now() });
    return value;
  } catch {
    return null;
  }
}

function chunk<T>(arr: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

/** Overall cap across all icon batches — on top of the per-command timeout. */
const ICONS_DEADLINE_MS = 3 * 60 * 1000;

/** Fetch base64-PNG icons for the given packages, in batches. Returns a pkg→dataURL map. */
export async function fetchIcons(
  deviceId: number | string,
  packages: string[],
  onBatch?: (icons: Record<string, string>) => void,
  signal?: AbortSignal,
): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  const deadline = Date.now() + ICONS_DEADLINE_MS;
  for (const batch of chunk(packages, 24)) {
    const left = deadline - Date.now();
    if (left <= 0) throw new Error('Icon fetch took too long — try again');
    const detail = await runForResult(deviceId, {
      type: 'apps.icons',
      payload: JSON.stringify({ packages: batch }),
    }, Math.min(75000, left), signal);
    const parsed = JSON.parse(detail || '{}') as { icons?: Array<{ pkg: string; pngBase64: string }> };
    const got: Record<string, string> = {};
    for (const ic of parsed.icons ?? []) got[ic.pkg] = `data:image/png;base64,${ic.pngBase64}`;
    Object.assign(out, got);
    onBatch?.(got);
  }
  return out;
}
