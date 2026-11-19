import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { BarChart3, ChevronLeft, ChevronRight, ExternalLink, Pin, PinOff, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { api } from '../../api/client';
import type { ReportView } from '../../api/client';
import { useAuth } from '../../api/auth';
import { useCapabilities } from '../../api/capabilities';
import { usePermission } from '../../api/permissions';
import { useListState } from '../../hooks/useListState';

const PAGE_SIZES = [10, 20, 50];
const FORMAT_NOW = Date.now();

export default function ReportList() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const caps = useCapabilities();
  const canManage = usePermission('report.manage');
  const [reports, setReports] = useState<ReportView[]>([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useListState('reports.offset', 0);
  const [limit, setLimit] = useListState('reports.limit', 10);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionId, setActionId] = useState<string | null>(null);
  const reqIdRef = useRef(0);

  const creator = user?.name || user?.userId || 'You';

  const load = useCallback(() => {
    const requestId = ++reqIdRef.current;
    setLoading(true);
    setError(null);
    api.reports.list(offset, limit)
      .then(response => {
        if (requestId !== reqIdRef.current) return;
        setReports(response.reports ?? []);
        setTotal(response.total ?? 0);
      })
      .catch(err => {
        if (requestId !== reqIdRef.current) return;
        setError(err instanceof Error ? err.message : 'Failed to load reports');
      })
      .finally(() => {
        if (requestId === reqIdRef.current) setLoading(false);
      });
  }, [offset, limit]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (loading) return;
    if (offset > 0 && offset >= total) {
      setOffset(Math.max(0, Math.floor(Math.max(0, total - 1) / limit) * limit));
    }
  }, [loading, offset, total, limit, setOffset]);

  const formatTime = (iso?: string | null) => {
    if (!iso) return '—';
    const d = new Date(iso);
    const diff = FORMAT_NOW - d.getTime();
    if (diff < 60_000) return 'just now';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
    return d.toLocaleDateString();
  };

  const openReport = (id: string) => navigate(`/datasets/reports/${id}`);

  const togglePin = async (report: ReportView, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!canManage || actionId) return;
    setActionId(report.id);
    setError(null);
    try {
      const layout = await api.reports.pins.layout();
      if (report.pin_id) {
        await api.reports.pins.unpin(report.pin_id, layout.layout_revision ?? 0);
      } else {
        await api.reports.pins.pin({
          report_id: report.id,
          width: 'HALF',
          expected_layout_revision: layout.layout_revision ?? 0,
        });
      }
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update report pin');
    } finally {
      setActionId(null);
    }
  };

  const remove = async (report: ReportView, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!canManage || actionId) return;
    if (!window.confirm(`Delete "${report.title}"? Pinned cards will be removed too.`)) return;
    setActionId(report.id);
    setError(null);
    try {
      const layout = report.pin_id ? await api.reports.pins.layout() : null;
      await api.reports.delete(
        report.id,
        report.revision,
        report.pin_id ? layout?.layout_revision : undefined,
      );
      if (reports.length === 1 && offset > 0) {
        setOffset(Math.max(0, offset - limit));
      } else {
        load();
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete report');
    } finally {
      setActionId(null);
    }
  };

  return (
    <div className="p-6">
      <div className="flex items-start justify-between gap-4 mb-6">
        <div>
          <div className="text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>
            <button onClick={() => navigate('/datasets')} className="cursor-pointer hover:underline">Datasets</button>
            <span className="mx-1">/</span>
            <span>Reports</span>
          </div>
          <h1 className="text-2xl font-semibold">Reports</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Dataset reports created in chat. Pin them to your home page; the data refreshes on every open.
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button onClick={load} disabled={loading}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg border text-sm cursor-pointer disabled:opacity-50"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <RefreshCw size={14} /> Refresh
          </button>
          {caps.chat && (
            <button onClick={() => navigate('/chat')}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
              style={{ background: 'var(--color-primary)', color: '#fff' }}>
              <Plus size={16} /> Create in chat
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm flex items-center justify-between gap-4"
          style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }}>
          <span>{error}</span>
          <button onClick={load} className="cursor-pointer underline shrink-0">Retry</button>
        </div>
      )}

      {loading && (
        <div className="grid gap-3 grid-cols-1 md:grid-cols-2">
          {Array.from({ length: 4 }).map((_, index) => (
            <div key={index} className="rounded-xl border p-4 animate-pulse" style={{ borderColor: 'var(--color-border)' }}>
              <div className="h-4 w-32 rounded mb-3" style={{ background: 'var(--color-bg-tertiary)' }} />
              <div className="h-3 w-48 rounded mb-2" style={{ background: 'var(--color-bg-tertiary)' }} />
              <div className="h-3 w-36 rounded" style={{ background: 'var(--color-bg-tertiary)' }} />
            </div>
          ))}
        </div>
      )}

      {!loading && reports.length === 0 && (
        <div className="rounded-xl border p-10 text-center" style={{ borderColor: 'var(--color-border)' }}>
          <BarChart3 size={28} className="mx-auto mb-3" style={{ color: 'var(--color-text-secondary)' }} />
          <div className="text-sm font-medium">No reports yet</div>
          <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Ask an agent in chat to turn a dataset into a report.
          </div>
          {caps.chat && (
            <button onClick={() => navigate('/chat')}
              className="inline-flex items-center gap-1.5 mt-4 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
              style={{ background: 'var(--color-primary)', color: '#fff' }}>
              <Plus size={15} /> Create in chat
            </button>
          )}
        </div>
      )}

      {!loading && reports.length > 0 && (
        <>
          <div className="grid gap-3 grid-cols-1 md:grid-cols-2">
            {reports.map(report => (
              <div key={report.id}
                role="button"
                tabIndex={0}
                onClick={() => openReport(report.id)}
                onKeyDown={e => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    openReport(report.id);
                  }
                }}
                className="rounded-xl border p-4 text-left cursor-pointer transition-colors hover:bg-[var(--color-bg-secondary)]"
                style={{ borderColor: 'var(--color-border)' }}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2 min-w-0">
                      <div className="text-sm font-semibold truncate">{report.title}</div>
                      {report.pin_id && (
                        <span className="text-[11px] px-2 py-0.5 rounded-full shrink-0"
                          style={{ background: '#6366f120', color: '#6366f1' }}>Pinned</span>
                      )}
                    </div>
                    <div className="text-xs mt-1 truncate" style={{ color: 'var(--color-text-secondary)' }}>
                      {report.display_type || 'TABLE'} · rev {report.revision}
                    </div>
                  </div>
                  <ExternalLink size={14} className="shrink-0" style={{ color: 'var(--color-text-secondary)' }} />
                </div>

                {report.description && (
                  <div className="text-xs mt-3 line-clamp-2" style={{ color: 'var(--color-text-secondary)' }}>{report.description}</div>
                )}

                <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 mt-3 text-[11px]"
                  style={{ color: 'var(--color-text-secondary)' }}>
                  <div className="truncate"><span className="opacity-70">Dataset</span> {report.dataset_name || report.dataset_id}</div>
                  <div className="truncate"><span className="opacity-70">Creator</span> {creator}</div>
                  <div className="truncate"><span className="opacity-70">Updated</span> {formatTime(report.updated_at)}</div>
                  <div><span className="opacity-70">Status</span> {report.pin_id ? 'Pinned' : 'Unpinned'}</div>
                </div>

                <div className="flex items-center gap-2 mt-3 pt-3 border-t" style={{ borderColor: 'var(--color-border)' }}>
                  <button onClick={e => { e.stopPropagation(); openReport(report.id); }}
                    className="inline-flex items-center gap-1 px-2 py-1 rounded border text-xs cursor-pointer"
                    style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                    <ExternalLink size={12} /> View
                  </button>
                  {canManage && (
                    <>
                      <button onClick={e => togglePin(report, e)} disabled={actionId === report.id}
                        className="inline-flex items-center gap-1 px-2 py-1 rounded border text-xs cursor-pointer disabled:opacity-50"
                        style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                        {report.pin_id ? <PinOff size={12} /> : <Pin size={12} />}
                        {report.pin_id ? 'Unpin' : 'Pin'}
                      </button>
                      <button onClick={e => remove(report, e)} disabled={actionId === report.id}
                        className="inline-flex items-center gap-1 px-2 py-1 rounded border text-xs cursor-pointer disabled:opacity-50 ml-auto"
                        style={{ borderColor: 'var(--color-border)', color: '#dc2626' }}>
                        <Trash2 size={12} /> Delete
                      </button>
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>

          <div className="flex items-center justify-between mt-4">
            <div className="flex items-center gap-3">
              <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                Showing {offset + 1}-{Math.min(offset + limit, total)} of {total}
              </span>
              <select value={limit}
                onChange={e => { setLimit(Number(e.target.value)); setOffset(0); }}
                className="px-2 py-1 rounded-lg border text-xs"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                {PAGE_SIZES.map(size => <option key={size} value={size}>{size} / page</option>)}
              </select>
            </div>
            <div className="flex gap-2">
              <button onClick={() => setOffset(Math.max(0, offset - limit))} disabled={offset === 0}
                className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
                <ChevronLeft size={14} /> Prev
              </button>
              <button onClick={() => setOffset(offset + limit)} disabled={reports.length === 0 || offset + limit >= total}
                className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
                Next <ChevronRight size={14} />
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
