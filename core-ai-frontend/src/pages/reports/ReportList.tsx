import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { BarChart3, ExternalLink, Plus, RefreshCw } from 'lucide-react';
import { api } from '../../api/client';
import type { ReportView } from '../../api/client';

export default function ReportList() {
  const navigate = useNavigate();
  const [reports, setReports] = useState<ReportView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    api.reports.list(0, 100)
      .then(response => {
        setReports(response.reports ?? []);
        setTotal(response.total ?? 0);
      })
      .catch(err => setError(err instanceof Error ? err.message : 'Failed to load reports'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="flex items-start justify-between gap-4 mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Reports</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Dataset reports created in chat. Pin them to your home page; the data refreshes on every open.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={load} disabled={loading}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg border text-sm cursor-pointer disabled:opacity-50"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <RefreshCw size={14} /> Refresh
          </button>
          <button onClick={() => navigate('/chat')}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
            style={{ background: 'var(--color-primary)', color: '#fff' }}>
            <Plus size={16} /> Create in chat
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm" style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }}>
          {error}
        </div>
      )}

      {loading && (
        <div className="grid gap-3 grid-cols-1 md:grid-cols-2">
          {Array.from({ length: 4 }).map((_, index) => (
            <div key={index} className="rounded-xl border p-4 animate-pulse" style={{ borderColor: 'var(--color-border)' }}>
              <div className="h-4 w-32 rounded mb-3" style={{ background: 'var(--color-bg-tertiary)' }} />
              <div className="h-3 w-48 rounded" style={{ background: 'var(--color-bg-tertiary)' }} />
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
        </div>
      )}

      {!loading && reports.length > 0 && (
        <>
          <div className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>{total} report{total === 1 ? '' : 's'}</div>
          <div className="grid gap-3 grid-cols-1 md:grid-cols-2">
            {reports.map(report => (
              <button key={report.id} onClick={() => navigate(`/reports/${report.id}`)}
                className="rounded-xl border p-4 text-left cursor-pointer transition-colors hover:bg-[var(--color-bg-secondary)]"
                style={{ borderColor: 'var(--color-border)' }}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="text-sm font-semibold truncate">{report.title}</div>
                    <div className="text-xs mt-1 truncate" style={{ color: 'var(--color-text-secondary)' }}>
                      {report.dataset_name || report.dataset_id} · {report.display_type || 'TABLE'} · rev {report.revision}
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    {report.pin_id && (
                      <span className="text-[11px] px-2 py-0.5 rounded-full" style={{ background: '#6366f120', color: '#6366f1' }}>Pinned</span>
                    )}
                    <ExternalLink size={14} style={{ color: 'var(--color-text-secondary)' }} />
                  </div>
                </div>
                {report.description && (
                  <div className="text-xs mt-3 line-clamp-2" style={{ color: 'var(--color-text-secondary)' }}>{report.description}</div>
                )}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
