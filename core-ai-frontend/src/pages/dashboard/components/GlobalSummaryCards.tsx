import { type ReactNode } from 'react';
import { ArrowDownRight, ArrowUpRight, Minus } from 'lucide-react';
import { formatCostUsd, formatCompact } from '../../traces/traceViewModel';
import type { AnalyticsGlobal } from '../../../api/client';

interface GlobalSummaryCardsProps {
  summary: AnalyticsGlobal;
  loading: boolean;
}

export default function GlobalSummaryCards({ summary, loading }: GlobalSummaryCardsProps) {
  const prevTokens = summary.prevTotalTokens;
  const prevCost = summary.prevTotalCostUsd;
  const tokenTrend = prevTokens != null && prevTokens > 0
    ? ((summary.totalTokens - prevTokens) / prevTokens) * 100 : null;
  const costTrend = prevCost != null && prevCost > 0
    ? ((summary.totalCostUsd - prevCost) / prevCost) * 100 : null;

  return (
    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-5">
      <Card label="Total Calls" value={formatCompact(summary.totalCalls)}
        icon={<span className="text-sm font-bold">#</span>}
        tone="primary"
        sub={loading ? '...' : `${formatCompact(summary.totalCalls)} requests`}
        loading={loading} />
      <Card label="Total Tokens" value={formatCompact(summary.totalTokens)}
        icon={<span className="text-sm font-bold">T</span>}
        tone="info"
        sub={summary.totalCachedTokens > 0
          ? `${formatCompact(summary.totalCachedTokens)} cached`
          : `${formatCompact(summary.totalInputTokens)} in / ${formatCompact(summary.totalOutputTokens)} out`}
        loading={loading}
        trend={tokenTrend} />
      <Card label="Total Cost" value={formatCostUsd(summary.totalCostUsd)}
        icon={<span className="text-sm font-bold">$</span>}
        tone="warning"
        sub={summary.totalCalls > 0
          ? `${formatCostUsd(summary.avgCostPerCall)} / call`
          : 'no calls'}
        loading={loading}
        trend={costTrend} />
      <Card label="Avg Tokens" value={formatCompact(Math.round(summary.avgTokensPerCall))}
        icon={<span className="text-sm font-bold">&mu;</span>}
        tone="teal"
        sub={`max ${formatCompact(summary.maxTokensPerCall)} / p90 ${formatCompact(Math.round(summary.p90TokensPerCall))}`}
        loading={loading} />
      <Card label="P90 Per Call" value={formatCompact(Math.round(summary.p90TokensPerCall))}
        icon={<span className="text-sm font-bold">P90</span>}
        tone="success"
        sub={`avg ${formatCompact(Math.round(summary.avgTokensPerCall))} / max ${formatCompact(summary.maxTokensPerCall)}`}
        loading={loading} />
    </div>
  );
}

function Card({ label, value, icon, tone, sub, loading, trend }: {
  label: string;
  value: string;
  icon: ReactNode;
  tone: 'primary' | 'info' | 'warning' | 'teal' | 'success';
  sub: string;
  loading: boolean;
  trend?: number | null;
}) {
  const color = cardColor(tone);
  return (
    <div className="rounded-lg border p-4"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="flex items-center justify-between gap-2 mb-3">
        <span className="text-xs font-semibold" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
        <span className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background: `${color}18`, color }}>
          {icon}
        </span>
      </div>
      {loading ? (
        <div className="h-7 w-20 rounded animate-pulse" style={{ background: 'var(--color-bg-tertiary)' }} />
      ) : (
        <>
          <div className="text-2xl font-bold" style={{ color }}>{value}</div>
          <div className="flex items-center gap-1.5 mt-2">
            <span className="text-xs truncate" style={{ color: 'var(--color-text-secondary)' }}>{sub}</span>
            {trend != null && (
              <span className="inline-flex items-center gap-0.5 text-xs font-semibold"
                style={{ color: trend > 0 ? '#dc2626' : trend < 0 ? '#16a34a' : 'var(--color-text-secondary)' }}>
                {trend > 0 ? <ArrowUpRight size={12} /> : trend < 0 ? <ArrowDownRight size={12} /> : <Minus size={12} />}
                {Math.abs(trend).toFixed(1)}%
              </span>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function cardColor(tone: string): string {
  switch (tone) {
    case 'primary': return '#6366f1';
    case 'info': return '#3b82f6';
    case 'warning': return '#d97706';
    case 'teal': return '#0891b2';
    case 'success': return '#16a34a';
    default: return '#6366f1';
  }
}
