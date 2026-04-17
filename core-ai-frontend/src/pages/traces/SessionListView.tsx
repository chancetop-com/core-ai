import { useEffect, useState, useCallback, Fragment } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, ChevronRight, ChevronDown, ChevronRight as ChevronRightIcon, MessageCircle } from 'lucide-react';
import { sessionApi } from '../../api/session';
import type { ChatSessionSummary } from '../../api/session';
import { api } from '../../api/client';
import type { Trace } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';
import { typeColors, sourceColors } from './colors';

const ALL_SOURCES = ['chat', 'test', 'api', 'a2a', 'scheduled'];

const SOURCE_OPTIONS: { key: string; label: string }[] = [
  { key: '', label: 'All sources' },
  { key: 'chat', label: 'Chat' },
  { key: 'test', label: 'Test' },
  { key: 'api', label: 'API' },
  { key: 'a2a', label: 'A2A' },
  { key: 'scheduled', label: 'Scheduled' },
];

function formatTime(iso?: string) {
  if (!iso) return '-';
  const d = new Date(iso);
  const now = new Date();
  const diffMs = now.getTime() - d.getTime();
  if (diffMs < 60000) return `${Math.floor(diffMs / 1000)}s ago`;
  if (diffMs < 3600000) return `${Math.floor(diffMs / 60000)}m ago`;
  if (diffMs < 86400000) return `${Math.floor(diffMs / 3600000)}h ago`;
  return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatDuration(ms?: number) {
  if (!ms) return '-';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

interface Props {
  onSelectTrace: (traceId: string) => void;
}

export default function SessionListView({ onSelectTrace }: Props) {
  const [sessions, setSessions] = useState<ChatSessionSummary[]>([]);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const [source, setSource] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [tracesBySession, setTracesBySession] = useState<Record<string, Trace[]>>({});
  const [loadingSession, setLoadingSession] = useState<Set<string>>(new Set());
  const limit = 20;
  const navigate = useNavigate();

  const fetchSessions = useCallback(() => {
    setLoading(true);
    const sources = source ? [source] : ALL_SOURCES;
    sessionApi.listChatSessions(offset, limit, sources)
      .then(res => setSessions(res.sessions || []))
      .finally(() => setLoading(false));
  }, [offset, source]);

  useEffect(() => { fetchSessions(); }, [fetchSessions]);

  const toggleExpand = async (sessionId: string) => {
    const next = new Set(expanded);
    if (next.has(sessionId)) {
      next.delete(sessionId);
      setExpanded(next);
      return;
    }
    next.add(sessionId);
    setExpanded(next);
    if (tracesBySession[sessionId]) return;

    setLoadingSession(prev => new Set(prev).add(sessionId));
    try {
      const traces = await api.traces.list(0, 100, { sessionId });
      setTracesBySession(prev => ({ ...prev, [sessionId]: traces }));
    } catch (e) {
      console.warn('failed to load traces for session', sessionId, e);
    } finally {
      setLoadingSession(prev => {
        const n = new Set(prev);
        n.delete(sessionId);
        return n;
      });
    }
  };

  return (
    <div>
      <div className="mb-4 flex items-center gap-2">
        <label className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Source:</label>
        <select
          value={source}
          onChange={e => { setSource(e.target.value); setOffset(0); }}
          className="px-3 py-1.5 rounded-md border text-sm"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
          {SOURCE_OPTIONS.map(s => <option key={s.key} value={s.key}>{s.label}</option>)}
        </select>
      </div>

      <div className="rounded-xl border overflow-hidden"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        {loading ? (
          <div className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : sessions.length === 0 ? (
          <div className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>No sessions found</div>
        ) : (
          <div>
            {sessions.map(s => {
              const src = sourceColors(s.source);
              const isExpanded = expanded.has(s.id);
              const isLoading = loadingSession.has(s.id);
              const traces = tracesBySession[s.id];
              const subtitleParts: string[] = [];
              if (s.agent_id) subtitleParts.push(s.agent_id);
              if (s.user_id) subtitleParts.push(s.user_id);
              subtitleParts.push(`${s.message_count ?? 0} msgs`);
              return (
                <Fragment key={s.id}>
                  <div
                    onClick={() => toggleExpand(s.id)}
                    className="group relative flex items-center gap-3 px-4 py-3 cursor-pointer transition-colors border-t first:border-t-0"
                    style={{
                      borderColor: 'var(--color-border)',
                      borderLeft: `4px solid ${src.color}`,
                      background: isExpanded ? 'var(--color-bg-tertiary)' : undefined,
                    }}
                    onMouseEnter={e => { if (!isExpanded) e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
                    onMouseLeave={e => { if (!isExpanded) e.currentTarget.style.background = 'transparent'; }}>
                    <div style={{ color: 'var(--color-text-secondary)' }}>
                      {isExpanded ? <ChevronDown size={16} /> : <ChevronRightIcon size={16} />}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-medium uppercase tracking-wide" style={{ color: src.color }}>
                          {src.label}
                        </span>
                        <span className="font-medium truncate" title={s.title}>
                          {s.title || <span style={{ color: 'var(--color-text-secondary)', fontStyle: 'italic' }}>untitled</span>}
                        </span>
                      </div>
                      <div className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-secondary)' }} title={s.id}>
                        {subtitleParts.join(' · ')}
                      </div>
                    </div>
                    <div className="text-xs whitespace-nowrap" style={{ color: 'var(--color-text-secondary)' }}>
                      {formatTime(s.last_message_at)}
                    </div>
                    <button
                      onClick={e => { e.stopPropagation(); navigate(`/chat?sessionId=${encodeURIComponent(s.id)}`); }}
                      className="opacity-0 group-hover:opacity-100 px-2 py-1 rounded-md text-xs flex items-center gap-1 cursor-pointer transition-opacity"
                      style={{ color: 'var(--color-primary)', background: 'var(--color-primary-bg)' }}
                      title="Open in Chat">
                      <MessageCircle size={12} /> Chat
                    </button>
                  </div>

                  {isExpanded && (
                    <div className="px-4 pb-4 pt-1" style={{ background: 'var(--color-bg-tertiary)', borderLeft: `4px solid ${src.color}` }}>
                      <div className="ml-8 pl-4" style={{ borderLeft: `1px dashed var(--color-border)` }}>
                        {isLoading ? (
                          <div className="text-xs py-3" style={{ color: 'var(--color-text-secondary)' }}>Loading traces…</div>
                        ) : !traces || traces.length === 0 ? (
                          <div className="text-xs py-3" style={{ color: 'var(--color-text-secondary)' }}>No traces recorded in this session</div>
                        ) : (
                          <div className="flex flex-col">
                            {traces.map(t => {
                              const tc = typeColors(t.type);
                              const tokens = t.totalTokens
                                ? `${(t.inputTokens || 0).toLocaleString()}↑ ${(t.outputTokens || 0).toLocaleString()}↓`
                                : null;
                              return (
                                <div key={t.id}
                                  onClick={e => { e.stopPropagation(); onSelectTrace(t.traceId || t.id); }}
                                  className="flex items-center gap-3 py-1.5 px-2 -ml-2 rounded-md cursor-pointer transition-colors"
                                  onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-secondary)')}
                                  onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                                  <span className="w-2 h-2 rounded-full shrink-0" style={{ background: tc.dot }} />
                                  <span className="text-xs font-medium shrink-0" style={{ color: tc.text }}>{tc.label}</span>
                                  <span className="text-sm truncate flex-1 min-w-0" title={t.name}>{t.name || t.traceId}</span>
                                  <span className="text-xs shrink-0" style={{ color: 'var(--color-text-secondary)' }}>
                                    {formatDuration(t.durationMs)}
                                  </span>
                                  {tokens && (
                                    <span className="text-xs shrink-0 font-mono" style={{ color: 'var(--color-text-secondary)' }}>
                                      {tokens}
                                    </span>
                                  )}
                                  <StatusBadge status={t.status} />
                                </div>
                              );
                            })}
                          </div>
                        )}
                      </div>
                    </div>
                  )}
                </Fragment>
              );
            })}
          </div>
        )}
      </div>

      <div className="flex items-center justify-between mt-4">
        <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          Showing {sessions.length > 0 ? offset + 1 : 0}-{offset + sessions.length}
        </span>
        <div className="flex gap-2">
          <button onClick={() => setOffset(Math.max(0, offset - limit))} disabled={offset === 0}
            className="px-3 py-1.5 rounded-md border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <ChevronLeft size={14} /> Prev
          </button>
          <button onClick={() => setOffset(offset + limit)} disabled={sessions.length < limit}
            className="px-3 py-1.5 rounded-md border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            Next <ChevronRight size={14} />
          </button>
        </div>
      </div>
    </div>
  );
}
