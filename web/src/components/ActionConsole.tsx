import { useCallback, useEffect, useState } from 'react';
import {
  ACTION_TEMPLATES, type CommandTemplateExt, queueCommand,
  listCommandHistory, forceSync, type DeviceState, type CommandHistoryItem,
} from '../api/commands';
import { useToast } from '../ui/toast';
import { KioskEnterModal } from './KioskEnterModal';
import { Modal } from '../ui/Modal';

type Device = { number: string };

const GROUPS: Array<{ id: 'safe' | 'disruptive' | 'destructive'; title: string }> = [
  { id: 'safe', title: 'Actions' },
  { id: 'disruptive', title: 'Disruptive' },
  { id: 'destructive', title: 'Destructive' },
];

export function ActionConsole({
  device,
  state,
  capabilities,
  onStateRefresh,
}: {
  device: Device;
  state: DeviceState | null;
  capabilities: Set<string> | null;
  onStateRefresh?: () => void;
}) {
  const toast = useToast();
  const [history, setHistory] = useState<CommandHistoryItem[]>([]);
  const [active, setActive] = useState<CommandTemplateExt | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [confirmText, setConfirmText] = useState('');
  const [busyKeys, setBusyKeys] = useState<Set<string>>(() => new Set());
  const [kioskOpen, setKioskOpen] = useState(false);

  const refresh = useCallback(async (signal?: AbortSignal): Promise<CommandHistoryItem[]> => {
    const hist = await listCommandHistory(device.number, 0, signal).catch(() => []);
    if (signal?.aborted) return [];
    setHistory(hist);
    return hist;
  }, [device.number]);

  useEffect(() => {
    let on = true;
    let t: ReturnType<typeof setTimeout>;
    let request: AbortController | null = null;
    // Light UI poll of server-side state — self-scheduling so slow responses can't overlap.
    const loop = async () => {
      if (document.hidden) {
        request?.abort();
        t = setTimeout(() => void loop(), 30000);
        return;
      }
      request?.abort();
      request = new AbortController();
      const items = await refresh(request.signal);
      if (!on) return;
      const hasActiveCommand = items.some((item) =>
        item.status === 'pending' || item.status === 'delivered' || item.status === 'accepted');
      t = setTimeout(() => void loop(), hasActiveCommand ? 5000 : 30000);
    };
    const resume = () => {
      if (!document.hidden) {
        clearTimeout(t);
        void loop();
      }
    };
    document.addEventListener('visibilitychange', resume);
    void loop();
    return () => {
      on = false;
      clearTimeout(t);
      request?.abort();
      document.removeEventListener('visibilitychange', resume);
    };
  }, [refresh]);

  function start(t: CommandTemplateExt) {
    setActive(t);
    setValues({});
    setConfirmText('');
  }

  async function send(t: CommandTemplateExt, values: Record<string, string>) {
    setBusyKeys((keys) => new Set(keys).add(t.key));
    try {
      const req = t.build ? t.build(values) : t.request;
      const res = await queueCommand(device.number, req);
      const id = (res?.id as number | string | undefined) ?? '';
      toast.push('ok', `${t.label} queued`, id ? `Command ${id}` : '');
      await forceSync(device.number).catch(() => undefined); // nudge (no-op until MQTT lands)
      await refresh();
      onStateRefresh?.();
    } catch (e) {
      toast.push('err', `${t.label} failed`, e instanceof Error ? e.message : '');
    } finally {
      setBusyKeys((keys) => {
        const next = new Set(keys);
        next.delete(t.key);
        return next;
      });
    }
  }

  const needsModal = (t: CommandTemplateExt) =>
    (t.params && t.params.length > 0) || !!t.confirm;

  async function onClick(t: CommandTemplateExt) {
    if (t.key === 'kiosk-enter') { setKioskOpen(true); return; }
    if (needsModal(t)) { start(t); return; }
    await send(t, {});
  }

  async function confirmAndSend() {
    if (!active) return;
    const t = active;
    setActive(null);
    await send(t, values);
  }

  const canSend =
    !active ? false
    : active.confirm === 'type-to-confirm' ? confirmText === 'WIPE'
    : active.params?.some((p) => p.required && !values[p.key]) ? false
    : true;

  return (
    <div className="panel">
      <div className="panel-head">
        <h2 className="panel-title">Device control</h2>
        <button
          className="btn"
          disabled={busyKeys.has('sync')}
          onClick={() => {
            setBusyKeys((keys) => new Set(keys).add('sync'));
            void forceSync(device.number)
              .then(() => { onStateRefresh?.(); return refresh(); })
              .catch(() => undefined)
              .finally(() => setBusyKeys((keys) => {
                const next = new Set(keys);
                next.delete('sync');
                return next;
              }));
          }}
        >
          Sync now
        </button>
      </div>

      <DeviceStatePanel state={state} />

      {GROUPS.map((g) => (
        <section key={g.id} className="action-group">
          <h3 className="action-group-title">{g.title}</h3>
          <div className="action-grid">
            {ACTION_TEMPLATES.filter((t) => (t.group ?? 'safe') === g.id).map((t) => {
              const required = t.request.requiresCapability;
              const unsupported = capabilities != null && !!required && !capabilities.has(required);
              return (
                <button
                  key={t.key}
                  className={`btn ${t.danger ? 'btn-danger' : ''} ${unsupported ? 'is-unsupported' : ''}`}
                  disabled={busyKeys.has(t.key)}
                  aria-disabled={unsupported}
                  title={unsupported ? `Not supported by this device (${required}).` : t.description}
                  aria-describedby={unsupported ? `capability-${t.key}` : undefined}
                  onClick={() => { if (!unsupported) void onClick(t); }}
                >
                  {t.label}
                  {unsupported && <span id={`capability-${t.key}`} className="sr-only">Not supported by this device.</span>}
                </button>
              );
            })}
          </div>
        </section>
      ))}

      <CommandTimeline items={history} />

      {kioskOpen && (
        <KioskEnterModal
          device={device}
          onClose={() => setKioskOpen(false)}
          onQueued={() => { void refresh(); }}
        />
      )}

      {active && (
        <Modal onClose={busyKeys.has(active.key) ? undefined : () => setActive(null)} ariaLabel={active.label}>
            <h3>{active.label}</h3>
            <p className="muted">{active.description}</p>
            {active.params?.map((p) => (
              <label key={p.key} className="field">
                <span>{p.label}</span>
                <input
                  type={p.kind === 'password' ? 'password' : p.kind === 'number' ? 'number' : 'text'}
                  placeholder={p.placeholder}
                  value={values[p.key] ?? ''}
                  onChange={(e) => setValues((v) => ({ ...v, [p.key]: e.target.value }))}
                />
              </label>
            ))}
            {active.confirm === 'type-to-confirm' && (
              <label className="field">
                <span>Type <strong>WIPE</strong> to confirm</span>
                <input value={confirmText} onChange={(e) => setConfirmText(e.target.value)} />
              </label>
            )}
            <div className="modal-actions">
              <button className="btn" disabled={busyKeys.has(active.key)} onClick={() => setActive(null)}>Cancel</button>
              <button
                className={`btn ${active.danger ? 'btn-danger' : 'btn-primary'}`}
                disabled={busyKeys.has(active.key) || !canSend}
                onClick={() => { void confirmAndSend(); }}
              >
                {active.danger ? 'Confirm' : 'Send'}
              </button>
            </div>
        </Modal>
      )}
    </div>
  );
}

