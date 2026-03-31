import { useEffect, useState } from 'react';
import { Activity, Zap, Clock, AlertCircle, Bot, TrendingUp } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, PieChart, Pie, Cell, AreaChart, Area } from 'recharts';
import { api } from '../../api/client';
import type { Trace, Span } from '../../api/client';

const STATUS_COLORS: Record<string, string> = {
  COMPLETED: '#22c55e',
  RUNNING: '#6366f1',
  ERROR: '#ef4444',
};

export default function Dashboard() {
  const [traces, setTraces] = useState<Trace[]>([]);
  const [llmSpans, setLlmSpans] = useState<Span[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.traces.list(0, 200),
      api.traces.generations(0, 200),
    ]).then(([t, s]) => {
      setTraces(t);
      setLlmSpans(s);
    }).finally(() => setLoading(false));
  }, []);

  const totalTraces = traces.length;
  const totalLLMCalls = llmSpans.length;
  const totalInputTokens = llmSpans.reduce((sum, s) => sum + (s.inputTokens || 0), 0);
  const totalOutputTokens = llmSpans.reduce((sum, s) => sum + (s.outputTokens || 0), 0);
  const totalTokens = totalInputTokens + totalOutputTokens;
  const avgDuration = totalTraces > 0 ? traces.reduce((sum, t) => sum + (t.durationMs || 0), 0) / totalTraces : 0;
  const errorCount = traces.filter(t => t.status === 'ERROR').length;
  const errorRate = totalTraces > 0 ? errorCount / totalTraces * 100 : 0;

  const statusData = ['COMPLETED', 'RUNNING', 'ERROR'].map(status => ({
    name: status,
    value: traces.filter(t => t.status === status).length,
  })).filter(d => d.value > 0);

  // Group by day, sorted chronologically
  const tokensByDay = (() => {
    const grouped: Record<string, { input: number; output: number; count: number }> = {};
    llmSpans.forEach(s => {
      const d = s.startedAt ? new Date(s.startedAt) : null;
      if (!d) return;
      const key = `${d.getMonth() + 1}/${d.getDate()}`;
      if (!grouped[key]) grouped[key] = { input: 0, output: 0, count: 0 };
      grouped[key].input += (s.inputTokens || 0);
      grouped[key].output += (s.outputTokens || 0);
      grouped[key].count += 1;
    });
    return Object.entries(grouped)
      .map(([date, d]) => ({ date, input: d.input, output: d.output, calls: d.count }))
      .slice(-7);
  })();

  // Latency distribution
  const latencyBuckets = (() => {
    const buckets = [
      { label: '<1s', min: 0, max: 1000, count: 0 },
      { label: '1-3s', min: 1000, max: 3000, count: 0 },
      { label: '3-5s', min: 3000, max: 5000, count: 0 },
      { label: '5-10s', min: 5000, max: 10000, count: 0 },
      { label: '>10s', min: 10000, max: Infinity, count: 0 },
    ];
    traces.forEach(t => {
      const ms = t.durationMs || 0;
      const b = buckets.find(b => ms >= b.min && ms < b.max);
      if (b) b.count++;
    });
    return buckets.map(b => ({ name: b.label, count: b.count }));
  })();

  // Model usage
  const modelUsage = (() => {
    const map: Record<string, { calls: number; tokens: number }> = {};
    llmSpans.forEach(s => {
      const m = s.model || 'unknown';
      if (!map[m]) map[m] = { calls: 0, tokens: 0 };
      map[m].calls += 1;
      map[m].tokens += (s.inputTokens || 0) + (s.outputTokens || 0);
    });
    return Object.entries(map)
      .sort((a, b) => b[1].calls - a[1].calls)
      .slice(0, 5)
      .map(([model, d]) => ({ model: model.split('/').pop() || model, calls: d.calls, tokens: d.tokens }));
  })();

  const formatNum = (n: number) => {
    if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
    if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
    return n.toLocaleString();
  };

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Dashboard</h1>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
          Overview of agent performance and usage
        </p>
      </div>

      {/* Stat Cards */}
      <div className="grid grid-cols-5 gap-4 mb-6">
        {[
          { label: 'Total Traces', value: totalTraces, sub: `${errorCount} errors`, icon: Activity, color: '#6366f1' },
          { label: 'LLM Calls', value: totalLLMCalls, sub: `${modelUsage.length} models`, icon: Bot, color: '#8b5cf6' },
          { label: 'Total Tokens', value: formatNum(totalTokens), sub: `${formatNum(totalInputTokens)} in / ${formatNum(totalOutputTokens)} out`, icon: Zap, color: '#f59e0b' },
          { label: 'Avg Latency', value: `${(avgDuration / 1000).toFixed(1)}s`, sub: `across ${totalTraces} traces`, icon: Clock, color: '#06b6d4' },
          { label: 'Error Rate', value: `${errorRate.toFixed(1)}%`, sub: `${errorCount} / ${totalTraces}`, icon: AlertCircle, color: errorRate > 10 ? '#ef4444' : '#22c55e' },
        ].map(({ label, value, sub, icon: Icon, color }) => (
          <div key={label} className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
              <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background: `${color}15` }}>
                <Icon size={16} style={{ color }} />
              </div>
            </div>
            <div className="text-2xl font-bold">{value}</div>
            <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>{sub}</div>
          </div>
        ))}
      </div>

      {/* Charts Row 1 */}
      <div className="grid grid-cols-3 gap-4 mb-4">
        {/* Token Usage Area Chart */}
        <div className="col-span-2 rounded-xl border p-5"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <div className="flex items-center justify-between mb-4">
            <div>
              <h3 className="font-medium text-sm">Token Usage</h3>
              <p className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>Last 7 days</p>
            </div>
            <div className="flex items-center gap-4 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm" style={{ background: '#6366f1' }} /> Input</span>
              <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm" style={{ background: '#f59e0b' }} /> Output</span>
            </div>
          </div>
          {tokensByDay.length > 0 ? (
            <ResponsiveContainer width="100%" height={240}>
              <AreaChart data={tokensByDay}>
                <defs>
                  <linearGradient id="inputGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#6366f1" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="#6366f1" stopOpacity={0} />
                  </linearGradient>
                  <linearGradient id="outputGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#f59e0b" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="#f59e0b" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: 'var(--color-text-secondary)' }} />
                <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: 'var(--color-text-secondary)' }} tickFormatter={formatNum} />
                <Tooltip
                  contentStyle={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)', borderRadius: 8, fontSize: 12 }}
                  formatter={(value: number) => formatNum(value)} />
                <Area type="monotone" dataKey="input" stroke="#6366f1" strokeWidth={2} fill="url(#inputGrad)" name="Input" />
                <Area type="monotone" dataKey="output" stroke="#f59e0b" strokeWidth={2} fill="url(#outputGrad)" name="Output" />
              </AreaChart>
            </ResponsiveContainer>
          ) : (
            <div className="flex items-center justify-center h-60 text-sm" style={{ color: 'var(--color-text-secondary)' }}>No data yet</div>
          )}
        </div>

        {/* Status Pie */}
        <div className="rounded-xl border p-5"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <h3 className="font-medium text-sm mb-1">Status Distribution</h3>
          <p className="text-xs mb-2" style={{ color: 'var(--color-text-secondary)' }}>By trace status</p>
          {statusData.length > 0 ? (
            <>
              <ResponsiveContainer width="100%" height={180}>
                <PieChart>
                  <Pie data={statusData} cx="50%" cy="50%" innerRadius={50} outerRadius={75} dataKey="value" paddingAngle={3} strokeWidth={0}>
                    {statusData.map((d) => <Cell key={d.name} fill={STATUS_COLORS[d.name] || '#94a3b8'} />)}
                  </Pie>
                  <Tooltip contentStyle={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)', borderRadius: 8, fontSize: 12 }} />
                </PieChart>
              </ResponsiveContainer>
              <div className="flex justify-center gap-4 mt-2">
                {statusData.map(d => (
                  <div key={d.name} className="flex items-center gap-1.5 text-xs">
                    <span className="w-2 h-2 rounded-full" style={{ background: STATUS_COLORS[d.name] }} />
                    <span style={{ color: 'var(--color-text-secondary)' }}>{d.name}</span>
                    <span className="font-medium">{d.value}</span>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div className="flex items-center justify-center h-60 text-sm" style={{ color: 'var(--color-text-secondary)' }}>No data yet</div>
          )}
        </div>
      </div>

      {/* Charts Row 2 */}
      <div className="grid grid-cols-2 gap-4">
        {/* Latency Distribution */}
        <div className="rounded-xl border p-5"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <div className="flex items-center gap-2 mb-4">
            <TrendingUp size={16} style={{ color: '#06b6d4' }} />
            <div>
              <h3 className="font-medium text-sm">Latency Distribution</h3>
              <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Response time buckets</p>
            </div>
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={latencyBuckets} barCategoryGap="20%">
              <XAxis dataKey="name" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: 'var(--color-text-secondary)' }} />
              <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: 'var(--color-text-secondary)' }} />
              <Tooltip contentStyle={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)', borderRadius: 8, fontSize: 12 }} />
              <Bar dataKey="count" fill="#06b6d4" radius={[6, 6, 0, 0]} name="Traces" />
            </BarChart>
          </ResponsiveContainer>
        </div>

        {/* Model Usage Table */}
        <div className="rounded-xl border p-5"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <div className="flex items-center gap-2 mb-4">
            <Bot size={16} style={{ color: '#8b5cf6' }} />
            <div>
              <h3 className="font-medium text-sm">Model Usage</h3>
              <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Top models by call count</p>
            </div>
          </div>
          {modelUsage.length > 0 ? (
            <div className="space-y-3">
              {modelUsage.map((m, i) => {
                const maxCalls = modelUsage[0].calls;
                const pct = maxCalls > 0 ? (m.calls / maxCalls) * 100 : 0;
                return (
                  <div key={m.model}>
                    <div className="flex items-center justify-between text-sm mb-1">
                      <span className="font-mono text-xs truncate" style={{ maxWidth: '60%' }}>{m.model}</span>
                      <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                        {m.calls} calls · {formatNum(m.tokens)} tokens
                      </span>
                    </div>
                    <div className="h-2 rounded-full" style={{ background: 'var(--color-bg-tertiary)' }}>
                      <div className="h-full rounded-full transition-all"
                        style={{ width: `${pct}%`, background: ['#6366f1', '#8b5cf6', '#a78bfa', '#c4b5fd', '#ddd6fe'][i] }} />
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="flex items-center justify-center h-48 text-sm" style={{ color: 'var(--color-text-secondary)' }}>No data yet</div>
          )}
        </div>
      </div>
    </div>
  );
}
