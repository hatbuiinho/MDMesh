import { useEffect, useState } from 'react';
import { getEvents, type DeviceEvent } from '../api/events';

type Device = { number: string };

const LABELS: Record<string, string> = {
  boot: 'Booted',
  appInstalled: 'App installed',
  appUninstalled: 'App uninstalled',
  commandResult: 'Command',
  connectivityChange: 'Network change',
  lowBattery: 'Low battery',
  enrolled: 'Enrolled',
};

export function EventTimeline({ device }: { device: Device }) {
  const [events, setEvents] = useState<DeviceEvent[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [initialLoading, setInitialLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState(0);
  const pageSize = 50;

  useEffect(() => {
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    let request: AbortController | null = null;
    // Self-scheduling poll — the next tick is armed only after the current one finishes.
    const load = async () => {
      if (document.hidden) {
        t = setTimeout(() => void load(), 30000);
        return;
      }
      request?.abort();
      request = new AbortController();
      setError(null);
      await getEvents(device.number, 0, pageSize, undefined, request.signal)
        .then((evs) => {
          if (!on) return;
          setEvents((current) => {
            const byId = new Map(current.map((event) => [event.id, event]));
            evs.forEach((event) => byId.set(event.id, event));
            return Array.from(byId.values()).sort((a, b) => b.ts - a.ts || b.id - a.id);
          });
          setHasMore((current) => current || evs.length === pageSize);
        })
        .catch((err) => {
          if (!request?.signal.aborted) setError(err instanceof Error ? err.message : 'Failed to load events.');
        })
        .finally(() => {
          if (!request?.signal.aborted) setInitialLoading(false);
        });
      if (!on) return;
      t = setTimeout(() => void load(), 30000);
    };
    const resume = () => {
      if (!document.hidden) {
        clearTimeout(t);
        void load();
      }
    };
    document.addEventListener('visibilitychange', resume);
    void load();
    return () => {
      on = false;
      clearTimeout(t);
      request?.abort();
      document.removeEventListener('visibilitychange', resume);
    };
  }, [device.number, retryKey]);

  async function loadOlder() {
    const oldest = events[events.length - 1];
    if (!oldest) return;
    setLoadingMore(true);
    setError(null);
    try {
      const older = await getEvents(device.number, 0, pageSize, oldest.ts);
      setEvents((current) => {
        const ids = new Set(current.map((item) => item.id));
        return [...current, ...older.filter((next) => !ids.has(next.id))];
      });
      setHasMore(older.length === pageSize);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load older events.');
    } finally {
      setLoadingMore(false);
    }
  }

  return (
    <div className="panel">
      <h2 className="panel-title">Events</h2>
      {error && (
        <div className="inline-error" role="alert">
          <span>{error}</span>
          <button className="btn" onClick={() => setRetryKey((key) => key + 1)}>Retry</button>
        </div>
      )}
      {initialLoading ? (
        <p className="muted"><span className="spin" /> Loading events…</p>
      ) : events.length === 0 && !error ? (
        <p className="muted">No events yet.</p>
      ) : (
        <ul className="timeline">
          {events.map((e) => (
            <li key={e.id} className="timeline-item">
              <span className="t-status">{new Date(e.ts).toLocaleString()}</span>
              <span className="t-type">{LABELS[e.type] ?? e.type}</span>
              {e.detail && <span className="t-detail">{e.detail}</span>}
            </li>
          ))}
        </ul>
      )}
      {events.length > 0 && hasMore && (
        <button className="btn device-history-more" disabled={loadingMore} onClick={() => void loadOlder()}>
          {loadingMore ? 'Loading…' : 'Load older events'}
        </button>
      )}
    </div>
  );
}
