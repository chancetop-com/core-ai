import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Database, Plus, Search, RefreshCw, ChevronLeft, ChevronRight, Trash2, Table2 } from 'lucide-react';
import { api } from '../../api/client';
import type { DatasetView } from '../../api/client';

const FORMAT_NOW = Date.now();

export default function DatasetList() {
  const [datasets, setDatasets] = useState<DatasetView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [offset, setOffset] = useState(0);
  const [limit, setLimit] = useState(10);
  const [search, setSearch] = useState('');
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reqIdRef = useRef(0);
  const navigate = useNavigate();

  const load = useCallback(() => {
    const requestId = ++reqIdRef.current;
    setLoading(true);
    api.datasets.list(search.trim() || undefined, offset, limit)
      .then(res => {
        if (requestId !== reqIdRef.current) return;
        setDatasets(res.datasets || []);
        setTotal(res.total || 0);
      })
      .finally(() => {
        if (requestId === reqIdRef.current) setLoading(false);
      });
  }, [search, offset, limit]);

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(load, 300);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [load]);

  const formatTime = (iso: string) => {
    if (!iso) return '-';
    const d = new Date(iso);
    const diff = FORMAT_NOW - d.getTime();
    if (diff < 60_000) return 'just now';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
    return d.toLocaleDateString();
  };

  const handleDelete = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!confirm('Delete this dataset and all its records?')) return;
    await api.datasets.delete(id);
    if (datasets.length === 1 && offset > 0) {
      setOffset(Math.max(0, offset - limit));
    } else {
      load();
    }
  };

  const inputStyle = {
    background: 'var(--color-bg-secondary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Datasets</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Define output datasets with structured schemas for agent runs
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={() => navigate('/datasets/new')}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={14} /> New Dataset
          </button>
          <button onClick={load}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            <RefreshCw size={14} /> Refresh
          </button>
        </div>
      </div>

      {/* Search */}
      <div className="flex items-center gap-3 mb-4">
        <div className="relative flex-1 max-w-sm">
          <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2"
            style={{ color: 'var(--color-text-secondary)' }} />
          <input
            type="text"
            placeholder="Search datasets..."
            value={search}
            onChange={e => { setSearch(e.target.value); setOffset(0); }}
            className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm"
            style={inputStyle} />
        </div>
      </div>

      {/* Dataset cards */}
      <div className="grid gap-4">
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : datasets.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            {total === 0 && !search
              ? 'No datasets yet. Create one to start extracting structured data from agent runs.'
              : 'No datasets match your search.'}
          </div>
        ) : datasets.map(d => (
          <div key={d.id}
            onClick={() => navigate(`/datasets/${d.id}`)}
            className="rounded-xl border p-4 cursor-pointer transition-colors"
            style={{
              background: 'var(--color-bg-secondary)',
              borderColor: 'var(--color-border)',
            }}
            onMouseEnter={e => { e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
            onMouseLeave={e => { e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <Database size={18} style={{ color: 'var(--color-primary)' }} />
                <span className="font-medium">{d.name}</span>
              </div>
              <div className="flex items-center gap-2">
                <button onClick={e => { e.stopPropagation(); navigate(`/datasets/${d.id}/records`); }}
                  className="px-2 py-1 rounded text-xs border cursor-pointer flex items-center gap-1"
                  style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                  <Table2 size={12} /> Records
                </button>
                <button onClick={e => handleDelete(d.id, e)}
                  className="px-2 py-1 rounded text-xs border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                  <Trash2 size={12} />
                </button>
                <span className="text-xs" style={{ color: 'var(--color-text-secondary)', minWidth: 80, textAlign: 'right' }}>
                  {formatTime(d.updated_at)}
                </span>
              </div>
            </div>
            {d.description && (
              <p className="text-sm mt-1 ml-7" style={{ color: 'var(--color-text-secondary)' }}>{d.description}</p>
            )}
            <p className="text-xs mt-1 ml-7" style={{ color: 'var(--color-text-tertiary)' }}>
              Created by {d.created_by || 'unknown'} &middot; {formatTime(d.created_at)}
            </p>
          </div>
        ))}
      </div>

      {/* Pagination */}
      {total > 0 && (
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-3">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {offset + 1}-{Math.min(offset + limit, total)} of {total}
            </span>
            <select value={limit}
              onChange={e => { setLimit(Number(e.target.value)); setOffset(0); }}
              className="px-2 py-1 rounded-lg border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
              {[10, 20, 50].map(n => <option key={n} value={n}>{n} / page</option>)}
            </select>
          </div>
          <div className="flex gap-2">
            <button onClick={() => setOffset(Math.max(0, offset - limit))} disabled={offset === 0}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button onClick={() => setOffset(offset + limit)} disabled={offset + limit >= total}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
