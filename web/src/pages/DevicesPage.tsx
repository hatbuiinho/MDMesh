import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { DeviceGlyph } from '../ui/DeviceGlyph';
import { useDevices } from '../data/useDevices';
import { isOnline as isOnlineByRecency } from '../ui/status';
import { ONLINE_WINDOW_MS } from '../ui/status';
import { useToast } from '../ui/toast';
import { deviceDisplayName, deviceSecondaryId, fmtRelative, orDash } from '../ui/format';
import {
  bulkSetConfiguration,
  deleteDevicesBulk,
  type DeviceView,
  type ConfigurationLookup,
} from '../api/devices';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import { BulkActionModal } from '../components/BulkActionModal';
import { Modal } from '../ui/Modal';

type StatusFilter = 'all' | 'online' | 'offline';

function configName(
  d: DeviceView,
  configs: Record<string, ConfigurationLookup>,
): string {
  if (d.configurationId == null) return '—';
  return configs[String(d.configurationId)]?.name ?? '—';
}

// Online = checked in recently. statusCode is config-compliance colour (green even for a device
// that was factory-reset and stopped reporting), so it must NOT drive the online/offline dot.
const isOnline = (d: DeviceView, now?: number) => isOnlineByRecency(d.lastUpdate, now);

function IconSearch() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="11" cy="11" r="7" />
      <path d="M21 21l-4-4" />
    </svg>
  );
}
function IconGrid() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="3" width="7" height="7" rx="1.5" />
      <rect x="3" y="14" width="7" height="7" rx="1.5" />
      <rect x="14" y="14" width="7" height="7" rx="1.5" />
    </svg>
  );
}
function IconList() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
      <line x1="4" y1="6" x2="20" y2="6" />
      <line x1="4" y1="12" x2="20" y2="12" />
      <line x1="4" y1="18" x2="20" y2="18" />
    </svg>
  );
}

