import { useEffect, useState } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowLeft, RefreshCw, ChevronLeft, ChevronRight, Search, Database } from 'lucide-react';
import { api } from '../../api/client';
import type { DatasetView, DatasetRecordView, DatasetRecordFilter } from '../../api/client';

export default function DatasetRecords() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const [dataset, setDataset] = useState<DatasetView | null>(null);
  const [records, setRecords] = useState<DatasetRecordView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);

  const limit = Number(searchParams.get('limit') || '20');
  const offset = Number(searchParams.get('offset') || '0');
  const agentId = searchParams.get('agent_id') || '';
  const fromDate = searchParams.get('from') || '';
  const toDate = searchParams.get('to') || '';

  // Build filter from search params
  const buildFilter = (): DatasetRecordFilter => {
    const f: DatasetRecordFilter = { limit, offset };
    if (agentId) f.agent_id = agentId;
    if (fromDate) f.from = new Date(fromDate).toISOString();
    if (toDate) f.to = new Date(toDate).toISOString();
    return f;
  };

  const load = () => {
    if (!id) return;
    setLoading(true);
    Promise.all([
      api.datasets.get(id),
      api.datasets.records(id, buildFilter()),
    ]).then(([d, r]) => {
      setDataset(d);
      setRecords(r.records || []);
      setTotal(r.total || 0);
    }).finally(() => setLoading(false));
  };

  useEffect(load, [id, searchParams]);

  const applyFilter = (updates: Record<string, string>) => {
    const next = new URLSearchParams(searchParams);
    Object.entries(updates).forEach(([k, v]) => {
      if (v) next.set(k, v); else next.delete(k);
    });
    next.set('offset', '0');
    setSearchParams(next);
  };

  const formatTime = (iso: string) => {
    if (!iso) return '-';
    return new Date(iso).toLocaleString();
  };

  const parseData = (data: string): Record<string, unknown> => {
    try { return JSON.parse(data); } catch { return {}; }
  };

  const fieldNames = dataset?.schema?.map(f => f.name) || [];
  const hasSchema = fieldNames.length > 0;

  const inputStyle = {
    background: 'var(--color-bg-secondary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  return (
    <div className="p-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <button onClick={() => navigate(`/datasets/${id}`)}
            className="flex items-center gap-1 text-sm cursor-pointer"
            style={{ color: 'var(--color-primary)' }}>
            <ArrowLeft size={16} /> Back to Dataset
          </button>
          <Database size={20} style={{ color: 'var(--color-primary)' }} />
          <h1 className="text-xl font-semibold">{dataset?.name || 'Loading...'} Records</h1>
          <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            ({total} total)
          </span>
        </div>
        <button onClick={load}
          className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
          style={{ borderColor: 'var(--color-border)' }}>
          <RefreshCw size={14} /> Refresh
        </button>
      </div>

      {/* Filters */}
      <div className="rounded-xl border p-3 mb-4 flex flex-wrap items-end gap-3"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <div>
          <label className="text-xs mb-0.5 block" style={{ color: 'var(--color-text-secondary)' }}>From</label>
          <input type="date" value={fromDate} onChange={e => applyFilter({ from: e.target.value })}
            className="px-2 py-1.5 rounded border text-xs" style={inputStyle} />
        </div>
        <div>
          <label className="text-xs mb-0.5 block" style={{ color: 'var(--color-text-secondary)' }}>To</label>
          <input type="date" value={toDate} onChange={e => applyFilter({ to: e.target.value })}
            className="px-2 py-1.5 rounded border text-xs" style={inputStyle} />
        </div>
        <div>
          <label className="text-xs mb-0.5 block" style={{ color: 'var(--color-text-secondary)' }}>Agent ID</label>
          <div className="relative">
            <Search size={14} className="absolute left-2 top-1/2 -translate-y-1/2"
              style={{ color: 'var(--color-text-secondary)' }} />
            <input
              value={agentId}
              onChange={e => applyFilter({ agent_id: e.target.value })}
              placeholder="Filter by agent..."
              className="pl-7 pr-2 py-1.5 rounded border text-xs w-40" style={inputStyle} />
          </div>
        </div>
        <div>
          <label className="text-xs mb-0.5 block" style={{ color: 'var(--color-text-secondary)' }}>Per page</label>
          <select value={limit}
            onChange={e => applyFilter({ limit: e.target.value })}
            className="px-2 py-1.5 rounded border text-xs" style={inputStyle}>
            {[10, 20, 50, 100].map(n => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
        {(agentId || fromDate || toDate) && (
          <button onClick={() => setSearchParams(new URLSearchParams({ limit: String(limit) }))}
            className="px-3 py-1.5 rounded text-xs cursor-pointer"
            style={{ color: 'var(--color-text-secondary)' }}>
            Clear Filters
          </button>
        )}
      </div>

      {/* Records table */}
      {loading ? (
        <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
      ) : records.length === 0 ? (
        <div className="text-center py-12 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          No records found.
        </div>
      ) : (
        <>
          <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr style={{ background: 'var(--color-bg-secondary)' }}>
                    <th className="text-left px-3 py-2 text-xs font-medium"
                      style={{ color: 'var(--color-text-secondary)' }}>Run ID</th>
                    <th className="text-left px-3 py-2 text-xs font-medium"
                      style={{ color: 'var(--color-text-secondary)' }}>Agent</th>
                    <th className="text-left px-3 py-2 text-xs font-medium"
                      style={{ color: 'var(--color-text-secondary)' }}>Started At</th>
                    {hasSchema ? fieldNames.map(fn => (
                      <th key={fn} className="text-left px-3 py-2 text-xs font-medium"
                        style={{ color: 'var(--color-text-secondary)' }}>
                        {dataset?.schema?.find(s => s.name === fn)?.label || fn}
                      </th>
                    )) : (
                      <th className="text-left px-3 py-2 text-xs font-medium"
                        style={{ color: 'var(--color-text-secondary)' }}>Output</th>
                    )}
                  </tr>
                </thead>
                <tbody>
                  {records.map(r => {
                    const data = parseData(r.data);
                    return (
                      <tr key={r.id} className="border-t"
                        style={{ borderColor: 'var(--color-border)' }}
                        onMouseEnter={e => e.currentTarget.style.background = 'var(--color-bg-secondary)'}
                        onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
                        <td className="px-3 py-2 text-xs font-mono"
                          style={{ color: 'var(--color-text-secondary)' }}>
                          <button onClick={() => navigate(`/runs/${r.run_id}`)}
                            className="cursor-pointer hover:underline"
                            style={{ color: 'var(--color-primary)' }}>
                            {r.run_id?.slice(0, 12)}...
                          </button>
                        </td>
                        <td className="px-3 py-2 text-xs"
                          style={{ color: 'var(--color-text-secondary)' }}>
                          {r.agent_id}
                        </td>
                        <td className="px-3 py-2 text-xs"
                          style={{ color: 'var(--color-text-secondary)' }}>
                          {formatTime(r.run_started_at)}
                        </td>
                        {hasSchema ? fieldNames.map(fn => (
                          <td key={fn} className="px-3 py-2 text-xs max-w-xs truncate"
                            title={String(data[fn] ?? '')}>
                            {data[fn] != null ? String(data[fn]) : '-'}
                          </td>
                        )) : (
                          <td className="px-3 py-2 text-xs max-w-md" style={{ wordBreak: 'break-word' }}>
                            {data.output != null ? (
                              <div className="max-h-32 overflow-y-auto whitespace-pre-wrap">
                                {String(data.output)}
                              </div>
                            ) : '-'}
                          </td>
                        )}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>

          {/* Pagination */}
          <div className="flex items-center justify-between mt-4">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {offset + 1}-{Math.min(offset + limit, total)} of {total}
            </span>
            <div className="flex gap-2">
              <button
                onClick={() => {
                  const next = new URLSearchParams(searchParams);
                  next.set('offset', String(Math.max(0, offset - limit)));
                  setSearchParams(next);
                }}
                disabled={offset === 0}
                className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
                <ChevronLeft size={14} /> Prev
              </button>
              <button
                onClick={() => {
                  const next = new URLSearchParams(searchParams);
                  next.set('offset', String(offset + limit));
                  setSearchParams(next);
                }}
                disabled={offset + limit >= total}
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
