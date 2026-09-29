import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { DeviceGlyph } from '../ui/DeviceGlyph';
import {
  getDeviceByNumber, searchDevices, updateDeviceDescription, type DeviceView, type ConfigurationLookup,
} from '../api/devices';
import { ConfigStatusCard } from '../components/ConfigStatusCard';
import { Modal } from '../ui/Modal';
import { getTelemetry, type TelemetrySnapshot } from '../api/telemetry';
import { getConfigStatus, type ConfigStatus } from '../api/configSync';
import {
  getDeviceCapabilities, getDeviceState, forceSync, queueCommand, syncConfigApps, type DeviceState,
} from '../api/commands';
import { ApiError } from '../api/client';
import { isOnline as isOnlineByRecency } from '../ui/status';
import { useToast } from '../ui/toast';
import { deviceDisplayName, deviceSecondaryId, fmtDateTime, fmtRelative, orDash } from '../ui/format';

type Tab = 'control' | 'apps' | 'appUsage' | 'telemetry' | 'events' | 'location' | 'details';

const loadActionConsole = () => import('../components/ActionConsole');
const loadTelemetryCard = () => import('../components/TelemetryCard');
const loadEventTimeline = () => import('../components/EventTimeline');
const loadLocationPanel = () => import('../components/LocationPanel');
const loadDeviceAppsTab = () => import('../components/DeviceAppsTab');
const loadDeviceAppUsageTab = () => import('../components/DeviceAppUsageTab');

const ActionConsole = lazy(() => loadActionConsole().then((m) => ({ default: m.ActionConsole })));
const TelemetryCard = lazy(() => loadTelemetryCard().then((m) => ({ default: m.TelemetryCard })));
const EventTimeline = lazy(() => loadEventTimeline().then((m) => ({ default: m.EventTimeline })));
const LocationPanel = lazy(() => loadLocationPanel().then((m) => ({ default: m.LocationPanel })));
const DeviceAppsTab = lazy(() => loadDeviceAppsTab().then((m) => ({ default: m.DeviceAppsTab })));
const DeviceAppUsageTab = lazy(() => loadDeviceAppUsageTab().then((m) => ({ default: m.DeviceAppUsageTab })));

const TAB_PREFETCH: Partial<Record<Tab, () => Promise<unknown>>> = {
  control: loadActionConsole,
  apps: loadDeviceAppsTab,
  appUsage: loadDeviceAppUsageTab,
  telemetry: loadTelemetryCard,
  events: loadEventTimeline,
  location: loadLocationPanel,
};

const TABS: Array<{ id: Tab; label: string; mobileOnly?: boolean }> = [
  { id: 'control', label: 'Control' },
  { id: 'apps', label: 'Apps' },
  { id: 'appUsage', label: 'App Usage' },
  { id: 'details', label: 'Details', mobileOnly: true },
  { id: 'telemetry', label: 'Telemetry' },
  { id: 'events', label: 'Events' },
  { id: 'location', label: 'Location' },
];

const TAB_IDS = new Set<Tab>(TABS.map((item) => item.id));

function tabFromQuery(value: string | null): Tab {
  return value && TAB_IDS.has(value as Tab) ? value as Tab : 'control';
}

interface Row {
  k: string;
  v: import('react').ReactNode;
  mono?: boolean;
  copy?: string;
}

function powerLabel(mode?: string | null): string {
  if (mode === 'alwaysOn') return 'Always-on';
  if (mode === 'adaptive') return 'Battery-saver';
  return '—';
}

/** Inline editor for the device's friendly name (description). Click to edit,
 *  Enter/Save to persist, Esc/Cancel to revert. An empty value clears the name. */
