import { useEffect, useState } from 'react';
import { listLocations, type LocationFix } from '../api/deviceLocations';
import { LocationMap } from './LocationMap';
import { fmtRelative } from '../ui/format';

export function LocationPanel({ device }: { device: { number: string } }) {
  const [fixes, setFixes] = useState<LocationFix[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const pageSize = 100;

  async function load(signal?: AbortSignal) {
    setErr(null);
    setRefreshing(true);
    try {
      const next = await listLocations(device.number, 0, pageSize, undefined, signal);
      setFixes(next);
      setHasMore(next.length === pageSize);
    } catch (e) {
      if (signal?.aborted) return;
      setErr(e instanceof Error ? e.message : 'Failed to load locations');
    } finally {
      if (!signal?.aborted) setRefreshing(false);
    }
  }

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [device.number]);

  async function loadOlder() {
    const oldest = fixes?.[fixes.length - 1];
    if (!oldest) return;
    setLoadingMore(true);
    setErr(null);
    try {
      const older = await listLocations(device.number, 0, pageSize, oldest.capturedAt);
      setFixes((current) => {
        const existing = new Set((current ?? []).map((fix) => fix.id ?? `${fix.capturedAt}:${fix.lat}:${fix.lon}`));
        return [...(current ?? []), ...older.filter((fix) => !existing.has(fix.id ?? `${fix.capturedAt}:${fix.lat}:${fix.lon}`))];
      });
      setHasMore(older.length === pageSize);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load older locations');
    } finally {
      setLoadingMore(false);
    }
  }

  return (
    <div className="panel">
      <div className="panel-head">
        <h2 className="panel-title">Location history</h2>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          {fixes && (
            <span className="muted">
              {fixes.length} fix{fixes.length === 1 ? '' : 'es'}
              {fixes[0] ? ` · latest ${fmtRelative(fixes[0].capturedAt)}` : ''}
            </span>
          )}
          <button className="btn" disabled={refreshing} onClick={() => void load()}>
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>
      {err && (
        <div className="inline-error" role="alert">
          <span>{err}</span>
          <button className="btn" disabled={refreshing} onClick={() => void load()}>Retry</button>
        </div>
      )}
      {!fixes && !err && <p className="muted">Loading location…</p>}
      {fixes && fixes.length === 0 && (
        <p className="muted">No location reported yet. Wake the device, or switch it to Accurate location mode.</p>
      )}
      {fixes && fixes.length > 0 && <LocationMap fixes={fixes} />}
      {fixes && fixes.length > 0 && hasMore && (
        <button className="btn device-history-more" disabled={loadingMore} onClick={() => void loadOlder()}>
          {loadingMore ? 'Loading…' : 'Load older locations'}
        </button>
      )}
    </div>
  );
}
