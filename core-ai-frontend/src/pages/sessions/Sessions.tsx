import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Clock, Zap, ChevronLeft, ChevronRight, Hash, AlertCircle, User, MessageSquare } from 'lucide-react';
import { api } from '../../api/client';
import type { SessionSummary } from '../../api/client';

export default function Sessions() {
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const limit = 20;
  const navigate = useNavigate();

  useEffect(() => {
    setLoading(true);
    api.traces.sessions(offset, limit)
      .then(setSessions)
      .finally(() => setLoading(false));
  }, [offset]);

  const formatDuration = (ms: number) => {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    return `${(ms / 60000).toFixed(1)}m`;
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

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Sessions</h1>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
          Traces grouped by session
        </p>
      </div>

      <div className="rounded-xl border overflow-hidden"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead>
            <tr style={{ background: 'var(--color-bg-tertiary)' }}>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Session ID</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><User size={14} /> User</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><Hash size={14} /> Traces</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><Zap size={14} /> Total Tokens</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><Clock size={14} /> Duration</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><AlertCircle size={14} /> Errors</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><MessageSquare size={14} /> First Request</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Last Active</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={8} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</td></tr>
            ) : sessions.length === 0 ? (
              <tr><td colSpan={8} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>No sessions found</td></tr>
            ) : sessions.map(s => (
              <tr key={s.session_id}
                onClick={() => navigate(`/?sessionId=${encodeURIComponent(s.session_id)}`)}
                className="cursor-pointer transition-colors border-t"
                style={{ borderColor: 'var(--color-border)' }}
                onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                <td className="px-4 py-3">
                  <div className="font-medium font-mono text-xs truncate" style={{ maxWidth: '200px' }}>{s.session_id}</div>
                </td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{s.user_id || '-'}</td>
                <td className="px-4 py-3">
                  <span className="px-2 py-0.5 rounded text-xs font-medium"
                    style={{ background: 'var(--color-bg-tertiary)' }}>
                    {s.trace_count}
                  </span>
                </td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{s.total_tokens.toLocaleString()}</td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatDuration(s.total_duration_ms)}</td>
                <td className="px-4 py-3">
                  {s.error_count > 0 ? (
                    <span className="px-2 py-0.5 rounded text-xs font-medium" style={{ background: '#fef2f2', color: '#ef4444' }}>
                      {s.error_count}
                    </span>
                  ) : (
                    <span style={{ color: 'var(--color-text-secondary)' }}>0</span>
                  )}
                </td>
                <td className="px-4 py-3">
                  <div className="max-w-[200px] truncate text-xs" style={{ color: 'var(--color-text-secondary)' }} title={s.first_request}>
                    {s.first_request}
                  </div>
                </td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(s.last_trace_at)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between mt-4">
        <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          Showing {sessions.length > 0 ? offset + 1 : 0}-{offset + sessions.length}
        </span>
        <div className="flex gap-2">
          <button onClick={() => setOffset(Math.max(0, offset - limit))} disabled={offset === 0}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <ChevronLeft size={14} /> Prev
          </button>
          <button onClick={() => setOffset(offset + limit)} disabled={sessions.length < limit}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            Next <ChevronRight size={14} />
          </button>
        </div>
      </div>
    </div>
  );
}
