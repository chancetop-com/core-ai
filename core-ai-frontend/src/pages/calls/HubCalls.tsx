import { useEffect, useState, type ComponentType, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Brain, CheckCircle2, ChevronDown, ChevronLeft, ChevronRight, Copy, HelpCircle, Info, Network, RotateCw, Wrench, XCircle } from 'lucide-react';
import { adminApi, api, type HubCall, type HubCallFilter, type UserStatus } from '../../api/client';
import { useAuth } from '../../api/auth';
import { formatDuration, formatRelativeTime, formatTokenPair } from '../traces/traceViewModel';

const PAGE_SIZES = [20, 50, 100];
const DEFAULT_RANGE = '7d';

const KIND_TABS: { key: string; label: string }[] = [
  { key: '', label: 'All' },
  { key: 'mcp_tool', label: 'MCP Tool' },
  { key: 'api_tool', label: 'API Tool' },
  { key: 'agent', label: 'Agent' },
];

const RANGE_OPTIONS: { key: string; label: string }[] = [
  { key: '1h', label: 'Last 1 hour' },
  { key: '24h', label: 'Last 24 hours' },
  { key: '7d', label: 'Last 7 days' },
  { key: '30d', label: 'Last 30 days' },
  { key: '90d', label: 'Last 90 days (retention limit)' },
];

const SOURCE_OPTIONS: { key: string; label: string }[] = [
  { key: '', label: 'All sources' },
  { key: 'cli', label: 'CLI' },
  { key: 'mcp', label: 'MCP' },
  { key: 'hub', label: 'Hub' },
  { key: 'unknown', label: 'Unknown' },
];

const STATE_OPTIONS: { key: string; label: string }[] = [
  { key: '', label: 'All results' },
  { key: 'completed', label: 'Succeeded' },
  { key: 'failed', label: 'Failed' },
  { key: 'unknown', label: 'Incomplete' },
];

const KIND_ICONS: Record<string, { icon: ComponentType<{ size?: number }>; label: string }> = {
  mcp_tool: { icon: Network, label: 'MCP tool' },
  api_tool: { icon: Wrench, label: 'API tool' },
  agent: { icon: Brain, label: 'Agent' },
};

interface HubCallFilters extends HubCallFilter {
  kind: string;
  source: string;
  state: string;
  userId: string;
  range: string;
}

const DEFAULT_FILTERS: HubCallFilters = { kind: '', source: '', state: '', userId: '', range: DEFAULT_RANGE };

function readFilters(params: URLSearchParams, isAdmin: boolean): HubCallFilters {
  return {
    kind: params.get('kind') ?? '',
    source: params.get('source') ?? '',
    state: params.get('state') ?? '',
    userId: isAdmin ? params.get('userId') ?? '' : '',
    range: params.get('range') ?? DEFAULT_RANGE,
  };
}

function writeFilters(filters: HubCallFilters): URLSearchParams {
  const params = new URLSearchParams();
  (Object.entries(filters) as [string, string][]).forEach(([key, value]) => { if (value) params.set(key, value); });
  return params;
}

function cleanFilters(filters: HubCallFilters): HubCallFilter {
  const result: HubCallFilter = {};
  if (filters.kind) result.kind = filters.kind;
  if (filters.source) result.source = filters.source;
  if (filters.state) result.state = filters.state;
  if (filters.userId) result.userId = filters.userId;
  result.range = filters.range || DEFAULT_RANGE;
  return result;
}

function rangeLabel(range: string) {
  return RANGE_OPTIONS.find(option => option.key === range)?.label ?? range;
}

function ResultBadge({ call }: { call: HubCall }) {
  if (call.success === true) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium"
        style={{ background: '#dcfce7', color: '#16a34a' }}>
        <CheckCircle2 size={12} /> Succeeded
      </span>
    );
  }
  if (call.success === false) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium" title={call.errorMessage ?? ''}
        style={{ background: '#fee2e2', color: '#dc2626' }}>
        <XCircle size={12} /> Failed
      </span>
    );
  }
  // the row was written when the call started but never completed (interrupted run)
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium" title="Not completed — no result was recorded"
      style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
      <HelpCircle size={12} /> Incomplete
    </span>
  );
}

