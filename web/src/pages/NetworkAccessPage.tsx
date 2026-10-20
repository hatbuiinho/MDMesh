import { useCallback, useEffect, useMemo, useState } from 'react';
import { Modal } from '../ui/Modal';
import { AppShell } from '../ui/AppShell';
import { listApplications } from '../api/applications';
import { decideNetworkRequest, getNetworkRules, listNetworkRequests, networkRequestHistory, type NetworkAudit, type NetworkRequest, type NetworkRule } from '../api/networkAccess';
import { useToast } from '../ui/toast';
const statusNames: Record<string, string> = { pending: 'Chờ duyệt', approved: 'Đã duyệt', rejected: 'Từ chối', revoked: 'Đã thu hồi', expired: 'Đã hết hạn', stale: 'Chính sách đã thay đổi' };
export function NetworkAccessPage() {
  const { push } = useToast();
  const [rows, setRows] = useState<NetworkRequest[]>([]);
  const [names, setNames] = useState<Map<string, string>>(new Map());
  const [selected, setSelected] = useState<NetworkRequest | null>(null);
  const [rules, setRules] = useState<NetworkRule[]>([]);
  const [page, setPage] = useState(1);
  const [filter, setFilter] = useState('pending');
  const [search, setSearch] = useState('');
  const [scope, setScope] = useState('APP');
  const [ruleId, setRuleId] = useState('');
  const [duration, setDuration] = useState('15M');
  const [note, setNote] = useState('');
  const [history, setHistory] = useState<NetworkAudit[]>([]);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const timezone = useMemo(() => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', []);
  const reload = useCallback(async () => { setLoading(true); try { setRows(await listNetworkRequests(page)); } catch (e) { push('err', 'Không tải được yêu cầu', String(e)); } finally { setLoading(false); } }, [push, page]);
  useEffect(() => { void reload(); const id = setInterval(() => void reload(), 30000); return () => clearInterval(id); }, [reload]);
  useEffect(() => { let live = true; listApplications().then(apps => { if (live) setNames(new Map(apps.map(a => [a.pkg, a.name]))); }).catch(() => {}); return () => { live = false; }; }, []);
  useEffect(() => { let live = true; setRules([]); if (selected) getNetworkRules(selected.configurationId).then(v => { if (live) setRules(v); }).catch(() => {}); return () => { live = false; }; }, [selected?.configurationId]);
  useEffect(() => { let live = true; setHistory([]); if (selected) networkRequestHistory(selected.id).then(v => { if (live) setHistory(v); }).catch(() => {}); return () => { live = false; }; }, [selected?.id]);
  const appName = (pkg: string) => pkg ? names.get(pkg) || `${pkg} · Không có trong Library` : 'Chưa xác định ứng dụng';
  const visible = rows.filter(r => (!filter || (r.effectiveStatus || r.status) === filter) && `${r.deviceName} ${names.get(r.packageName) || r.packageName} ${r.domain}`.toLowerCase().includes(search.toLowerCase()));
  const action = async (kind: 'approve' | 'reject' | 'revoke') => {
    if (!selected) return;
    setBusy(true);
    try { await decideNetworkRequest(selected.id, { action: kind, duration, timezone, scope, ruleId, note }); setSelected(null); await reload(); push('ok', 'Đã lưu quyết định', 'Thiết bị cần đồng bộ và áp dụng trước khi truy cập thay đổi.'); }
    catch (e) { push('err', 'Không lưu được quyết định', String(e)); }
    finally { setBusy(false); }
  };
  return <AppShell title="Truy cập mạng"><main className="network-page"><h1>Yêu cầu và ngoại lệ truy cập mạng</h1>
    <p>Ngoại lệ chỉ áp dụng trên thiết bị gửi yêu cầu, đúng hostname. Đã duyệt chưa có nghĩa thiết bị đã áp dụng; xem trạng thái đồng bộ cấu hình trên thiết bị. Thay đổi áp dụng cho truy vấn mới; kết nối đã mở có thể cần đóng và mở lại.</p>
    <div className="network-toolbar"><input className="input" placeholder="Tìm thiết bị, ứng dụng, domain" value={search} onChange={e => setSearch(e.target.value)} /><select className="sel" value={filter} onChange={e => setFilter(e.target.value)}><option value="">Tất cả</option>{Object.entries(statusNames).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select><button className="btn" disabled={loading} onClick={() => void reload()}>Làm mới</button></div>
    <div className="network-toolbar"><button className="btn" disabled={loading || page === 1} onClick={() => setPage(p => p - 1)}>Trang trước</button><span>Trang {page} · tối đa 500 yêu cầu/trang · bộ lọc áp dụng trong trang</span><button className="btn" disabled={loading || rows.length < 500} onClick={() => setPage(p => p + 1)}>Trang sau</button></div>
    {!visible.length && <p>{loading ? 'Đang tải…' : 'Không có yêu cầu phù hợp.'}</p>}
    {visible.map(r => <article className="usage-rule-card network-card" key={r.id}><h3>{r.deviceName || r.deviceNumber} · {appName(r.packageName)}</h3><strong>{r.domain}</strong>{!r.supported && <p>Cần cập nhật Agent để hỗ trợ ngoại lệ mạng.</p>}{r.status === 'approved' && r.effectiveStatus === 'approved' && <p>{r.applied ? 'Thiết bị đã áp dụng' : 'Đang chờ thiết bị áp dụng'}</p>}<p>{r.reason}</p><p>{statusNames[r.effectiveStatus || r.status] || r.status} · {new Date(r.createdAt).toLocaleString()}{r.status === 'approved' ? r.expiresAt == null ? ' · Vô thời hạn — đến khi thu hồi' : ` · ${r.expiresAt <= Date.now() ? 'Đã hết hạn' : 'Đến'} ${new Date(r.expiresAt).toLocaleString()}` : ''}</p>{r.decisionBy && <p>{r.decisionBy}: {r.decisionNote || 'Không ghi chú'}</p>}{r.grantPackages && <p>Phạm vi: {(JSON.parse(r.grantPackages) as string[]).map(p => p === '*' ? 'Tất cả ứng dụng trên máy này' : appName(p)).join(', ')}</p>}{<button className="btn btn-ghost" onClick={() => { setSelected(r); setScope('APP'); setDuration('15M'); setRuleId(''); setNote(''); }}>Chi tiết / lịch sử</button>}{['pending', 'approved'].includes(r.status) && <button className="btn" onClick={() => { setSelected(r); setScope('APP'); setDuration('15M'); setRuleId(''); setNote(''); }}> {r.status === 'pending' ? 'Xem và duyệt' : 'Thu hồi ngoại lệ'}</button>}</article>)}
    {selected && <Modal ariaLabel="Duyệt truy cập mạng" className="network-dialog" onClose={busy ? undefined : () => setSelected(null)}><h2>{selected.status === 'pending' ? 'Duyệt' : 'Chi tiết'} {selected.domain}</h2><p>Chỉ trên máy {selected.deviceName || selected.deviceNumber}. Không sửa chính sách dùng chung.</p>{selected.status === 'pending' && <>
      <label>Phạm vi <select className="sel" value={scope} disabled={busy} onChange={e => setScope(e.target.value)}><option value="APP" disabled={!selected.packageName}>Đúng ứng dụng gửi yêu cầu</option><option value="GROUP" disabled={!selected.packageName}>Nhóm ứng dụng của chính sách</option><option value="ALL">Tất cả ứng dụng trên máy này</option></select></label>
      {scope === 'GROUP' && <label>Nhóm <select className="sel" value={ruleId} onChange={e => setRuleId(e.target.value)}><option value="">Chọn nhóm</option>{rules.filter(r => r.scope === 'SELECTED' && r.packages.includes(selected.packageName)).map(r => <option key={r.id} value={r.id}>{r.name} · {r.packages.map(p => appName(p)).join(', ')}</option>)}</select></label>}
      {scope === 'ALL' && <p className="note">Bạn đang mở hostname này cho tất cả ứng dụng trên thiết bị, kể cả truy vấn không xác định được ứng dụng.</p>}
      {!selected.packageName && <p>Không xác định được ứng dụng. Không thể duyệt riêng theo app; admin phải chủ động chọn phạm vi tất cả ứng dụng hoặc từ chối.</p>}
      <label>Thời hạn <select className="sel" value={duration} disabled={busy} onChange={e => setDuration(e.target.value)}><option value="15M">15 phút</option><option value="1H">1 giờ</option><option value="TODAY">Đến cuối ngày ({timezone})</option><option value="FOREVER">Vô thời hạn — đến khi thu hồi</option></select></label>
    </>}<label>Ghi chú <textarea className="input" maxLength={1000} value={note} disabled={busy} onChange={e => setNote(e.target.value)} /></label>
      {selected.status === 'pending' ? <><button className="btn btn-primary" disabled={busy || !selected.supported || selected.effectiveStatus === 'stale' || scope === 'APP' && !selected.packageName || scope === 'GROUP' && !ruleId} onClick={() => void action('approve')}>Duyệt mở truy cập</button> <button className="btn" disabled={busy} onClick={() => void action('reject')}>Từ chối</button></> : selected.status === 'approved' ? <button className="btn" disabled={busy} onClick={() => void action('revoke')}>Thu hồi ngoại lệ</button> : null} <button className="btn" disabled={busy} onClick={() => setSelected(null)}>Đóng</button>
      <h3>Lịch sử quyết định</h3>{history.length ? history.map((h, i) => <p key={i}>{new Date(h.createdat).toLocaleString()} · {h.actor} · {statusNames[h.status] || h.status} · {h.note || 'Không ghi chú'}</p>) : <p>Chưa có quyết định.</p>}
    </Modal>}
  </main></AppShell>;
}
