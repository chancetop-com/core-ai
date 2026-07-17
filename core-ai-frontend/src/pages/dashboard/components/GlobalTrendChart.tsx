import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { formatCompact } from '../../traces/traceViewModel';
import type { TrendPoint } from '../../../api/client';

interface GlobalTrendChartProps {
  data: TrendPoint[];
  loading: boolean;
}

export default function GlobalTrendChart({ data, loading }: GlobalTrendChartProps) {
  const chartData = data.map(p => ({
    ...p,
    timestamp: formatTimestamp(p.timestamp, data.length > 48),
  }));

  if (loading) {
    return (
      <ChartShell title="Usage Trend">
        <div className="h-[240px] flex items-center justify-center">
          <div className="animate-pulse text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        </div>
      </ChartShell>
    );
  }

  if (chartData.length === 0) {
    return (
      <ChartShell title="Usage Trend">
        <div className="h-[240px] flex items-center justify-center">
          <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            No usage data for this period.
          </div>
        </div>
      </ChartShell>
    );
  }

  return (
    <ChartShell title="Usage Trend">
      <div className="flex items-center gap-4 px-4 pt-2 pb-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
        <Legend color="#3b82f6" label="Input" />
        <Legend color="#22c55e" label="Output" />
        <Legend color="#a855f7" label="Cached" />
        <Legend color="#f97316" label="Cost" />
      </div>
      <ResponsiveContainer width="100%" height={240}>
        <AreaChart data={chartData} margin={{ top: 4, right: 12, left: 0, bottom: 0 }}>
          <defs>
            <linearGradient id="inputGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3} />
              <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
            </linearGradient>
            <linearGradient id="outputGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#22c55e" stopOpacity={0.3} />
              <stop offset="95%" stopColor="#22c55e" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
          <XAxis dataKey="timestamp" axisLine={false} tickLine={false}
            tick={{ fontSize: 11, fill: 'var(--color-text-secondary)' }}
            interval="preserveStartEnd" />
          <YAxis yAxisId="tokens" axisLine={false} tickLine={false}
            tick={{ fontSize: 11, fill: 'var(--color-text-secondary)' }}
            tickFormatter={formatCompact} />
          <YAxis yAxisId="cost" orientation="right" axisLine={false} tickLine={false}
            tick={{ fontSize: 11, fill: '#f97316' }}
            tickFormatter={v => `${Number(v).toFixed(2)}`} />
          <Tooltip contentStyle={{
            background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)',
            borderRadius: 8, fontSize: 12,
          }} />
          <Area yAxisId="tokens" type="monotone" dataKey="inputTokens" name="Input"
            stroke="#3b82f6" strokeWidth={1.5} fill="url(#inputGrad)" stackId="1" />
          <Area yAxisId="tokens" type="monotone" dataKey="outputTokens" name="Output"
            stroke="#22c55e" strokeWidth={1.5} fill="url(#outputGrad)" stackId="1" />
          <Area yAxisId="tokens" type="monotone" dataKey="cachedTokens" name="Cached"
            stroke="#a855f7" strokeWidth={1.5} fill="transparent" strokeDasharray="4 3" />
          <Area yAxisId="cost" type="monotone" dataKey="costUsd" name="Cost"
            stroke="#f97316" strokeWidth={2} fill="transparent" />
        </AreaChart>
      </ResponsiveContainer>
    </ChartShell>
  );
}

function ChartShell({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-lg border overflow-hidden"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="px-4 py-3 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <h2 className="font-semibold text-base">{title}</h2>
      </div>
      {children}
    </section>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className="w-2.5 h-2.5 rounded-sm" style={{ background: color }} />
      {label}
    </span>
  );
}

function formatTimestamp(ts: string, longRange: boolean): string {
  try {
    const d = new Date(ts);
    if (Number.isNaN(d.getTime())) return ts;
    if (longRange) return `${d.getMonth() + 1}/${d.getDate()}`;
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  } catch {
    return ts;
  }
}

export { ChartShell };
