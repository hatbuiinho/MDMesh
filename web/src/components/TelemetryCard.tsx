import type { TelemetrySnapshot } from '../api/telemetry';

function fieldLabel(value: string): string {
  const spaced = value.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ');
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function fieldValue(value: unknown): string {
  if (value == null || value === '') return '—';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  return Array.isArray(value) ? value.join(', ') : String(value);
}

function Group({ title, data }: { title: string; data?: Record<string, unknown> }) {
  if (!data || Object.keys(data).length === 0) return null;
  return (
    <section className="tele-group">
      <h3 className="action-group-title">{title}</h3>
      <dl className="state-grid">
        {Object.entries(data).map(([k, v]) => (
          <div key={k}>
            <dt>{fieldLabel(k)}</dt>
            <dd>{fieldValue(v)}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export function TelemetryCard({ telemetry }: { telemetry: TelemetrySnapshot | null }) {
  if (!telemetry) {
    return (
      <div className="panel">
        <h2 className="panel-title">Telemetry</h2>
        <p className="muted">No telemetry yet.</p>
      </div>
    );
  }
  return (
    <div className="panel">
      <h2 className="panel-title">Telemetry</h2>
      <Group title="Hardware" data={telemetry.hardware} />
      <Group title="Network & Battery" data={telemetry.dynamic} />
      <Group title="Security" data={telemetry.security} />
      <Group title="Identity" data={telemetry.identity} />
    </div>
  );
}
