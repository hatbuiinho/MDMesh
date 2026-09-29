import { useEffect, useMemo, useState } from 'react';
import { getDeviceAppUsageReport, type AppUsageReport } from '../api/appUsage';
import { getLatestScan } from '../api/deviceApps';

type Range = 1 | 7 | 30;
const iso = (d: Date) => d.toISOString().slice(0, 10);
const duration = (ms: number) => {
  const minutes = Math.round(ms / 60000);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60), rest = minutes % 60;
  return `${hours}h${rest ? ` ${rest}m` : ''}`;
};

export function DeviceAppUsageTab({ device }: { device: { number: string } }) {
  const [range, setRange] = useState<Range>(7);
  const [rows, setRows] = useState<AppUsageReport[]>([]);
  const [labels, setLabels] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    const ac = new AbortController();
    const end = new Date(), start = new Date();
    start.setDate(end.getDate() - range + 1);
    setLoading(true); setError('');
    void Promise.all([
      getDeviceAppUsageReport(device.number, iso(start), iso(end), ac.signal),
      getLatestScan(device.number),
    ]).then(([report, scan]) => {
      if (ac.signal.aborted) return;
      setRows(report);
      setLabels(Object.fromEntries((scan?.apps ?? []).map((app) => [app.pkg, app.label || app.pkg])));
    }).catch((e: unknown) => {
      if (!ac.signal.aborted) setError(e instanceof Error ? e.message : 'Could not load app usage.');
    }).finally(() => { if (!ac.signal.aborted) setLoading(false); });
    return () => ac.abort();
  }, [device.number, range]);

  const apps = useMemo(() => {
    const totals = new Map<string, { total: number; latest?: AppUsageReport }>();
    for (const row of rows) {
      const value = totals.get(row.packageName) ?? { total: 0 };
      value.total += row.foregroundMs;
      if (!value.latest || row.usageDate > value.latest.usageDate) value.latest = row;
      totals.set(row.packageName, value);
    }
    return [...totals.entries()].sort((a, b) => b[1].total - a[1].total);
  }, [rows]);
  const total = apps.reduce((sum, [, value]) => sum + value.total, 0);
  const latestUpdate = rows.reduce((latest, row) => Math.max(latest, (row as AppUsageReport & { updatedAt?: number }).updatedAt ?? 0), 0);

  return <div className="device-usage">
    <div className="device-usage-head">
      <div><h2>App usage</h2><p className="muted">Daily foreground time reported by this device.</p></div>
      <div className="seg" aria-label="Usage date range">
        {([1, 7, 30] as Range[]).map((days) => <button key={days} className={range === days ? 'on' : ''} onClick={() => setRange(days)}>{days === 1 ? 'Today' : `${days} days`}</button>)}
      </div>
    </div>
    {loading && <p className="muted">Loading app usage…</p>}
    {error && <div className="banner banner-alert">{error}</div>}
    {!loading && !error && <>
      <div className="device-usage-summary">
        <div><span>Total foreground time</span><strong>{duration(total)}</strong></div>
        <div><span>Apps reported</span><strong>{apps.length}</strong></div>
        <div><span>Last report</span><strong>{latestUpdate ? new Date(latestUpdate).toLocaleString() : '—'}</strong></div>
      </div>
      {!apps.length ? <div className="empty">No app usage has been reported for this period.</div> :
        <div className="usage-report"><table><thead><tr><th>App</th><th>Total</th><th>Average / day</th><th>Latest status</th></tr></thead><tbody>
          {apps.slice(0, 100).map(([pkg, value]) => <tr key={pkg}><td><strong>{labels[pkg] || pkg}</strong>{labels[pkg] && <div className="mono muted">{pkg}</div>}</td><td>{duration(value.total)}</td><td>{duration(value.total / range)}</td><td>{value.latest?.status?.replace(/_/g, ' ') || '—'}</td></tr>)}
        </tbody></table></div>}
    </>}
  </div>;
}
