import { useMemo, useState } from 'react';
import type { Application } from '../api/applications';
import { AppPicker } from './AppPicker';

export function webFilterPackages(value: unknown): string[] {
  return [...new Set(String(value ?? '').split(/[,\s]+/).map((pkg) => pkg.trim()).filter(Boolean))];
}

/** Keep the server's package-ID format while selecting apps by name from the Library. */
export function WebFilterAppSelector({ apps, value, disabled, onChange }: {
  apps: Application[];
  value: unknown;
  disabled?: boolean;
  onChange: (value: string) => void;
}) {
  const [pickerOpen, setPickerOpen] = useState(false);
  const [addedApps, setAddedApps] = useState<Application[]>([]);
  const selected = webFilterPackages(value);
  const available = useMemo(() => {
    const seen = new Set<string>();
    return [...addedApps, ...apps].filter((app) => {
      const pkg = app.pkg?.trim();
      if (!pkg || (app.type ?? 'app') === 'web' || seen.has(pkg)) return false;
      seen.add(pkg);
      return true;
    });
  }, [addedApps, apps]);
  const byPackage = new Map(available.map((app) => [app.pkg.trim(), app]));
  const excludedIds = new Set(available.filter((app) => selected.includes(app.pkg.trim())).map((app) => app.id));

  const update = (packages: string[]) => onChange(packages.join('\n'));

  return (
    <div className="cfg-web-apps">
      {selected.length === 0 ? <div className="cfg-empty">No apps selected.</div> : selected.map((pkg) => {
        const app = byPackage.get(pkg);
        return (
          <div className="cfg-app" key={pkg}>
            <span className="cfg-app-nm">{app?.name || pkg}</span>
            <span className="cfg-app-pkg mono">{pkg}{!app ? ' · Not in Library' : ''}</span>
            {!disabled && <button type="button" className="btn btn-sm btn-ghost" onClick={() => update(selected.filter((item) => item !== pkg))} aria-label={`Remove ${app?.name || pkg}`}>✕</button>}
          </div>
        );
      })}
      {!disabled && <button type="button" className="btn btn-sm cfg-app-add" onClick={() => setPickerOpen(true)}>Select apps from Library or Play Store</button>}
      {pickerOpen && <AppPicker
        apps={available}
        excludeIds={excludedIds}
        title="Select apps for web filter"
        enablePlaySearch
        onLibraryAppAdded={(app) => setAddedApps((current) => [...current, app])}
        onAdd={(chosen) => update([...selected, ...chosen.map((app) => app.pkg.trim())])}
        onClose={() => setPickerOpen(false)}
      />}
    </div>
  );
}
