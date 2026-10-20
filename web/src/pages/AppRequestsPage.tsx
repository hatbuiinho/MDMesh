import { useCallback, useEffect, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { Modal } from '../ui/Modal';
import { useToast } from '../ui/toast';
import { appRequestHistory, decideAppRequest, listAppRequests, type AppInstallRequest, type AppInstallAudit } from '../api/appRequests';
const labels: Record<string, string> = { pending: 'Chờ duyệt', approved: 'Đã duyệt', rejected: 'Từ chối', revoked: 'Đã thu hồi', stale: 'Cấu hình đã thay đổi' };
export function AppRequestsPage() {
  const { push } = useToast();
  const [rows, setRows] = useState<AppInstallRequest[]>([]);
  const [selected, setSelected] = useState<AppInstallRequest | null>(null);
  const [history, setHistory] = useState<AppInstallAudit[]>([]);
  const [page, setPage] = useState(1);
  const [filter, setFilter] = useState('pending');
  const [query, setQuery] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const reload = useCallback(async () => { setLoading(true); try { setRows(await listAppRequests(page)); } catch (e) { push('err', 'Không tải được yêu cầu', String(e)); } finally { setLoading(false); } }, [page, push]);
  useEffect(() => { void reload(); const timer = setInterval(() => void reload(), 30000); return () => clearInterval(timer); }, [reload]);
  useEffect(() => { let live = true; setHistory([]); if (selected) appRequestHistory(selected.id).then(v => { if (live) setHistory(v); }).catch(e => { if (live) push('err', 'Không tải được lịch sử', String(e)); }); return () => { live = false; }; }, [selected?.id, push]);
  const decide = async (action: 'approve' | 'reject' | 'revoke') => {
    if (!selected) return; setBusy(true);
    try { await decideAppRequest(selected.id, action, note); setSelected(null); await reload(); push('ok', 'Đã lưu quyết định', 'Agent sẽ nhận thay đổi khi làm mới.'); }
    catch (e) { push('err', 'Không lưu được quyết định', String(e)); }
    finally { setBusy(false); }
  };
  const visible = rows.filter(r => (!filter || r.status === filter) && `${r.appName} ${r.deviceName || r.deviceNumber} ${r.developer || ''}`.toLowerCase().includes(query.toLowerCase()));
  return <AppShell title="Yêu cầu cài ứng dụng"><main className="network-page"><h1>Yêu cầu cài ứng dụng</h1>
    <p>Duyệt cho đúng ứng dụng và thiết bị gửi yêu cầu. User tự cài qua Google Play trên máy; khả năng cài phụ thuộc Google Play và chính sách thiết bị.</p>
    <div className="network-toolbar"><input className="input" placeholder="Tìm ứng dụng hoặc thiết bị" value={query} onChange={e => setQuery(e.target.value)} /><select className="sel" value={filter} onChange={e => setFilter(e.target.value)}><option value="">Tất cả</option>{Object.entries(labels).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select><button className="btn" disabled={loading} onClick={() => void reload()}>Làm mới</button></div>
    <div className="network-toolbar"><button className="btn" disabled={loading || page === 1} onClick={() => setPage(p => p - 1)}>Trang trước</button><span>Trang {page} · tối đa 100 yêu cầu/trang · lọc trong trang</span><button className="btn" disabled={loading || rows.length < 100} onClick={() => setPage(p => p + 1)}>Trang sau</button></div>
    {!visible.length && <p>{loading ? 'Đang tải…' : 'Không có yêu cầu phù hợp.'}</p>}
    {visible.map(r => <article className="usage-rule-card network-card" key={r.id}><h3>{r.appName} · {r.deviceName || r.deviceNumber}</h3><p>{r.developer}</p><p>{r.reason}</p><p>{labels[r.status] || r.status} · {new Date(r.createdAt).toLocaleString()}</p>{r.installedAt && <p>Thiết bị báo đã cài: {new Date(r.installedAt).toLocaleString()}</p>}{r.decisionBy && <p>{r.decisionBy}: {r.decisionNote || 'Không ghi chú'}</p>}<button className="btn" onClick={() => { setSelected(r); setNote(''); }}>Chi tiết / duyệt</button></article>)}
    {selected && <Modal ariaLabel="Yêu cầu cài ứng dụng" className="network-dialog" onClose={busy ? undefined : () => setSelected(null)}><h2>{selected.appName}</h2><p>{selected.developer}</p><p>Thiết bị: {selected.deviceName || selected.deviceNumber}</p><p>{selected.reason}</p><p>{labels[selected.status] || selected.status}</p>
      <a href={`https://play.google.com/store/apps/details?id=${encodeURIComponent(selected.packageName)}`} target="_blank" rel="noreferrer">Xem trên Google Play</a>
      <p>Phê duyệt cho phép Agent mở đúng trang cài và bổ sung app vào danh sách ứng dụng được phép của thiết bị. Google Play thông thường vẫn có thể cho phép cài app khác. Chính sách kiosk và truy cập mạng vẫn áp dụng.</p>
      <label>Ghi chú<textarea className="input" maxLength={1000} value={note} disabled={busy} onChange={e => setNote(e.target.value)} /></label>
      {selected.status === 'pending' && <><button className="btn btn-primary" disabled={busy} onClick={() => void decide('approve')}>Duyệt</button> <button className="btn" disabled={busy} onClick={() => void decide('reject')}>Từ chối</button></>}
      {selected.status === 'approved' && <button className="btn" disabled={busy} onClick={() => void decide('revoke')}>Thu hồi</button>}
      <button className="btn" disabled={busy} onClick={() => setSelected(null)}>Đóng</button>
      <h3>Lịch sử quyết định</h3>{history.map((h, i) => <p key={i}>{new Date(h.createdat).toLocaleString()} · {labels[h.status] || h.status} · {h.actor} · {h.note || 'Không ghi chú'}</p>)}
    </Modal>}
  </main></AppShell>;
}
