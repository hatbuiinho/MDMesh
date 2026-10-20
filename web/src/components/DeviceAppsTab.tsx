import { useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import { getLatestScan, scanApps, type AppInfo } from '../api/deviceApps';
import { forceSync, queueCommand } from '../api/commands';
import { useToast } from '../ui/toast';
import { Modal } from '../ui/Modal';
import { deviceDisplayName } from '../ui/format';
import { Link } from 'react-router-dom';
import { listApplications, saveAndroidApplication, type Application } from '../api/applications';
import { RowActionsMenu } from '../ui/RowActionsMenu';

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
  const [library, setLibrary] = useState<Application[] | null>(null);
  const [libraryError, setLibraryError] = useState<string | null>(null);
  const [checkedPackages, setCheckedPackages] = useState<Set<string>>(new Set());
  const [adding, setAdding] = useState(false);
  const addingRef = useRef(false);

  async function loadLibrary() {
    setLibraryError(null);
    try {
      setLibrary(await listApplications());
    } catch {
      setLibraryError('Could not load the app library. Retry before adding packages.');
    }
  }

  useEffect(() => { void loadLibrary(); }, []);

  useEffect(() => {
    const ac = new AbortController();
    abortRef.current = ac;
    setScanning(false);
    setQueuedPackages(new Set());
    setApps(null);
    setScannedAt(null);
    setCheckedPackages(new Set());
    setSelected(null);
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
    const signal = abortRef.current?.signal;
    setScanning(true);
    setError(null);
    try {
      const fresh = await scanApps(device.number, signal);
      if (signal?.aborted) return;
      setApps(fresh);
      setScannedAt(Date.now());
      setSelected(null);
      setQueuedPackages(new Set());
    } catch (e) {
      if (!signal?.aborted) setError(e instanceof Error ? e.message : 'Scan failed');
    } finally {
      if (!signal?.aborted) setScanning(false);
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

  const libraryPackages = useMemo(() => new Set((library ?? [])
    .filter((app) => (app.type ?? 'app') === 'app').map((app) => app.pkg)), [library]);
  const selectable = useMemo(() => visible.filter((app) => !libraryPackages.has(app.pkg)), [visible, libraryPackages]);
  const checkedApps = useMemo(() => sorted.filter((app) => checkedPackages.has(app.pkg) && !libraryPackages.has(app.pkg)),
    [sorted, checkedPackages, libraryPackages]);
  const allVisibleChecked = selectable.length > 0 && selectable.every((app) => checkedPackages.has(app.pkg));

  async function addToLibrary(candidates: AppInfo[]) {
    if (addingRef.current || !candidates.length) return;
    addingRef.current = true;
    setAdding(true);
    const completed = new Set<string>();
    const failures: string[] = [];
    let added = 0;
    try {
      // Recheck before creating: another admin/tab may have added these packages.
      const current = await listApplications();
      setLibraryError(null);
      const known = new Set(current.filter((app) => (app.type ?? 'app') === 'app').map((app) => app.pkg));
      setLibrary([...current]);
      for (const app of candidates) {
        if (known.has(app.pkg)) {
          completed.add(app.pkg);
          continue;
        }
        try {
          const saved = await saveAndroidApplication({
            name: app.label || app.pkg, pkg: app.pkg, type: 'app', version: '0', versionCode: 0,
          });
          current.push(saved);
          known.add(app.pkg);
          completed.add(app.pkg);
          added++;
          setLibrary([...current]);
        } catch (e) {
          // A concurrent create can fail as a duplicate; never update an existing app.
          const existing = await listApplications(app.pkg).catch(() => []);
          const match = existing.find((a) => a.pkg === app.pkg && (a.type ?? 'app') === 'app');
          if (match) {
            current.push(match);
            known.add(app.pkg);
            completed.add(app.pkg);
            setLibrary([...current]);
          } else {
            failures.push(`${app.label || app.pkg}: ${e instanceof Error ? e.message : 'Save failed'}`);
          }
        }
      }
      if (completed.size) toast.push('ok', added ? 'Added to Library' : 'Already in Library',
        `${added} package(s) added. No APK was downloaded or installed.`);
      if (failures.length) toast.push('err', `${failures.length} package(s) could not be added`, failures.join('\n'));
    } catch (e) {
      setLibraryError('Could not refresh the app library. Retry before adding packages.');
      toast.push('err', 'Add to Library failed', e instanceof Error ? e.message : 'Could not load the library.');
    } finally {
      setCheckedPackages((previous) => new Set([...previous].filter((pkg) => !completed.has(pkg))));
      setAdding(false);
      addingRef.current = false;
    }
  }

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
        <p className="muted">Apps reported by {deviceDisplayName(device)}. Add to Library registers the package name for use in configurations; it does not download or install an APK. Uninstall results appear in Control → Recent commands.</p>
        <div className="kiosk-toolbar">
          <input className="kiosk-search" placeholder="Search apps…" aria-label="Search apps"
            value={query} onChange={(e) => setQuery(e.target.value)} />
          <label className="kiosk-toggle">
            <input type="checkbox" checked={showSystem} onChange={(e) => setShowSystem(e.target.checked)} /> Show system apps
          </label>
          <button className="btn" disabled={scanning || busy || adding} onClick={() => { void rescan(); }}>
            {scanning ? 'Refreshing…' : apps ? 'Refresh apps' : 'Scan installed apps'}
          </button>
        </div>
        {scannedAt && <p className="muted">Scan from {new Date(scannedAt).toLocaleString()}{scanning ? ' · refreshing…' : ''} · {apps?.length ?? 0} apps</p>}
        {error && <p className="err-text">{error}</p>}
        {libraryError && <p className="err-text">{libraryError} <button className="btn btn-sm" disabled={adding} onClick={() => void loadLibrary()}>Retry library</button></p>}
        {apps && <div className="kiosk-toolbar">
          <label className="kiosk-toggle">
            <input type="checkbox" checked={allVisibleChecked} disabled={!library || !!libraryError || adding || !selectable.length}
              onChange={() => setCheckedPackages((previous) => {
                const next = new Set(previous);
                for (const app of selectable) { if (allVisibleChecked) next.delete(app.pkg); else next.add(app.pkg); }
                return next;
              })} /> Select all matching apps ({selectable.length})
          </label>
          <button className="btn btn-primary" disabled={!library || !!libraryError || adding || busy || scanning || !checkedApps.length}
            onClick={() => void addToLibrary(checkedApps)}>{adding ? 'Adding…' : `Add selected to Library (${checkedApps.length})`}</button>
          {!!checkedPackages.size && <button className="btn btn-sm" disabled={adding} onClick={() => setCheckedPackages(new Set())}>Clear selection</button>}
          <Link to="/apps">Open Library</Link>
        </div>}
        {!apps && scanning && <p className="muted">Asking the device for installed apps… You can leave this tab while it completes.</p>}
        {!apps && !scanning && !error && <div className="empty">No app snapshot yet. Scan the device when it is online.</div>}
        <div className="kiosk-applist">
          {visible.slice(0, visibleLimit).map((app) => (
            <div key={app.pkg} className="kiosk-app" title={app.pkg}>
              <input type="checkbox" aria-label={`Select ${app.label || app.pkg}`} checked={checkedPackages.has(app.pkg) && !libraryPackages.has(app.pkg)}
                disabled={!library || !!libraryError || adding || libraryPackages.has(app.pkg)}
                onChange={(event) => {
                  const checked = event.target.checked;
                  setCheckedPackages((previous) => {
                    const next = new Set(previous);
                    if (checked) next.add(app.pkg); else next.delete(app.pkg);
                    return next;
                  });
                }} />
              <span className="kiosk-ico ph">{(app.label || app.pkg).slice(0, 1).toUpperCase()}</span>
              <span className="kiosk-app-meta">
                <span className="kiosk-app-label">{app.label || app.pkg}</span>
                <span className="kiosk-app-pkg">{app.pkg}</span>
              </span>
              <span className="kiosk-badges">
                {app.system && <span className="kiosk-badge sys">system</span>}
                {queuedPackages.has(app.pkg) && <span className="kiosk-badge">Queued</span>}
                {libraryPackages.has(app.pkg) && <Link className="kiosk-badge" to="/apps">In Library</Link>}
              </span>
                <RowActionsMenu label={`Actions for ${app.label || app.pkg}`} actions={[
                  { label: 'Add to Library', disabled: libraryPackages.has(app.pkg) || !library || !!libraryError || adding || busy || scanning,
                    onSelect: () => { void addToLibrary([app]); } },
                  { label: 'Uninstall', danger: true, disabled: busy || adding || scanning || queuedPackages.has(app.pkg),
                    onSelect: () => setSelected(app) },
                ]} />
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