function CopyChip({ label, value }: { label: string; value?: string }) {
  const [copied, setCopied] = useState(false);
  if (!value) return <span style={{ color: 'var(--color-text-secondary)' }}>-</span>;
  const copy = () => {
    navigator.clipboard.writeText(value).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2500);
    }).catch(() => {});
  };
  return (
    <button onClick={copy} title={value}
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs cursor-pointer"
      style={{ background: copied ? 'var(--color-success, #16a34a)' : 'var(--color-bg-tertiary)', color: copied ? '#ffffff' : 'var(--color-text-secondary)' }}>
      <Copy size={11} /> {copied ? 'Copied' : label}
    </button>
  );
}

function MetaItem({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <div style={{ color: 'var(--color-text-secondary)' }}>{label}</div>
      <div className="font-mono break-all">{children}</div>
    </div>
  );
}

function CallDetail({ call }: { call: HubCall }) {
  return (
    <div className="px-6 py-4 text-xs" style={{ background: 'var(--color-bg-tertiary)' }}>
      <div className="grid grid-cols-4 gap-x-6 gap-y-3">
        <MetaItem label="Call ID"><CopyChip label={call.id} value={call.id} /></MetaItem>
        <MetaItem label="Kind">{KIND_ICONS[call.kind ?? '']?.label ?? call.kind ?? '-'}</MetaItem>
        <MetaItem label="Source">{call.source ?? '-'}</MetaItem>
        <MetaItem label="Ref ID">{call.refId ?? '-'}</MetaItem>
        <MetaItem label="Target">{call.target ?? '-'}</MetaItem>
        <MetaItem label="Status code">{call.statusCode ?? '-'}</MetaItem>
        <MetaItem label="Duration">{formatDuration(call.durationMs)}</MetaItem>
        <MetaItem label="Output size">{call.outputBytes != null ? `${call.outputBytes} B` : '-'}</MetaItem>
        <MetaItem label="User">
          {call.userName || call.userEmail
            ? `${call.userName ?? ''}${call.userEmail ? ` <${call.userEmail}>` : ''}`
            : call.userId ?? '-'}
        </MetaItem>
        <MetaItem label="User type">{call.userType ?? '-'}</MetaItem>
        <MetaItem label="Tokens">
          {formatTokenPair(call.inputTokens, call.outputTokens)} (in / out)
        </MetaItem>
        <MetaItem label="Time">{call.createdAt ? new Date(call.createdAt).toLocaleString() : '-'}</MetaItem>
        {call.taskId && <MetaItem label="A2A task ID">{call.taskId}</MetaItem>}
        {call.contextId && <MetaItem label="A2A context ID">{call.contextId}</MetaItem>}
        <MetaItem label="Args hash">{call.argsHash ?? '-'}</MetaItem>
      </div>

      <div className="mt-4">
        <div style={{ color: 'var(--color-text-secondary)' }}>Arguments preview (truncated to 512 characters)</div>
        <pre className="mt-1 p-2 rounded overflow-auto whitespace-pre-wrap break-all max-h-64"
          style={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)' }}>
          {call.argsPreview || '(no arguments)'}
        </pre>
      </div>

      {call.errorMessage && (
        <div className="mt-3">
          <div style={{ color: 'var(--color-text-secondary)' }}>Error</div>
          <pre className="mt-1 p-2 rounded overflow-auto whitespace-pre-wrap break-all"
            style={{ background: '#fef2f2', color: '#dc2626', border: '1px solid #fecaca' }}>
            {call.errorMessage}
          </pre>
        </div>
      )}
    </div>
  );
}

