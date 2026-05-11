import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Activity,
  AlertCircle,
  ArrowRight,
  Bot,
  Calendar,
  Clock,
  DollarSign,
  ExternalLink,
  MessageCircle,
  Plus,
  RefreshCw,
  TrendingUp,
  Zap,
} from 'lucide-react';
import { Area, AreaChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../../api/client';
import type { Span, Trace } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';
import { sourceColors } from '../traces/colors';
import {
  extractTracePreview,
  formatCostUsd,
  formatDuration,
  formatRelativeTime,
  resolveTraceSource,
} from '../traces/traceViewModel';

type TimeRange = '1h' | '24h' | '7d';
type Severity = 'danger' | 'warning';

interface AttentionItem {
  key: string;
  severity: Severity;
  label: string;
  title: string;
  trace: Trace;
  meta: string[];
  iconText: string;
}

interface SourceSummary {
  key: string;
  label: string;
  count: number;
  errors: number;
  running: number;
  tokens: number;
}

interface DashboardState {
  requestKey: string;
  traces: Trace[];
  llmSpans: Span[];
  error: string | null;
  loadedAt: string | null;
}

const RANGE_MS: Record<TimeRange, number> = {
  '1h': 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
};

const RANGE_LABEL: Record<TimeRange, string> = {
  '1h': 'Last 1h',
  '24h': 'Last 24h',
  '7d': 'Last 7d',
};

const TRACE_LIMIT = 200;
const GENERATION_LIMIT = 200;
const LONG_RUNNING_MS = 10 * 60 * 1000;
const HIGH_TOKEN_THRESHOLD = 100_000;
const EMPTY_TRACES: Trace[] = [];
const EMPTY_SPANS: Span[] = [];

export default function Dashboard() {
  const navigate = useNavigate();
  const [timeRange, setTimeRange] = useState<TimeRange>('24h');
  const [reloadKey, setReloadKey] = useState(0);
  const requestKey = `${timeRange}:${reloadKey}`;
  const [state, setState] = useState<DashboardState>({ requestKey: '', traces: [], llmSpans: [], error: null, loadedAt: null });

  useEffect(() => {
    let cancelled = false;
    const startFrom = new Date(Date.now() - RANGE_MS[timeRange]).toISOString();

    Promise.all([
      api.traces.list(0, TRACE_LIMIT, { startFrom }),
      api.traces.generations(0, GENERATION_LIMIT),
    ])
      .then(([nextTraces, nextSpans]) => {
        if (cancelled) return;
        setState({
          requestKey,
          traces: nextTraces || [],
          llmSpans: (nextSpans || []).filter(span => isWithinRange(span.startedAt, timeRange)),
          error: null,
          loadedAt: new Date().toISOString(),
        });
      })
      .catch(err => {
        console.warn('load dashboard failed', err);
        if (!cancelled) {
          setState({
            requestKey,
            traces: [],
            llmSpans: [],
            error: err instanceof Error ? err.message : 'Failed to load dashboard',
            loadedAt: new Date().toISOString(),
          });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [timeRange, requestKey]);

  const loading = state.requestKey !== requestKey;
  const traces = loading ? EMPTY_TRACES : state.traces;
  const llmSpans = loading ? EMPTY_SPANS : state.llmSpans;
  const error = loading ? null : state.error;
  const metrics = useMemo(() => buildMetrics(traces, llmSpans), [traces, llmSpans]);
  const attention = useMemo(() => buildAttentionItems(traces), [traces]);
  const recentActivity = traces.slice(0, 4);
  const runningNow = metrics.runningTraces.slice(0, 3);
  const sourceSummaries = useMemo(() => buildSourceSummaries(traces), [traces]);
  const usageSeries = useMemo(() => buildUsageSeries(llmSpans, timeRange), [llmSpans, timeRange]);
  const topAgents = useMemo(() => buildTopAgents(traces), [traces]);

  const rangeCopy = `${RANGE_LABEL[timeRange]} · ${loading ? 'refreshing' : 'sample up to 200 traces'}${state.loadedAt ? ` · refreshed ${formatRelativeTime(state.loadedAt)}` : ''}`;

  return (
    <div className="p-6">
      <div className="mb-5 flex flex-col gap-3 xl:flex-row xl:items-start xl:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Dashboard</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            {rangeCopy}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="inline-flex rounded-lg border p-1" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            {(['1h', '24h', '7d'] as TimeRange[]).map(range => (
              <button key={range} onClick={() => setTimeRange(range)}
                className="px-3 py-1.5 rounded-md text-sm cursor-pointer"
                style={{
                  background: timeRange === range ? 'var(--color-bg-tertiary)' : 'transparent',
                  color: timeRange === range ? 'var(--color-text)' : 'var(--color-text-secondary)',
                  fontWeight: timeRange === range ? 600 : 400,
                }}>
                {range}
              </button>
            ))}
          </div>
          <button onClick={() => navigate('/chat')}
            className="inline-flex items-center gap-2 px-3 py-2 rounded-lg border text-sm font-medium cursor-pointer"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <Plus size={16} /> New Chat
          </button>
          <button onClick={() => navigate('/traces')}
            className="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <ArrowRight size={16} /> View Traces
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm flex items-center justify-between gap-3"
          style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }}>
          <span>Could not load dashboard: {error}</span>
          <button onClick={() => setReloadKey(prev => prev + 1)}
            className="inline-flex items-center gap-1.5 px-2 py-1 rounded-md border cursor-pointer"
            style={{ borderColor: '#fecaca', background: '#fff' }}>
            <RefreshCw size={14} /> Retry
          </button>
        </div>
      )}

      {loading ? (
        <DashboardSkeleton />
      ) : (
        <>
          <div className="grid gap-3 mb-4 md:grid-cols-2 xl:grid-cols-5">
            <MetricCard
              label="System health"
              value={metrics.errorCount > 0 || metrics.longRunningCount > 0 ? 'Attention' : 'Healthy'}
              sub={metrics.errorCount > 0 ? `${metrics.errorCount} failures need review` : `${metrics.runningCount} running now`}
              tone={metrics.errorCount > 0 ? 'danger' : 'success'}
              icon={<Activity size={16} />}
              onClick={() => navigate(metrics.errorCount > 0 ? '/traces?status=ERROR' : '/traces')}
            />
            <MetricCard
              label="Running now"
              value={metrics.runningCount}
              sub={metrics.longRunningCount > 0 ? `${metrics.longRunningCount} over 10 minutes` : 'No long running traces'}
              tone={metrics.longRunningCount > 0 ? 'warning' : 'primary'}
              icon={<Clock size={16} />}
              onClick={() => navigate('/traces?status=RUNNING')}
            />
            <MetricCard
              label="Error rate"
              value={`${metrics.errorRate.toFixed(1)}%`}
              sub={`${metrics.errorCount} / ${metrics.totalTraces} traces`}
              tone={metrics.errorRate > 0 ? 'danger' : 'success'}
              icon={<AlertCircle size={16} />}
              onClick={() => navigate('/traces?status=ERROR')}
            />
            <MetricCard
              label="Tokens"
              value={formatCompact(metrics.totalTokens)}
              sub={`${formatCompact(metrics.totalInputTokens)} in / ${formatCompact(metrics.totalOutputTokens)} out`}
              tone="warning"
              icon={<Zap size={16} />}
              onClick={() => navigate('/traces')}
            />
            <MetricCard
              label="Estimated cost"
              value={formatCostUsd(metrics.totalCostUsd)}
              sub={metrics.totalCachedTokens > 0 ? `${formatCompact(metrics.totalCachedTokens)} cached tokens` : `${formatCompact(metrics.totalLLMCalls)} LLM calls`}
              tone="teal"
              icon={<DollarSign size={16} />}
              onClick={() => navigate('/traces')}
            />
          </div>

          <div className="grid gap-4 xl:grid-cols-[minmax(0,1.55fr)_minmax(320px,0.85fr)]">
            <section className="rounded-lg border overflow-hidden"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <PanelHeader title="Needs attention" subtitle="Failures, stuck runs, and unusually expensive calls">
                <button onClick={() => navigate('/traces?status=ERROR')}
                  className="inline-flex items-center gap-1 text-sm font-medium cursor-pointer"
                  style={{ color: 'var(--color-primary)' }}>
                  Review all <ArrowRight size={14} />
                </button>
              </PanelHeader>

              {attention.length > 0 ? (
                <div>
                  {attention.map(item => (
                    <AttentionRow key={item.key} item={item}
                      onOpenTrace={() => navigate(`/traces?traceId=${encodeURIComponent(item.trace.traceId || item.trace.id)}`)}
                      onOpenContext={() => openTraceContext(item.trace, navigate)}
                    />
                  ))}
                </div>
              ) : (
                <EmptyState title="No issues found" description="No errors, long running traces, or high-token runs in this range." />
              )}

              <div className="border-t" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)' }}>
                <div className="px-4 pt-4 pb-2 flex items-center justify-between gap-3">
                  <h3 className="text-sm font-semibold">Recent activity</h3>
                  <button onClick={() => navigate('/traces')} className="text-sm font-medium cursor-pointer" style={{ color: 'var(--color-primary)' }}>
                    Trace list
                  </button>
                </div>
                <div className="pb-2">
                  {recentActivity.length > 0 ? recentActivity.map(trace => (
                    <RecentActivityRow key={trace.id} trace={trace} onClick={() => navigate(`/traces?traceId=${encodeURIComponent(trace.traceId || trace.id)}`)} />
                  )) : (
                    <div className="px-4 py-8 text-sm text-center" style={{ color: 'var(--color-text-secondary)' }}>
                      No traces in this range.
                    </div>
                  )}
                </div>
                {sourceSummaries.length > 0 && (
                  <div className="grid gap-2 p-4 pt-2 sm:grid-cols-2 xl:grid-cols-4">
                    {sourceSummaries.slice(0, 4).map(source => (
                      <SourceSummaryCard key={source.key} source={source} onClick={() => navigate(`/traces?source=${encodeURIComponent(source.key)}`)} />
                    ))}
                  </div>
                )}
              </div>
            </section>

            <div className="grid gap-4">
              <section className="rounded-lg border overflow-hidden"
                style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
                <PanelHeader title="Running now" subtitle="Live sessions and scheduled runs">
                  <button onClick={() => navigate('/traces?status=RUNNING')} className="text-sm font-medium cursor-pointer" style={{ color: 'var(--color-primary)' }}>
                    All running
                  </button>
                </PanelHeader>
                <div className="p-4 grid gap-3">
                  {runningNow.length > 0 ? runningNow.map(trace => (
                    <RunningCard key={trace.id} trace={trace} onClick={() => openTraceContext(trace, navigate)} />
                  )) : (
                    <EmptyState title="Nothing running" description="New active sessions will appear here." compact />
                  )}
                </div>
              </section>

              <section className="rounded-lg border overflow-hidden"
                style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
                <PanelHeader title="Quick actions" subtitle="Common paths from the homepage" />
                <div className="grid gap-2 p-4 sm:grid-cols-2">
                  <QuickAction icon={<Plus size={16} />} title="New chat" description="Talk to an agent" onClick={() => navigate('/chat')} />
                  <QuickAction icon={<Bot size={16} />} title="New agent" description="Build or import" onClick={() => navigate('/agents/new')} />
                  <QuickAction icon={<TrendingUp size={16} />} title="Trace errors" description="Filtered debug view" onClick={() => navigate('/traces?status=ERROR')} />
                  <QuickAction icon={<Calendar size={16} />} title="Add trigger" description="Webhook or schedule" onClick={() => navigate('/triggers')} />
                </div>
              </section>
            </div>
          </div>

          <div className="grid gap-4 mt-4 xl:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)]">
            <section className="rounded-lg border overflow-hidden"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <PanelHeader title="Usage trend" subtitle="Tokens and LLM calls over time">
                <div className="flex items-center gap-3 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  <span className="inline-flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm" style={{ background: '#6366f1' }} /> Tokens</span>
                  <span className="inline-flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm" style={{ background: '#0891b2' }} /> Calls</span>
                </div>
              </PanelHeader>
              <div className="p-4">
                {usageSeries.length > 0 ? (
                  <ResponsiveContainer width="100%" height={230}>
                    <AreaChart data={usageSeries}>
                      <defs>
                        <linearGradient id="dashboardTokens" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#6366f1" stopOpacity={0.25} />
                          <stop offset="95%" stopColor="#6366f1" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <XAxis dataKey="label" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: 'var(--color-text-secondary)' }} />
                      <YAxis yAxisId="tokens" axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: 'var(--color-text-secondary)' }} tickFormatter={formatCompact} />
                      <YAxis yAxisId="calls" orientation="right" axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: 'var(--color-text-secondary)' }} allowDecimals={false} />
                      <Tooltip
                        contentStyle={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)', borderRadius: 8, fontSize: 12 }}
                        formatter={(value, name) => name === 'tokens' ? formatCompact(Number(value)) : Number(value).toLocaleString()}
                      />
                      <Area yAxisId="tokens" type="monotone" dataKey="tokens" name="tokens" stroke="#6366f1" strokeWidth={2} fill="url(#dashboardTokens)" />
                      <Area yAxisId="calls" type="monotone" dataKey="calls" name="calls" stroke="#0891b2" strokeWidth={2} fill="transparent" strokeDasharray="5 4" />
                    </AreaChart>
                  </ResponsiveContainer>
                ) : (
                  <EmptyState title="No LLM usage yet" description="LLM calls will appear once agents run in this time range." compact />
                )}
              </div>
            </section>

            <section className="rounded-lg border overflow-hidden"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <PanelHeader title="Top agents" subtitle="Volume, errors, and estimated spend">
                <button onClick={() => navigate('/agents')} className="text-sm font-medium cursor-pointer" style={{ color: 'var(--color-primary)' }}>
                  Open Agents
                </button>
              </PanelHeader>
              <div className="p-4 grid gap-3">
                {topAgents.length > 0 ? topAgents.map(agent => (
                  <TopAgentRow key={agent.name} agent={agent} maxTokens={topAgents[0].tokens} onClick={() => navigate(`/traces?agentName=${encodeURIComponent(agent.name)}`)} />
                )) : (
                  <EmptyState title="No agent activity" description="Agent traces will appear here after runs complete." compact />
                )}
              </div>
            </section>
          </div>
        </>
      )}
    </div>
  );
}

