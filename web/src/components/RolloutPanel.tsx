import { useCallback, useEffect, useRef, useState } from 'react';
import {
  listAgentReleases, uploadAgentRelease, getActiveRollout, previewRollout, createRollout,
  promoteRollout, cancelRollout, finishRollout, retryRolloutDevice,
  type AgentRelease, type ActiveRollout, type RolloutCounts, type RolloutDevice, type DeviceRolloutStatus,
} from '../api/rollout';
import { useAuth } from '../auth/AuthContext';
import { fmtRelative } from '../ui/format';

const labels: Record<DeviceRolloutStatus, string> = {
  updated: 'Updated / newer', pending: 'Installing', waiting: 'Waiting for check-in', offline: 'Waiting for connection',
  busy: 'Waiting for another install', verifying: 'Verifying new version', failed: 'Failed',
  ineligible: 'Not eligible', not_started: 'Awaiting promotion',
};
function CohortBar({ label, counts: c }: { label: string; counts: RolloutCounts }) {
  return <div className="rollout-cohort">
    <div className="rollout-cohort-head"><span>{label}</span><span>{c.updated}/{c.total} updated</span></div>
    <div className="rollout-track"><div className="rollout-fill" style={{ width: `${c.total ? Math.round(c.updated / c.total * 100) : 0}%` }} /></div>
    <div className="rollout-legend">{(Object.keys(labels) as DeviceRolloutStatus[]).filter((s) => s !== 'not_started' && c[s as keyof RolloutCounts] > 0).map((s) =>
      <span key={s}>{c[s as keyof RolloutCounts]} {labels[s].toLowerCase()}</span>)}</div>
  </div>;
}
export function RolloutPanel() {
  const { user } = useAuth();
  const canEdit = !!(user?.superAdmin || user?.userRole?.superAdmin
    || user?.userRole?.permissions?.some((p) => p.name.toLowerCase() === 'edit_devices'));
  const [releases, setReleases] = useState<AgentRelease[]>([]);
  const [releaseId, setReleaseId] = useState('');
  const [rollout, setRollout] = useState<ActiveRollout | null>(null);
  const [devices, setDevices] = useState<RolloutDevice[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [allDevices, setAllDevices] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('');
  const alive = useRef(true);
  const release = releases.find((r) => String(r.id) === releaseId);
  const refresh = useCallback(async () => {
    const [items, active] = await Promise.all([listAgentReleases(), getActiveRollout()]);
    if (!alive.current) return;
    setReleases(items); setRollout(active);
    setReleaseId((id) => id || (items[0] ? String(items[0].id) : ''));
  }, []);
  useEffect(() => {
    alive.current = true;
    void refresh().catch((e: Error) => { if (alive.current) setErr(e.message); }).finally(() => { if (alive.current) setLoading(false); });
    return () => { alive.current = false; };
  }, [refresh]);
  useEffect(() => {
    if (!rollout) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try { const r = await getActiveRollout(); if (!stopped) setRollout(r); }
      catch (e) { if (!stopped) setErr((e as Error).message); }
      if (!stopped) timer = setTimeout(() => void poll(), 10000);
    };
    timer = setTimeout(() => void poll(), 10000);
    return () => { stopped = true; clearTimeout(timer); };
  }, [rollout?.id]);
  useEffect(() => {
    setSelected(new Set()); setDevices([]);
    if (!releaseId || rollout) return;
    let stopped = false;
    setPreviewLoading(true);
    void previewRollout(Number(releaseId)).then((d) => { if (!stopped) setDevices(d); })
      .catch((e: Error) => { if (!stopped) setErr(e.message); })
      .finally(() => { if (!stopped) setPreviewLoading(false); });
    return () => { stopped = true; };
  }, [releaseId, rollout?.id]);

  async function act(fn: () => Promise<unknown>) {
    setBusy(true); setErr(null);
    try { await fn(); await refresh(); }
    catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }
  async function upload(file?: File) {
    if (!file) return;
    await act(async () => {
      const r = await uploadAgentRelease(file); setReleaseId(String(r.id));
    });
  }
  const toggle = (number: string) => setSelected((old) => {
    const next = new Set(old); if (next.has(number)) next.delete(number); else next.add(number); return next;
  });
  const start = () => {
    if (!release) return;
    if (allDevices && !window.confirm(`Deploy agent ${release.versionName} (${release.versionCode}) to all ${devices.length} devices in this organization? Offline devices will update when they reconnect.`)) return;
    void act(() => createRollout({ releaseId: release.id, allDevices, canaryDeviceNumbers: Array.from(selected) }));
  };
  const canary = rollout?.progress.canary;
  const canPromote = !!canary && canary.total > 0 && canary.updated === canary.total;
  const canFinish = rollout?.stage === 'fleet' && rollout.devices.every((d) => d.status === 'updated' || d.status === 'ineligible');
  const rows = (rollout?.devices ?? devices).filter((d) => d.deviceNumber.toLowerCase().includes(search.toLowerCase()) && (!filter || d.status === filter));
  const eligible = devices.filter((d) => d.status !== 'ineligible' && d.status !== 'updated').length;

  return <section className="panel">
    <div className="panel-head"><h2 className="panel-title">Agent releases & rollout</h2></div>
    <p className="muted">Upload a signed MDMesh APK, then deploy it to a test group or all devices. Offline devices remain targeted until you finish or cancel the rollout.</p>
    <label className="set-row">
      <span className="k">Upload agent APK<small>Keep the original keystore and increase versionCode. Maximum 128 MiB.</small></span>
      <input type="file" accept=".apk" disabled={!canEdit || busy || loading} onChange={(e) => { const file = e.target.files?.[0]; e.target.value = ''; void upload(file); }} />
    </label>
    {!loading && releases.length === 0 && <p className="muted">First upload the APK currently installed on your devices to establish the signing certificate, then upload the new version.</p>}
    {err && <p role="alert" className="ub-warn">{err}</p>}
    {loading && <p>Loading releases…</p>}
    {!rollout && releases.length > 0 && <>
      <label className="set-row"><span className="k">Release</span><select value={releaseId} disabled={!canEdit || busy} onChange={(e) => setReleaseId(e.target.value)}>
        {releases.map((r) => <option key={r.id} value={r.id}>{r.versionName} ({r.versionCode}) · {r.packageName}</option>)}
      </select></label>
      {release && <details><summary>Verified APK details</summary><p>Package: {release.packageName}</p><p style={{ overflowWrap: 'anywhere' }}>Signing certificate: {release.signatureChecksum}<br />SHA-256: {release.sha256}</p><a href={release.url}>Download this APK</a></details>}
      <label className="set-row"><span className="k">Deployment</span><select disabled={!canEdit || busy} value={allDevices ? 'all' : 'canary'} onChange={(e) => setAllDevices(e.target.value === 'all')}>
        <option value="canary">Test group first (recommended)</option><option value="all">All devices in this organization</option>
      </select></label>
      <p>{previewLoading ? 'Checking devices…' : `${devices.length} devices · ${eligible} need an update · ${devices.filter((d) => d.status === 'updated').length} already updated · ${devices.filter((d) => d.status === 'ineligible').length} not eligible`}</p>
      {devices.some((d) => !d.identityVerified) && <p className="muted">Older agents do not report their signing certificate or versionCode. Use a test group first; Android still verifies the package signature during installation.</p>}
      <button className="btn btn-sm btn-primary" disabled={!canEdit || busy || previewLoading || !eligible || (!allDevices && selected.size === 0)} onClick={start}>
        {busy ? 'Working…' : allDevices ? `Deploy to all ${devices.length} devices` : `Start canary (${selected.size})`}
      </button>
    </>}
    {rollout && <>
      <p><b>Agent {rollout.targetVersion} ({rollout.apkVersionCode})</b> · {rollout.stage}</p>
      {rollout.progress.canary.total > 0 && <CohortBar label="Canary" counts={rollout.progress.canary} />}
      {rollout.progress.fleet && <CohortBar label="Fleet" counts={rollout.progress.fleet} />}
      <div className="set-row" style={{ justifyContent: 'flex-end', gap: 8 }}>
        {rollout.stage === 'canary' && <button className="btn btn-sm btn-primary" disabled={!canEdit || busy || !canPromote} onClick={() => {
          if (window.confirm('Deploy this tested release to all remaining devices in this rollout?')) void act(() => promoteRollout(rollout.id));
        }}>Promote to fleet</button>}
        {rollout.stage === 'fleet' && <button className="btn btn-sm" disabled={!canEdit || busy || !canFinish} onClick={() => void act(() => finishRollout(rollout.id))}>Finish</button>}
        <button className="btn btn-sm" disabled={!canEdit || busy} onClick={() => {
          if (window.confirm('Stop this rollout? Offline devices will no longer receive it. Installs already delivered may still complete.')) void act(() => cancelRollout(rollout.id));
        }}>Cancel rollout</button>
      </div>
    </>}
    {(devices.length > 0 || rollout) && <>
      <div className="set-row" style={{ gap: 8 }}>
        <input aria-label="Search rollout devices" placeholder="Search device number" value={search} onChange={(e) => setSearch(e.target.value)} />
        <select aria-label="Filter rollout status" value={filter} onChange={(e) => setFilter(e.target.value)}><option value="">All statuses</option>
          {Object.entries(labels).map(([key, value]) => <option key={key} value={key}>{value}</option>)}</select>
      </div>
      <div style={{ overflow: 'auto', maxHeight: 480 }}><table style={{ width: '100%' }}>
        <thead><tr><th>Device</th><th>Installed version</th><th>Status</th><th>Last check-in</th><th>Action</th></tr></thead>
        <tbody>{rows.map((d) => <tr key={d.deviceNumber}>
          <td>{!rollout && !allDevices && <input type="checkbox" aria-label={`Select ${d.deviceNumber}`} checked={selected.has(d.deviceNumber)} disabled={!canEdit || busy || d.status === 'ineligible' || d.status === 'updated'} onChange={() => toggle(d.deviceNumber)} />} {d.deviceNumber}</td>
          <td>{d.agentVersion ?? 'Unknown'}{d.agentVersionCode != null && ` (${d.agentVersionCode})`}</td>
          <td>{labels[d.status]}{d.cohort && ` · ${d.cohort}`}{d.detail && <details><summary>Details</summary><pre style={{ whiteSpace: 'pre-wrap', maxWidth: 400 }}>{d.detail}</pre></details>}</td>
          <td>{d.lastSeen ? fmtRelative(d.lastSeen) : 'Never'}</td>
          <td>{rollout && d.status === 'failed' && <button className="btn btn-sm" disabled={!canEdit || busy} onClick={() => void act(() => retryRolloutDevice(rollout.id, d.deviceNumber))}>Retry</button>}</td>
        </tr>)}</tbody>
      </table></div>
    </>}
  </section>;
}
