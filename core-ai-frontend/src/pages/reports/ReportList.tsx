import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { BarChart3, Pin, PinOff, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { api } from '../../api/client';
import type { ReportView } from '../../api/client';
import { useAuth } from '../../api/auth';
import { useCapabilities } from '../../api/capabilities';
import { usePermission } from '../../api/permissions';
import { useListState } from '../../hooks/useListState';
import Pagination from '../../components/Pagination';

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

  const actionButtonStyle = {
    borderColor: 'var(--color-border)',
    color: 'var(--color-text-secondary)',
    background: 'var(--color-bg-secondary)',
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between gap-4 mb-4">
        <div>
          <div className="text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>
            <button onClick={() => navigate('/datasets')} className="cursor-pointer hover:underline">Datasets</button>
            <span className="mx-1">/</span>
            <span>Reports</span>
          </div>
          <h1 className="text-xl font-semibold">Reports</h1>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button onClick={load} disabled={loading}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg border text-sm cursor-pointer disabled:opacity-50"
            style={{ ...actionButtonStyle }}>
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

      <div className="rounded-xl border overflow-x-auto"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead>
            <tr style={{ background: 'var(--color-bg-tertiary)' }}>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Title</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Dataset</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Creator</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Updated</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Revision</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Status</th>
              <th className="text-right px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>
                  Loading...
                </td>
              </tr>
            ) : reports.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>
                  <BarChart3 size={24} className="mx-auto mb-2" />
                  <div className="text-sm font-medium" style={{ color: 'var(--color-text)' }}>No reports yet</div>
                  <div className="text-xs mt-1">Ask an agent in chat to turn a dataset into a report.</div>
                </td>
              </tr>
            ) : reports.map(report => (
              <tr key={report.id}
                onClick={() => openReport(report.id)}
                className="border-t cursor-pointer transition-colors hover:bg-[var(--color-bg-tertiary)]"
                style={{ borderColor: 'var(--color-border)' }}>
                <td className="px-4 py-3 min-w-[220px]">
                  <div className="font-medium truncate" title={report.description || report.title}>{report.title}</div>
                </td>
                <td className="px-4 py-3 max-w-[220px] truncate" title={report.dataset_name || report.dataset_id}>
                  {report.dataset_name || report.dataset_id}
                </td>
                <td className="px-4 py-3 whitespace-nowrap">{creator}</td>
                <td className="px-4 py-3 whitespace-nowrap" style={{ color: 'var(--color-text-secondary)' }}>
                  <span title={report.updated_at || undefined}>{formatTime(report.updated_at)}</span>
                </td>
                <td className="px-4 py-3">{report.revision}</td>
                <td className="px-4 py-3 whitespace-nowrap">
                  <span className="text-[11px] px-2 py-0.5 rounded-full"
                    style={report.pin_id
                      ? { background: '#6366f120', color: '#6366f1' }
                      : { background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                    {report.pin_id ? 'Pinned' : 'Unpinned'}
                  </span>
                </td>
                <td className="px-4 py-3 text-right whitespace-nowrap" onClick={e => e.stopPropagation()}>
                  <div className="inline-flex items-center gap-1.5">
                    <button onClick={() => openReport(report.id)}
                      className="px-2 py-1 rounded border text-xs cursor-pointer"
                      style={actionButtonStyle}>
                      View
                    </button>
                    {canManage && (
                      <button onClick={e => togglePin(report, e)} disabled={actionId === report.id}
                        className="inline-flex items-center gap-1 px-2 py-1 rounded border text-xs cursor-pointer disabled:opacity-50"
                        style={actionButtonStyle}>
                        {report.pin_id ? <PinOff size={12} /> : <Pin size={12} />}
                        {report.pin_id ? 'Unpin' : 'Pin'}
                      </button>
                    )}
                    {canManage && (
                      <button onClick={e => remove(report, e)} disabled={actionId === report.id}
                        className="inline-flex items-center gap-1 px-2 py-1 rounded border text-xs cursor-pointer disabled:opacity-50"
                        style={{ ...actionButtonStyle, color: '#dc2626' }}>
                        <Trash2 size={12} /> Delete
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Pagination
        offset={offset}
        limit={limit}
        total={total}
        shown={reports.length}
        loading={loading}
        pageSizes={PAGE_SIZES}
        onOffsetChange={setOffset}
        onLimitChange={nextLimit => { setLimit(nextLimit); setOffset(0); }}
      />
    </div>
  );
}