function buildMetrics(traces: Trace[], llmSpans: Span[]) {
  const totalTraces = traces.length;
  const runningTraces = traces.filter(trace => trace.status === 'RUNNING');
  const errorCount = traces.filter(trace => trace.status === 'ERROR').length;
  const errorRate = totalTraces > 0 ? (errorCount / totalTraces) * 100 : 0;
  const totalInputTokens = llmSpans.reduce((sum, span) => sum + (span.inputTokens || 0), 0);
  const totalOutputTokens = llmSpans.reduce((sum, span) => sum + (span.outputTokens || 0), 0);
  const totalCachedTokens = traces.reduce((sum, trace) => sum + (trace.cachedTokens || 0), 0);
  const totalCostUsd = traces.reduce((sum, trace) => sum + (trace.costUsd || 0), 0);
  const longRunningCount = runningTraces.filter(trace => traceAgeMs(trace) >= LONG_RUNNING_MS).length;

  return {
    totalTraces,
    totalLLMCalls: llmSpans.length,
    totalInputTokens,
    totalOutputTokens,
    totalTokens: totalInputTokens + totalOutputTokens,
    totalCachedTokens,
    totalCostUsd,
    errorCount,
    errorRate,
    runningCount: runningTraces.length,
    runningTraces,
    longRunningCount,
  };
}

