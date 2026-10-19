import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { useAuth } from '../auth/AuthContext';
import { useTheme } from '../ui/theme';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import { API_BASE } from '../api/client';
import { fetchAuthOptions } from '../api/auth';
import { RolloutPanel } from '../components/RolloutPanel';
import { orDash } from '../ui/format';

const APP_VERSION = '0.1.0';
const DEFAULT_CONFIG_KEY = 'mdmesh-default-config';

type Conn = 'checking' | 'ok' | 'down';

export function SettingsPage() {
  const navigate = useNavigate();
  const { user, signOut } = useAuth();
  const { theme, setTheme, density, setDensity } = useTheme();
  const [configList, setConfigList] = useState<ConfigurationSummary[]>([]);
  const [conn, setConn] = useState<Conn>('checking');
  const [defaultConfig, setDefaultConfig] = useState<string>(() => {
    try {
      return localStorage.getItem(DEFAULT_CONFIG_KEY) ?? '';
    } catch {
      return '';
    }
  });

  useEffect(() => {
    let cancelled = false;
    fetchAuthOptions()
      .then(() => !cancelled && setConn('ok'))
      .catch(() => !cancelled && setConn('down'));
    listConfigurations()
      .then((list) => {
        if (!cancelled)
          setConfigList([...list].sort((a, b) => a.name.localeCompare(b.name)));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    try {
      if (defaultConfig) localStorage.setItem(DEFAULT_CONFIG_KEY, defaultConfig);
      else localStorage.removeItem(DEFAULT_CONFIG_KEY);
    } catch {
      /* ignore */
    }
  }, [defaultConfig]);

  const connMeta: Record<Conn, { tone: string; label: string }> = {
    checking: { tone: 'idle', label: 'Checking…' },
    ok: { tone: 'ok', label: 'Connected' },
    down: { tone: 'alert', label: 'Unreachable' },
  };
  const cm = connMeta[conn];

  return (
    <AppShell title="Settings">
      <div className="page-head">
        <h1>Settings</h1>
      </div>

      <div className="settings">
        {/* Account & session */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Account</h2>
            <button className="btn btn-sm" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
          <div className="set-row">
            <span className="k">Signed in as</span>
            <span className="v">{orDash(user?.name || user?.login)}</span>
          </div>
          <div className="set-row">
            <span className="k">Login</span>
            <span className="v mono">{orDash(user?.login)}</span>
          </div>
          <div className="set-row">
            <span className="k">Email</span>
            <span className="v mono">{orDash(user?.email)}</span>
          </div>
          <div className="set-row">
            <span className="k">Role</span>
            <span className="v">{user?.superAdmin ? 'Super admin' : 'Admin'}</span>
          </div>
        </section>

        {/* Server & connection */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Server &amp; connection</h2>
          </div>
          <div className="set-row">
            <span className="k">Status</span>
            <span className="v">
              <span className="conn">
                <span className={`dot dot-${cm.tone}`} />
                {cm.label}
              </span>
            </span>
          </div>
          <div className="set-row">
            <span className="k">API base</span>
            <span className="v mono">{API_BASE || '(same origin)'}</span>
          </div>
          <div className="set-row">
            <span className="k">Console version</span>
            <span className="v mono">MDMesh {APP_VERSION}</span>
          </div>
        </section>

        {/* Staged agent-APK rollout (renders itself only when there's an apk to roll out or an active rollout) */}
        <div id="rollout-anchor">
          <RolloutPanel />
        </div>

        {/* Enrollment defaults */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Enrollment defaults</h2>
            <button
              className="btn btn-sm btn-primary"
              onClick={() => navigate('/enroll')}
            >
              Enroll a device →
            </button>
          </div>
          <div className="set-row">
            <span className="k">
              Default configuration
              <small>Pre-selected when you enroll a new device.</small>
            </span>
            <span className="v">
              <select
                className="sel"
                value={defaultConfig}
                onChange={(e) => setDefaultConfig(e.target.value)}
              >
                <option value="">No default</option>
                {configList.map((c) => (
                  <option key={c.id} value={String(c.id)}>
                    {c.name}
                  </option>
                ))}
              </select>
            </span>
          </div>
          {configList.length === 0 && (
            <div className="set-row">
              <span className="k" style={{ fontWeight: 400 }}>
                No configurations are assigned to devices yet.
              </span>
            </div>
          )}
        </section>

        {/* Appearance */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Appearance</h2>
          </div>
          <div className="set-row">
            <span className="k">
              Theme
              <small>Switches with a smooth crossfade.</small>
            </span>
            <span className="v">
              <span className="seg">
                <button
                  className={theme === 'light' ? 'on' : ''}
                  onClick={() => setTheme('light')}
                >
                  Light
                </button>
                <button
                  className={theme === 'dark' ? 'on' : ''}
                  onClick={() => setTheme('dark')}
                >
                  Dark
                </button>
              </span>
            </span>
          </div>
          <div className="set-row">
            <span className="k">
              Density
              <small>Tighten spacing across the console.</small>
            </span>
            <span className="v">
              <span className="seg">
                <button
                  className={density === 'comfortable' ? 'on' : ''}
                  onClick={() => setDensity('comfortable')}
                >
                  Comfortable
                </button>
                <button
                  className={density === 'compact' ? 'on' : ''}
                  onClick={() => setDensity('compact')}
                >
                  Compact
                </button>
              </span>
            </span>
          </div>
        </section>
      </div>
    </AppShell>
  );
}
