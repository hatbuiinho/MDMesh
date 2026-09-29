import { useEffect, useMemo, useState } from 'react';
import { getAppUsagePolicy, saveAppUsagePolicy, type AppUsagePolicy, type AppUsageRule, type AppUsageWindow } from '../api/appUsage';
import type { ConfigApp } from '../api/configurations';
import { useToast } from '../ui/toast';

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const WEEKDAYS = [1, 2, 3, 4, 5], WEEKEND = [6, 7], EVERY_DAY = [1, 2, 3, 4, 5, 6, 7];
const clone = (p: AppUsagePolicy): AppUsagePolicy => JSON.parse(JSON.stringify(p)) as AppUsagePolicy;
const browserZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
const zones = (current: string) => {
  const intl = Intl as typeof Intl & { supportedValuesOf?: (key: 'timeZone') => string[] };
  const fallback = ['UTC', 'Asia/Ho_Chi_Minh', 'Asia/Bangkok', 'Asia/Singapore', 'Asia/Tokyo', 'Europe/London', 'Europe/Paris', 'America/New_York', 'America/Los_Angeles', 'Australia/Sydney'];
  return Array.from(new Set(['UTC', browserZone(), current, ...(intl.supportedValuesOf?.('timeZone') ?? fallback)]))
    .sort((a, b) => zoneOffset(a) - zoneOffset(b) || a.localeCompare(b));
};
const zoneOffset = (zone: string) => {
  try {
    const now = new Date();
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: zone, hour12: false, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    }).formatToParts(now);
    const value = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find((part) => part.type === type)?.value);
    let hour = value('hour');
    if (hour === 24) hour = 0;
    const zoned = Date.UTC(value('year'), value('month') - 1, value('day'), hour, value('minute'), value('second'));
    return zoned - Math.floor(now.getTime() / 1000) * 1000;
  } catch { return 0; }
};
const zoneLabel = (zone: string) => {
  try { const offset = new Intl.DateTimeFormat('en', { timeZone: zone, timeZoneName: 'shortOffset' }).formatToParts(new Date()).find((p) => p.type === 'timeZoneName')?.value; return `${zone.replace(/_/g, ' ').replace('/', ' — ')}${offset ? ` (${offset})` : ''}`; }
  catch { return zone; }
};
const zoneTime = (zone: string) => { try { return new Intl.DateTimeFormat(undefined, { timeZone: zone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date()); } catch { return ''; } };
const windowError = (w: AppUsageWindow) => !w.days.length ? 'Choose at least one day.' : w.from === w.to ? 'Start and end time must be different.' : '';

export function AppUsagePolicyEditor({ configurationId, apps, readOnly }: { configurationId?: number; apps: ConfigApp[]; readOnly: boolean }) {
  const toast = useToast();
  const initial = useMemo<AppUsagePolicy>(() => ({ timezone: browserZone(), rules: [] }), []);
  const [policy, setPolicy] = useState(initial), [saved, setSaved] = useState(initial);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [reload, setReload] = useState(0);
  const [picker, setPicker] = useState(false), [query, setQuery] = useState('');

  useEffect(() => {
    if (!configurationId) return;
    let cancelled = false; setLoading(true); setError('');
    void getAppUsagePolicy(configurationId).then((p) => { if (!cancelled) { const value = { ...p, rules: p.rules ?? [] }; setPolicy(clone(value)); setSaved(clone(value)); } }).catch((e: unknown) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Could not load policy.'); }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [configurationId, reload]);

  const dirty = JSON.stringify(policy) !== JSON.stringify(saved);
  useEffect(() => { if (!dirty) return; const warn = (e: BeforeUnloadEvent) => e.preventDefault(); window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn); }, [dirty]);
  if (!configurationId) return <p className="note">Save the configuration first, then add app usage rules.</p>;

  const installed = apps.filter((a): a is ConfigApp & { pkg: string } => (a.action ?? 1) === 1 && Boolean(a.pkg));
  const packages = installed.map((a) => a.pkg);
  const choices = installed.filter((a) => !policy.rules.some((r) => r.packageName === a.pkg) && (!query.trim() || a.name?.toLowerCase().includes(query.toLowerCase()) || a.pkg.toLowerCase().includes(query.toLowerCase())));
  const update = (i: number, patch: Partial<AppUsageRule>) => setPolicy((p) => ({ ...p, rules: p.rules.map((r, n) => n === i ? { ...r, ...patch } : r) }));
  const updateWindow = (ri: number, wi: number, patch: Partial<AppUsageWindow>) => update(ri, { allowedWindows: policy.rules[ri].allowedWindows.map((w, n) => n === wi ? { ...w, ...patch } : w) });
  const addApp = (app: ConfigApp & { pkg: string }) => { setPolicy((p) => ({ ...p, rules: [...p.rules, { packageName: app.pkg, dailyLimitMinutes: 60, warningMinutes: 5, action: 'suspend', enabled: true, allowedWindows: [] }] })); setPicker(false); setQuery(''); };
  const save = async () => {
    if (policy.rules.some((r) => r.allowedWindows.some(windowError))) return toast.push('err', 'Invalid time window', 'Fix the highlighted time windows before saving.');
    const selected = policy.rules.map((r) => r.packageName);
    if (selected.some((pkg) => !packages.includes(pkg)) || new Set(selected).size !== selected.length) return toast.push('err', 'Invalid app', 'Every rule must use a unique installed app.');
    setBusy(true); try { const value = await saveAppUsagePolicy(configurationId, policy); const normalized = value ?? policy; setPolicy(clone(normalized)); setSaved(clone(normalized)); toast.push('ok', 'App usage saved', 'Devices will apply it at their next check-in.'); } catch (e) { toast.push('err', 'Save failed', e instanceof Error ? e.message : ''); } finally { setBusy(false); }
  };

  if (loading) return <div className="usage-loading" role="status">Loading app usage policy…</div>;
  if (error) return <div className="banner banner-alert usage-load-error" role="alert"><span><b>Could not load app usage policy.</b><br />{error}</span><button className="btn btn-sm" onClick={() => setReload((v) => v + 1)}>Retry</button></div>;
  return <>
    <div className="usage-timezone"><div><label htmlFor={`usage-timezone-${configurationId}`}>Policy timezone</label><span>All schedules in this configuration use this timezone.</span></div><div><select id={`usage-timezone-${configurationId}`} className="sel" disabled={readOnly} value={policy.timezone} onChange={(e) => setPolicy({ ...policy, timezone: e.target.value })}>{zones(policy.timezone).map((z) => <option key={z} value={z}>{zoneLabel(z)}</option>)}</select><small>Current policy time: {zoneTime(policy.timezone)}</small></div></div>
    {!policy.rules.length && <div className="cfg-empty usage-empty">No app limits yet. Add an installed app to create a daily limit.</div>}
    <div className="usage-rules">{policy.rules.map((r, ri) => { const app = installed.find((a) => a.pkg === r.packageName); return <article className={`usage-rule-card${r.enabled ? '' : ' is-disabled'}`} key={r.id ?? r.packageName}>
      <header className="usage-rule-head"><div><strong>{app?.name || r.packageName}</strong><span className="mono">{r.packageName}</span></div><label className="kiosk-toggle"><input type="checkbox" disabled={readOnly} checked={r.enabled} onChange={(e) => update(ri, { enabled: e.target.checked })} /> Enabled</label>{!readOnly && <button className="btn btn-sm btn-ghost usage-danger" onClick={() => setPolicy({ ...policy, rules: policy.rules.filter((_, n) => n !== ri) })}>Delete rule</button>}</header>
      <div className="usage-rule-settings"><label>Daily limit <span><input className="input" type="number" min={1} max={1440} disabled={readOnly} value={r.dailyLimitMinutes ?? ''} onChange={(e) => update(ri, { dailyLimitMinutes: e.target.value ? Number(e.target.value) : undefined })} /> minutes</span></label><label>Warn before <span><input className="input" type="number" min={0} max={120} disabled={readOnly} value={r.warningMinutes} onChange={(e) => update(ri, { warningMinutes: Number(e.target.value) })} /> minutes</span></label></div>
      <div className="usage-windows-head"><div><strong>Allowed time windows</strong><span>{r.allowedWindows.length ? 'The app is available only during these windows.' : 'No window means the app may be used at any time, subject to its daily limit.'}</span></div>{!readOnly && <button className="btn btn-sm" onClick={() => update(ri, { allowedWindows: [...r.allowedWindows, { days: WEEKDAYS, from: '08:00', to: '17:00' }] })}>+ Add time window</button>}</div>
      {r.allowedWindows.map((w, wi) => { const validation = windowError(w); return <div className={`usage-window${validation ? ' has-error' : ''}`} key={wi}><div className="usage-day-presets">{!readOnly && <div className="usage-presets"><button type="button" onClick={() => updateWindow(ri, wi, { days: WEEKDAYS })}>Weekdays</button><button type="button" onClick={() => updateWindow(ri, wi, { days: WEEKEND })}>Weekend</button><button type="button" onClick={() => updateWindow(ri, wi, { days: EVERY_DAY })}>Every day</button></div>}<div className="usage-days">{DAYS.map((day, di) => <label key={day}><input type="checkbox" disabled={readOnly} checked={w.days.includes(di + 1)} onChange={(e) => updateWindow(ri, wi, { days: e.target.checked ? [...w.days, di + 1].sort() : w.days.filter((d) => d !== di + 1) })} /><span>{day}</span></label>)}</div></div><label className="usage-time">From<input className="input" type="time" disabled={readOnly} value={w.from} onChange={(e) => updateWindow(ri, wi, { from: e.target.value })} /></label><label className="usage-time">To<input className="input" type="time" disabled={readOnly} value={w.to} onChange={(e) => updateWindow(ri, wi, { to: e.target.value })} /></label>{!readOnly && <button className="btn btn-sm btn-ghost usage-window-remove" onClick={() => update(ri, { allowedWindows: r.allowedWindows.filter((_, n) => n !== wi) })}>Remove window</button>}{validation && <div className="usage-window-error">{validation}</div>}</div>; })}
    </article>; })}</div>
    {!readOnly && <div className="usage-actions"><div className="usage-app-add-wrap"><button className="btn" disabled={!choices.length && !picker} onClick={() => setPicker((v) => !v)}>+ Add app limit</button>{picker && <div className="usage-app-menu"><input className="input" type="search" autoFocus placeholder="Search by app name or package…" value={query} onChange={(e) => setQuery(e.target.value)} /><div className="usage-app-results">{choices.map((app) => <button key={app.pkg} type="button" onClick={() => addApp(app)}><strong>{app.name || app.pkg}</strong><span className="mono">{app.pkg}</span></button>)}{!choices.length && <div>No matching installed apps.</div>}</div></div>}</div><span className="usage-save-state">{dirty ? 'Unsaved changes' : 'All changes saved'}</span><button className="btn" disabled={!dirty || busy} onClick={() => setPolicy(clone(saved))}>Discard</button><button className="btn btn-primary" disabled={!dirty || busy} onClick={() => void save()}>{busy ? 'Saving…' : 'Save changes'}</button></div>}
  </>;
}