function buildAttentionItems(traces: Trace[]): AttentionItem[] {
  const items: AttentionItem[] = [];
  const used = new Set<string>();

  for (const trace of traces.filter(trace => trace.status === 'ERROR').slice(0, 3)) {
    used.add(trace.id);
    items.push({
      key: `error-${trace.id}`,
      severity: 'danger',
      label: 'ERROR',
      title: trace.name || `${trace.agentName || 'Trace'} failed`,
      trace,
      meta: compactMeta([sourceColors(resolveTraceSource(trace)).label, trace.agentName, shortId(trace.traceId || trace.id), formatRelativeTime(trace.startedAt || trace.createdAt)]),
      iconText: '!',
    });
  }

  for (const trace of traces.filter(trace => trace.status === 'RUNNING' && traceAgeMs(trace) >= LONG_RUNNING_MS && !used.has(trace.id)).slice(0, 2)) {
    used.add(trace.id);
    items.push({
      key: `running-${trace.id}`,
      severity: 'warning',
      label: 'SLOW',
      title: trace.name || `${trace.agentName || 'Trace'} is still running`,
      trace,
      meta: compactMeta([sourceColors(resolveTraceSource(trace)).label, trace.agentName, trace.sessionId ? shortId(trace.sessionId) : '', `started ${formatRelativeTime(trace.startedAt || trace.createdAt)}`]),
      iconText: formatDuration(traceAgeMs(trace)).replace('.0', ''),
    });
  }

  for (const trace of traces
    .filter(trace => !used.has(trace.id) && ((trace.totalTokens || 0) >= HIGH_TOKEN_THRESHOLD || (trace.costUsd || 0) >= 1))
    .sort((a, b) => (b.totalTokens || 0) - (a.totalTokens || 0))
    .slice(0, 2)) {
    used.add(trace.id);
    items.push({
      key: `cost-${trace.id}`,
      severity: 'warning',
      label: (trace.costUsd || 0) >= 1 ? 'COST' : 'TOKENS',
      title: `${trace.agentName || trace.name || 'Trace'} used ${formatCompact(trace.totalTokens || 0)} tokens`,
      trace,
      meta: compactMeta([sourceColors(resolveTraceSource(trace)).label, trace.model || trace.metadata?.model, formatCostUsd(trace.costUsd), formatRelativeTime(trace.startedAt || trace.createdAt)]),
      iconText: '$',
    });
  }

  return items.slice(0, 4);
}

