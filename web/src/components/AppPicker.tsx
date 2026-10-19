import { useEffect, useMemo, useState } from 'react';
import { appCategory, saveAndroidApplication, type AppCategory, type Application } from '../api/applications';
import { getPlayStatus, searchPlay, type PlayApp, type PlayStatus } from '../api/playstore';
import { Modal } from '../ui/Modal';

const CAT_LABEL: Record<AppCategory, string> = {
  uploaded: 'Uploaded',
  system: 'System',
  web: 'Web apps',
};
const CAT_ORDER: AppCategory[] = ['uploaded', 'system', 'web'];

/** A searchable, categorised, multi-select app picker. Defaults to "Uploaded". */
export function AppPicker({
  apps,
  excludeIds,
  onAdd,
  onClose,
  title = 'Add apps',
  enablePlaySearch = false,
  onLibraryAppAdded,
}: {
  apps: Application[];
  excludeIds: Set<number>;
  onAdd: (apps: Application[]) => void;
  onClose: () => void;
  title?: string;
  enablePlaySearch?: boolean;
  onLibraryAppAdded?: (app: Application) => void;
}) {
  const [q, setQ] = useState('');
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [source, setSource] = useState<'library' | 'play'>('library');
  const [playQuery, setPlayQuery] = useState('');
  const [playStatus, setPlayStatus] = useState<PlayStatus | null>(null);
  const [playResults, setPlayResults] = useState<PlayApp[]>([]);
  const [playSearching, setPlaySearching] = useState(false);
  const [playAdding, setPlayAdding] = useState<string | null>(null);
  const [playError, setPlayError] = useState<string | null>(null);

  useEffect(() => {
    if (!enablePlaySearch) return;
    let cancelled = false;
    getPlayStatus()
      .then((status) => { if (!cancelled) setPlayStatus(status); })
      .catch(() => { if (!cancelled) setPlayStatus({ enabled: false, available: false, profile: '', message: 'Could not connect to Play Store.' }); });
    return () => { cancelled = true; };
  }, [enablePlaySearch]);

  useEffect(() => {
    if (source !== 'play' || !playStatus?.enabled || !playStatus.available || playStatus.linked === false || playQuery.trim().length < 2) {
      setPlayResults([]);
      setPlaySearching(false);
      setPlayError(null);
      return;
    }
    setPlayResults([]);
    setPlaySearching(true);
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setPlayError(null);
      searchPlay(playQuery, 30, controller.signal)
        .then(setPlayResults)
        .catch((error) => {
          if (controller.signal.aborted) return;
          setPlayResults([]);
          setPlayError(error instanceof Error ? error.message : 'Play Store search failed.');
        })
        .finally(() => { if (!controller.signal.aborted) setPlaySearching(false); });
    }, 350);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [source, playQuery, playStatus]);

  const available = useMemo(
    () => apps.filter((a) => !excludeIds.has(a.id)),
    [apps, excludeIds],
  );

  const counts = useMemo(() => {
    const c: Record<AppCategory, number> = { uploaded: 0, system: 0, web: 0 };
    for (const a of available) c[appCategory(a)]++;
    return c;
  }, [available]);

  const tabs = CAT_ORDER.filter((c) => counts[c] > 0);
  const [cat, setCat] = useState<AppCategory>(
    counts.uploaded > 0 ? 'uploaded' : (tabs[0] ?? 'uploaded'),
  );

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return available
      .filter((a) => appCategory(a) === cat)
      .filter((a) => !needle || `${a.name} ${a.pkg}`.toLowerCase().includes(needle))
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [available, cat, q]);

  function toggle(id: number) {
    setPicked((p) => {
      const n = new Set(p);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  function add() {
    const chosen = apps.filter((a) => picked.has(a.id));
    if (chosen.length) onAdd(chosen);
    onClose();
  }

  async function addPlayApp(app: PlayApp) {
    setPlayAdding(app.packageName);
    setPlayError(null);
    try {
      const existing = apps.find((item) => item.pkg === app.packageName);
      const saved = existing ?? await saveAndroidApplication({
        name: app.name,
        pkg: app.packageName,
        version: '0',
        versionCode: 0,
        type: 'app',
      });
      if (!existing) onLibraryAppAdded?.(saved);
      onAdd([saved]);
      onClose();
    } catch (error) {
      setPlayError(error instanceof Error ? error.message : 'Could not add this package to App Library.');
    } finally {
      setPlayAdding(null);
    }
  }

  return (
    <Modal className="app-picker" onClose={onClose} ariaLabel={title}>
        <h3>{title}</h3>

        {enablePlaySearch && <div className="seg" role="tablist" aria-label="App source">
          <button type="button" role="tab" aria-selected={source === 'library'} className={source === 'library' ? 'on' : ''} onClick={() => setSource('library')}>App Library</button>
          <button type="button" role="tab" aria-selected={source === 'play'} className={source === 'play' ? 'on' : ''} onClick={() => setSource('play')}>Play Store</button>
        </div>}

        {source === 'library' ? <>
        <div className="picker-top">
          <div className="dv-search" style={{ flex: 1 }}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="11" cy="11" r="7" />
              <path d="M21 21l-4-4" />
            </svg>
            <input type="search" placeholder="Search apps" value={q} onChange={(e) => setQ(e.target.value)} autoFocus />
          </div>
          <span className="seg">
            {tabs.map((c) => (
              <button key={c} className={cat === c ? 'on' : ''} onClick={() => setCat(c)}>
                {CAT_LABEL[c]} <span style={{ opacity: 0.6 }}>{counts[c]}</span>
              </button>
            ))}
          </span>
        </div>

        <div className="picker-list">
          {shown.length === 0 ? (
            <div className="cfg-empty" style={{ padding: 20 }}>No apps here.</div>
          ) : (
            shown.map((a) => {
              const on = picked.has(a.id);
              return (
                <label key={a.id} className={`picker-row ${on ? 'on' : ''}`}>
                  <input type="checkbox" className="dev-check" checked={on} onChange={() => toggle(a.id)} />
                  <span className="picker-ic" aria-hidden="true">{(a.name.trim()[0] ?? '?').toUpperCase()}</span>
                  <span className="picker-meta">
                    <span className="picker-nm">{a.name}</span>
                    <span className="picker-pkg mono">{a.pkg}</span>
                  </span>
                  {a.version && <span className="picker-ver">v{a.version}</span>}
                </label>
              );
            })
          )}
        </div>

        <div className="modal-actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" disabled={picked.size === 0} onClick={add}>
            Add {picked.size || ''}
          </button>
        </div>
        </> : <>
          {!playStatus ? <div className="cfg-empty" role="status">Connecting to Play Store…</div>
            : !playStatus.enabled || !playStatus.available || playStatus.linked === false ? <div className="banner banner-alert">
              {playStatus.message || 'Play Store integration is not ready.'} <a href="/apps">Set up Play Store in Apps</a>.
            </div>
              : <>
                <div className="picker-top">
                  <input className="input" type="search" placeholder="Search name, package ID or Play link" value={playQuery} onChange={(e) => setPlayQuery(e.target.value)} autoFocus />
                </div>
                <p className="note">Choosing a result adds its package to App Library. It does not download or install the app.</p>
                {playError && <div className="banner banner-alert" role="alert">{playError}</div>}
                <div className="picker-list">
                  {playSearching ? <div className="cfg-empty" role="status" style={{ padding: 20 }}>Searching Play Store…</div>
                    : playQuery.trim().length < 2 ? <div className="cfg-empty" style={{ padding: 20 }}>Enter at least two characters to search.</div>
                      : playResults.length === 0 ? <div className="cfg-empty" style={{ padding: 20 }}>No results.</div>
                        : playResults.map((app) => (
                          <div key={app.packageName} className="picker-row">
                            <span className="picker-ic" aria-hidden="true">{(app.name.trim()[0] ?? '?').toUpperCase()}</span>
                            <span className="picker-meta"><span className="picker-nm">{app.name}</span><span className="picker-pkg mono">{app.packageName}</span></span>
                            <button type="button" className="btn btn-sm btn-primary" disabled={playAdding !== null || apps.some((item) => item.pkg === app.packageName && excludeIds.has(item.id))} onClick={() => void addPlayApp(app)}>
                              {playAdding === app.packageName ? 'Adding…' : apps.some((item) => item.pkg === app.packageName && excludeIds.has(item.id)) ? 'Selected' : 'Select'}
                            </button>
                          </div>
                        ))}
                </div>
              </>}
          <div className="modal-actions"><button type="button" className="btn" onClick={onClose}>Close</button></div>
        </>}
    </Modal>
  );
}
