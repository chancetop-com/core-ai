import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { sourceColors } from '../../traces/colors';
import { formatCompact } from '../../traces/traceViewModel';
import type { TrendPoint, AnalyticsDimension } from '../../../api/client';

interface DimensionTrendChartProps {
  dimension: AnalyticsDimension;
  trends: Map<string, TrendPoint[]>;
  loading: boolean;
}

const COLORS = [
  '#6366f1', '#3b82f6', '#22c55e', '#f59e0b', '#ec4899',
  '#14b8a6', '#f97316', '#a855f7', '#06b6d4', '#10b981',
];

export default function DimensionTrendChart({ dimension, trends, loading }: DimensionTrendChartProps) {
  if (loading) {
    return (
      <div className="py-8 text-center text-sm animate-pulse" style={{ color: 'var(--color-text-secondary)' }}>
        Loading trends...
      </div>
    );
  }

  const entries = Array.from(trends.entries());
  if (entries.length === 0) return null;

  // merge all series into a single timeline with key -> value per timestamp
  const timeline = new Map<string, Record<string, number>>();
  entries.forEach(([key, points]) => {
    points.forEach(p => {
      const ts = formatTs(p.timestamp);
      const row = timeline.get(ts) || {};
      row[key] = (p.inputTokens || 0) + (p.outputTokens || 0);
      timeline.set(ts, row);
    });
  });

  const chartData = Array.from(timeline.entries())
    .map(([ts, keys]) => ({ timestamp: ts, ...keys }))
    .slice(0, 200); // guard against too many points

  if (chartData.length === 0) return null;

  return (
    <div style={{ background: 'var(--color-bg-secondary)' }}>
      <div className="flex items-center gap-4 px-4 pt-3 pb-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
        {entries.slice(0, 10).map(([key], i) => (
          <Legend key={key} color={dimension === 'source' ? sourceColors(key).color : COLORS[i % COLORS.length]} label={key} />
        ))}
      </div>
      <ResponsiveContainer width="100%" height={200}>
        <LineChart data={chartData} margin={{ top: 4, right: 12, left: 0, bottom: 0 }}>
          <XAxis dataKey="timestamp" axisLine={false} tickLine={false}
            tick={{ fontSize: 10, fill: 'var(--color-text-secondary)' }}
            interval="preserveStartEnd" />
          <YAxis axisLine={false} tickLine={false}
            tick={{ fontSize: 10, fill: 'var(--color-text-secondary)' }}
            tickFormatter={formatCompact} width={60} />
          <Tooltip contentStyle={{
            background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)',
            borderRadius: 8, fontSize: 11,
          }} />
          {entries.slice(0, 10).map(([key], i) => (
            <Line key={key} type="monotone" dataKey={key}
              stroke={dimension === 'source' ? sourceColors(key).color : COLORS[i % COLORS.length]}
              strokeWidth={1.5} dot={false} />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
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

function formatTs(ts: string): string {
  try {
    const d = new Date(ts);
    if (Number.isNaN(d.getTime())) return ts;
    return `${d.getMonth() + 1}/${d.getDate()}`;
  } catch {
    return ts;
  }
}