function buildSourceSummaries(traces: Trace[]): SourceSummary[] {
  const map = new Map<string, SourceSummary>();
  for (const trace of traces) {
    const key = resolveTraceSource(trace);
    const palette = sourceColors(key);
    const current = map.get(key) || { key, label: palette.label, count: 0, errors: 0, running: 0, tokens: 0 };
    current.count += 1;
    current.tokens += trace.totalTokens || 0;
    if (trace.status === 'ERROR') current.errors += 1;
    if (trace.status === 'RUNNING') current.running += 1;
    map.set(key, current);
  }
  return Array.from(map.values()).sort((a, b) => b.count - a.count);
}

function buildUsageSeries(spans: Span[], timeRange: TimeRange) {
  const bucketMs = timeRange === '7d' ? 24 * 60 * 60 * 1000 : timeRange === '24h' ? 2 * 60 * 60 * 1000 : 10 * 60 * 1000;
  const startMs = Date.now() - RANGE_MS[timeRange];
  const bucketCount = Math.ceil(RANGE_MS[timeRange] / bucketMs);
  const buckets = Array.from({ length: bucketCount }, (_, index) => {
    const bucketStart = startMs + index * bucketMs;
    return {
      bucketStart,
      label: formatBucketLabel(bucketStart, timeRange),
      tokens: 0,
      calls: 0,
    };
  });

  for (const span of spans) {
    const timestamp = new Date(span.startedAt || '').getTime();
    if (!Number.isFinite(timestamp) || timestamp < startMs) continue;
    const index = Math.min(Math.floor((timestamp - startMs) / bucketMs), buckets.length - 1);
    if (index < 0 || !buckets[index]) continue;
    buckets[index].tokens += (span.inputTokens || 0) + (span.outputTokens || 0);
    buckets[index].calls += 1;
  }

  return buckets.filter(bucket => bucket.calls > 0 || bucket.tokens > 0);
}

