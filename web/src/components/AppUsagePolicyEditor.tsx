import { useEffect, useState } from 'react';
import { getAppUsagePolicy, getAppUsageReport, grantAppUsage, saveAppUsagePolicy, type AppUsagePolicy, type AppUsageRule, type AppUsageReport } from '../api/appUsage';
import type { ConfigApp } from '../api/configurations';
import { useToast } from '../ui/toast';

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const iso = (d: Date) => d.toISOString().slice(0, 10);

export function AppUsagePolicyEditor({ configurationId, apps, readOnly }: { configurationId?: number; apps: ConfigApp[]; readOnly: boolean }) {
  const toast = useToast();
  const [policy, setPolicy] = useState<AppUsagePolicy>({ timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', rules: [] });
  const [report, setReport] = useState<AppUsageReport[]>([]);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!configurationId) return;
    void getAppUsagePolicy(configurationId).then(setPolicy).catch(() => toast.push('err', 'App usage', 'Could not load policy.'));
    const now = new Date(); const from = new Date(now.getTime() - 6 * 86400000);
    void getAppUsageReport(configurationId, iso(from), iso(now)).then(setReport).catch(() => undefined);
  }, [configurationId]);
  if (!configurationId) return <p className="note">Save the configuration first, then add app usage rules.</p>;
  const availableApps = apps.filter((a): a is ConfigApp & { pkg: string } => (a.action ?? 1) === 1 && Boolean(a.pkg));
  const packages = availableApps.map((a) => a.pkg);
  const add = () => {
    const pkg = packages.find((p) => !policy.rules.some((r) => r.packageName === p)); if (!pkg) return;
    setPolicy({ ...policy, rules: [...policy.rules, { packageName: pkg, dailyLimitMinutes: 60, warningMinutes: 5, action: 'suspend', enabled: true, allowedWindows: [] }] });
  };
  const update = (i: number, patch: Partial<AppUsageRule>) => setPolicy({ ...policy, rules: policy.rules.map((r, n) => n === i ? { ...r, ...patch } : r) });
  const save = async () => {
    const selected = policy.rules.map((r) => r.packageName);
    if (selected.some((pkg) => !packages.includes(pkg))) {
      toast.push('err', 'Invalid app', 'Choose an app from the search suggestions before saving.');
      return;
    }
    if (new Set(selected).size !== selected.length) {
      toast.push('err', 'Duplicate app', 'Each app can only have one usage rule.');
      return;
    }
    setBusy(true);
    try { setPolicy(await saveAppUsagePolicy(configurationId, policy)); toast.push('ok', 'App usage saved', 'Devices will apply it at their next check-in.'); } catch (e) { toast.push('err', 'Save failed', e instanceof Error ? e.message : ''); } finally { setBusy(false); }
  };
  const grant = async (row: AppUsageReport) => { try { await grantAppUsage(row.deviceNumber, row.packageName, 60); toast.push('ok', 'Extra time granted', `${row.deviceNumber} can use ${row.packageName} for 60 more minutes today.`); } catch (e) { toast.push('err', 'Grant failed', e instanceof Error ? e.message : ''); } };
  return <>
    <div className="cfg-field"><div className="cfg-field-label"><label>Policy timezone</label></div><input className="input" disabled={readOnly} value={policy.timezone} onChange={(e) => setPolicy({ ...policy, timezone: e.target.value })} /></div>
    {policy.rules.map((r, i) => <div className="usage-rule" key={i}>
      <div className="usage-app-picker">
        <input
          className="input"
          type="search"
          list={`usage-apps-${configurationId}-${i}`}
          aria-label="Search app"
          placeholder="Search app name or package"
          disabled={readOnly}
          value={r.packageName}
          onChange={(e) => update(i, { packageName: e.target.value })}
        />
        <datalist id={`usage-apps-${configurationId}-${i}`}>
          {availableApps.map((app) => <option key={app.pkg} value={app.pkg}>{app.name || app.pkg}</option>)}
        </datalist>
      </div>
      <label>Daily minutes <input className="input" type="number" min={1} max={1440} disabled={readOnly} value={r.dailyLimitMinutes ?? ''} onChange={(e) => update(i, { dailyLimitMinutes: e.target.value ? Number(e.target.value) : undefined })} /></label>
      <label>Warn before <input className="input" type="number" min={0} max={120} disabled={readOnly} value={r.warningMinutes} onChange={(e) => update(i, { warningMinutes: Number(e.target.value) })} /></label>
      <label className="kiosk-toggle"><input type="checkbox" disabled={readOnly} checked={r.enabled} onChange={(e) => update(i, { enabled: e.target.checked })} /> Enabled</label>
      <button className="btn btn-sm" disabled={readOnly} onClick={() => update(i, { allowedWindows: [...r.allowedWindows, { days: [1,2,3,4,5], from: '08:00', to: '17:00' }] })}>Add window</button>
      {!readOnly && <button className="btn btn-sm btn-ghost" onClick={() => setPolicy({ ...policy, rules: policy.rules.filter((_, n) => n !== i) })}>Remove</button>}
      {r.allowedWindows.map((w, wi) => <div className="usage-window" key={wi}>
        {DAYS.map((d, di) => <label key={d}><input type="checkbox" disabled={readOnly} checked={w.days.includes(di + 1)} onChange={(e) => { const days=e.target.checked?[...w.days,di+1]:w.days.filter(x=>x!==di+1); update(i,{allowedWindows:r.allowedWindows.map((x,n)=>n===wi?{...x,days}:x)}); }} />{d}</label>)}
        <input className="input" type="time" disabled={readOnly} value={w.from} onChange={(e)=>update(i,{allowedWindows:r.allowedWindows.map((x,n)=>n===wi?{...x,from:e.target.value}:x)})}/>
        <span>–</span><input className="input" type="time" disabled={readOnly} value={w.to} onChange={(e)=>update(i,{allowedWindows:r.allowedWindows.map((x,n)=>n===wi?{...x,to:e.target.value}:x)})}/>
        {!readOnly && <button className="btn btn-sm btn-ghost usage-window-remove" aria-label={`Remove time window ${wi + 1}`} onClick={() => update(i, { allowedWindows: r.allowedWindows.filter((_, n) => n !== wi) })}>Remove window</button>}
      </div>)}
    </div>)}
    {!readOnly && <div className="modal-actions"><button className="btn" disabled={!packages.length} onClick={add}>Add app rule</button><button className="btn btn-primary" disabled={busy} onClick={() => void save()}>{busy ? 'Saving…' : 'Save usage policy'}</button></div>}
    {report.length > 0 && <div className="usage-report"><h4>Last 7 days</h4><table><thead><tr><th>Device</th><th>App</th><th>Date</th><th>Usage</th><th>Status</th><th /></tr></thead><tbody>{report.slice(0,100).map((x,n)=><tr key={n}><td>{x.deviceNumber}</td><td className="mono">{x.packageName}</td><td>{x.usageDate}</td><td>{Math.round(x.foregroundMs/60000)} min</td><td>{x.status ?? '—'}</td><td>{!readOnly && x.usageDate === iso(new Date()) && <button className="btn btn-sm" onClick={() => void grant(x)}>+60 min</button>}</td></tr>)}</tbody></table></div>}
  </>;
}
