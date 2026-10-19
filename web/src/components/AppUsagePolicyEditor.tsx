import { AppPicker } from './AppPicker';
import { RestScheduleEditor } from './RestScheduleEditor';
import { useEffect, useMemo, useState } from 'react';
import { getAppUsagePolicy, saveAppUsagePolicy, type AppUsagePolicy, type AppUsageRule, type AppUsageWindow } from '../api/appUsage';
import type { ConfigApp } from '../api/configurations';
import type { Application } from '../api/applications';
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

export function AppUsagePolicyEditor({ configurationId, apps, libraryApps, readOnly }: { configurationId?: number; apps: ConfigApp[]; libraryApps: Application[]; readOnly: boolean }) {
  const toast = useToast();
  const initial = useMemo<AppUsagePolicy>(() => ({ timezone: browserZone(), rules: [], schedules: [] }), []);
  const [policy, setPolicy] = useState(initial), [saved, setSaved] = useState(initial);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [reload, setReload] = useState(0);
  const [picker, setPicker] = useState(false);
  const [addedApps, setAddedApps] = useState<Application[]>([]);
  const available = useMemo(() => {
    const seen = new Set<string>();
    return [...addedApps, ...libraryApps].filter((app) => {
      const pkg = app.pkg?.trim();
      if (!pkg || (app.type ?? 'app') === 'web' || seen.has(pkg)) return false;
      seen.add(pkg);
      return true;
    });
  }, [addedApps, libraryApps]);
  const byPackage = useMemo(() => new Map(available.map((app) => [app.pkg.trim(), app])), [available]);
  const excludedIds = useMemo(() => {
    const selected = new Set(policy.rules.map((rule) => rule.packageName));
    return new Set(available.filter((app) => selected.has(app.pkg.trim())).map((app) => app.id));
  }, [available, policy.rules]);
  // Hundreds of Intl.DateTimeFormat constructions were running on every schedule keystroke.
  const timezoneOptions = useMemo(() => zones(policy.timezone).map((zone) => ({ zone, label: zoneLabel(zone) })), [policy.timezone]);

  useEffect(() => {
    if (!configurationId) return;
    let cancelled = false; setLoading(true); setError('');
    void getAppUsagePolicy(configurationId).then((p) => { if (!cancelled) { const value = { ...p, rules: p.rules ?? [], schedules: p.schedules ?? [] }; setPolicy(clone(value)); setSaved(clone(value)); } }).catch((e: unknown) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Could not load policy.'); }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [configurationId, reload]);

  const dirty = JSON.stringify(policy) !== JSON.stringify(saved);
  useEffect(() => { if (!dirty) return; const warn = (e: BeforeUnloadEvent) => e.preventDefault(); window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn); }, [dirty]);
  if (!configurationId) return <p className="note">Save the configuration first, then add app usage rules.</p>;

  const installed = apps.filter((a): a is ConfigApp & { pkg: string } => (a.action ?? 1) === 1 && Boolean(a.pkg));
  const update = (i: number, patch: Partial<AppUsageRule>) => setPolicy((p) => ({ ...p, rules: p.rules.map((r, n) => n === i ? { ...r, ...patch } : r) }));
  const updateWindow = (ri: number, wi: number, patch: Partial<AppUsageWindow>) => update(ri, { allowedWindows: policy.rules[ri].allowedWindows.map((w, n) => n === wi ? { ...w, ...patch } : w) });
  const addApps = (chosen: Application[]) => setPolicy((p) => {
    const selected = new Set(p.rules.map((rule) => rule.packageName));
    const rules = [...p.rules];
    for (const app of chosen) {
      const packageName = app.pkg.trim();
      if (!packageName || selected.has(packageName)) continue;
      selected.add(packageName);
      rules.push({ packageName, dailyLimitMinutes: 60, warningMinutes: 5, action: 'suspend', enabled: true, allowedWindows: [] });
    }
    return { ...p, rules };
  });
  const save = async () => {
    if (policy.schedules?.some(s => !s.name.trim() || windowError(s) || (s.scope === 'SELECTED' && !s.packages.length) || !Number.isInteger(s.warningMinutes) || s.warningMinutes < 0 || s.warningMinutes > 60)) return toast.push('err', 'Lịch nghỉ chưa hợp lệ', 'Nhập tên, ngày, khung giờ khác nhau, nhóm app và thời gian báo trước 0–60 phút.');
    if (policy.rules.some((r) => r.allowedWindows.some(windowError))) return toast.push('err', 'Invalid time window', 'Fix the highlighted time windows before saving.');
    const selected = policy.rules.map((r) => r.packageName);
    if (new Set(selected).size !== selected.length) return toast.push('err', 'Invalid app', 'Each app may only have one rule.');
    setBusy(true); try { const value = await saveAppUsagePolicy(configurationId, policy); const normalized = value ?? policy; setPolicy(clone(normalized)); setSaved(clone(normalized)); toast.push('ok', 'App usage saved', 'Devices will apply it at their next check-in.'); } catch (e) { toast.push('err', 'Save failed', e instanceof Error ? e.message : ''); } finally { setBusy(false); }
  };

  if (loading) return <div className="usage-loading" role="status">Loading app usage policy…</div>;
  if (error) return <div className="banner banner-alert usage-load-error" role="alert"><span><b>Could not load app usage policy.</b><br />{error}</span><button className="btn btn-sm" onClick={() => setReload((v) => v + 1)}>Retry</button></div>;
  return <>
    <div className="usage-timezone"><div><label htmlFor={`usage-timezone-${configurationId}`}>Policy timezone</label><span>All schedules in this configuration use this timezone.</span></div><div><select id={`usage-timezone-${configurationId}`} className="sel" disabled={readOnly} value={policy.timezone} onChange={(e) => setPolicy({ ...policy, timezone: e.target.value })}>{timezoneOptions.map(({ zone, label }) => <option key={zone} value={zone}>{label}</option>)}</select><small>Current policy time: {zoneTime(policy.timezone)}</small></div></div>
    <RestScheduleEditor schedules={policy.schedules ?? []} onChange={schedules => setPolicy(p => ({ ...p, schedules }))} apps={libraryApps} readOnly={readOnly} />
    {!policy.rules.length && <div className="cfg-empty usage-empty">No app limits yet. Select an app from Library or Play Store to create a daily limit.</div>}
    <div className="usage-rules">{policy.rules.map((r, ri) => { const app = byPackage.get(r.packageName) ?? installed.find((a) => a.pkg === r.packageName); return <article className={`usage-rule-card${r.enabled ? '' : ' is-disabled'}`} key={r.id ?? r.packageName}>
      <header className="usage-rule-head"><div><strong>{app?.name || r.packageName}</strong><span className="mono">{r.packageName}{!app ? ' · Not in Library' : ''}</span></div><label className="kiosk-toggle"><input type="checkbox" disabled={readOnly} checked={r.enabled} onChange={(e) => update(ri, { enabled: e.target.checked })} /> Enabled</label>{!readOnly && <button className="btn btn-sm btn-ghost usage-danger" onClick={() => setPolicy({ ...policy, rules: policy.rules.filter((_, n) => n !== ri) })}>Delete rule</button>}</header>
      <div className="usage-rule-settings"><label>Daily limit <span><input className="input" type="number" min={1} max={1440} disabled={readOnly} value={r.dailyLimitMinutes ?? ''} onChange={(e) => update(ri, { dailyLimitMinutes: e.target.value ? Number(e.target.value) : undefined })} /> minutes</span></label><label>Warn before <span><input className="input" type="number" min={0} max={120} disabled={readOnly} value={r.warningMinutes} onChange={(e) => update(ri, { warningMinutes: Number(e.target.value) })} /> minutes</span></label></div>
      <div className="usage-windows-head"><div><strong>Allowed time windows</strong><span>{r.allowedWindows.length ? 'The app is available only during these windows.' : 'No window means the app may be used at any time, subject to its daily limit.'}</span></div>{!readOnly && <button className="btn btn-sm" onClick={() => update(ri, { allowedWindows: [...r.allowedWindows, { days: WEEKDAYS, from: '08:00', to: '17:00' }] })}>+ Add time window</button>}</div>
      {r.allowedWindows.map((w, wi) => { const validation = windowError(w); return <div className={`usage-window${validation ? ' has-error' : ''}`} key={wi}><div className="usage-day-presets">{!readOnly && <div className="usage-presets"><button type="button" onClick={() => updateWindow(ri, wi, { days: WEEKDAYS })}>Weekdays</button><button type="button" onClick={() => updateWindow(ri, wi, { days: WEEKEND })}>Weekend</button><button type="button" onClick={() => updateWindow(ri, wi, { days: EVERY_DAY })}>Every day</button></div>}<div className="usage-days">{DAYS.map((day, di) => <label key={day}><input type="checkbox" disabled={readOnly} checked={w.days.includes(di + 1)} onChange={(e) => updateWindow(ri, wi, { days: e.target.checked ? [...w.days, di + 1].sort() : w.days.filter((d) => d !== di + 1) })} /><span>{day}</span></label>)}</div></div><label className="usage-time">From<input className="input" type="time" disabled={readOnly} value={w.from} onChange={(e) => updateWindow(ri, wi, { from: e.target.value })} /></label><label className="usage-time">To<input className="input" type="time" disabled={readOnly} value={w.to} onChange={(e) => updateWindow(ri, wi, { to: e.target.value })} /></label>{!readOnly && <button className="btn btn-sm btn-ghost usage-window-remove" onClick={() => update(ri, { allowedWindows: r.allowedWindows.filter((_, n) => n !== wi) })}>Remove window</button>}{validation && <div className="usage-window-error">{validation}</div>}</div>; })}
    </article>; })}</div>
    {!readOnly && <div className="usage-actions"><div className="usage-app-add-wrap"><button type="button" className="btn" onClick={() => setPicker(true)}>+ Add app limit</button></div><span className="usage-save-state">{dirty ? 'Unsaved changes' : 'All changes saved'}</span><button className="btn" disabled={!dirty || busy} onClick={() => setPolicy(clone(saved))}>Discard</button><button className="btn btn-primary" disabled={!dirty || busy} onClick={() => void save()}>{busy ? 'Saving…' : 'Save changes'}</button></div>}
    {picker && !readOnly && <AppPicker
      apps={available}
      excludeIds={excludedIds}
      title="Select apps for daily limits"
      enablePlaySearch
      onLibraryAppAdded={(app) => setAddedApps((current) => [...current, app])}
      onAdd={addApps}
      onClose={() => setPicker(false)}
    />}
  </>;
}