function buildTopAgents(traces: Trace[]) {
  const map = new Map<string, { name: string; traces: number; errors: number; running: number; tokens: number; cost: number }>();
  for (const trace of traces) {
    const name = trace.agentName || (trace.type === 'llm_call' ? 'LLM Calls' : 'Unknown Agent');
    const current = map.get(name) || { name, traces: 0, errors: 0, running: 0, tokens: 0, cost: 0 };
    current.traces += 1;
    current.tokens += trace.totalTokens || 0;
    current.cost += trace.costUsd || 0;
    if (trace.status === 'ERROR') current.errors += 1;
    if (trace.status === 'RUNNING') current.running += 1;
    map.set(name, current);
  }

  return Array.from(map.values())
    .sort((a, b) => b.tokens - a.tokens || b.traces - a.traces)
    .slice(0, 5);
}

function MetricCard({ label, value, sub, icon, tone, onClick }: {
  label: string;
  value: ReactNode;
  sub: string;
  icon: ReactNode;
  tone: 'danger' | 'warning' | 'success' | 'primary' | 'teal';
  onClick: () => void;
}) {
  const color = toneColor(tone);
  return (
    <button onClick={onClick}
      className="rounded-lg border p-4 text-left cursor-pointer transition-colors hover:shadow-sm"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="flex items-center justify-between gap-2 mb-3">
        <span className="text-xs font-semibold" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
        <span className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background: `${color}18`, color }}>
          {icon}
        </span>
      </div>
      <div className="text-2xl font-bold" style={{ color: tone === 'danger' ? color : undefined }}>{value}</div>
      <div className="text-xs mt-2 truncate" style={{ color: 'var(--color-text-secondary)' }}>{sub}</div>
    </button>
  );
}

