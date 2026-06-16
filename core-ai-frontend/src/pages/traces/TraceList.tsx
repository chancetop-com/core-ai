import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Clock, Database, DollarSign, Filter, MessageCircle, Search, UserCircle, X, Zap } from 'lucide-react';
import { api } from '../../api/client';
import type { SessionSummary, Trace, TraceFacet, TraceFilter } from '../../api/client';
import { useAuth } from '../../api/auth';
import StatusBadge from '../../components/StatusBadge';
import TraceDetailPanel from './TraceDetailPanel';
import { sourceColors, typeColors } from './colors';
import {
  extractTracePreview,
  formatCostUsd,
  formatDuration,
  formatRelativeTime,
  formatTokenCount,
  formatTokenPair,
  resolveTraceSource,
  resolveTraceType,
} from './traceViewModel';

const PAGE_SIZES = [20, 50, 100];
const SEARCH_DEBOUNCE_MS = 300;
const DEFAULT_RANGE = '1h';

const TRACE_TYPE_TABS: { key: string; label: string }[] = [
  { key: '', label: 'All' },
  { key: 'agent', label: 'Agent' },
  { key: 'llm_call', label: 'LLM Call' },
  { key: 'external', label: 'External' },
];

// Source dropdown is intentionally trimmed to entry-point sources; llm_*/external are expressed via type tab
const SOURCE_OPTIONS: { key: string; label: string }[] = [
  { key: '', label: 'All sources' },
  { key: 'chat', label: 'Chat' },
  { key: 'a2a', label: 'A2A' },
  { key: 'api', label: 'API' },
  { key: 'scheduled', label: 'Scheduled' },
  { key: 'workflow', label: 'Workflow' },
  { key: 'cli', label: 'CLI' },
];

const STATUS_SEGMENTS: { key: string; label: string; dotColor?: string }[] = [
  { key: '', label: 'All' },
  { key: 'COMPLETED', label: 'Completed', dotColor: 'var(--color-success, #16a34a)' },
  { key: 'CANCELLED', label: 'Cancelled', dotColor: '#b45309' },
  { key: 'ERROR', label: 'Errors', dotColor: 'var(--color-danger, #dc2626)' },
  { key: 'RUNNING', label: 'Running', dotColor: 'var(--color-primary, #4f46e5)' },
];

const TIME_PRESETS: { key: string; label: string }[] = [
  { key: '15m', label: 'Last 15 minutes' },
  { key: '1h', label: 'Last 1 hour' },
  { key: '24h', label: 'Last 24 hours' },
  { key: '7d', label: 'Last 7 days' },
  { key: '30d', label: 'Last 30 days' },
  { key: 'custom', label: 'Custom range...' },
  { key: '', label: 'All time' },
];

const EMPTY_FILTERS: TraceFilter = {
  q: '',
  name: '',
  type: '',
  source: '',
  agentName: '',
  model: '',
  status: '',
  sessionId: '',
  userId: '',
  range: DEFAULT_RANGE,
  startFrom: '',
  startTo: '',
};

// Matches RFC-4122 UUID with or without dashes, plus long hex (32-char trace IDs)
const UUID_RE = /^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$/;
const LONG_HEX_RE = /^[0-9a-fA-F]{16,}$/;
// Short hex prefix — the UI shows 8-char session prefixes, so users naturally search by them
const HEX_PREFIX_RE = /^[0-9a-fA-F]{6,}$/;

function idMatchKind(value: string): 'full' | 'prefix' | 'none' {
  if (UUID_RE.test(value) || LONG_HEX_RE.test(value)) return 'full';
  if (HEX_PREFIX_RE.test(value)) return 'prefix';
  return 'none';
}

interface TraceListState {
  requestKey: string;
  traces: Trace[];
  total: number;
}

