import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Clock, Database, DollarSign, Filter, MessageCircle, Search, X, Zap } from 'lucide-react';
import { api } from '../../api/client';
import type { Trace, TraceFilter } from '../../api/client';
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

const LIMIT = 20;

const TRACE_TYPE_TABS: { key: string; label: string }[] = [
  { key: '', label: 'All' },
  { key: 'agent', label: 'Agent' },
  { key: 'llm_call', label: 'LLM Call' },
  { key: 'external', label: 'External' },
];

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

const STATUS_OPTIONS = [
  { key: '', label: 'All statuses' },
  { key: 'RUNNING', label: 'Running' },
  { key: 'COMPLETED', label: 'Completed' },
  { key: 'ERROR', label: 'Error' },
];

const EMPTY_FILTERS: TraceFilter = {
  name: '',
  type: '',
  source: '',
  agentName: '',
  model: '',
  status: '',
  sessionId: '',
  userId: '',
  startFrom: '',
  startTo: '',
};

interface TraceListState {
  requestKey: string;
  traces: Trace[];
}

export default function TraceList() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const [result, setResult] = useState<TraceListState>({ requestKey: '', traces: [] });
  const [offset, setOffset] = useState(0);
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(() => hasAdvancedFilters(readFilters(searchParams)));
  const [draftFilters, setDraftFilters] = useState<TraceFilter>(() => readFilters(searchParams));
  const [appliedFilters, setAppliedFilters] = useState<TraceFilter>(() => readFilters(searchParams));

  const activeFilterCount = countFilters(appliedFilters);
  const advancedFilterCount = countAdvancedFilters(appliedFilters);
  const requestKey = JSON.stringify({ offset, filters: normalizeFilters(appliedFilters) });

  useEffect(() => {
    let cancelled = false;
    api.traces.list(offset, LIMIT, cleanFilters(appliedFilters))
      .then(nextTraces => {
        if (!cancelled) setResult({ requestKey, traces: nextTraces });
      })
      .catch(error => {
        console.warn('load traces failed', error);
        if (!cancelled) setResult({ requestKey, traces: [] });
      });

    return () => {
      cancelled = true;
    };
  }, [offset, appliedFilters, requestKey]);

  const loading = result.requestKey !== requestKey;
  const traces = loading ? [] : result.traces;

  const applyFilters = (nextFilters = draftFilters) => {
    const next = normalizeFilters(nextFilters);
    setDraftFilters(next);
    setAppliedFilters(next);
    setOffset(0);
    syncSearchParams(next, setSearchParams);
  };

  const clearFilters = () => {
    const next = { ...EMPTY_FILTERS };
    setDraftFilters(next);
    setAppliedFilters(next);
    setOffset(0);
    setSearchParams({});
  };

  const updateDraft = (key: keyof TraceFilter, value: string) => {
    setDraftFilters(prev => ({ ...prev, [key]: value }));
  };

  const selectType = (type: string) => {
    applyFilters({ ...draftFilters, type });
  };

  const selectedKey = selectedTraceId || '';

  return (
    <div className="flex h-full min-h-0">
      <div className="p-6 flex-1 min-w-0 overflow-auto">
        <div className="mb-5 flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold">Traces</h1>
            <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
              Execution records across agents, LLM calls, APIs, and scheduled runs
            </p>
          </div>
          {activeFilterCount > 0 && (
            <button onClick={clearFilters}
              className="px-3 py-1.5 rounded-md border text-sm flex items-center gap-1.5 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <X size={14} /> Clear filters
            </button>
          )}
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-1 border-b" style={{ borderColor: 'var(--color-border)' }}>
          {TRACE_TYPE_TABS.map(tab => {
            const active = (appliedFilters.type || '') === tab.key;
            return (
              <button key={tab.key} onClick={() => selectType(tab.key)}
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

        <div className="mb-4 rounded-lg border p-3"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          <div className="flex flex-wrap items-end gap-2">
            <div className="relative w-full max-w-[320px]">
              <Search size={14} className="absolute left-2.5 top-2.5" style={{ color: 'var(--color-text-secondary)' }} />
              <input
                value={draftFilters.name || ''}
                onChange={event => updateDraft('name', event.target.value)}
                onKeyDown={event => event.key === 'Enter' && applyFilters()}
                placeholder="Search trace name or operation"
                className="w-full pl-8 pr-3 py-2 rounded-md border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
            </div>

            <SelectFilter value={draftFilters.source || ''} onChange={value => updateDraft('source', value)}>
              {SOURCE_OPTIONS.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
            </SelectFilter>

            <SelectFilter value={draftFilters.status || ''} onChange={value => updateDraft('status', value)}>
              {STATUS_OPTIONS.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
            </SelectFilter>

            <input
              value={draftFilters.model || ''}
              onChange={event => updateDraft('model', event.target.value)}
              onKeyDown={event => event.key === 'Enter' && applyFilters()}
              placeholder="Model"
              className="w-40 px-3 py-2 rounded-md border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
            />

            <button onClick={() => applyFilters()}
              className="px-3 py-2 rounded-md text-sm font-medium text-white cursor-pointer"
              style={{ background: 'var(--color-primary)' }}>
              Apply
            </button>

            <button onClick={() => setShowAdvanced(prev => !prev)}
              className="px-3 py-2 rounded-md border text-sm flex items-center gap-1.5 cursor-pointer"
              style={{
                borderColor: advancedFilterCount > 0 ? 'var(--color-primary)' : 'var(--color-border)',
                color: advancedFilterCount > 0 ? 'var(--color-primary)' : 'var(--color-text-secondary)',
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
            <div className="mt-3 pt-3 border-t grid gap-2 md:grid-cols-2 xl:grid-cols-5"
              style={{ borderColor: 'var(--color-border)' }}>
              <input
                value={draftFilters.agentName || ''}
                onChange={event => updateDraft('agentName', event.target.value)}
                onKeyDown={event => event.key === 'Enter' && applyFilters()}
                placeholder="Agent name"
                className="px-3 py-2 rounded-md border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
              <input
                value={draftFilters.sessionId || ''}
                onChange={event => updateDraft('sessionId', event.target.value)}
                onKeyDown={event => event.key === 'Enter' && applyFilters()}
                placeholder="Session ID"
                className="px-3 py-2 rounded-md border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
              <input
                value={draftFilters.userId || ''}
                onChange={event => updateDraft('userId', event.target.value)}
                onKeyDown={event => event.key === 'Enter' && applyFilters()}
                placeholder="User ID"
                className="px-3 py-2 rounded-md border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
              <input
                type="datetime-local"
                value={toDateTimeLocalValue(draftFilters.startFrom)}
                onChange={event => updateDraft('startFrom', event.target.value ? new Date(event.target.value).toISOString() : '')}
                className="px-3 py-2 rounded-md border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
              <input
                type="datetime-local"
                value={toDateTimeLocalValue(draftFilters.startTo)}
                onChange={event => updateDraft('startTo', event.target.value ? new Date(event.target.value).toISOString() : '')}
                className="px-3 py-2 rounded-md border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}
              />
            </div>
          )}
        </div>

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
                  />
                );
              })}
            </div>
          )}
        </div>

        <div className="flex items-center justify-between mt-4">
          <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            Showing {traces.length > 0 ? offset + 1 : 0}-{offset + traces.length}
          </span>
          <div className="flex gap-2">
            <button onClick={() => setOffset(Math.max(0, offset - LIMIT))} disabled={offset === 0}
              className="px-3 py-1.5 rounded-md border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button onClick={() => setOffset(offset + LIMIT)} disabled={traces.length < LIMIT}
              className="px-3 py-1.5 rounded-md border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      </div>

      {selectedTraceId && (
        <TraceDetailPanel traceId={selectedTraceId} onClose={() => setSelectedTraceId(null)} />
      )}
    </div>
  );
}