function PanelHeader({ title, subtitle, children }: { title: string; subtitle: string; children?: ReactNode }) {
  return (
    <div className="px-4 py-3 border-b flex items-start justify-between gap-3" style={{ borderColor: 'var(--color-border)' }}>
      <div>
        <h2 className="font-semibold text-base">{title}</h2>
        <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>{subtitle}</p>
      </div>
      {children}
    </div>
  );
}

function AttentionRow({ item, onOpenTrace, onOpenContext }: { item: AttentionItem; onOpenTrace: () => void; onOpenContext: () => void }) {
  const color = item.severity === 'danger' ? '#dc2626' : '#d97706';
  return (
    <div className="px-4 py-3 border-b last:border-b-0 grid gap-3 md:grid-cols-[34px_minmax(0,1fr)_auto] md:items-center"
      style={{ borderColor: 'var(--color-border)' }}>
      <div className="w-9 h-9 rounded-lg flex items-center justify-center text-xs font-bold"
        style={{ background: item.severity === 'danger' ? '#fee2e2' : '#fef3c7', color }}>
        {item.iconText}
      </div>
      <div className="min-w-0">
        <div className="flex items-center gap-2 min-w-0">
          <span className="px-2 py-0.5 rounded-full text-xs font-semibold" style={{ background: item.severity === 'danger' ? '#fee2e2' : '#fef3c7', color }}>
            {item.label}
          </span>
          <button onClick={onOpenTrace} className="font-semibold truncate text-left cursor-pointer hover:underline">
            {item.title}
          </button>
        </div>
        <div className="flex flex-wrap gap-x-3 gap-y-1 text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
          {item.meta.map(value => <span key={value}>{value}</span>)}
        </div>
      </div>
      <div className="flex items-center gap-2">
        <button onClick={onOpenContext} className="w-8 h-8 rounded-lg border flex items-center justify-center cursor-pointer"
          style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }} title="Open context">
          <MessageCircle size={15} />
        </button>
        <button onClick={onOpenTrace} className="w-8 h-8 rounded-lg border flex items-center justify-center cursor-pointer"
          style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }} title="Open trace">
          <ExternalLink size={15} />
        </button>
      </div>
    </div>
  );
}

