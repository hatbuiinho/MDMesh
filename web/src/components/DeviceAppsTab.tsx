import { useEffect, useMemo, useRef, useState } from 'react';
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

  useEffect(() => {
    const ac = new AbortController();
    abortRef.current = ac;
    setScanning(true);
    void (async () => {
      const saved = await getLatestScan(device.number).catch(() => null);
      if (ac.signal.aborted) return;
      if (saved) {
        setApps(saved.apps);
        setScannedAt(saved.scannedAt ?? null);
      }
      try {
        const fresh = await scanApps(device.number, ac.signal);
        if (ac.signal.aborted) return;
        setApps(fresh);
        setScannedAt(Date.now());
        setSelected(null);
      } catch (e) {
        if (!ac.signal.aborted) setError(e instanceof Error ? e.message : 'Scan failed');
      } finally {
        if (!ac.signal.aborted) setScanning(false);
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

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (apps ?? [])
      .filter((app) => app.pkg && (showSystem || !app.system))
      .filter((app) => !q || app.label.toLowerCase().includes(q) || app.pkg.toLowerCase().includes(q))
      .sort((a, b) => a.label.localeCompare(b.label));
  }, [apps, query, showSystem]);

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
            {scanning ? 'Scanning…' : 'Re-scan'}
          </button>
        </div>
        {scannedAt && <p className="muted">Scan from {new Date(scannedAt).toLocaleString()}{scanning ? ' · refreshing…' : ''} · {apps?.length ?? 0} apps</p>}
        {error && <p className="err-text">{error}</p>}
        {!apps && scanning && <p className="muted">Asking the device for installed apps…</p>}
        <div className="kiosk-applist">
          {visible.map((app) => (
            <div key={app.pkg} className="kiosk-app" title={app.pkg}>
              <span className="kiosk-ico ph">{(app.label || app.pkg).slice(0, 1).toUpperCase()}</span>
              <span className="kiosk-app-meta">
                <span className="kiosk-app-label">{app.label || app.pkg}</span>
                <span className="kiosk-app-pkg">{app.pkg}</span>
              </span>
              <span className="kiosk-badges">
                {app.system && <span className="kiosk-badge sys">system</span>}
                {queuedPackages.has(app.pkg) && <span className="kiosk-badge">Queued</span>}
                <button className="btn btn-sm btn-danger" disabled={busy || scanning || queuedPackages.has(app.pkg)}
                  onClick={() => setSelected(app)}>Uninstall</button>
              </span>
            </div>
          ))}
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
