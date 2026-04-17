import { useEffect, useState, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Clock, Zap, ChevronLeft, ChevronRight, Search, X, Filter } from 'lucide-react';
import { api } from '../../api/client';
import type { Trace, TraceFilter } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';
import TraceDetailPanel from './TraceDetailPanel';
import SessionListView from './SessionListView';
import { typeColors, sourceColors } from './colors';

const STATUS_OPTIONS = ['', 'RUNNING', 'COMPLETED', 'ERROR'];

const TYPE_OPTIONS: { key: string; label: string }[] = [
  { key: '', label: 'All types' },
  { key: 'agent', label: 'Agent' },
  { key: 'llm_call', label: 'LLM Call' },
  { key: 'external', label: 'External' },
];

const VIEW_TABS: { key: 'traces' | 'sessions'; label: string }[] = [
  { key: 'traces', label: 'Traces' },
  { key: 'sessions', label: 'Sessions' },
];

export default function TraceList() {
  const [traces, setTraces] = useState<Trace[]>([]);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const [showFilters, setShowFilters] = useState(false);
  const [searchParams, setSearchParams] = useSearchParams();
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null);
  const limit = 20;

  const initialView = (searchParams.get('view') === 'sessions' ? 'sessions' : 'traces') as 'traces' | 'sessions';
  const [view, setView] = useState<'traces' | 'sessions'>(initialView);

  const [filters, setFilters] = useState<TraceFilter>({
    name: searchParams.get('name') || '',
    type: searchParams.get('type') || '',
    source: searchParams.get('source') || '',
    agentName: searchParams.get('agentName') || '',
    model: searchParams.get('model') || '',
    status: searchParams.get('status') || '',
    sessionId: searchParams.get('sessionId') || '',
    userId: searchParams.get('userId') || '',
    startFrom: searchParams.get('startFrom') || '',
    startTo: searchParams.get('startTo') || '',
  });

  const activeFilterCount = Object.entries(filters).filter(([, v]) => v).length;

  const switchView = (v: 'traces' | 'sessions') => {
    setView(v);
    const params = new URLSearchParams(searchParams);
    if (v === 'sessions') params.set('view', 'sessions');
    else params.delete('view');
    setSearchParams(params);
  };

  const SOURCE_OPTIONS: { key: string; label: string }[] = [
    { key: '', label: 'All sources' },
    { key: 'chat', label: 'Chat' },
    { key: 'test', label: 'Test' },
    { key: 'api', label: 'API' },
    { key: 'a2a', label: 'A2A' },
    { key: 'scheduled', label: 'Scheduled' },
    { key: 'llm_test', label: 'LLM Test' },
    { key: 'llm_api', label: 'LLM API' },
    { key: 'external', label: 'External' },
  ];

  const fetchTraces = useCallback(() => {
    if (view !== 'traces') return;
    setLoading(true);
    const cleanFilters: TraceFilter = {};
    Object.entries(filters).forEach(([k, v]) => {
      if (v) (cleanFilters as Record<string, string>)[k] = v;
    });
    api.traces.list(offset, limit, Object.keys(cleanFilters).length > 0 ? cleanFilters : undefined)
      .then(setTraces)
      .finally(() => setLoading(false));
  }, [offset, filters, view]);

  useEffect(() => { fetchTraces(); }, [fetchTraces]);

  const applyFilters = () => {
    setOffset(0);
    const params = new URLSearchParams();
    if (view === 'sessions') params.set('view', 'sessions');
    Object.entries(filters).forEach(([k, v]) => { if (v) params.set(k, v); });
    setSearchParams(params);
  };

  const clearFilters = () => {
    setFilters({ name: '', type: '', source: '', agentName: '', model: '', status: '', sessionId: '', userId: '', startFrom: '', startTo: '' });
    setOffset(0);
    setSearchParams({});
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

  const formatDuration = (ms: number) => {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  };

  const formatTokens = (t: Trace) => {
    if (!t.totalTokens) return '-';
    const input = t.inputTokens || 0;
    const output = t.outputTokens || 0;
    return `${input.toLocaleString()} / ${output.toLocaleString()}`;
  };

  const extractModel = (t: Trace): string => {
    if (t.model) return t.model;
    if (t.metadata?.model) return t.metadata.model;
    return '-';
  };

  const extractInputPreview = (t: Trace): string => {
    try {
      const parsed = JSON.parse(t.input);
      const msgs = parsed?.messages;
      if (Array.isArray(msgs) && msgs.length > 0) {
        const last = msgs[msgs.length - 1];
        const content = Array.isArray(last?.content) ? last.content[0]?.text : last?.content;
        if (typeof content === 'string') return content.replace(/\s+/g, ' ').slice(0, 80);
      }
    } catch { /* ignore */ }
    return '';
  };

  return (
    <div className="flex h-full">
    <div className="p-6 flex-1 min-w-0 overflow-auto">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{view === 'sessions' ? 'Sessions' : 'Traces'}</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            {view === 'sessions' ? 'User-facing conversations grouped by session' : 'Monitor agent executions and LLM calls'}
          </p>
        </div>
        {view === 'traces' && (
          <div className="flex items-center gap-2">
            <button
              onClick={() => setShowFilters(!showFilters)}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1.5 cursor-pointer transition-colors"
              style={{
                borderColor: activeFilterCount > 0 ? 'var(--color-primary)' : 'var(--color-border)',
                background: activeFilterCount > 0 ? 'var(--color-primary-bg)' : 'var(--color-bg-secondary)',
                color: activeFilterCount > 0 ? 'var(--color-primary)' : undefined,
              }}>
              <Filter size={14} />
              Filters
              {activeFilterCount > 0 && (
                <span className="ml-1 px-1.5 py-0.5 rounded-full text-xs font-medium"
                  style={{ background: 'var(--color-primary)', color: 'white' }}>
                  {activeFilterCount}
                </span>
              )}
            </button>
          </div>
        )}
      </div>

      {/* View tabs */}
      <div className="mb-4 flex gap-1 border-b" style={{ borderColor: 'var(--color-border)' }}>
        {VIEW_TABS.map(t => {
          const active = view === t.key;
          return (
            <button key={t.key} onClick={() => switchView(t.key)}
              className="px-3 py-2 text-sm cursor-pointer border-b-2 -mb-[2px] transition-colors"
              style={{
                borderColor: active ? 'var(--color-primary)' : 'transparent',
                color: active ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                fontWeight: active ? 600 : 400,
              }}>
              {t.label}
            </button>
          );
        })}
      </div>

      {view === 'sessions' ? <SessionListView onSelectTrace={setSelectedTraceId} /> : (
      <>
      {/* Filter panel */}
      {showFilters && (
        <div className="mb-4 rounded-xl border p-4"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label className="text-xs font-medium mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Name</label>
              <div className="relative">
                <Search size={14} className="absolute left-2.5 top-2.5" style={{ color: 'var(--color-text-secondary)' }} />
                <input
                  type="text"
                  value={filters.name}
                  onChange={e => setFilters(f => ({ ...f, name: e.target.value }))}
                  onKeyDown={e => e.key === 'Enter' && applyFilters()}
                  placeholder="Search by name..."
                  className="w-full pl-8 pr-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
                />
              </div>
            </div>
            <div>
              <label className="text-xs font-medium mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Type</label>
              <select
                value={filters.type}
                onChange={e => { setFilters(f => ({ ...f, type: e.target.value })); }}
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
                {TYPE_OPTIONS.map(t => <option key={t.key} value={t.key}>{t.label}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs font-medium mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Source</label>
              <select
                value={filters.source}
                onChange={e => { setFilters(f => ({ ...f, source: e.target.value })); }}
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
                {SOURCE_OPTIONS.map(s => <option key={s.key} value={s.key}>{s.label}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs font-medium mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Status</label>
              <select
                value={filters.status}
                onChange={e => { setFilters(f => ({ ...f, status: e.target.value })); }}
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
                {STATUS_OPTIONS.map(s => <option key={s} value={s}>{s || 'All statuses'}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs font-medium mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Agent Name</label>
              <input
                type="text"
                value={filters.agentName}
                onChange={e => setFilters(f => ({ ...f, agentName: e.target.value }))}
                onKeyDown={e => e.key === 'Enter' && applyFilters()}
                placeholder="Filter by agent..."
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
            </div>
            <div>
              <label className="text-xs font-medium mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Model</label>
              <input
                type="text"
                value={filters.model}
                onChange={e => setFilters(f => ({ ...f, model: e.target.value }))}
                onKeyDown={e => e.key === 'Enter' && applyFilters()}
                placeholder="Filter by model..."
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
            </div>
            <div>
              <label className="text-xs font-medium mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Session ID</label>
              <input
                type="text"
                value={filters.sessionId}
                onChange={e => setFilters(f => ({ ...f, sessionId: e.target.value }))}
                onKeyDown={e => e.key === 'Enter' && applyFilters()}
                placeholder="Filter by session..."
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
            </div>
            <div>
              <label className="text-xs font-medium mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>User ID</label>
              <input
                type="text"
                value={filters.userId}
                onChange={e => setFilters(f => ({ ...f, userId: e.target.value }))}
                onKeyDown={e => e.key === 'Enter' && applyFilters()}
                placeholder="Filter by user..."
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
            </div>
            <div>
              <label className="text-xs font-medium mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Start From</label>
              <input
                type="datetime-local"
                value={filters.startFrom ? filters.startFrom.slice(0, 16) : ''}
                onChange={e => setFilters(f => ({ ...f, startFrom: e.target.value ? new Date(e.target.value).toISOString() : '' }))}
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
            </div>
            <div>
              <label className="text-xs font-medium mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Start To</label>
              <input
                type="datetime-local"
                value={filters.startTo ? filters.startTo.slice(0, 16) : ''}
                onChange={e => setFilters(f => ({ ...f, startTo: e.target.value ? new Date(e.target.value).toISOString() : '' }))}
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
            </div>
          </div>
          <div className="flex items-center gap-2 mt-3">
            <button onClick={applyFilters}
              className="px-4 py-1.5 rounded-lg text-sm font-medium text-white cursor-pointer"
              style={{ background: 'var(--color-primary)' }}>
              Apply
            </button>
            {activeFilterCount > 0 && (
              <button onClick={clearFilters}
                className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>
                <X size={14} /> Clear
              </button>
            )}
          </div>
        </div>
      )}

      <div className="rounded-xl border overflow-hidden"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead>
            <tr style={{ background: 'var(--color-bg-tertiary)' }}>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Type</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Name</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Model</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Status</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><Clock size={14} /> Duration</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                <span className="flex items-center gap-1"><Zap size={14} /> Tokens (in/out)</span>
              </th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Time</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={7} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</td></tr>
            ) : traces.length === 0 ? (
              <tr><td colSpan={7} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>
                {activeFilterCount > 0 ? 'No traces match your filters' : 'No traces yet'}
              </td></tr>
            ) : traces.map(t => {
              const model = extractModel(t);
              const preview = extractInputPreview(t);
              const src = sourceColors(t.source);
              const tc = typeColors(t.type);
              return (
              <tr key={t.id}
                onClick={() => setSelectedTraceId(t.traceId || t.id)}
                className="cursor-pointer transition-colors border-t"
                style={{ borderColor: 'var(--color-border)' }}
                onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                <td className="px-4 py-3">
                  <span className="text-xs px-2 py-0.5 rounded-md font-medium whitespace-nowrap inline-flex items-center gap-1.5"
                    style={{ background: tc.bg, color: tc.text }}
                    title={`type: ${t.type || 'unknown'}`}>
                    <span className="w-1.5 h-1.5 rounded-full" style={{ background: tc.dot }} />
                    {tc.label}
                  </span>
                </td>
                <td className="px-4 py-3">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="inline-flex items-center gap-1.5 text-xs font-medium whitespace-nowrap"
                      style={{ color: src.color }}
                      title={`source: ${t.source || 'unknown'}`}>
                      <span className="w-1.5 h-1.5 rounded-full" style={{ background: src.color }} />
                      {src.label}
                    </span>
                    <div className="font-medium truncate" style={{ maxWidth: '260px' }}>{t.name || t.traceId}</div>
                    {t.agentName && (
                      <span className="text-xs px-2 py-0.5 rounded-md whitespace-nowrap"
                        style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
                        {t.agentName}
                      </span>
                    )}
                  </div>
                  {preview && (
                    <div className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-secondary)', maxWidth: '320px' }}>
                      {preview}
                    </div>
                  )}
                </td>
                <td className="px-4 py-3">
                  {model !== '-' ? (
                    <span className="text-xs px-2 py-0.5 rounded-full"
                      style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                      {model}
                    </span>
                  ) : <span style={{ color: 'var(--color-text-secondary)' }}>-</span>}
                </td>
                <td className="px-4 py-3"><StatusBadge status={t.status} /></td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatDuration(t.durationMs)}</td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatTokens(t)}</td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(t.startedAt || t.createdAt)}</td>
              </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between mt-4">
        <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          Showing {traces.length > 0 ? offset + 1 : 0}-{offset + traces.length}
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
      </>
      )}
    </div>
    {selectedTraceId && (
      <TraceDetailPanel traceId={selectedTraceId} onClose={() => setSelectedTraceId(null)} />
    )}
    </div>
  );
}