function NameField({
  device,
  onSaved,
}: {
  device: DeviceView;
  onSaved: (description: string) => void;
}) {
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(device.description ?? '');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!editing) setValue(device.description ?? '');
  }, [device.description, editing]);

  const cancel = () => {
    setValue(device.description ?? '');
    setEditing(false);
  };

  async function save() {
    const next = value.trim();
    if (next === (device.description ?? '')) {
      setEditing(false);
      return;
    }
    setSaving(true);
    try {
      await updateDeviceDescription(device.id, next);
      onSaved(next);
      setEditing(false);
      toast.push('ok', 'Name saved', next || 'Name cleared.');
    } catch (e) {
      toast.push('err', 'Rename failed', e instanceof Error ? e.message : '');
    } finally {
      setSaving(false);
    }
  }

  if (editing) {
    return (
      <div className="dd-name-edit">
        <input
          autoFocus
          value={value}
          maxLength={200}
          placeholder="Device name"
          disabled={saving}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void save();
            else if (e.key === 'Escape') cancel();
          }}
        />
        <button className="pri" disabled={saving} onClick={() => void save()}>
          {saving ? 'Saving…' : 'Save'}
        </button>
        <button className="sec" disabled={saving} onClick={cancel}>
          Cancel
        </button>
      </div>
    );
  }

  return (
    <button
      type="button"
      className="dd-name"
      onClick={() => setEditing(true)}
      title="Rename this device"
      aria-label="Rename device"
    >
      <span className={`mfr ${device.description ? '' : 'muted'}`}>
        {device.description ? 'Rename' : 'Add a name'}
      </span>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M12 20h9" />
        <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" />
      </svg>
    </button>
  );
}

function CopyValue({ value, children }: { value: string; children: import('react').ReactNode }) {
  const toast = useToast();
  return (
    <span className="dd-copy-value">
      <span>{children}</span>
      <button
        type="button"
        className="dd-copy-button"
        aria-label={`Copy ${value}`}
        title="Copy"
        onClick={() => {
          void navigator.clipboard.writeText(value)
            .then(() => toast.push('ok', 'Copied', value))
            .catch(() => toast.push('err', 'Copy failed', 'Clipboard access is unavailable.'));
        }}
      >
        Copy
      </button>
    </span>
  );
}