export default function HubCalls() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { user } = useAuth();
  const isAdmin = user?.role === 'admin';

  const [filters, setFilters] = useState<HubCallFilters>(() => readFilters(searchParams, isAdmin));
  const [offset, setOffset] = useState(0);
  const [limit, setLimit] = useState(PAGE_SIZES[0]);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [result, setResult] = useState<{ requestKey: string; calls: HubCall[]; total: number }>({ requestKey: '', calls: [], total: 0 });
  const [error, setError] = useState('');
  const [userList, setUserList] = useState<UserStatus[]>([]);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    if (isAdmin) adminApi.listUsers().then(res => setUserList(res.users)).catch(() => {});
  }, [isAdmin]);

  const requestKey = JSON.stringify({ offset, limit, filters, refresh: refreshKey });
  useEffect(() => {
    let cancelled = false;
    api.hubCalls.list(offset, limit, cleanFilters(filters))
      .then(response => {
        if (!cancelled) {
          setResult({ requestKey, calls: response.calls ?? [], total: response.total ?? 0 });
          setError('');
        }
      })
      .catch(e => {
        if (!cancelled) {
          setResult({ requestKey, calls: [], total: 0 });
          setError(e?.message ?? 'Failed to load hub calls');
        }
      });
    return () => { cancelled = true; };
  }, [offset, limit, filters, refreshKey, requestKey]);

  const loading = result.requestKey !== requestKey;
  const calls = loading ? [] : result.calls;
  const total = result.total;

  const updateFilter = (patch: Partial<HubCallFilters>) => {
    const next = { ...filters, ...patch };
    setFilters(next);
    setOffset(0);
    setSearchParams(writeFilters(next), { replace: true });
  };

  const narrowed = Boolean(filters.kind || filters.source || filters.state || filters.userId);
  const emptyMessage = narrowed
    ? `No hub calls match the current filters (${rangeLabel(filters.range)}).`
    : `No hub calls in the ${rangeLabel(filters.range)}.`;

  return (
    <div className="p-6">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Hub Calls</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Hub tool executions by remote clients (CLI / MCP bridge / Agent Hub) — {total} in this window
          </p>
        </div>
        <div className="flex items-center gap-2">
          <select value={filters.range} onChange={e => updateFilter({ range: e.target.value })}
            className="px-3 py-1.5 rounded-lg border text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            {RANGE_OPTIONS.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
          </select>
          <select value={filters.source} onChange={e => updateFilter({ source: e.target.value })}
            className="px-3 py-1.5 rounded-lg border text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            {SOURCE_OPTIONS.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
          </select>
          <select value={filters.state} onChange={e => updateFilter({ state: e.target.value })}
            className="px-3 py-1.5 rounded-lg border text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            {STATE_OPTIONS.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
          </select>
          {isAdmin && (
            <select value={filters.userId} onChange={e => updateFilter({ userId: e.target.value })}
              className="px-3 py-1.5 rounded-lg border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <option value="">All users</option>
              {userList.filter(item => item.user_id).map(item => (
                <option key={item.user_id} value={item.user_id}>{item.name || item.email || item.user_id}</option>
              ))}
            </select>
          )}
          {narrowed && (
            <button onClick={() => { const next = { ...DEFAULT_FILTERS, range: filters.range }; setFilters(next); setOffset(0); setSearchParams(writeFilters(next), { replace: true }); }}
              className="px-3 py-1.5 rounded-lg border text-sm cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Clear
            </button>
          )}
          <button onClick={() => setRefreshKey(key => key + 1)}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <RotateCw size={14} /> Refresh
          </button>
        </div>
      </div>

      <div className="flex gap-1 mb-4" style={{ borderBottom: '1px solid var(--color-border)' }}>
        {KIND_TABS.map(tab => (
          <button key={tab.key} onClick={() => updateFilter({ kind: tab.key })}
            className="px-4 py-2 text-sm font-medium cursor-pointer"
            style={{
              color: filters.kind === tab.key ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              borderBottom: filters.kind === tab.key ? '2px solid var(--color-primary)' : '2px solid transparent',
              marginBottom: '-1px',
            }}>
            {tab.label}
          </button>
        ))}
      </div>

      <div className="mb-4 rounded-lg border p-3 text-xs flex gap-2"
        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
        <Info size={14} className="shrink-0 mt-0.5" />
        <div>
          These are executions made by remote clients against the hub endpoints directly, so they are not part of Traces.
          Arguments and results are never stored in full — only a sha256 hash and a 512-character preview — and records are
          retained for 90 days. A call that started but never completed shows as <span className="font-medium">Incomplete</span>.
        </div>
      </div>

      {error && <div className="mb-4 rounded border border-red-300 bg-red-50 p-2 text-sm text-red-700">{error}</div>}

      <div className="rounded-xl border overflow-hidden" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead>
            <tr style={{ background: 'var(--color-bg-tertiary)' }}>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Tool</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>User</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Result</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Duration</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Tokens</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Source</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Time</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={7} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</td></tr>
            ) : calls.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>
                  <div>{emptyMessage}</div>
                  {narrowed && (
                    <button onClick={() => { const next = { ...DEFAULT_FILTERS, range: filters.range }; setFilters(next); setOffset(0); setSearchParams(writeFilters(next), { replace: true }); }}
                      className="mt-2 underline cursor-pointer">Clear filters</button>
                  )}
                </td>
              </tr>
            ) : calls.map(call => {
              const kind = KIND_ICONS[call.kind ?? ''];
              const KindIcon = kind?.icon ?? Network;
              const expanded = expandedId === call.id;
              return (
                <HubCallRow key={call.id} call={call} expanded={expanded} KindIcon={KindIcon} kindLabel={kind?.label ?? call.kind ?? '-'}
                  onToggle={() => setExpandedId(expanded ? null : call.id)} />
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between mt-4">
        <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          Showing {calls.length > 0 ? offset + 1 : 0}-{offset + calls.length} of {total}
        </span>
        <div className="flex items-center gap-2">
          <select value={limit} onChange={e => { setLimit(Number(e.target.value)); setOffset(0); }}
            className="px-3 py-1.5 rounded-lg border text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            {PAGE_SIZES.map(size => <option key={size} value={size}>{size} / page</option>)}
          </select>
          <button onClick={() => setOffset(Math.max(0, offset - limit))} disabled={offset === 0}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <ChevronLeft size={14} /> Prev
          </button>
          <button onClick={() => setOffset(offset + limit)} disabled={calls.length === 0 || offset + calls.length >= total}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            Next <ChevronRight size={14} />
          </button>
        </div>
      </div>
    </div>
  );
}

function HubCallRow({ call, expanded, KindIcon, kindLabel, onToggle }
  : { call: HubCall; expanded: boolean; KindIcon: ComponentType<{ size?: number }>; kindLabel: string; onToggle: () => void }) {
  return (
    <>
      <tr onClick={onToggle} className="border-t cursor-pointer"
        style={{ borderColor: 'var(--color-border)' }}
        onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
        onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
        <td className="px-4 py-3">
          <div className="flex items-center gap-2">
            <ChevronDown size={14} style={{ transform: expanded ? 'rotate(0deg)' : 'rotate(-90deg)', color: 'var(--color-text-secondary)' }} />
            <KindIcon size={14} />
            <div className="min-w-0">
              <div className="font-medium truncate" style={{ maxWidth: '280px' }} title={call.target ?? ''}>{call.name || call.target || '-'}</div>
              <div className="text-xs font-mono truncate" style={{ color: 'var(--color-text-secondary)', maxWidth: '280px' }}>
                {[kindLabel, call.group].filter(Boolean).join(' · ')}
              </div>
            </div>
          </div>
        </td>
        <td className="px-4 py-3">
          <div className="truncate" style={{ maxWidth: '180px' }}>{call.userName || call.userId || '-'}</div>
          {call.userEmail && (
            <div className="text-xs truncate" style={{ color: 'var(--color-text-secondary)', maxWidth: '180px' }}>{call.userEmail}</div>
          )}
        </td>
        <td className="px-4 py-3">
          <ResultBadge call={call} />
          {call.statusCode != null && (
            <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>HTTP {call.statusCode}</div>
          )}
        </td>
        <td className="px-4 py-3 whitespace-nowrap">{formatDuration(call.durationMs)}</td>
        <td className="px-4 py-3 whitespace-nowrap" style={{ color: 'var(--color-text-secondary)' }}>
          {formatTokenPair(call.inputTokens, call.outputTokens)}
        </td>
        <td className="px-4 py-3">
          <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono" style={{ background: 'var(--color-bg-tertiary)' }}>
            {call.source ?? 'unknown'}
          </span>
        </td>
        <td className="px-4 py-3 whitespace-nowrap" style={{ color: 'var(--color-text-secondary)' }}>
          <span title={call.createdAt ? new Date(call.createdAt).toLocaleString() : ''}>{formatRelativeTime(call.createdAt)}</span>
        </td>
      </tr>
      {expanded && (
        <tr className="border-t" style={{ borderColor: 'var(--color-border)' }}>
          <td colSpan={7} className="p-0"><CallDetail call={call} /></td>
        </tr>
      )}
    </>
  );
}
