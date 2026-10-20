import { useEffect, useMemo, useState } from 'react';
import type { Application } from '../api/applications';
import { getNetworkRules, saveNetworkRules, type NetworkRule } from '../api/networkAccess';
import { AppPicker } from './AppPicker';
import { useToast } from '../ui/toast';
export function NetworkPolicyEditor({ configurationId, apps, readOnly }: { configurationId?: number; apps: Application[]; readOnly: boolean }) {
  const { push } = useToast();
  const [rules, setRules] = useState<NetworkRule[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [picker, setPicker] = useState<string | null>(null);
  const [added, setAdded] = useState<Application[]>([]);
  const available = useMemo(() => {
    const byPackage = new Map<string, Application>();
    for (const app of [...apps, ...added]) if (app.pkg && app.type !== 'web') byPackage.set(app.pkg.trim(), app);
    return [...byPackage.values()];
  }, [apps, added]);
  const names = useMemo(() => new Map(available.map(a => [a.pkg, a.name])), [available]);
  useEffect(() => {
    let live = true; setLoaded(false); setRules([]); setDirty(false);
    if (!configurationId) return;
    getNetworkRules(configurationId).then(v => { if (live) { setRules(v); setLoaded(true); } }).catch(e => { if (live) push('err', 'Không tải được chính sách mạng', String(e)); });
    return () => { live = false; };
  }, [configurationId, push]);
  const update = (id: string, patch: Partial<NetworkRule>) => { setRules(current => current.map(r => r.id === id ? { ...r, ...patch } : r)); setDirty(true); };
  const selected = rules.find(r => r.id === picker);
  return <section className="usage-rules network-policy" aria-label="Chính sách truy cập mạng">
    <h3>Truy cập mạng theo ứng dụng / nhóm</h3>
    <p>Mỗi quy tắc là một nhóm ứng dụng có tên. Các quy tắc áp dụng đồng thời; chặn được ưu tiên. Ngoại lệ được duyệt chỉ mở đúng hostname và thiết bị. Quyền sử dụng ứng dụng vẫn quản lý trong App Usage.</p>
    <p className="note">Cần Agent hỗ trợ device.networkAccess. Khi không xác định được ứng dụng, Agent áp dụng các quy tắc chặn có thể liên quan và không sử dụng ngoại lệ riêng của app. DNS riêng và kết nối trực tiếp bằng IP có thể vượt bộ lọc DNS.</p>
    {!configurationId ? <p>Lưu cấu hình trước khi thêm chính sách mạng.</p> : !loaded ? <p>Chưa tải được chính sách. Chưa thể chỉnh sửa.</p> : <>
      {rules.map(r => <article className="usage-rule-card network-card" key={r.id}>
        <label>Tên chính sách / nhóm <input className="input" disabled={readOnly || saving} value={r.name} maxLength={120} onChange={e => update(r.id, { name: e.target.value })} /></label>
        <label>Chế độ <select className="sel" disabled={readOnly || saving} value={r.mode} onChange={e => update(r.id, { mode: e.target.value as NetworkRule['mode'] })}><option value="BLOCKLIST">Chặn domain được liệt kê</option><option value="ALLOWLIST">Chỉ cho phép domain được liệt kê</option></select></label>
        <label>Phạm vi <select className="sel" disabled={readOnly || saving} value={r.scope} onChange={e => update(r.id, { scope: e.target.value as NetworkRule['scope'] })}><option value="SELECTED">Ứng dụng / nhóm đã chọn</option><option value="ALL">Tất cả ứng dụng được quản lý</option></select></label>
        {r.scope === 'SELECTED' && <div>{r.packages.map(pkg => <div className="cfg-app" key={pkg}><span>{names.get(pkg) || `${pkg} · Không còn trong Library`}</span>{!readOnly && <button type="button" className="btn btn-sm" disabled={saving} onClick={() => update(r.id, { packages: r.packages.filter(p => p !== pkg) })}>Bỏ</button>}</div>)}{!readOnly && <button type="button" className="btn" disabled={saving} onClick={() => setPicker(r.id)}>Chọn ứng dụng từ Library hoặc Play Store</button>}</div>}
        <label>Domain, mỗi dòng một hostname<textarea className="input" rows={5} disabled={readOnly || saving} value={r.domains.join('\n')} onChange={e => update(r.id, { domains: e.target.value.split('\n') })} /></label>
        {!readOnly && <button type="button" className="btn btn-sm" disabled={saving} onClick={() => { setRules(current => current.filter(v => v.id !== r.id)); setDirty(true); }}>Xóa quy tắc</button>}
      </article>)}
      {!readOnly && <><button type="button" className="btn" disabled={saving || rules.length >= 50} onClick={() => { setRules(current => [...current, { id: crypto.randomUUID(), name: 'Chính sách mạng', mode: 'BLOCKLIST', scope: 'SELECTED', packages: [], domains: [] }]); setDirty(true); }}>+ Thêm chính sách mạng</button>{' '}<button type="button" className="btn btn-primary" disabled={saving || !dirty} onClick={async () => {
        setSaving(true);
        try { const value = await saveNetworkRules(configurationId, rules.map(r => ({ ...r, domains: [...new Set(r.domains.map(d => d.trim().toLowerCase().replace(/^\*\./, '').replace(/\.$/, '')).filter(Boolean))] }))); setRules(value); setDirty(false); push('ok', 'Đã lưu chính sách mạng', 'Agent tương thích sẽ nhận chính sách khi đồng bộ.'); }
        catch (e) { push('err', 'Không lưu được chính sách mạng', String(e)); }
        finally { setSaving(false); }
      }}>{saving ? 'Đang lưu…' : 'Lưu chính sách mạng'}</button><p className="note">Lưu riêng với cấu hình chính. Thay đổi chính sách mạng làm các ngoại lệ cũ ngừng hiệu lực; cần gửi yêu cầu mới.</p></>}
    </>}
    {selected && <AppPicker apps={available} title="Chọn ứng dụng cho chính sách mạng" enablePlaySearch excludeIds={new Set(available.filter(a => selected.packages.includes(a.pkg)).map(a => a.id))} onLibraryAppAdded={app => setAdded(current => [...current, app])} onAdd={chosen => update(selected.id, { packages: [...new Set([...selected.packages, ...chosen.map(a => a.pkg)])] })} onClose={() => setPicker(null)} />}
  </section>;
}
