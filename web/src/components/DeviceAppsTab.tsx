import { useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import { getLatestScan, scanApps, type AppInfo } from '../api/deviceApps';
import { forceSync, queueCommand } from '../api/commands';
import { useToast } from '../ui/toast';
import { Modal } from '../ui/Modal';
import { deviceDisplayName } from '../ui/format';

export function DeviceAppsTab({ device }: { device: { number: string; description?: string | null } }) {
  const toast = useToast();
  const abortRef = useRef<AbortController | null>(null);
  const [apps, setApps] = useState<AppInfo[] | null>(null);
  const [scannedAt, setScannedAt] = useState<number | null>(null);
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [showSystem, setShowSystem] = useState(false);
  const [selected, setSelected] = useState<AppInfo | null>(null);
  const [busy, setBusy] = useState(false);
  const [queuedPackages, setQueuedPackages] = useState<Set<string>>(new Set());
  const [visibleLimit, setVisibleLimit] = useState(150);

  useEffect(() => {
    const ac = new AbortController();
    abortRef.current = ac;
    setError(null);
    void (async () => {
      const saved = await getLatestScan(device.number).catch(() => null);
      if (ac.signal.aborted) return;
      if (saved) {
        setApps(saved.apps);
        setScannedAt(saved.scannedAt ?? null);
      }
    })();
    return () => ac.abort();
  }, [device.number]);

  async function rescan() {
    setScanning(true);
    setError(null);
    try {
      const fresh = await scanApps(device.number, abortRef.current?.signal);
      if (abortRef.current?.signal.aborted) return;
      setApps(fresh);
      setScannedAt(Date.now());
      setSelected(null);
      setQueuedPackages(new Set());
    } catch (e) {
      if (!abortRef.current?.signal.aborted) setError(e instanceof Error ? e.message : 'Scan failed');
    } finally {
      if (!abortRef.current?.signal.aborted) setScanning(false);
    }
  }

  const deferredQuery = useDeferredValue(query.trim().toLowerCase());
  const sorted = useMemo(() => [...(apps ?? [])].filter((app) => app.pkg)
    .sort((a, b) => (a.label || a.pkg).localeCompare(b.label || b.pkg)), [apps]);
  const visible = useMemo(() => sorted
    .filter((app) => showSystem || !app.system)
    .filter((app) => !deferredQuery || app.label?.toLowerCase().includes(deferredQuery) || app.pkg.toLowerCase().includes(deferredQuery)),
  [sorted, deferredQuery, showSystem]);
  useEffect(() => setVisibleLimit(150), [deferredQuery, showSystem, apps]);

  async function uninstall() {
    if (!selected) return;
    setBusy(true);
    try {
      const queued = await queueCommand(device.number, {
        type: 'app.uninstall', requiresCapability: 'app.silentInstall',
        payload: JSON.stringify({ packageName: selected.pkg }),
      });
      toast.push('ok', 'Uninstall queued', `${selected.label} (${selected.pkg}) · command ${queued.id ?? 'queued'}`);
      setQueuedPackages((prev) => new Set(prev).add(selected.pkg));
      await forceSync(device.number).catch(() => undefined);
      setSelected(null);
    } catch (e) {
      toast.push('err', 'Uninstall failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="device-apps">
        <h2>Installed apps</h2>
        <p className="muted">Apps reported by {deviceDisplayName(device)}. Uninstall results appear in Control → Recent commands.</p>
        <div className="kiosk-toolbar">
          <input className="kiosk-search" placeholder="Search apps…" aria-label="Search apps"
            value={query} onChange={(e) => setQuery(e.target.value)} />
          <label className="kiosk-toggle">
            <input type="checkbox" checked={showSystem} onChange={(e) => setShowSystem(e.target.checked)} /> Show system apps
          </label>
          <button className="btn" disabled={scanning || busy} onClick={() => { void rescan(); }}>
            {scanning ? 'Refreshing…' : apps ? 'Refresh apps' : 'Scan installed apps'}
          </button>
        </div>
        {scannedAt && <p className="muted">Scan from {new Date(scannedAt).toLocaleString()}{scanning ? ' · refreshing…' : ''} · {apps?.length ?? 0} apps</p>}
        {error && <p className="err-text">{error}</p>}
        {!apps && scanning && <p className="muted">Asking the device for installed apps… You can leave this tab while it completes.</p>}
        {!apps && !scanning && !error && <div className="empty">No app snapshot yet. Scan the device when it is online.</div>}
        <div className="kiosk-applist">
          {visible.slice(0, visibleLimit).map((app) => (
            <div key={app.pkg} className="kiosk-app" title={app.pkg}>
              <span className="kiosk-ico ph">{(app.label || app.pkg).slice(0, 1).toUpperCase()}</span>
              <span className="kiosk-app-meta">
                <span className="kiosk-app-label">{app.label || app.pkg}</span>
                <span className="kiosk-app-pkg">{app.pkg}</span>
              </span>
              <span className="kiosk-badges">
                {app.system && <span className="kiosk-badge sys">system</span>}
                {queuedPackages.has(app.pkg) && <span className="kiosk-badge">Queued</span>}
                <button className="btn btn-sm btn-danger" disabled={busy || queuedPackages.has(app.pkg)}
                  onClick={() => setSelected(app)}>Uninstall</button>
              </span>
            </div>
          ))}
          {visible.length > visibleLimit && <button className="device-apps-more" onClick={() => setVisibleLimit((n) => n + 150)}>
            Show {Math.min(150, visible.length - visibleLimit)} more apps
          </button>}
          {apps && !visible.length && <p className="muted" style={{ padding: 10 }}>No apps match.</p>}
        </div>
        {selected && <Modal onClose={busy ? undefined : () => setSelected(null)} ariaLabel="Confirm uninstall">
            <h3>Uninstall app</h3>
            <p>Remove <strong>{selected.label || selected.pkg}</strong> ({selected.pkg}) from {deviceDisplayName(device)}?</p>
            <div className="modal-actions">
              <button className="btn" disabled={busy} onClick={() => setSelected(null)}>Cancel</button>
              <button className="btn btn-danger" disabled={busy || scanning} onClick={() => { void uninstall(); }}>
                {busy ? 'Sending…' : 'Confirm uninstall'}
              </button>
            </div>
        </Modal>}
    </div>
  );
}