export function DevicesPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const toast = useToast();
  const pageSize = 50;
  const page = Math.max(1, Number(params.get('page')) || 1);
  const view = params.get('view') === 'grid' ? 'grid' : 'list';
  const status = (['online', 'offline'].includes(params.get('status') ?? '') ? params.get('status') : 'all') as StatusFilter;
  const config = params.get('config') ?? 'all';
  const android = params.get('android') ?? 'all';
  const sort = params.get('sort') ?? 'recent';
  const dupOnly = params.get('duplicates') === '1';
  const urlQuery = params.get('q') ?? '';
  const [q, setQ] = useState(urlQuery);
  const request = useMemo(() => ({
    value: urlQuery,
    pageNum: page,
    pageSize,
    configurationId: config === 'all' ? undefined : Number(config),
    androidVersion: android === 'all' ? undefined : android,
    onlineLaterMillis: status === 'online' ? ONLINE_WINDOW_MS : undefined,
    onlineEarlierMillis: status === 'offline' ? ONLINE_WINDOW_MS : undefined,
    sortBy: sort === 'name' ? 'DESCRIPTION' : sort === 'config' ? 'CONFIGURATION' : sort === 'android' ? 'ANDROID_VERSION' : 'LAST_UPDATE',
    sortDir: sort === 'recent' ? 'DESC' as const : 'ASC' as const,
  }), [urlQuery, page, config, android, status, sort]);
  const { devices, total, configurations, loading, refreshing, error, lastUpdated, reload } = useDevices(request);

  const updateParams = (changes: Record<string, string | null>, resetPage = true) => {
    setParams((current) => {
      const next = new URLSearchParams(current);
      for (const [key, value] of Object.entries(changes)) {
        if (!value || value === 'all' || (key === 'view' && value === 'list')) next.delete(key);
        else next.set(key, value);
      }
      if (resetPage) next.delete('page');
      return next;
    }, { replace: true });
  };
  const clearFilters = () => {
    setQ('');
    const keep: Record<string, string> = {};
    if (view === 'grid') keep.view = 'grid';
    if (sort !== 'recent') keep.sort = sort;
    setParams(keep, { replace: true });
  };

  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [allConfigs, setAllConfigs] = useState<ConfigurationSummary[]>([]);
  const [moveOpen, setMoveOpen] = useState(false);
  const [delOpen, setDelOpen] = useState(false);
  const [actionsOpen, setActionsOpen] = useState(false);
  const [target, setTarget] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setSelected(new Set());
  }, [page, urlQuery, status, config, android, dupOnly, sort]);

  useEffect(() => {
    const pageCount = Math.max(1, Math.ceil(total / pageSize));
    if (!loading && page > pageCount) updateParams({ page: String(pageCount) }, false);
  // updateParams intentionally derives from current URL state.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading, page, total]);

  useEffect(() => {
    listConfigurations()
      .then((l) => setAllConfigs([...l].sort((a, b) => a.name.localeCompare(b.name))))
      .catch(() => undefined);
  }, []);

  // Tick every 30s so online/offline chips and dots decay as devices go quiet.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(t);
  }, []);

  // Debounce the URL itself so back/forward and shared links preserve the query.
  useEffect(() => {
    if (q === urlQuery) return;
    const t = setTimeout(() => updateParams({ q: q.trim() || null }), 300);
    return () => clearTimeout(t);
  // updateParams intentionally derives from the latest search params.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, urlQuery]);

  useEffect(() => setQ(urlQuery), [urlQuery]);

  const onlineCount = useMemo(
    () => devices.filter((d) => isOnline(d, now)).length,
    [devices, now],
  );

  // Group by hardware id: a value shared by >1 row = same physical device enrolled twice.
  const dupCount = useMemo(() => {
    const m = new Map<string, number>();
    for (const d of devices) if (d.hardwareId) m.set(d.hardwareId, (m.get(d.hardwareId) ?? 0) + 1);
    return m;
  }, [devices]);
  const dupOf = (d: DeviceView) => (d.hardwareId ? dupCount.get(d.hardwareId) ?? 0 : 0);
  const dupTotal = useMemo(
    () => devices.filter((d) => (d.hardwareId ? (dupCount.get(d.hardwareId) ?? 0) : 0) > 1).length,
    [devices, dupCount],
  );

  const androidOptions = useMemo(() => {
    const v = new Set<string>();
    for (const d of devices) if (d.androidVersion) v.add(d.androidVersion);
    return [...v].sort();
  }, [devices]);

  const filtered = useMemo(() => {
    return devices.filter((d) => {
      if (dupOnly && (d.hardwareId ? (dupCount.get(d.hardwareId) ?? 0) : 0) <= 1) return false;
      return true;
    });
  }, [devices, dupOnly, dupCount]);

  // Route by number (not id) so the detail page can fetch the device with a narrow search.
  const go = (d: DeviceView) => navigate(`/devices/${encodeURIComponent(d.number)}`);

  const toggle = (id: number) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const clearSel = () => setSelected(new Set());
  const selectAllFiltered = () => setSelected(new Set(filtered.map((d) => d.id)));
  const selectionActive = selected.size > 0;
  const allFilteredSelected =
    filtered.length > 0 && filtered.every((d) => selected.has(d.id));
  const toggleAll = () => (allFilteredSelected ? clearSel() : selectAllFiltered());

  async function applyMove() {
    if (!target) return;
    setBusy(true);
    try {
      await bulkSetConfiguration([...selected], Number(target));
      const name = allConfigs.find((c) => c.id === Number(target))?.name ?? 'configuration';
      toast.push('ok', 'Configuration changed', `${selected.size} device(s) → ${name}.`);
      setMoveOpen(false);
      setTarget('');
      clearSel();
      await reload();
    } catch (e) {
      toast.push('err', 'Change failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function applyDelete() {
    setBusy(true);
    try {
      await deleteDevicesBulk([...selected]);
      toast.push('ok', 'Devices deleted', `${selected.size} device(s) removed.`);
      setDelOpen(false);
      clearSel();
      await reload();
    } catch (e) {
      toast.push('err', 'Delete failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  return (
    <AppShell title="Devices">
      <div className="dv-head">
        <h1>Devices</h1>
        <span className="dv-count">
          {total} total · {onlineCount} online on this page
        </span>
        <div className="dv-spacer" />
        <div className="dv-search">
          <IconSearch />
          <input
            type="search"
            placeholder="Search devices"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
        <div className="toggle" role="group" aria-label="View">
          <button className={view === 'grid' ? 'on' : ''} onClick={() => updateParams({ view: 'grid' }, false)} aria-label="Grid view" title="Grid">
            <IconGrid />
          </button>
          <button className={view === 'list' ? 'on' : ''} onClick={() => updateParams({ view: 'list' }, false)} aria-label="List view" title="List">
            <IconList />
          </button>
        </div>
        <button className="btn btn-dark" onClick={() => navigate('/enroll')}>
          Enroll device
        </button>
      </div>

      <div className="filters">
        <button className={`filter-chip ${status === 'all' ? 'on' : ''}`} onClick={() => updateParams({ status: null })}>
          All {status === 'all' && <b>{total}</b>}
        </button>
        <button className={`filter-chip ${status === 'online' ? 'on' : ''}`} onClick={() => updateParams({ status: 'online' })}>
          Online {status === 'online' && <b>{total}</b>}
        </button>
        <button className={`filter-chip ${status === 'offline' ? 'on' : ''}`} onClick={() => updateParams({ status: 'offline' })}>
          Offline {status === 'offline' && <b>{total}</b>}
        </button>
        {dupTotal > 0 && (
          <button
            className={`filter-chip dup ${dupOnly ? 'on' : ''}`}
            onClick={() => updateParams({ duplicates: dupOnly ? null : '1' })}
            title="Devices that share a hardware id with another row — likely the same physical device enrolled more than once"
          >
            ⚠ Duplicates <b>{dupTotal}</b>
          </button>
        )}
        <span className="filter-div" />
        <select className="sel" value={config} onChange={(e) => updateParams({ config: e.target.value })} aria-label="Filter by configuration">
          <option value="all">Config: All</option>
          {allConfigs.map((c) => (
            <option key={c.id} value={String(c.id)}>{c.name}</option>
          ))}
        </select>
        <select className="sel" value={android} onChange={(e) => updateParams({ android: e.target.value })} aria-label="Filter by Android version">
          <option value="all">Android: All</option>
          {androidOptions.map((a) => (
            <option key={a} value={a}>Android {a}</option>
          ))}
        </select>
        <select className="sel" value={sort} onChange={(e) => updateParams({ sort: e.target.value })} aria-label="Sort devices">
          <option value="recent">Sort: Recently seen</option>
          <option value="name">Sort: Name</option>
          <option value="config">Sort: Configuration</option>
          <option value="android">Sort: Android</option>
        </select>
        {(status !== 'all' || config !== 'all' || android !== 'all' || dupOnly || urlQuery) && (
          <button className="btn btn-sm btn-ghost" onClick={clearFilters}>
            Clear filters
          </button>
        )}
      </div>

      {selected.size > 0 && (
        <div className="bulk-bar">
          <span className="bulk-count">{selected.size} selected</span>
          <button className="btn btn-sm" onClick={() => setActionsOpen(true)}>
            Actions
          </button>
          <button className="btn btn-sm" onClick={() => setMoveOpen(true)}>
            Change configuration
          </button>
          <button className="btn btn-sm btn-danger" onClick={() => setDelOpen(true)}>
            Delete
          </button>
          <div style={{ flex: 1 }} />
          {selected.size < filtered.length && (
            <button className="btn btn-sm btn-ghost" onClick={selectAllFiltered}>
              Select all {filtered.length}
            </button>
          )}
          <button className="btn btn-sm btn-ghost" onClick={clearSel}>
            Clear
          </button>
        </div>
      )}

      {error && (
        <div className="banner banner-alert" role="alert">
          <span>{error}{lastUpdated ? ` Showing data from ${new Date(lastUpdated).toLocaleTimeString()}.` : ''}</span>
          <button className="btn btn-sm" onClick={() => void reload()}>Retry</button>
        </div>
      )}
      {refreshing && <div className="list-refresh" role="status"><span className="spin" /> Updating devices…</div>}

      {loading ? (
        <div className="device-skeletons" aria-label="Loading devices" role="status">
          {Array.from({ length: 6 }, (_, i) => <div className="device-skeleton" key={i}><i /><i /><i /></div>)}
        </div>
      ) : filtered.length === 0 ? (
        <div className="panel">
          <div className="empty">
            <span className="label">No devices</span>
            {devices.length === 0 ? 'No devices are enrolled yet.' : 'No devices match these filters.'}
            <div className="empty-actions">
              {status !== 'all' || config !== 'all' || android !== 'all' || dupOnly || urlQuery ? (
                <button className="btn" onClick={clearFilters}>Clear filters</button>
              ) : (
                <button className="btn btn-dark" onClick={() => navigate('/enroll')}>Enroll device</button>
              )}
              {error && <button className="btn" onClick={() => void reload()}>Retry</button>}
            </div>
          </div>
        </div>
      ) : (
        <>
          <div className="select-all">
            <input
              type="checkbox"
              className="dev-check"
              checked={allFilteredSelected}
              ref={(el) => {
                if (el) el.indeterminate = selectionActive && !allFilteredSelected;
              }}
              onChange={toggleAll}
              aria-label="Select all devices"
            />
            <span onClick={toggleAll} style={{ cursor: 'pointer' }}>
              {allFilteredSelected ? 'Clear selection' : 'Select all'} · {filtered.length} device
              {filtered.length === 1 ? '' : 's'}
            </span>
          </div>
          {view === 'grid' ? (
            <div className="dev-grid">
              {filtered.map((d) => (
                <DeviceCard
                  key={d.id}
                  d={d}
                  now={now}
                  config={configName(d, configurations)}
                  dup={dupOf(d)}
                  selected={selected.has(d.id)}
                  selectionActive={selectionActive}
                  onToggle={() => toggle(d.id)}
                  onOpen={() => go(d)}
                />
              ))}
            </div>
          ) : (
            <div className="dev-list">
              {filtered.map((d) => (
                <DeviceRow
                  key={d.id}
                  d={d}
                  now={now}
                  config={configName(d, configurations)}
                  dup={dupOf(d)}
                  selected={selected.has(d.id)}
                  selectionActive={selectionActive}
                  onToggle={() => toggle(d.id)}
                  onOpen={() => go(d)}
                />
              ))}
            </div>
          )}
          {total > pageSize && (
            <nav className="pager" aria-label="Device pages">
              <button className="btn" disabled={page <= 1 || refreshing} onClick={() => updateParams({ page: String(page - 1) }, false)}>← Previous</button>
              <span>Page {page} of {Math.ceil(total / pageSize)}</span>
              <button className="btn" disabled={page >= Math.ceil(total / pageSize) || refreshing} onClick={() => updateParams({ page: String(page + 1) }, false)}>Next →</button>
            </nav>
          )}
        </>
      )}

      {actionsOpen && (
        <BulkActionModal
          deviceIds={[...selected]}
          onClose={() => setActionsOpen(false)}
          onDone={() => clearSel()}
        />
      )}

      {moveOpen && (
        <Modal onClose={busy ? undefined : () => setMoveOpen(false)} ariaLabel="Change configuration">
            <h3>Change configuration</h3>
            <p className="muted" style={{ marginTop: 2 }}>
              Move {selected.size} device{selected.size === 1 ? '' : 's'} to a configuration.
            </p>
            <label className="field">
              <span>Configuration</span>
              <select className="sel" value={target} onChange={(e) => setTarget(e.target.value)} style={{ width: '100%' }}>
                <option value="">Select a configuration…</option>
                {allConfigs.map((c) => (
                  <option key={c.id} value={String(c.id)}>{c.name}</option>
                ))}
              </select>
            </label>
            <div className="modal-actions">
              <button className="btn" onClick={() => setMoveOpen(false)} disabled={busy}>Cancel</button>
              <button className="btn btn-primary" disabled={busy || !target} onClick={() => void applyMove()}>
                {busy ? 'Moving…' : 'Move'}
              </button>
            </div>
        </Modal>
      )}

      {delOpen && (
        <Modal onClose={busy ? undefined : () => setDelOpen(false)} ariaLabel="Delete devices">
            <h3>Delete devices</h3>
            <p className="muted" style={{ marginTop: 2 }}>
              Permanently remove {selected.size} device{selected.size === 1 ? '' : 's'} from MDMesh?
              The device(s) will re-appear if they check in again.
            </p>
            <div className="modal-actions">
              <button className="btn" onClick={() => setDelOpen(false)} disabled={busy}>Cancel</button>
              <button className="btn btn-danger" disabled={busy} onClick={() => void applyDelete()}>
                {busy ? 'Deleting…' : `Delete ${selected.size}`}
              </button>
            </div>
        </Modal>
      )}
    </AppShell>
  );
}

function SelectBox({ selected, onToggle }: { selected: boolean; onToggle: () => void }) {
  return (
    <input
      type="checkbox"
      className="dev-check"
      checked={selected}
      onClick={(e) => e.stopPropagation()}
      onChange={onToggle}
      aria-label="Select device"
    />
  );
}

function DupBadge({ n }: { n: number }) {
  return (
    <span
      className="dup-badge"
      title={`Shares a hardware id with ${n - 1} other device${n - 1 === 1 ? '' : 's'} — likely the same physical device enrolled more than once`}
    >
      ⚠ {n}×
    </span>
  );
}

function DeviceCard({
  d,
  now,
  config,
  dup,
  selected,
  selectionActive,
  onToggle,
  onOpen,
}: {
  d: DeviceView;
  now: number;
  config: string;
  dup: number;
  selected: boolean;
  selectionActive: boolean;
  onToggle: () => void;
  onOpen: () => void;
}) {
  const online = isOnline(d, now);
  // Once a selection is in progress, clicking a card toggles it instead of opening it.
  const act = selectionActive ? onToggle : onOpen;
  return (
    <div
      className={`dev ${selected ? 'sel' : ''}`}
      role="button"
      tabIndex={0}
      onClick={act}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          act();
        }
      }}
    >
      <div className="h">
        <SelectBox selected={selected} onToggle={onToggle} />
        <span className={`dot ${online ? 'on' : 'off'}`} aria-hidden="true" />
        <span className="sr-only">{online ? 'Online' : 'Offline'}</span>
        <span className="nm">{deviceDisplayName(d)}</span>
        {dup > 1 && <DupBadge n={dup} />}
        <DeviceGlyph className="ico" name={d.description || d.number} size={16} />
      </div>
      {deviceSecondaryId(d) && <div className="sub mono">{deviceSecondaryId(d)}</div>}
      <div className="kv">
        <div>
          <div className="k">Android</div>
          <div className="v">{orDash(d.androidVersion)}</div>
        </div>
        <div>
          <div className="k">Config</div>
          <div className="v">{config}</div>
        </div>
        <div>
          <div className="k">Seen</div>
          <div className="v">{fmtRelative(d.lastUpdate)}</div>
        </div>
      </div>
    </div>
  );
}

function DeviceRow({
  d,
  now,
  config,
  dup,
  selected,
  selectionActive,
  onToggle,
  onOpen,
}: {
  d: DeviceView;
  now: number;
  config: string;
  dup: number;
  selected: boolean;
  selectionActive: boolean;
  onToggle: () => void;
  onOpen: () => void;
}) {
  const online = isOnline(d, now);
  const act = selectionActive ? onToggle : onOpen;
  return (
    <div
      className={`dev-row ${selected ? 'sel' : ''}`}
      role="button"
      tabIndex={0}
      onClick={act}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          act();
        }
      }}
    >
      <div className="id">
        <SelectBox selected={selected} onToggle={onToggle} />
        <span className={`dot ${online ? 'on' : 'off'}`} aria-hidden="true" />
        <span className="sr-only">{online ? 'Online' : 'Offline'}</span>
        <DeviceGlyph className="ico" name={d.description || d.number} size={15} />
        <div style={{ minWidth: 0 }}>
          <div className="nm">{deviceDisplayName(d)}</div>
          {deviceSecondaryId(d) && <div className="sub mono">{deviceSecondaryId(d)}</div>}
        </div>
        {dup > 1 && <DupBadge n={dup} />}
      </div>
      <div className="lc">
        <span className="lk">Android</span>
        <span className="lv">{orDash(d.androidVersion)}</span>
      </div>
      <div className="lc">
        <span className="lk">Config</span>
        <span className="lv">{config}</span>
      </div>
      <div className="lc">
        <span className="lk">Seen</span>
        <span className="lv">{fmtRelative(d.lastUpdate)}</span>
      </div>
    </div>
  );
}