function powerLabel(mode?: string | null): string {
  if (mode === 'alwaysOn') return 'Always-on';
  if (mode === 'adaptive') return 'Battery-saver';
  return '—';
}

function DeviceStatePanel({ state }: { state: DeviceState | null }) {
  if (!state) return <p className="muted">No state reported yet.</p>;
  const seen = state.updatedAt ? new Date(state.updatedAt).toLocaleTimeString() : '—';
  return (
    <dl className="state-grid">
      <div><dt>Battery</dt><dd>{state.battery < 0 ? '—' : `${state.battery}%`}{state.charging ? ' ⚡' : ''}</dd></div>
      <div><dt>Screen</dt><dd>{state.locked ? 'Locked' : 'Unlocked'}</dd></div>
      <div><dt>Kiosk</dt><dd>{state.kioskActive ? 'Active' : 'Off'}</dd></div>
      <div><dt>Android</dt><dd>{state.androidRelease || '—'}</dd></div>
      <div><dt>Agent</dt><dd>{state.agentVersion || '—'}</dd></div>
      <div><dt>Connectivity</dt><dd>{powerLabel(state.powerMode)}</dd></div>
      <div><dt>State as of</dt><dd>{seen}</dd></div>
    </dl>
  );
}

function CommandTimeline({ items }: { items: CommandHistoryItem[] }) {
  if (!items.length) return null;
  return (
    <section className="timeline">
      <h3 className="action-group-title">Recent commands</h3>
      <ul>
        {items.map((c) => (
          <li key={String(c.id)} className={`timeline-item status-${c.status}`}>
            <span className="t-type">{c.type}</span>
            <span className={`t-status status-${c.status}`}>{c.status}</span>
            {c.detail && <CommandDetail text={c.detail} />}
          </li>
        ))}
      </ul>
    </section>
  );
}

const DETAIL_LIMIT = 160;

function CommandDetail({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  if (text.length <= DETAIL_LIMIT) return <span className="t-detail">{text}</span>;
  return (
    <span className={`t-detail ${open ? 'open' : 'clamped'}`}>
      {open ? text : `${text.slice(0, DETAIL_LIMIT)}…`}
      <button type="button" className="t-more" onClick={() => setOpen((v) => !v)}>
        {open ? 'show less' : 'show more'}
      </button>
    </span>
  );
}
