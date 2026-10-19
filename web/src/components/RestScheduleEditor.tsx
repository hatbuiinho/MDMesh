import { useMemo, useState } from 'react';
import type { AppRestSchedule } from '../api/appUsage';
import type { Application } from '../api/applications';
import { AppPicker } from './AppPicker';

const DAYS = ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'];
export function RestScheduleEditor({ schedules, onChange, apps, readOnly }: {
  schedules: AppRestSchedule[]; onChange: (value: AppRestSchedule[]) => void;
  apps: Application[]; readOnly: boolean;
}) {
  const [openPicker, setOpenPicker] = useState<{ id: string; field: 'packages' | 'exceptions' } | null>(null);
  const [addedApps, setAddedApps] = useState<Application[]>([]);
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
  const update = (index: number, patch: Partial<AppRestSchedule>) => onChange(schedules.map((s, i) => i === index ? { ...s, ...patch } : s));
  const selectedApps = (s: AppRestSchedule, index: number, field: 'packages' | 'exceptions') =>
    <div className="usage-schedule-apps">
      <div className="usage-windows-head"><strong>{field === 'packages' ? 'Ứng dụng bị hạn chế' : 'Ngoại lệ của riêng lịch này'}</strong>
        {!readOnly && <button type="button" className="btn btn-sm" onClick={() => setOpenPicker({ id: s.id, field })}>Chọn ứng dụng</button>}
      </div>
      {s[field].length === 0 ? <div className="cfg-empty">Chưa chọn ứng dụng.</div> : s[field].map((pkg) =>
        <div className="cfg-app" key={pkg}>
          <span className="cfg-app-nm">{byPackage.get(pkg)?.name || pkg}</span>
          <span className="cfg-app-pkg mono">{pkg}{!byPackage.has(pkg) ? ' · Không còn trong Library' : ''}</span>
          {!readOnly && <button type="button" className="btn btn-sm btn-ghost" aria-label={`Bỏ ${pkg}`} onClick={() => update(index, { [field]: s[field].filter((item) => item !== pkg) })}>✕</button>}
        </div>)}
    </div>;
  return <section className="usage-rules" aria-label="Lịch hạn chế ứng dụng">
    <div><h3>Lịch nghỉ dùng chung</h3><p>Lặp lại theo múi giờ cấu hình. Giờ kết thúc nhỏ hơn giờ bắt đầu nghĩa là kết thúc vào ngày hôm sau. Mọi lịch hạn chế và hạn mức ngày được áp dụng đồng thời.</p><p className="note">Thiết bị cần phiên bản agent hỗ trợ lịch nghỉ. Agent và thành phần hệ thống thiết yếu luôn được bảo vệ.</p></div>
    {schedules.map((s, i) => <article className="usage-rule-card" key={s.id}>
      <header className="usage-rule-head"><label>Tên lịch <input className="input" maxLength={120} disabled={readOnly} value={s.name} onChange={e => update(i, { name: e.target.value })} /></label><label><input type="checkbox" disabled={readOnly} checked={s.enabled} onChange={e => update(i, { enabled: e.target.checked })} /> Bật lịch</label>{!readOnly && <button type="button" className="btn btn-sm" onClick={() => onChange(schedules.filter((_, n) => n !== i))}>Xóa lịch</button>}</header>
      <div className="usage-days">{DAYS.map((day, n) => <label key={day}><input type="checkbox" disabled={readOnly} checked={s.days.includes(n + 1)} onChange={e => update(i, { days: e.target.checked ? [...s.days, n + 1].sort() : s.days.filter(d => d !== n + 1) })} />{day}</label>)}</div>
      <div className="usage-rule-settings"><label>Bắt đầu <input className="input" type="time" disabled={readOnly} value={s.from} onChange={e => update(i, { from: e.target.value })} /></label><label>Kết thúc <input className="input" type="time" disabled={readOnly} value={s.to} onChange={e => update(i, { to: e.target.value })} /></label><label>Báo trước (phút) <input className="input" type="number" min={0} max={60} disabled={readOnly} value={s.warningMinutes} onChange={e => update(i, { warningMinutes: Number(e.target.value) })} /></label></div>
      <label>Phạm vi <select className="sel" disabled={readOnly} value={s.scope} onChange={e => update(i, { scope: e.target.value as AppRestSchedule['scope'] })}><option value="ALL">Tất cả ứng dụng, gồm app mới cài</option><option value="SELECTED">Nhóm ứng dụng đã chọn</option></select></label>
      {s.scope === 'SELECTED' && selectedApps(s, i, 'packages')}{selectedApps(s, i, 'exceptions')}
      <p className="note">Ngoại lệ này không bỏ qua lịch khác hoặc hạn mức phút/ngày. Thiết bị nhắc trước giờ nghỉ và hiển thị lý do chặn.</p>
    </article>)}
    {!readOnly && <button type="button" className="btn" disabled={schedules.length >= 50} onClick={() => onChange([...schedules, { id: crypto.randomUUID(), name: 'Giờ ngủ', enabled: true, scope: 'ALL', packages: [], exceptions: [], days: [1, 2, 3, 4, 5, 6, 7], from: '22:00', to: '06:00', warningMinutes: 5 }])}>+ Thêm lịch nghỉ</button>}
    {openPicker && (() => {
      const index = schedules.findIndex((schedule) => schedule.id === openPicker.id);
      if (index < 0) return null;
      const schedule = schedules[index];
      const selected = schedule[openPicker.field];
      return <AppPicker
        apps={available}
        excludeIds={new Set(available.filter((app) => selected.includes(app.pkg.trim())).map((app) => app.id))}
        title={openPicker.field === 'packages' ? 'Chọn ứng dụng bị hạn chế' : 'Chọn ứng dụng ngoại lệ'}
        enablePlaySearch
        onLibraryAppAdded={(app) => setAddedApps((current) => [...current, app])}
        onAdd={(chosen) => update(index, { [openPicker.field]: [...new Set([...selected, ...chosen.map((app) => app.pkg.trim())])] })}
        onClose={() => setOpenPicker(null)}
      />;
    })()}
  </section>;
}