export default function TraceList() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const { user } = useAuth();
  const isAdmin = user?.role === 'admin';
  const [result, setResult] = useState<TraceListState>({ requestKey: '', traces: [], total: 0 });
  const [offset, setOffset] = useState(0);
  const [limit, setLimit] = useState(PAGE_SIZES[0]);
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(() => hasAdvancedFilters(scopeFiltersForRole(readFilters(searchParams), isAdmin), isAdmin));
  const [filters, setFilters] = useState<TraceFilter>(() => scopeFiltersForRole(readFilters(searchParams), isAdmin));
  const [searchInput, setSearchInput] = useState(() => filters.q || '');
  const lastPushedQ = useRef(filters.q || '');
  const [modelFacets, setModelFacets] = useState<TraceFacet[]>([]);
  const [agentFacets, setAgentFacets] = useState<TraceFacet[]>([]);
  const [sessionSummary, setSessionSummary] = useState<SessionSummary | null>(null);

  const activeFilterCount = countActiveFilters(filters);
  const advancedFilterCount = countAdvancedFilters(filters, isAdmin);
  const requestKey = JSON.stringify({ offset, limit, filters: normalizeFilters(filters) });

  useEffect(() => {
    let cancelled = false;
    api.traces.list(offset, limit, cleanFilters(filters))
      .then(response => {
        if (!cancelled) setResult({ requestKey, traces: response.traces, total: response.total });
      })
      .catch(error => {
        console.warn('load traces failed', error);
        // -1 = unknown total: keeps the pagination in prev/next mode instead of claiming "No results"
        if (!cancelled) setResult({ requestKey, traces: [], total: -1 });
      });

    return () => {
      cancelled = true;
    };
  }, [offset, limit, filters, requestKey]);

  // When the list is pinned to one session, show an aggregate summary bar — the page doubles as a session detail view
  const sessionFilterId = filters.sessionId || '';
  useEffect(() => {
    if (!sessionFilterId) {
      setSessionSummary(null);
      return undefined;
    }
    let cancelled = false;
    api.traces.sessionSummary(sessionFilterId)
      .then(summary => { if (!cancelled) setSessionSummary(summary); })
      .catch(() => { if (!cancelled) setSessionSummary(null); });
    return () => { cancelled = true; };
  }, [sessionFilterId]);

  // Facet contexts deliberately drop the field being queried, so the dropdown shows all options under the current peer filters
  const facetContext = useMemo(() => cleanFilters(filters) || {}, [filters]);
  useEffect(() => {
    let cancelled = false;
    const modelContext = { ...facetContext };
    delete modelContext.model;
    api.traces.facets('model', modelContext)
      .then(rows => { if (!cancelled) setModelFacets(rows); })
      .catch(() => { if (!cancelled) setModelFacets([]); });
    const agentContext = { ...facetContext };
    delete agentContext.agentName;
    api.traces.facets('agentName', agentContext)
      .then(rows => { if (!cancelled) setAgentFacets(rows); })
      .catch(() => { if (!cancelled) setAgentFacets([]); });
    return () => { cancelled = true; };
  }, [facetContext]);

  const loading = result.requestKey !== requestKey;
  const traces = loading ? [] : result.traces;
  // Keep the last known total while a page loads so the pagination bar doesn't collapse between pages
  const total = result.total;

  // Merge from a ref, not the render closure: the debounce timer may fire after other filters
  // changed, and merging into a stale snapshot would silently revert those changes
  const filtersRef = useRef(filters);
  filtersRef.current = filters;
  const updateFilter = (patch: Partial<TraceFilter>) => {
    const next = normalizeFilters(scopeFiltersForRole({ ...filtersRef.current, ...patch }, isAdmin));
    setFilters(next);
    setOffset(0);
    syncSearchParams(next, setSearchParams);
  };

  const clearAllFilters = () => {
    // Reset wipes defaults too — leaves time range as "All time" so the user sees everything
    const next = { ...EMPTY_FILTERS, range: '' };
    setFilters(next);
    setSearchInput('');
    setOffset(0);
    setSearchParams({});
  };

  // Keep the input responsive and only push the query into filters after a short pause
  useEffect(() => {
    if (searchInput === (filters.q || '')) return undefined;
    const timer = setTimeout(() => {
      lastPushedQ.current = searchInput;
      updateFilter({ q: searchInput });
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchInput]);

  // Sync the input only when q changes from outside the box (chip clear, reset) — not from our own debounce push
  useEffect(() => {
    if ((filters.q || '') === lastPushedQ.current) return;
    lastPushedQ.current = filters.q || '';
    setSearchInput(filters.q || '');
  }, [filters.q]);

  const selectedKey = selectedTraceId || '';
  const searchKind = searchInput ? idMatchKind(searchInput.trim()) : 'none';
  const searchHint = searchInput
    ? (searchKind === 'full' ? `Detected ID → ${isAdmin ? 'session/user/trace' : 'session/trace'} · all time`
      : searchKind === 'prefix' ? 'Detected ID prefix → session/trace · all time'
      : 'name / agent')
    : '';

  const chips = activeChips(filters);
  const isCustomRange = filters.range === 'custom';

  return (
    <div className="flex h-full min-h-0">
      <div className="p-6 flex-1 min-w-0 overflow-auto">
        <div className="mb-5">
          <h1 className="text-2xl font-semibold">Traces</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Execution records across agents, LLM calls, and external services
          </p>
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-1 border-b" style={{ borderColor: 'var(--color-border)' }}>
          {TRACE_TYPE_TABS.map(tab => {
            const active = (filters.type || '') === tab.key;
            return (
              <button key={tab.key} onClick={() => updateFilter({ type: tab.key })}
                className="px-3 py-2 text-sm cursor-pointer border-b-2 -mb-[2px] transition-colors"
                style={{
                  borderColor: active ? 'var(--color-primary)' : 'transparent',
                  color: active ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                  fontWeight: active ? 600 : 400,
                }}>
                {tab.label}
              </button>
            );
          })}
        </div>

        <div className="mb-3 rounded-lg border p-3"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          {/* Row 1: status segmented control + time range + reset */}
          <div className="flex flex-wrap items-center gap-3">
            <Segmented options={STATUS_SEGMENTS} value={filters.status || ''} onChange={value => updateFilter({ status: value })} />

            <TimeRangeControl
              range={filters.range || ''}
              startFrom={filters.startFrom || ''}
              startTo={filters.startTo || ''}
              onChange={patch => updateFilter(patch)}
            />

            <div className="flex-1" />

            {activeFilterCount > 0 && (
              <button onClick={clearAllFilters}
                className="px-3 py-1.5 rounded-md border text-sm flex items-center gap-1.5 cursor-pointer"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
                <X size={14} /> Reset
              </button>
            )}
          </div>

          {/* Row 2: smart search */}
          <div className="mt-3 relative">
            <Search size={14} className="absolute left-3 top-2.5" style={{ color: 'var(--color-text-secondary)' }} />
            <input
              value={searchInput}
              onChange={event => setSearchInput(event.target.value)}
              placeholder={isAdmin ? 'Search trace name, agent, or paste a session/user ID...' : 'Search trace name, agent, or paste a session/trace ID...'}
              className="w-full pl-9 pr-32 py-2 rounded-md border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              autoComplete="off"
            />
            {searchHint && (
              <span className="absolute right-2 top-2 text-[11px] px-2 py-0.5 rounded border pointer-events-none"
                style={{
                  borderColor: searchKind !== 'none' ? 'var(--color-primary)' : 'var(--color-border)',
                  color: searchKind !== 'none' ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                  background: 'var(--color-bg-secondary)',
                }}>
                {searchHint}
              </span>
            )}
          </div>

          {/* Row 3: secondary filters */}
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <SelectFilter value={filters.source || ''} onChange={value => updateFilter({ source: value })}>
              {SOURCE_OPTIONS.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
            </SelectFilter>

            <FacetCombobox label="Model" placeholder="All models" value={filters.model || ''}
              facets={modelFacets} onChange={value => updateFilter({ model: value })} />

            <FacetCombobox label="Agent" placeholder="All agents" value={filters.agentName || ''}
              facets={agentFacets} onChange={value => updateFilter({ agentName: value })} />

            <button onClick={() => setShowAdvanced(prev => !prev)}
              className="px-3 py-2 rounded-md border text-sm flex items-center gap-1.5 cursor-pointer"
              style={{
                borderColor: advancedFilterCount > 0 ? 'var(--color-primary)' : 'var(--color-border)',
                color: advancedFilterCount > 0 ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                background: advancedFilterCount > 0 ? 'var(--color-primary-bg)' : 'transparent',
              }}>
              <Filter size={14} /> More
              {advancedFilterCount > 0 && (
                <span className="px-1.5 py-0.5 rounded-full text-[10px]"
                  style={{ background: 'var(--color-primary)', color: 'white' }}>
                  {advancedFilterCount}
                </span>
              )}
            </button>
          </div>

          {showAdvanced && (
            <div className="mt-3 pt-3 border-t grid gap-2 md:grid-cols-2 xl:grid-cols-3"
              style={{ borderColor: 'var(--color-border)' }}>
              <input
                value={filters.sessionId || ''}
                onChange={event => updateFilter({ sessionId: event.target.value })}
                placeholder="Session ID (exact)"
                className="px-3 py-2 rounded-md border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
              {isAdmin && (
                <input
                  value={filters.userId || ''}
                  onChange={event => updateFilter({ userId: event.target.value })}
                  placeholder="User ID (exact)"
                  className="px-3 py-2 rounded-md border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
                />
              )}
              <input
                value={filters.name || ''}
                onChange={event => updateFilter({ name: event.target.value })}
                placeholder="Name regex (advanced)"
                className="px-3 py-2 rounded-md border text-sm font-mono"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
              {isCustomRange && (
                <>
                  <input type="datetime-local"
                    value={toDateTimeLocalValue(filters.startFrom)}
                    onChange={event => updateFilter({ startFrom: event.target.value ? new Date(event.target.value).toISOString() : '' })}
                    className="px-3 py-2 rounded-md border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
                  />
                  <input type="datetime-local"
                    value={toDateTimeLocalValue(filters.startTo)}
                    onChange={event => updateFilter({ startTo: event.target.value ? new Date(event.target.value).toISOString() : '' })}
                    className="px-3 py-2 rounded-md border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
                  />
                </>
              )}
            </div>
          )}
        </div>

        {chips.length > 0 && (
          <div className="mb-3 flex flex-wrap gap-1.5">
            {chips.map(chip => (
              <button key={chip.key} onClick={() => updateFilter(chip.clearPatch)}
                className="inline-flex items-center gap-1.5 px-2 py-1 rounded-full text-xs cursor-pointer"
                style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
                <span style={{ opacity: 0.7 }}>{chip.label}:</span>
                <span className="font-medium">{chip.value}</span>
                <X size={12} />
              </button>
            ))}
          </div>
        )}

        {sessionFilterId && sessionSummary && (
          <SessionSummaryBar
            summary={sessionSummary}
            onOpenChat={() => navigate(`/chat?sessionId=${encodeURIComponent(sessionFilterId)}`)}
            onClear={() => updateFilter({ sessionId: '' })}
          />
        )}

        <div className="rounded-lg border overflow-hidden"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          {loading ? (
            <EmptyList>Loading...</EmptyList>
          ) : traces.length === 0 ? (
            <EmptyList>{activeFilterCount > 0 ? 'No traces match the current filters' : 'No traces yet'}</EmptyList>
          ) : (
            <div>
              {traces.map(trace => {
                const traceId = trace.traceId || trace.id;
                return (
                  <TraceRow
                    key={trace.id}
                    trace={trace}
                    selected={selectedKey === traceId}
                    onSelect={() => setSelectedTraceId(traceId)}
                    onOpenSession={() => navigate(`/chat?sessionId=${encodeURIComponent(trace.sessionId)}`)}
                    onFilterSession={() => updateFilter({ sessionId: trace.sessionId, q: '' })}
                  />
                );
              })}
            </div>
          )}
        </div>

        <Pagination
          offset={offset}
          limit={limit}
          total={total}
          shown={traces.length}
          loading={loading}
          onOffsetChange={setOffset}
          onLimitChange={nextLimit => { setLimit(nextLimit); setOffset(0); }}
        />
      </div>

      {selectedTraceId && (
        <TraceDetailPanel traceId={selectedTraceId} onClose={() => setSelectedTraceId(null)} />
      )}
    </div>
  );
}

function SessionSummaryBar({ summary, onOpenChat, onClear }: {
  summary: SessionSummary;
  onOpenChat: () => void;
  onClear: () => void;
}) {
  const metricStyle = { color: 'var(--color-text-secondary)' };
  return (
    <div className="mb-3 rounded-lg border px-4 py-3"
      style={{ borderColor: 'var(--color-primary)', background: 'var(--color-primary-bg)' }}>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <span className="inline-flex items-center gap-1.5 text-sm font-semibold" title={summary.session_id}>
          <MessageCircle size={14} style={{ color: 'var(--color-primary)' }} />
          Session {summary.session_id.slice(0, 8)}
        </span>
        <span className="text-sm" style={metricStyle}>{summary.trace_count} {summary.trace_count === 1 ? 'turn' : 'turns'}</span>
        <span className="inline-flex items-center gap-1 text-sm" style={metricStyle}>
          <Zap size={12} /> {formatTokenCount(summary.total_tokens)} tokens
        </span>
        <span className="inline-flex items-center gap-1 text-sm" style={metricStyle}>
          <Database size={12} /> {formatTokenCount(summary.total_cached_tokens)} cached
        </span>
        <span className="inline-flex items-center gap-1 text-sm" style={metricStyle}>
          <DollarSign size={12} /> {formatCostUsd(summary.total_cost_usd)}
        </span>
        <span className="inline-flex items-center gap-1 text-sm" style={metricStyle}>
          <Clock size={12} /> {formatDuration(summary.total_duration_ms)}
        </span>
        {summary.error_count > 0 && (
          <span className="text-sm font-medium" style={{ color: 'var(--color-danger, #dc2626)' }}>
            {summary.error_count} {summary.error_count === 1 ? 'error' : 'errors'}
          </span>
        )}
        <span className="text-sm" style={metricStyle}>
          {formatRelativeTime(summary.first_trace_at)} → {formatRelativeTime(summary.last_trace_at)}
        </span>
        <div className="flex-1" />
        <button onClick={onOpenChat}
          className="px-3 py-1 rounded-md text-sm font-medium cursor-pointer"
          style={{ background: 'var(--color-primary)', color: 'white' }}>
          Open Chat
        </button>
        <button onClick={onClear} title="Clear session filter"
          className="p-1 rounded-md cursor-pointer"
          style={{ color: 'var(--color-text-secondary)' }}>
          <X size={16} />
        </button>
      </div>
      {summary.first_request && (
        <div className="text-xs mt-1.5 truncate" style={metricStyle} title={summary.first_request}>
          {summary.first_request}
        </div>
      )}
    </div>
  );
}

// Numbered pagination: first/last always visible, a sliding window around the current page, ellipsis between
function pageItems(page: number, totalPages: number): (number | '...')[] {
  if (totalPages <= 7) return Array.from({ length: totalPages }, (_, i) => i + 1);
  const pages = new Set<number>([1, totalPages, page - 1, page, page + 1]);
  const sorted = [...pages].filter(p => p >= 1 && p <= totalPages).sort((a, b) => a - b);
  const items: (number | '...')[] = [];
  let prev = 0;
  for (const p of sorted) {
    if (prev && p - prev > 1) items.push('...');
    items.push(p);
    prev = p;
  }
  return items;
}

function Pagination({ offset, limit, total, shown, loading, onOffsetChange, onLimitChange }: {
  offset: number;
  limit: number;
  total: number;
  shown: number;
  loading: boolean;
  onOffsetChange: (offset: number) => void;
  onLimitChange: (limit: number) => void;
}) {
  const page = Math.floor(offset / limit) + 1;
  // total < 0 means the server could not count this filter combination — fall back to prev/next paging
  const hasTotal = total >= 0;
  const totalPages = Math.max(1, Math.ceil(Math.max(total, 0) / limit));
  const buttonStyle = { borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' };

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 mt-4">
      <div className="flex items-center gap-3 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
        <span>
          {loading ? 'Loading...'
            : !hasTotal ? `Showing ${shown > 0 ? offset + 1 : 0}-${offset + shown}`
            : total > 0 ? `Showing ${offset + 1}-${offset + shown} of ${total}`
            : 'No results'}
        </span>
        <label className="flex items-center gap-1.5">
          <select value={limit} onChange={event => onLimitChange(Number(event.target.value))}
            className="px-2 py-1 rounded-md border text-sm cursor-pointer"
            style={{ ...buttonStyle, background: 'var(--color-bg-tertiary)' }}>
            {PAGE_SIZES.map(size => <option key={size} value={size}>{size}</option>)}
          </select>
          per page
        </label>
      </div>
      <div className="flex items-center gap-1.5">
        <button onClick={() => onOffsetChange(Math.max(0, offset - limit))} disabled={offset === 0}
          className="px-2.5 py-1.5 rounded-md border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
          style={buttonStyle}>
          <ChevronLeft size={14} /> Prev
        </button>
        {hasTotal && pageItems(page, totalPages).map((item, index) => item === '...' ? (
          <span key={`gap-${index}`} className="px-1 text-sm" style={{ color: 'var(--color-text-secondary)' }}>...</span>
        ) : (
          <button key={item} onClick={() => onOffsetChange((item - 1) * limit)} disabled={item === page}
            className="min-w-[32px] px-2 py-1.5 rounded-md border text-sm cursor-pointer disabled:cursor-default"
            style={item === page
              ? { borderColor: 'var(--color-primary)', background: 'var(--color-primary)', color: 'white', fontWeight: 600 }
              : buttonStyle}>
            {item}
          </button>
        ))}
        <button onClick={() => onOffsetChange(offset + limit)}
          disabled={hasTotal ? page >= totalPages : shown < limit}
          className="px-2.5 py-1.5 rounded-md border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
          style={buttonStyle}>
          Next <ChevronRight size={14} />
        </button>
      </div>
    </div>
  );
}

function TraceRow({ trace, selected, onSelect, onOpenSession, onFilterSession }: {
  trace: Trace;
  selected: boolean;
  onSelect: () => void;
  onOpenSession: () => void;
  onFilterSession: () => void;
}) {
  const traceType = typeColors(resolveTraceType(trace));
  const source = sourceColors(resolveTraceSource(trace));
  // List responses carry a server-computed preview; fall back to client extraction for older servers
  const preview = trace.preview || extractTracePreview(trace);
  const model = trace.model || trace.metadata?.model || '';

  return (
    <div onClick={onSelect}
      className="border-t first:border-t-0 px-4 py-3 cursor-pointer transition-colors"
      style={{
        borderColor: 'var(--color-border)',
        background: selected ? 'var(--color-primary-bg)' : 'transparent',
        borderLeft: selected ? '3px solid var(--color-primary)' : '3px solid transparent',
      }}
      onMouseEnter={event => { if (!selected) event.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
      onMouseLeave={event => { if (!selected) event.currentTarget.style.background = 'transparent'; }}>
      <div className="flex items-start gap-4 min-w-0">
        <div className="w-20 shrink-0 pt-0.5">
          <SourceBadge label={source.label} color={source.color} />
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap min-w-0">
            <span className="text-xs px-2 py-0.5 rounded-md font-medium whitespace-nowrap"
              style={{ background: traceType.bg, color: traceType.text }}>
              {traceType.label}
            </span>
            <span className="font-medium truncate max-w-[340px]" title={trace.name || trace.traceId}>
              {trace.name || trace.traceId}
            </span>
            {trace.agentName && <NeutralChip>{trace.agentName}</NeutralChip>}
            {trace.userId && <AccountChip trace={trace} />}
            {trace.sessionId && (
              <span className="inline-flex items-center rounded-md overflow-hidden"
                style={{ background: 'var(--color-bg-tertiary)' }}>
                <button onClick={event => {
                  event.stopPropagation();
                  onOpenSession();
                }}
                  className="inline-flex items-center gap-1 px-2 py-0.5 text-xs cursor-pointer"
                  style={{ color: 'var(--color-primary)' }}
                  title={`Open chat session ${trace.sessionId}`}>
                  <MessageCircle size={11} /> {trace.sessionId.slice(0, 8)}
                </button>
                <button onClick={event => {
                  event.stopPropagation();
                  onFilterSession();
                }}
                  className="px-1.5 py-0.5 cursor-pointer border-l"
                  style={{ color: 'var(--color-text-secondary)', borderColor: 'var(--color-border)' }}
                  title="Filter traces by this session">
                  <Filter size={11} />
                </button>
              </span>
            )}
          </div>
          {preview && (
            <div className="text-xs mt-1 truncate max-w-[560px]" style={{ color: 'var(--color-text-secondary)' }}>
              {preview}
            </div>
          )}
        </div>

        <div className="shrink-0 min-w-[310px] flex flex-col items-end gap-1">
          <div className="flex items-center gap-2 flex-wrap justify-end">
            {model && <NeutralChip mono>{model}</NeutralChip>}
            <StatusBadge status={trace.status} />
          </div>
          <div className="flex flex-wrap items-center justify-end gap-x-3 gap-y-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            <span className="inline-flex items-center gap-1"><Clock size={12} /> {formatDuration(trace.durationMs)}</span>
            <span className="inline-flex items-center gap-1"><Zap size={12} /> {formatTokenPair(trace.inputTokens, trace.outputTokens)}</span>
            <span className="inline-flex items-center gap-1"><Database size={12} /> {formatTokenCount(trace.cachedTokens)} cached</span>
            <span className="inline-flex items-center gap-1"><DollarSign size={12} /> {formatCostUsd(trace.costUsd)}</span>
            <span>{formatRelativeTime(trace.startedAt || trace.createdAt)}</span>
          </div>
        </div>
      </div>
    </div>
  );
}

function AccountChip({ trace }: { trace: Trace }) {
  const label = trace.account?.name || trace.account?.email || trace.userId;
  const title = [
    trace.account?.name,
    trace.account?.email,
    trace.userId,
    trace.account?.role,
  ].filter(Boolean).join(' · ');
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs max-w-[220px]"
      style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}
      title={title}>
      <UserCircle size={11} />
      <span className="truncate">{label}</span>
    </span>
  );
}

function SelectFilter({ value, onChange, children }: {
  value: string;
  onChange: (value: string) => void;
  children: ReactNode;
}) {
  return (
    <select
      value={value}
      onChange={event => onChange(event.target.value)}
      className="px-3 py-2 rounded-md border text-sm"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
      {children}
    </select>
  );
}

function SourceBadge({ label, color }: { label: string; color: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium whitespace-nowrap"
      style={{ color }}>
      <span className="w-1.5 h-1.5 rounded-full" style={{ background: color }} />
      {label}
    </span>
  );
}

function NeutralChip({ children, mono }: { children: ReactNode; mono?: boolean }) {
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs ${mono ? 'font-mono' : ''}`}
      style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
      {children}
    </span>
  );
}

function EmptyList({ children }: { children: ReactNode }) {
  return (
    <div className="px-4 py-12 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
      {children}
    </div>
  );
}

function readFilters(params: URLSearchParams): TraceFilter {
  // Range defaults to "1h" when nothing is in the URL; absent range with explicit from/to means custom
  const hasRangeParam = params.has('range');
  const hasFrom = params.has('startFrom') || params.has('startTo');
  let range = params.get('range') || '';
  if (!hasRangeParam && !hasFrom) range = DEFAULT_RANGE;
  if (!hasRangeParam && hasFrom) range = 'custom';
  return normalizeFilters({
    q: params.get('q') || '',
    name: params.get('name') || '',
    type: params.get('type') || '',
    source: params.get('source') || '',
    agentName: params.get('agentName') || '',
    model: params.get('model') || '',
    status: params.get('status') || '',
    sessionId: params.get('sessionId') || '',
    userId: params.get('userId') || '',
    range,
    startFrom: params.get('startFrom') || '',
    startTo: params.get('startTo') || '',
  });
}

function normalizeFilters(filters: TraceFilter): TraceFilter {
  return {
    q: filters.q || '',
    name: filters.name || '',
    type: filters.type || '',
    source: filters.source || '',
    agentName: filters.agentName || '',
    model: filters.model || '',
    status: filters.status || '',
    sessionId: filters.sessionId || '',
    userId: filters.userId || '',
    range: filters.range ?? '',
    startFrom: filters.startFrom || '',
    startTo: filters.startTo || '',
  };
}

function scopeFiltersForRole(filters: TraceFilter, isAdmin: boolean): TraceFilter {
  if (isAdmin) return filters;
  return { ...filters, userId: '' };
}

function cleanFilters(filters: TraceFilter): TraceFilter | undefined {
  // Pasting an ID (or filtering by session) means "find this exact thing" —
  // drop the time window so old sessions aren't silently filtered out
  const idDetected = (filters.q ? idMatchKind(filters.q.trim()) !== 'none' : false) || Boolean(filters.sessionId);
  const cleaned: Record<string, string> = {};
  (Object.entries(filters) as [keyof TraceFilter, string | undefined][]).forEach(([key, value]) => {
    if (!value) return;
    // Custom range relies on startFrom/startTo; don't forward the synthetic "custom" marker to the backend
    if (key === 'range' && value === 'custom') return;
    if (idDetected && (key === 'range' || key === 'startFrom' || key === 'startTo')) return;
    cleaned[key] = value;
  });
  return Object.keys(cleaned).length > 0 ? (cleaned as TraceFilter) : undefined;
}

function syncSearchParams(filters: TraceFilter, setSearchParams: (params: URLSearchParams) => void) {
  const params = new URLSearchParams();
  (Object.entries(filters) as [keyof TraceFilter, string | undefined][]).forEach(([key, value]) => {
    if (!value) return;
    // Hide the default range from URL — keeps shareable links clean and matches "no chip for default" behavior
    if (key === 'range' && value === DEFAULT_RANGE) return;
    params.set(key, value);
  });
  setSearchParams(params);
}

// Count of filters the user has explicitly changed away from defaults — drives "Reset" visibility
function countActiveFilters(filters: TraceFilter): number {
  let count = 0;
  if (filters.q) count++;
  if (filters.name) count++;
  if (filters.type) count++;
  if (filters.source) count++;
  if (filters.agentName) count++;
  if (filters.model) count++;
  if (filters.status) count++;
  if (filters.sessionId) count++;
  if (filters.userId) count++;
  if (filters.range && filters.range !== DEFAULT_RANGE) count++;
  return count;
}

function countAdvancedFilters(filters: TraceFilter, isAdmin: boolean): number {
  return [filters.sessionId, isAdmin ? filters.userId : '', filters.name].filter(Boolean).length;
}

function hasAdvancedFilters(filters: TraceFilter, isAdmin: boolean): boolean {
  return countAdvancedFilters(filters, isAdmin) > 0;
}

function toDateTimeLocalValue(value?: string): string {
  if (!value) return '';
  return value.slice(0, 16);
}

interface ChipDescriptor {
  key: string;
  label: string;
  value: string;
  clearPatch: Partial<TraceFilter>;
}

// Build chips for filters the user has actively set. Default-value filters (e.g. range=1h, status=All) are hidden.
function activeChips(filters: TraceFilter): ChipDescriptor[] {
  const chips: ChipDescriptor[] = [];
  if (filters.q) chips.push({ key: 'q', label: 'Search', value: filters.q, clearPatch: { q: '' } });
  if (filters.status) {
    const seg = STATUS_SEGMENTS.find(s => s.key === filters.status);
    chips.push({ key: 'status', label: 'Status', value: seg?.label || filters.status, clearPatch: { status: '' } });
  }
  if (filters.range && filters.range !== DEFAULT_RANGE) {
    const preset = TIME_PRESETS.find(p => p.key === filters.range);
    chips.push({ key: 'range', label: 'Time', value: preset?.label || filters.range, clearPatch: { range: DEFAULT_RANGE, startFrom: '', startTo: '' } });
  }
  if (filters.source) {
    const src = SOURCE_OPTIONS.find(s => s.key === filters.source);
    chips.push({ key: 'source', label: 'Source', value: src?.label || filters.source, clearPatch: { source: '' } });
  }
  if (filters.model) chips.push({ key: 'model', label: 'Model', value: filters.model, clearPatch: { model: '' } });
  if (filters.agentName) chips.push({ key: 'agent', label: 'Agent', value: filters.agentName, clearPatch: { agentName: '' } });
  if (filters.sessionId) chips.push({ key: 'sessionId', label: 'Session', value: `${filters.sessionId.slice(0, 8)}...`, clearPatch: { sessionId: '' } });
  if (filters.userId) chips.push({ key: 'userId', label: 'User', value: filters.userId, clearPatch: { userId: '' } });
  if (filters.name) chips.push({ key: 'name', label: 'Name regex', value: filters.name, clearPatch: { name: '' } });
  return chips;
}

function Segmented({ options, value, onChange }: {
  options: { key: string; label: string; dotColor?: string }[];
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div className="inline-flex items-center gap-0.5 p-0.5 rounded-md"
      style={{ background: 'var(--color-bg-tertiary)' }}>
      {options.map(option => {
        const active = option.key === value;
        return (
          <button key={option.key} onClick={() => onChange(option.key)}
            className="px-3 py-1 text-xs rounded cursor-pointer transition-colors inline-flex items-center gap-1.5"
            style={{
              background: active ? 'var(--color-bg-secondary)' : 'transparent',
              color: active ? 'var(--color-text)' : 'var(--color-text-secondary)',
              fontWeight: active ? 600 : 500,
              boxShadow: active ? '0 1px 2px rgba(0,0,0,0.08)' : 'none',
            }}>
            {option.dotColor && <span className="w-1.5 h-1.5 rounded-full" style={{ background: option.dotColor }} />}
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

function TimeRangeControl({ range, startFrom, startTo, onChange }: {
  range: string;
  startFrom: string;
  startTo: string;
  onChange: (patch: Partial<TraceFilter>) => void;
}) {
  const handleChange = (value: string) => {
    if (value === 'custom') {
      onChange({ range: 'custom' });
    } else {
      // Preset or "All time" — drop manual datetime values so they don't bleed through
      onChange({ range: value, startFrom: '', startTo: '' });
    }
  };
  return (
    <div className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm"
      style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
      <Clock size={14} />
      <select value={range}
        onChange={event => handleChange(event.target.value)}
        className="bg-transparent border-0 outline-none cursor-pointer"
        style={{ color: 'var(--color-text)', fontWeight: 500 }}>
        {TIME_PRESETS.map(preset => <option key={preset.key} value={preset.key}>{preset.label}</option>)}
      </select>
      {range === 'custom' && (
        <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          {startFrom || startTo ? `${formatRangeLabel(startFrom)} → ${formatRangeLabel(startTo)}` : 'set below'}
        </span>
      )}
    </div>
  );
}

function formatRangeLabel(iso: string): string {
  if (!iso) return '...';
  try {
    const d = new Date(iso);
    return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  } catch {
    return iso.slice(0, 16);
  }
}

function FacetCombobox({ label, placeholder, value, facets, onChange }: {
  label: string;
  placeholder: string;
  value: string;
  facets: TraceFacet[];
  onChange: (value: string) => void;
}) {
  return (
    <div className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md border text-sm"
      style={{
        borderColor: value ? 'var(--color-primary)' : 'var(--color-border)',
        background: value ? 'var(--color-primary-bg)' : 'var(--color-bg-tertiary)',
      }}>
      <span style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
      <select value={value} onChange={event => onChange(event.target.value)}
        className="bg-transparent border-0 outline-none cursor-pointer"
        style={{ color: value ? 'var(--color-primary)' : 'var(--color-text)', fontWeight: value ? 600 : 400 }}>
        <option value="">{placeholder}</option>
        {value && !facets.some(f => f.value === value) && <option value={value}>{value}</option>}
        {facets.map(facet => (
          <option key={facet.value} value={facet.value}>
            {facet.value} ({facet.count})
          </option>
        ))}
      </select>
    </div>
  );
}