export function DeviceDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const toast = useToast();
  const [device, setDevice] = useState<DeviceView | null>(null);
  const [configs, setConfigs] = useState<Record<string, ConfigurationLookup>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tele, setTele] = useState<TelemetrySnapshot | null>(null);
  const [ds, setDs] = useState<DeviceState | null>(null);
  const [cfgStatus, setCfgStatus] = useState<ConfigStatus | null>(null);
  const [capabilities, setCapabilities] = useState<Set<string> | null>(null);
  const [tab, setTab] = useState<Tab>(() => tabFromQuery(searchParams.get('tab')));
  const [busy, setBusy] = useState(false);
  const [liveError, setLiveError] = useState<string | null>(null);
  const [lastLiveRefresh, setLastLiveRefresh] = useState<number | null>(null);
  const [refreshingLive, setRefreshingLive] = useState(false);
  const [confirmLock, setConfirmLock] = useState(false);
  const [browserOffline, setBrowserOffline] = useState(() => !navigator.onLine);
  const liveRequestSeq = useRef(0);

  const selectTab = useCallback((next: Tab) => {
    setTab(next);
    setSearchParams((current) => {
      const params = new URLSearchParams(current);
      if (next === 'control') params.delete('tab');
      else params.set('tab', next);
      return params;
    }, { replace: true });
  }, [setSearchParams]);

  useEffect(() => {
    setTab(tabFromQuery(searchParams.get('tab')));
  }, [searchParams]);

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    setError(null);
    try {
      // Current links use the device number and therefore take the lightweight exact endpoint.
      // Keep a one-row search only for backwards-compatible, old database-id links.
      const matches = (d: DeviceView) => d.number === id || String(d.id) === id;
      let found: DeviceView | null = null;
      try {
        found = await getDeviceByNumber(id ?? '', signal);
      } catch (exactError) {
        if (exactError instanceof ApiError && (exactError.httpStatus === 0 || exactError.httpStatus === 401 || exactError.httpStatus === 403)) {
          throw exactError;
        }
        const res = await searchDevices({ value: id, pageSize: 1 }, signal);
        found = (res.devices?.items ?? []).find(matches) ?? null;
        setConfigs(res.configurations ?? {});
      }
      setDevice(found);
      if (!found) setError('Device not found.');
    } catch (err) {
      if (err instanceof ApiError && err.httpStatus === 0)
        setError('Cannot reach the server.');
      else setError('Failed to load device.');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  useEffect(() => {
    if (!device) return;
    const controller = new AbortController();
    void getDeviceCapabilities(device.number, controller.signal)
      .then((tokens) => setCapabilities(new Set(tokens)))
      .catch(() => undefined);
    return () => controller.abort();
  }, [device]);

  useEffect(() => {
    const desktop = window.matchMedia('(min-width: 921px)');
    const leaveMobileDetails = (event: MediaQueryListEvent | MediaQueryList) => {
      if (event.matches) setTab((current) => {
        if (current !== 'details') return current;
        setSearchParams((params) => {
          const next = new URLSearchParams(params);
          next.delete('tab');
          return next;
        }, { replace: true });
        return 'control';
      });
    };
    leaveMobileDetails(desktop);
    desktop.addEventListener('change', leaveMobileDetails);
    return () => desktop.removeEventListener('change', leaveMobileDetails);
  }, [setSearchParams]);

  useEffect(() => {
    const updateNetworkState = () => setBrowserOffline(!navigator.onLine);
    window.addEventListener('online', updateNetworkState);
    window.addEventListener('offline', updateNetworkState);
    return () => {
      window.removeEventListener('online', updateNetworkState);
      window.removeEventListener('offline', updateNetworkState);
    };
  }, []);

  const refreshLive = useCallback(async (signal?: AbortSignal) => {
    if (!device) return;
    const requestSeq = ++liveRequestSeq.current;
    setRefreshingLive(true);
    const results = await Promise.allSettled([
      getTelemetry(device.number, signal),
      getDeviceState(device.number, signal),
      getConfigStatus(device.number, signal),
    ]);
    if (requestSeq !== liveRequestSeq.current) return;
    if (signal?.aborted) {
      setRefreshingLive(false);
      return;
    }
    const [telemetryResult, stateResult, configResult] = results;
    if (telemetryResult.status === 'fulfilled') setTele(telemetryResult.value);
    if (stateResult.status === 'fulfilled') setDs(stateResult.value);
    if (configResult.status === 'fulfilled') setCfgStatus(configResult.value);
    const successCount = results.filter((result) => result.status === 'fulfilled').length;
    if (successCount > 0) setLastLiveRefresh(Date.now());
    setLiveError(successCount === results.length ? null : 'Some live data could not be refreshed. Showing the last available values.');
    setRefreshingLive(false);
  }, [device]);

  useEffect(() => {
    if (!device) return;
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    let request: AbortController | null = null;
    // Self-scheduling poll: the next tick is armed only after the current one finishes, so slow
    // responses can't stack overlapping requests.
    const poll = async () => {
      if (document.hidden) {
        request?.abort();
        t = setTimeout(() => void poll(), 30000);
        return;
      }
      request?.abort();
      request = new AbortController();
      await refreshLive(request.signal);
      if (!on) return;
      const delay = isOnlineByRecency(device.lastUpdate) ? 10000 : 30000;
      t = setTimeout(() => void poll(), delay);
    };
    const resume = () => {
      if (!document.hidden) {
        clearTimeout(t);
        void poll();
      }
    };
    document.addEventListener('visibilitychange', resume);
    void poll();
    return () => {
      on = false;
      clearTimeout(t);
      request?.abort();
      document.removeEventListener('visibilitychange', resume);
    };
  }, [device, refreshLive]);

  const refreshState = useCallback(() => {
    if (!device) return;
    void getDeviceState(device.number).then(setDs).catch(() => undefined);
  }, [device]);

  const configName =
    device?.configurationName ?? (device?.configurationId != null
      ? configs[String(device.configurationId)]?.name ?? '—'
      : '—');

  const hw = (tele?.hardware ?? {}) as Record<string, unknown>;
  const idn = (tele?.identity ?? {}) as Record<string, unknown>;
  const sec = (tele?.security ?? {}) as Record<string, unknown>;
  const dyn = (tele?.dynamic ?? {}) as Record<string, unknown>;
  const teleStr = (v: unknown): string | undefined =>
    v == null ? undefined : Array.isArray(v) ? (v[0] != null ? String(v[0]) : undefined) : String(v);
  const onOff = (v: unknown, fallback: boolean | null | undefined): string =>
    v === true ? 'On' : v === false ? 'Off' : fallback == null ? '—' : fallback ? 'On' : 'Off';

  async function syncNow() {
    if (!device) return;
    setBusy(true);
    try {
      await forceSync(device.number);
      void refreshLive();
      toast.push('ok', 'Sync requested', '');
    } catch (e) {
      toast.push('err', 'Sync failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function lock() {
    if (!device) return;
    setBusy(true);
    try {
      await queueCommand(device.number, {
        type: 'device.lock',
        requiresCapability: 'device.lock',
      });
      await forceSync(device.number).catch(() => undefined);
      toast.push('ok', 'Lock queued', '');
    } catch (e) {
      toast.push('err', 'Lock failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  // Re-queue the device's configuration apps (action=install) — for devices enrolled before the
  // config's app list changed, or enrolled before config-driven install existed.
  async function installConfigApps() {
    if (!device) return;
    setBusy(true);
    try {
      const res = await syncConfigApps(device.number);
      toast.push('ok', 'Config apps queued', `${res.queued} install command${res.queued === 1 ? '' : 's'} queued.`);
    } catch (e) {
      toast.push('err', 'Sync apps failed', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return (
      <AppShell title="Device">
        <div className="panel">
          <div className="empty">
            <span className="spin" /> Loading device…
          </div>
        </div>
      </AppShell>
    );
  }

  if (!device) {
    return (
      <AppShell title="Device">
        <div className="crumb">
          <a href="/devices" onClick={(e) => { e.preventDefault(); navigate('/devices'); }}>
            Devices
          </a>
        </div>
        <div className="banner banner-alert">{error ?? 'Device not found.'}</div>
      </AppShell>
    );
  }

  // Online/offline is recency of last check-in — NOT statusCode (which is config compliance and
  // stays green for a device that was factory-reset and stopped reporting).
  const lastSeen = Math.max(device.lastUpdate ?? 0, ds?.updatedAt ?? 0);
  const online = isOnlineByRecency(lastSeen);
  const statusLabel = online ? 'Online' : 'Offline';
  const batteryTone = ds?.battery == null || ds.battery < 0
    ? 'unknown'
    : ds.battery <= 15 ? 'critical'
    : ds.battery <= 30 ? 'low'
    : 'normal';
  const canLock = capabilities == null || capabilities.has('device.lock');

  const statusRows: Row[] = [
    { k: 'Battery', v: ds ? (ds.battery < 0 ? '—' : `${ds.battery}% · ${ds.charging ? 'charging' : 'not charging'}`) : '—' },
    { k: 'Screen', v: ds ? (ds.locked ? 'Locked' : 'Unlocked') : '—' },
    { k: 'Kiosk', v: ds ? (ds.kioskActive ? 'On' : 'Off') : '—' },
    { k: 'Connectivity', v: powerLabel(ds?.powerMode) },
  ];
  const hardwareRows: Row[] = [
    { k: 'Android', v: orDash(teleStr(hw.osRelease) ?? ds?.androidRelease ?? device.androidVersion) },
    { k: 'Storage', v: orDash(teleStr(hw.storage) ?? teleStr(hw.storageFree)) },
    { k: 'Serial', v: orDash(teleStr(idn.serial) ?? device.serial), mono: true, copy: teleStr(idn.serial) ?? device.serial },
    { k: 'IMEI', v: orDash(teleStr(idn.imei) ?? device.imei), mono: true, copy: teleStr(idn.imei) ?? device.imei },
  ];
  const networkRows: Row[] = [
    { k: 'Type', v: orDash(teleStr(dyn.networkType) ?? teleStr(dyn.network)) },
    { k: 'Local IP', v: orDash(teleStr(dyn.localIp) ?? teleStr(hw.localIp)), mono: true, copy: teleStr(dyn.localIp) ?? teleStr(hw.localIp) },
    { k: 'Public IP', v: orDash(teleStr((tele as Record<string, unknown> | null)?.publicIp) ?? device.publicIp), mono: true, copy: teleStr((tele as Record<string, unknown> | null)?.publicIp) ?? device.publicIp },
  ];
  const managementRows: Row[] = [
    { k: 'Config', v: configName },
    { k: 'Agent', v: orDash(ds?.agentVersion ?? device.launcherVersion) },
    { k: 'MDM mode', v: onOff(sec.isDeviceOwner, device.mdmMode) },
    { k: 'Enrolled', v: fmtDateTime(device.enrollTime) },
  ];

  const loc = dyn.location as
    | { lat?: number; lon?: number; accuracyM?: number; provider?: string; capturedAt?: number }
    | undefined;
  const hasFix = !!loc && typeof loc.lat === 'number' && typeof loc.lon === 'number';
  const locationRows: Row[] = hasFix
    ? [
        {
          k: 'Coordinates',
          v: (
            <a href={`https://www.google.com/maps?q=${loc!.lat},${loc!.lon}`} target="_blank" rel="noopener noreferrer">
              {loc!.lat!.toFixed(5)}, {loc!.lon!.toFixed(5)} ↗
            </a>
          ),
          mono: true,
          copy: `${loc!.lat},${loc!.lon}`,
        },
        { k: 'Accuracy', v: loc!.accuracyM != null ? `±${Math.round(loc!.accuracyM)} m` : '—' },
        { k: 'Source', v: orDash(loc!.provider) },
        { k: 'Fix age', v: loc!.capturedAt ? fmtRelative(loc!.capturedAt) : '—' },
      ]
    : [{ k: 'Location', v: 'No fix reported yet' }];

  const groups: Array<{ title: string; rows: Row[] }> = [
    { title: 'Status', rows: statusRows },
    { title: 'Location', rows: locationRows },
    { title: 'Hardware', rows: hardwareRows },
    { title: 'Network', rows: networkRows },
    { title: 'Management', rows: managementRows },
  ];

  const detailGroups = groups.map((g) => (
    <div key={g.title}>
      <div className="grp">{g.title}</div>
      {g.rows.map((r) => (
        <div className="row" key={r.k}>
          <span className="k">{r.k}</span>
          <span className={`v ${r.mono ? 'mono' : ''}`}>
            {r.copy ? <CopyValue value={r.copy}>{r.v}</CopyValue> : r.v}
          </span>
        </div>
      ))}
    </div>
  ));
  const mobileDetailGroups = groups.map((g, index) => (
    <details className="dd-detail-group" key={g.title} open={index === 0}>
      <summary>{g.title}</summary>
      <div className="dd-detail-group-body">
        {g.rows.map((r) => (
          <div className="row" key={r.k}>
            <span className="k">{r.k}</span>
            <span className={`v ${r.mono ? 'mono' : ''}`}>
              {r.copy ? <CopyValue value={r.copy}>{r.v}</CopyValue> : r.v}
            </span>
          </div>
        ))}
      </div>
    </details>
  ));

  function moveTab(current: Tab, direction: -1 | 1) {
    const visibleTabs = TABS.filter((item) => !item.mobileOnly || window.matchMedia('(max-width: 920px)').matches);
    const currentIndex = visibleTabs.findIndex((item) => item.id === current);
    const next = visibleTabs[(currentIndex + direction + visibleTabs.length) % visibleTabs.length];
    selectTab(next.id);
    requestAnimationFrame(() => document.getElementById(`device-tab-${next.id}`)?.focus());
  }

  function focusEdgeTab(edge: 'first' | 'last') {
    const visibleTabs = TABS.filter((item) => !item.mobileOnly || window.matchMedia('(max-width: 920px)').matches);
    const next = edge === 'first' ? visibleTabs[0] : visibleTabs[visibleTabs.length - 1];
    selectTab(next.id);
    requestAnimationFrame(() => document.getElementById(`device-tab-${next.id}`)?.focus());
  }

  return (
    <AppShell title={deviceDisplayName(device)}>
      <div className="crumb">
        <a href="/devices" onClick={(e) => { e.preventDefault(); navigate('/devices'); }}>
          Devices
        </a>{' '}
        / {deviceDisplayName(device)}
      </div>

      {(browserOffline || liveError) && (
        <div className="dd-live-warning" role="status">
          <span>
            {browserOffline ? 'Connection lost. Showing the last available values.' : liveError}
            {lastLiveRefresh ? ` Last refreshed ${fmtRelative(lastLiveRefresh)}.` : ''}
          </span>
          <button className="btn" disabled={browserOffline || refreshingLive} onClick={() => void refreshLive()}>
            {refreshingLive ? 'Refreshing…' : 'Retry'}
          </button>
        </div>
      )}

      <div className="dd-cols">
        {/* LEFT: the device */}
        <aside className="panel detail-rail">
          <div className="top">
            <span className={`dot ${online ? 'on' : 'off'}`} />
            <span className={`st ${online ? 'on' : 'off'}`}>{statusLabel}</span>
            <span className="ago">· {fmtRelative(lastSeen)}</span>
            <DeviceGlyph className="ico" name={device.description || device.number} size={20} />
          </div>
          <h1>{deviceDisplayName(device)}</h1>
          {deviceSecondaryId(device) && <div className="dd-device-id mono">{deviceSecondaryId(device)}</div>}
          <NameField
            device={device}
            onSaved={(desc) => setDevice((d) => (d ? { ...d, description: desc } : d))}
          />

          <div className="dd-mobile-vitals" aria-label="Device summary">
            <span className={`battery-${batteryTone}`}>
              {ds?.battery != null && ds.battery >= 0
                ? `${ds.battery}%${ds.charging ? ' · charging' : ''}`
                : 'Battery unknown'}
            </span>
            <span>{configName === '—' ? 'No configuration' : configName}</span>
          </div>

          <div className="actions">
            <button className="pri" disabled={busy} onClick={() => void syncNow()} aria-label="Sync device now">
              Sync now
            </button>
            <div className="dd-desktop-actions">
              <button
                className="sec"
                disabled={busy}
                aria-disabled={!canLock}
                title={canLock ? undefined : 'This device does not support remote lock.'}
                onClick={() => { if (canLock) setConfirmLock(true); }}
                aria-label="Lock device"
              >
                Lock
              </button>
              <button className="sec" disabled={busy} onClick={() => void installConfigApps()}>
                Install config apps
              </button>
            </div>
            <details className="dd-mobile-action-menu">
              <summary className="sec">More actions</summary>
              <div className="dd-mobile-action-popover">
                <button
                  className="sec"
                  disabled={busy}
                  aria-disabled={!canLock}
                  title={canLock ? undefined : 'This device does not support remote lock.'}
                  onClick={() => { if (canLock) setConfirmLock(true); }}
                  aria-label="Lock device"
                >
                  Lock device
                </button>
                <button className="sec" disabled={busy} onClick={() => void installConfigApps()}>
                  Install configuration apps
                </button>
              </div>
            </details>
          </div>

          <div className="dd-desktop-details">
            {detailGroups}
            <ConfigStatusCard status={cfgStatus} />
          </div>
        </aside>

        {/* RIGHT: work */}
        <section className="panel detail-main">
          <div className="tabs" role="tablist" aria-label="Device sections">
            {TABS.map((item) => (
              <button
                key={item.id}
                id={`device-tab-${item.id}`}
                type="button"
                role="tab"
                aria-selected={tab === item.id}
                aria-controls="device-tab-panel"
                tabIndex={tab === item.id ? 0 : -1}
                className={`${tab === item.id ? 'on' : ''} ${item.mobileOnly ? 'mobile-only-tab' : ''}`}
                onClick={(event) => {
                  selectTab(item.id);
                  event.currentTarget.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
                }}
                onPointerEnter={() => { void TAB_PREFETCH[item.id]?.(); }}
                onFocus={() => { void TAB_PREFETCH[item.id]?.(); }}
                onKeyDown={(event) => {
                  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
                    event.preventDefault();
                    moveTab(item.id, event.key === 'ArrowLeft' ? -1 : 1);
                  } else if (event.key === 'Home' || event.key === 'End') {
                    event.preventDefault();
                    focusEdgeTab(event.key === 'Home' ? 'first' : 'last');
                  }
                }}
              >
                {item.label}
              </button>
            ))}
          </div>

          <div
            id="device-tab-panel"
            className="tabbody"
            role="tabpanel"
            aria-labelledby={`device-tab-${tab}`}
          >
            <Suspense fallback={<div className="empty"><span className="spin" /> Loading…</div>}>
              {tab === 'control' && (
                <ActionConsole
                  device={device}
                  state={ds}
                  capabilities={capabilities}
                  onStateRefresh={refreshState}
                />
              )}
              {tab === 'apps' && <DeviceAppsTab device={device} />}
              {tab === 'appUsage' && <DeviceAppUsageTab device={device} />}
              {tab === 'details' && (
                <div className="dd-mobile-details detail-rail">
                  {mobileDetailGroups}
                  <details className="dd-detail-group">
                    <summary>Configuration status</summary>
                    <div className="dd-detail-group-body dd-config-status">
                      <ConfigStatusCard status={cfgStatus} />
                    </div>
                  </details>
                </div>
              )}
              {tab === 'telemetry' && <TelemetryCard telemetry={tele} />}
              {tab === 'events' && <EventTimeline device={device} />}
              {tab === 'location' && <LocationPanel device={device} />}
            </Suspense>
          </div>
        </section>
      </div>

      {confirmLock && (
        <Modal onClose={busy ? undefined : () => setConfirmLock(false)} ariaLabel="Lock device">
          <h3>Lock this device?</h3>
          <p className="muted">The screen will lock as soon as the device receives the command.</p>
          <div className="modal-actions">
            <button className="btn" disabled={busy} onClick={() => setConfirmLock(false)}>Cancel</button>
            <button
              className="btn btn-danger"
              disabled={busy}
              onClick={() => {
                setConfirmLock(false);
                void lock();
              }}
            >
              Lock device
            </button>
          </div>
        </Modal>
      )}
    </AppShell>
  );
}
