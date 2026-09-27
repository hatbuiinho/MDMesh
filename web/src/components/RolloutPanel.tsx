import { useCallback, useEffect, useRef, useState } from 'react';
import {
  listAgentReleases, uploadAgentRelease, getActiveRollout, previewRollout, createRollout,
  promoteRollout, cancelRollout, finishRollout, retryRolloutDevice,
  type AgentRelease, type ActiveRollout, type RolloutCounts, type RolloutDevice, type DeviceRolloutStatus,
} from '../api/rollout';
import { useAuth } from '../auth/AuthContext';
import { deviceDisplayName, deviceSecondaryId, fmtRelative } from '../ui/format';
import { ApkDropzone } from './ApkDropzone';

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
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const [uploadName, setUploadName] = useState<string | null>(null);
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
    setUploadName(file.name); setUploadProgress(0);
    setBusy(true); setErr(null);
    try {
      const r = await uploadAgentRelease(file, setUploadProgress);
      setReleaseId(String(r.id));
      await refresh();
    } catch (e) {
      setUploadName(null);
      setErr((e as Error).message);
    } finally {
      setUploadProgress(null); setBusy(false);
    }
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
  const rows = (rollout?.devices ?? devices).filter((d) => `${d.description ?? ''} ${d.deviceNumber}`.toLowerCase().includes(search.toLowerCase()) && (!filter || d.status === filter));
  const eligible = devices.filter((d) => d.status !== 'ineligible' && d.status !== 'updated').length;

  const updated = devices.filter((d) => d.status === 'updated').length;
  const ineligible = devices.filter((d) => d.status === 'ineligible').length;

  return <section className="panel rollout-panel">
    <div className="panel-head rollout-title">
      <div><h2 className="panel-title">Agent releases & rollout</h2><p>Upload, verify and deploy a signed MDMesh Agent APK.</p></div>
      {rollout && <span className={`rollout-stage ${rollout.stage}`}>{rollout.stage}</span>}
    </div>

    {!rollout && <div className="rollout-section">
      <div className="rollout-step"><span>1</span><div><b>Upload release</b><small>Keep the original signing key and increase versionCode.</small></div></div>
      <ApkDropzone accept=".apk,application/vnd.android.package-archive" extensions={['.apk']}
        maxBytes={128 * 1024 * 1024} disabled={!canEdit || loading} busy={busy && uploadProgress != null}
        progress={uploadProgress} fileName={uploadName} title="Drop the signed Agent APK here"
        hint="or click to browse · APK only · maximum 128 MiB" busyLabel={uploadProgress === 100 ? 'Verifying' : 'Uploading'}
        onFile={(file) => { setErr(null); void upload(file); }} onError={setErr} />
      {!loading && releases.length === 0 && <p className="rollout-note">First upload the APK currently installed on your devices to establish the signing certificate.</p>}
    </div>}

    {err && <div role="alert" className="rollout-alert">{err}</div>}
    {loading && <div className="rollout-loading"><span className="spin" /> Loading releases…</div>}

    {!rollout && releases.length > 0 && <div className="rollout-setup">
      <div className="rollout-section">
        <div className="rollout-step"><span>2</span><div><b>Choose release</b><small>The server verifies package metadata and signing certificate.</small></div></div>
        <select className="sel rollout-release-select" value={releaseId} disabled={!canEdit || busy} onChange={(e) => setReleaseId(e.target.value)}>
          {releases.map((r) => <option key={r.id} value={r.id}>{r.versionName} ({r.versionCode}) · {r.packageName}</option>)}
        </select>
        {release && <div className="release-card">
          <div><span className="release-ok">✓ Verified APK</span><strong>{release.versionName}</strong><small className="mono">{release.packageName} · versionCode {release.versionCode}</small></div>
          <a className="btn btn-sm" href={release.url}>Download</a>
          <details><summary>Technical details</summary><dl><dt>Signing certificate</dt><dd>{release.signatureChecksum}</dd><dt>SHA-256</dt><dd>{release.sha256}</dd></dl></details>
        </div>}
      </div>

      <div className="rollout-section">
        <div className="rollout-step"><span>3</span><div><b>Choose deployment</b><small>Start with a test group before promoting to the fleet.</small></div></div>
        <div className="rollout-mode" role="radiogroup" aria-label="Deployment mode">
          <label className={!allDevices ? 'on' : ''}><input type="radio" checked={!allDevices} onChange={() => setAllDevices(false)} /> <span><b>Test group first</b><small>Recommended · select canary devices below</small></span></label>
          <label className={allDevices ? 'on' : ''}><input type="radio" checked={allDevices} onChange={() => setAllDevices(true)} /> <span><b>All devices</b><small>Deploy immediately across the organization</small></span></label>
        </div>
        <div className="rollout-summary">
          {previewLoading ? <><span className="spin" /> Checking devices…</> : <>
            <span><b>{devices.length}</b> total</span><span><b>{eligible}</b> need update</span>
            <span><b>{updated}</b> updated</span><span><b>{ineligible}</b> ineligible</span>
          </>}
        </div>
        {devices.some((d) => !d.identityVerified) && <p className="rollout-note">Some older agents cannot report their signing identity. Android will still verify the APK during installation; use a canary first.</p>}
      </div>
    </div>}

    {rollout && <div className="rollout-section active-rollout">
      <div className="active-release"><div><small>Active release</small><strong>Agent {rollout.targetVersion}</strong><span className="mono">versionCode {rollout.apkVersionCode}</span></div></div>
      {rollout.progress.canary.total > 0 && <CohortBar label="Canary" counts={rollout.progress.canary} />}
      {rollout.progress.fleet && <CohortBar label="Fleet" counts={rollout.progress.fleet} />}
      <div className="rollout-actions">
        {rollout.stage === 'canary' && <button className="btn btn-primary" disabled={!canEdit || busy || !canPromote} onClick={() => {
          if (window.confirm('Deploy this tested release to all remaining devices in this rollout?')) void act(() => promoteRollout(rollout.id));
        }}>Promote to fleet</button>}
        {rollout.stage === 'fleet' && <button className="btn btn-primary" disabled={!canEdit || busy || !canFinish} onClick={() => void act(() => finishRollout(rollout.id))}>Finish rollout</button>}
        <button className="btn" disabled={!canEdit || busy} onClick={() => {
          if (window.confirm('Stop this rollout? Offline devices will no longer receive it. Installs already delivered may still complete.')) void act(() => cancelRollout(rollout.id));
        }}>Cancel rollout</button>
      </div>
    </div>}

    {(devices.length > 0 || rollout) && <div className="rollout-devices">
      <div className="rollout-device-head"><div><b>{rollout ? 'Rollout devices' : allDevices ? 'Deployment preview' : 'Select canary devices'}</b><small>{rows.length} shown{!rollout && !allDevices ? ` · ${selected.size} selected` : ''}</small></div>
        <div className="rollout-filters">{!rollout && !allDevices && <button className="btn btn-sm" disabled={!canEdit || busy} onClick={() => {
          const candidates = rows.filter((d) => d.status !== 'ineligible' && d.status !== 'updated').map((d) => d.deviceNumber);
          setSelected(selected.size ? new Set() : new Set(candidates));
        }}>{selected.size ? 'Clear selection' : 'Select shown'}</button>}<input className="input" aria-label="Search rollout devices" placeholder="Search device number" value={search} onChange={(e) => setSearch(e.target.value)} />
          <select className="sel" aria-label="Filter rollout status" value={filter} onChange={(e) => setFilter(e.target.value)}><option value="">All statuses</option>{Object.entries(labels).map(([key, value]) => <option key={key} value={key}>{value}</option>)}</select></div>
      </div>
      <div className="rollout-table-wrap"><table className="rollout-table">
        <thead><tr><th>Device</th><th>Installed version</th><th>Status</th><th>Last check-in</th><th /></tr></thead>
        <tbody>{rows.map((d) => <tr key={d.deviceNumber}>
          <td>{!rollout && !allDevices && <input type="checkbox" aria-label={`Select ${deviceDisplayName({ number: d.deviceNumber, description: d.description })}`} checked={selected.has(d.deviceNumber)} disabled={!canEdit || busy || d.status === 'ineligible' || d.status === 'updated'} onChange={() => toggle(d.deviceNumber)} />} <span className="rollout-device-name"><b>{deviceDisplayName({ number: d.deviceNumber, description: d.description })}</b>{deviceSecondaryId({ number: d.deviceNumber, description: d.description }) && <small className="mono">{d.deviceNumber}</small>}</span></td>
          <td>{d.agentVersion ?? 'Unknown'}{d.agentVersionCode != null && ` (${d.agentVersionCode})`}</td>
          <td><span className={`rollout-status ${d.status}`}>{labels[d.status]}</span>{d.cohort && <small> · {d.cohort}</small>}{d.detail && <details><summary>Details</summary><pre>{d.detail}</pre></details>}</td>
          <td>{d.lastSeen ? fmtRelative(d.lastSeen) : 'Never'}</td>
          <td>{rollout && d.status === 'failed' && <button className="btn btn-sm" disabled={!canEdit || busy} onClick={() => void act(() => retryRolloutDevice(rollout.id, d.deviceNumber))}>Retry</button>}</td>
        </tr>)}</tbody>
      </table></div>
      {!rollout && <div className="rollout-start"><span>{allDevices ? `${eligible} eligible devices will receive this release.` : `${selected.size} canary device${selected.size === 1 ? '' : 's'} selected.`}</span>
        <button className="btn btn-primary" disabled={!canEdit || busy || previewLoading || !eligible || (!allDevices && selected.size === 0)} onClick={start}>{busy ? 'Working…' : allDevices ? `Deploy to all ${devices.length}` : `Start canary (${selected.size})`}</button></div>}
    </div>}
  </section>;
}
