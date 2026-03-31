import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Clock, Zap, ChevronLeft, ChevronRight, Cpu } from 'lucide-react';
import { api } from '../../api/client';
import type { Span } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';

export default function Generations() {
  const [spans, setSpans] = useState<Span[]>([]);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const [modelFilter, setModelFilter] = useState('');
  const limit = 20;
  const navigate = useNavigate();

  useEffect(() => {
    setLoading(true);
    api.traces.generations(offset, limit, modelFilter || undefined)
      .then(setSpans)
      .finally(() => setLoading(false));
  }, [offset, modelFilter]);

  const formatDuration = (ms: number) => {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  };

  const formatTime = (iso: string) => {
    if (!iso) return '-';
    const d = new Date(iso);
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    if (diffMs < 60000) return `${Math.floor(diffMs / 1000)}s ago`;
    if (diffMs < 3600000) return `${Math.floor(diffMs / 60000)}m ago`;
    if (diffMs < 86400000) return `${Math.floor(diffMs / 3600000)}h ago`;
    return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  const models = [...new Set(spans.map(s => s.model).filter(Boolean))];

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Generations</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            All LLM calls across traces
          </p>
        </div>
        <div>
          <select
            value={modelFilter}
            onChange={e => { setModelFilter(e.target.value); setOffset(0); }}
            className="px-3 py-1.5 rounded-lg border text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <option value="">All models</option>
            {models.map(m => <option key={m} value={m}>{m}</option>)}
          </select>
        </div>
      </div>

      <div className="rounded-xl border overflow-hidden"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead>
            <tr style={{ background: 'var(--color-bg-tertiary)' }}>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Name</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><Cpu size={14} /> Model</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Status</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><Clock size={14} /> Latency</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><Zap size={14} /> Input</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><Zap size={14} /> Output</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Time</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={7} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</td></tr>
            ) : spans.length === 0 ? (
              <tr><td colSpan={7} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>No LLM generations found</td></tr>
            ) : spans.map(s => (
              <tr key={s.span_id}
                onClick={() => navigate(`/traces/${s.trace_id}`)}
                className="cursor-pointer transition-colors border-t"
                style={{ borderColor: 'var(--color-border)' }}
                onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                <td className="px-4 py-3">
                  <div className="font-medium truncate" style={{ maxWidth: '200px' }}>{s.name}</div>
                  <div className="text-xs mt-0.5 font-mono truncate" style={{ color: 'var(--color-text-secondary)', maxWidth: '200px' }}>
                    {s.trace_id}
                  </div>
                </td>
                <td className="px-4 py-3">
                  <span className="px-2 py-0.5 rounded text-xs font-mono"
                    style={{ background: 'var(--color-bg-tertiary)' }}>
                    {s.model || '-'}
                  </span>
                </td>
                <td className="px-4 py-3"><StatusBadge status={s.status} /></td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatDuration(s.duration_ms)}</td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{(s.input_tokens || 0).toLocaleString()}</td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{(s.output_tokens || 0).toLocaleString()}</td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(s.started_at)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between mt-4">
        <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          Showing {spans.length > 0 ? offset + 1 : 0}-{offset + spans.length}
        </span>
        <div className="flex gap-2">
          <button onClick={() => setOffset(Math.max(0, offset - limit))} disabled={offset === 0}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <ChevronLeft size={14} /> Prev
          </button>
          <button onClick={() => setOffset(offset + limit)} disabled={spans.length < limit}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            Next <ChevronRight size={14} />
          </button>
        </div>
      </div>
    </div>
  );
}