function RecentActivityRow({ trace, onClick }: { trace: Trace; onClick: () => void }) {
  const source = sourceColors(resolveTraceSource(trace));
  return (
    <button onClick={onClick}
      className="w-full px-4 py-2 grid gap-2 text-left cursor-pointer sm:grid-cols-[90px_minmax(0,1fr)_110px_70px] sm:items-center hover:bg-[var(--color-bg-tertiary)]">
      <span className="justify-self-start px-2 py-0.5 rounded-full text-xs font-semibold" style={{ background: 'var(--color-bg-tertiary)', color: source.color }}>
        {source.label}
      </span>
      <span className="min-w-0 truncate text-sm font-medium">{trace.agentName || trace.name || extractTracePreview(trace) || trace.traceId}</span>
      <StatusBadge status={trace.status} />
      <span className="text-xs sm:text-right" style={{ color: 'var(--color-text-secondary)' }}>{formatRelativeTime(trace.startedAt || trace.createdAt)}</span>
    </button>
  );
}

function SourceSummaryCard({ source, onClick }: { source: SourceSummary; onClick: () => void }) {
  return (
    <button onClick={onClick}
      className="rounded-lg border p-3 text-left cursor-pointer"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="text-xs font-semibold mb-1" style={{ color: 'var(--color-text-secondary)' }}>{source.label}</div>
      <div className="text-xl font-bold">{source.count}</div>
      <div className="text-xs mt-1 truncate" style={{ color: 'var(--color-text-secondary)' }}>
        {source.errors > 0 ? `${source.errors} errors` : source.running > 0 ? `${source.running} running` : `${formatCompact(source.tokens)} tokens`}
      </div>
    </button>
  );
}

function RunningCard({ trace, onClick }: { trace: Trace; onClick: () => void }) {
  const ageMs = traceAgeMs(trace);
  const pct = Math.min(Math.max((ageMs / LONG_RUNNING_MS) * 100, 14), 100);
  const longRunning = ageMs >= LONG_RUNNING_MS;
  return (
    <button onClick={onClick}
      className="rounded-lg p-3 text-left cursor-pointer"
      style={{ background: 'var(--color-bg-tertiary)' }}>
      <div className="flex items-center justify-between gap-3 mb-2">
        <span className="font-semibold text-sm truncate">{trace.agentName || trace.name || trace.traceId}</span>
        <span className="px-2 py-0.5 rounded-full text-xs font-semibold"
          style={{ background: longRunning ? '#fef3c7' : '#dcfce7', color: longRunning ? '#d97706' : '#16a34a' }}>
          {formatDuration(ageMs)}
        </span>
      </div>
      <div className="h-1.5 rounded-full overflow-hidden" style={{ background: 'var(--color-border)' }}>
        <div className="h-full rounded-full" style={{ width: `${pct}%`, background: longRunning ? '#d97706' : 'var(--color-primary)' }} />
      </div>
      <div className="text-xs mt-2 truncate" style={{ color: 'var(--color-text-secondary)' }}>
        {sourceColors(resolveTraceSource(trace)).label}{trace.sessionId ? ` · ${shortId(trace.sessionId)}` : ''}
      </div>
    </button>
  );
}

function QuickAction({ icon, title, description, onClick }: { icon: ReactNode; title: string; description: string; onClick: () => void }) {
  return (
    <button onClick={onClick}
      className="min-h-[72px] rounded-lg border p-3 text-left flex items-start gap-3 cursor-pointer"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <span style={{ color: 'var(--color-primary)' }}>{icon}</span>
      <span>
        <span className="block text-sm font-semibold">{title}</span>
        <span className="block text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>{description}</span>
      </span>
    </button>
  );
}

function TopAgentRow({ agent, maxTokens, onClick }: {
  agent: { name: string; traces: number; errors: number; running: number; tokens: number; cost: number };
  maxTokens: number;
  onClick: () => void;
}) {
  const pct = maxTokens > 0 ? Math.max((agent.tokens / maxTokens) * 100, 4) : 0;
  const color = agent.errors > 0 ? '#dc2626' : agent.running > 0 ? '#d97706' : '#16a34a';
  return (
    <button onClick={onClick} className="text-left cursor-pointer">
      <div className="flex items-center justify-between gap-3 mb-1">
        <div className="min-w-0 flex items-center gap-2">
          <span className="font-semibold text-sm truncate">{agent.name}</span>
          {agent.errors > 0 && <span className="px-2 py-0.5 rounded-full text-xs font-semibold" style={{ background: '#fee2e2', color }}>{agent.errors} errors</span>}
          {agent.errors === 0 && agent.running > 0 && <span className="px-2 py-0.5 rounded-full text-xs font-semibold" style={{ background: '#fef3c7', color }}>{agent.running} running</span>}
        </div>
        <span className="text-xs font-mono">{formatCostUsd(agent.cost)}</span>
      </div>
      <div className="h-2 rounded-full overflow-hidden" style={{ background: 'var(--color-bg-tertiary)' }}>
        <div className="h-full rounded-full" style={{ width: `${pct}%`, background: color }} />
      </div>
      <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
        {agent.traces} traces · {formatCompact(agent.tokens)} tokens
      </div>
    </button>
  );
}

