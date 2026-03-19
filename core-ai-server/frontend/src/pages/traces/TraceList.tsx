import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Clock, Zap, ChevronLeft, ChevronRight } from 'lucide-react';
import { api } from '../../api/client';
import type { Trace } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';

export default function TraceList() {
  const [traces, setTraces] = useState<Trace[]>([]);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const limit = 20;
  const navigate = useNavigate();

  useEffect(() => {
    setLoading(true);
    api.traces.list(offset, limit).then(setTraces).finally(() => setLoading(false));
  }, [offset]);

  const formatTime = (iso: string) => {
    if (!iso) return '-';
    const d = new Date(iso);
    return d.toLocaleString();
  };

  const formatDuration = (ms: number) => {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  };

  const formatTokens = (t: Trace) => {
    if (!t.total_tokens) return '-';
    const input = t.input_tokens || 0;
    const output = t.output_tokens || 0;
    return `${input.toLocaleString()} / ${output.toLocaleString()}`;
  };

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Traces</h1>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
          Monitor agent executions and LLM calls
        </p>
      </div>

      <div className="rounded-xl border overflow-hidden"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead>
            <tr style={{ background: 'var(--color-bg-tertiary)' }}>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Name</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Status</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><Clock size={14} /> Duration</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><Zap size={14} /> Tokens (in/out)</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Started At</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={5} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</td></tr>
            ) : traces.length === 0 ? (
              <tr><td colSpan={5} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>No traces yet</td></tr>
            ) : traces.map(t => (
              <tr key={t.id}
                onClick={() => navigate(`/traces/${t.id}`)}
                className="cursor-pointer transition-colors border-t"
                style={{ borderColor: 'var(--color-border)' }}
                onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                <td className="px-4 py-3">
                  <div className="font-medium">{t.name || t.trace_id}</div>
                  {t.session_id && <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>Session: {t.session_id}</div>}
                </td>
                <td className="px-4 py-3"><StatusBadge status={t.status} /></td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatDuration(t.duration_ms)}</td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatTokens(t)}</td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(t.started_at || t.created_at)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between mt-4">
        <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          Showing {offset + 1}-{offset + traces.length}
        </span>
        <div className="flex gap-2">
          <button onClick={() => setOffset(Math.max(0, offset - limit))} disabled={offset === 0}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <ChevronLeft size={14} /> Prev
          </button>
          <button onClick={() => setOffset(offset + limit)} disabled={traces.length < limit}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            Next <ChevronRight size={14} />
          </button>
        </div>
      </div>
    </div>
  );
}
