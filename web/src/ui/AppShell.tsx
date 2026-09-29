import { useEffect, useRef, useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useTheme } from './theme';
import { UpdateBanner } from '../components/UpdateBanner';
import { ReloadPrompt } from '../components/ReloadPrompt';
import { preloadRoute } from './routePrefetch';
import {
  IconDashboard,
  IconDevices,
  IconConfig,
  IconApps,
  IconEnroll,
  IconSettings,
  IconSignOut,
  IconMenu,
  IconSun,
  IconMoon,
} from './icons';

interface NavEntry {
  to: string;
  label: string;
  Icon: (p: { className?: string }) => ReactNode;
}

const NAV: NavEntry[] = [
  { to: '/dashboard', label: 'Overview', Icon: IconDashboard },
  { to: '/devices', label: 'Devices', Icon: IconDevices },
  { to: '/configs', label: 'Configurations', Icon: IconConfig },
  { to: '/apps', label: 'Apps', Icon: IconApps },
  { to: '/enroll', label: 'Enroll', Icon: IconEnroll },
  { to: '/settings', label: 'Settings', Icon: IconSettings },
];

export function AppShell({
  title,
  children,
}: {
  /** Page label, shown only in the mobile top bar. */
  title?: string;
  children: ReactNode;
}) {
  const { user, signOut } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLButtonElement>(null);
  const sidebarRef = useRef<HTMLElement>(null);
  const close = () => setOpen(false);

  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : menuRef.current;
    const sidebar = sidebarRef.current;
    sidebar?.querySelector<HTMLElement>('a, button')?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        menuRef.current?.focus();
        return;
      }
      if (event.key !== 'Tab' || !sidebar) return;
      const focusable = [...sidebar.querySelectorAll<HTMLElement>('a, button:not(:disabled)')];
      const first = focusable[0];
      const last = focusable.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      if (previous && document.contains(previous)) previous.focus();
    };
  }, [open]);

  return (
    <div className="shell">
      <a className="skip-link" href="#main-content">Skip to main content</a>
      <div
        className={`scrim ${open ? 'show' : ''}`}
        onClick={close}
        aria-hidden="true"
      />
      <aside ref={sidebarRef} id="primary-navigation" className={`sidebar ${open ? 'open' : ''}`} aria-label="Primary navigation">
        <div className="sidebar-brand">
          <span className="wordmark" aria-label="MDMesh">
            <span className="bullet" aria-hidden="true" />
            <span>
              <span className="mdm">MDM</span>
              <span className="esh">esh</span>
            </span>
          </span>
        </div>
        <nav className="nav">
          {NAV.map(({ to, label, Icon }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
              onClick={close}
              onMouseEnter={() => preloadRoute(to)}
              onFocus={() => preloadRoute(to)}
            >
              <Icon className="ico" />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div className="sidebar-user">
            <span className="who">
              {user?.login || user?.name || 'admin@localhost'}
            </span>
          </div>
          <button
            className="btn btn-ghost"
            onClick={toggleTheme}
            aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
          >
            {theme === 'dark' ? <IconSun className="ico" /> : <IconMoon className="ico" />}
            <span style={{ marginLeft: 8 }}>{theme === 'dark' ? 'Light' : 'Dark'}</span>
          </button>
          <button className="btn btn-ghost" onClick={() => void signOut()}>
            <IconSignOut className="ico" />
            <span style={{ marginLeft: 8 }}>Sign out</span>
          </button>
        </div>
      </aside>

      <div className="main">
        <div className="rail-mobilebar">
          <button
            ref={menuRef}
            className="btn btn-ghost menu-btn"
            onClick={() => setOpen((v) => !v)}
            aria-label="Toggle navigation"
            aria-expanded={open}
            aria-controls="primary-navigation"
          >
            <IconMenu />
          </button>
          <span style={{ fontWeight: 600 }}>{title ?? 'MDMesh'}</span>
        </div>
        <main id="main-content" className="content-scroll" tabIndex={-1}>
          <div className="content route-enter"><ReloadPrompt /><UpdateBanner />{children}</div>
        </main>
      </div>
    </div>
  );
}