function EmptyState({ title, description, compact }: { title: string; description: string; compact?: boolean }) {
  return (
    <div className={`text-center ${compact ? 'py-8' : 'py-12 px-4'}`} style={{ color: 'var(--color-text-secondary)' }}>
      <div className="text-sm font-medium" style={{ color: 'var(--color-text)' }}>{title}</div>
      <div className="text-xs mt-1">{description}</div>
    </div>
  );
}

function DashboardSkeleton() {
  return (
    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-5">
      {Array.from({ length: 5 }).map((_, index) => (
        <div key={index} className="rounded-lg border p-4 animate-pulse"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <div className="h-3 w-24 rounded mb-4" style={{ background: 'var(--color-bg-tertiary)' }} />
          <div className="h-7 w-20 rounded mb-3" style={{ background: 'var(--color-bg-tertiary)' }} />
          <div className="h-3 w-32 rounded" style={{ background: 'var(--color-bg-tertiary)' }} />
        </div>
      ))}
    </div>
  );
}

function openTraceContext(trace: Trace, navigate: (path: string) => void) {
  const source = resolveTraceSource(trace);
  if (trace.sessionId && source === 'chat') {
    navigate(`/chat?sessionId=${encodeURIComponent(trace.sessionId)}`);
    return;
  }
  if (source === 'scheduled') {
    navigate('/triggers/schedule');
    return;
  }
  if (trace.agentName) {
    navigate(`/traces?agentName=${encodeURIComponent(trace.agentName)}`);
    return;
  }
  navigate(`/traces?traceId=${encodeURIComponent(trace.traceId || trace.id)}`);
}

function isWithinRange(iso: string | undefined, timeRange: TimeRange) {
  if (!iso) return false;
  const timestamp = new Date(iso).getTime();
  return Number.isFinite(timestamp) && timestamp >= Date.now() - RANGE_MS[timeRange];
}

function traceAgeMs(trace: Trace) {
  const start = new Date(trace.startedAt || trace.createdAt || '').getTime();
  if (!Number.isFinite(start)) return 0;
  const end = trace.completedAt ? new Date(trace.completedAt).getTime() : Date.now();
  return Math.max((Number.isFinite(end) ? end : Date.now()) - start, 0);
}

function formatCompact(n: number) {
  if (!Number.isFinite(n)) return '0';
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2).replace(/\.00$/, '')}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1).replace(/\.0$/, '')}K`;
  return Math.round(n).toLocaleString();
}

function formatBucketLabel(timestamp: number, timeRange: TimeRange) {
  const date = new Date(timestamp);
  if (timeRange === '7d') return `${date.getMonth() + 1}/${date.getDate()}`;
  return date.toLocaleTimeString([], { hour: '2-digit', minute: timeRange === '1h' ? '2-digit' : undefined });
}

function shortId(value: string) {
  return value.length > 12 ? value.slice(0, 10) : value;
}

function compactMeta(values: Array<string | undefined | null>) {
  return values.filter((value): value is string => Boolean(value && value !== '-' && value !== '$0.0000'));
}

function toneColor(tone: 'danger' | 'warning' | 'success' | 'primary' | 'teal') {
  switch (tone) {
    case 'danger': return '#dc2626';
    case 'warning': return '#d97706';
    case 'success': return '#16a34a';
    case 'teal': return '#0891b2';
    default: return 'var(--color-primary)';
  }
}