function TraceRow({ trace, selected, onSelect, onOpenSession }: {
  trace: Trace;
  selected: boolean;
  onSelect: () => void;
  onOpenSession: () => void;
}) {
  const traceType = typeColors(resolveTraceType(trace));
  const source = sourceColors(resolveTraceSource(trace));
  const preview = extractTracePreview(trace);
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
            {trace.sessionId && (
              <button onClick={event => {
                event.stopPropagation();
                onOpenSession();
              }}
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs cursor-pointer"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-primary)' }}
                title={trace.sessionId}>
                <MessageCircle size={11} /> {trace.sessionId.slice(0, 8)}
              </button>
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
  return normalizeFilters({
    name: params.get('name') || '',
    type: params.get('type') || '',
    source: params.get('source') || '',
    agentName: params.get('agentName') || '',
    model: params.get('model') || '',
    status: params.get('status') || '',
    sessionId: params.get('sessionId') || '',
    userId: params.get('userId') || '',
    startFrom: params.get('startFrom') || '',
    startTo: params.get('startTo') || '',
  });
}

function normalizeFilters(filters: TraceFilter): TraceFilter {
  return {
    name: filters.name || '',
    type: filters.type || '',
    source: filters.source || '',
    agentName: filters.agentName || '',
    model: filters.model || '',
    status: filters.status || '',
    sessionId: filters.sessionId || '',
    userId: filters.userId || '',
    startFrom: filters.startFrom || '',
    startTo: filters.startTo || '',
  };
}

function cleanFilters(filters: TraceFilter): TraceFilter | undefined {
  const cleaned: TraceFilter = {};
  Object.entries(filters).forEach(([key, value]) => {
    if (value) (cleaned as Record<string, string>)[key] = value;
  });
  return Object.keys(cleaned).length > 0 ? cleaned : undefined;
}

function syncSearchParams(filters: TraceFilter, setSearchParams: (params: URLSearchParams) => void) {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value) params.set(key, value);
  });
  setSearchParams(params);
}

function countFilters(filters: TraceFilter): number {
  return Object.values(filters).filter(Boolean).length;
}

function countAdvancedFilters(filters: TraceFilter): number {
  return [filters.agentName, filters.sessionId, filters.userId, filters.startFrom, filters.startTo].filter(Boolean).length;
}

function hasAdvancedFilters(filters: TraceFilter): boolean {
  return countAdvancedFilters(filters) > 0;
}

function toDateTimeLocalValue(value?: string): string {
  if (!value) return '';
  return value.slice(0, 16);
}
