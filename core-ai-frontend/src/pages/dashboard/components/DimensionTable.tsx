import { useMemo } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { formatCostUsd, formatCompact } from '../../traces/traceViewModel';
import { sourceColors } from '../../traces/colors';
import Sparkline from './Sparkline';
import type { DimensionItem, TrendPoint, AnalyticsDimension } from '../../../api/client';

interface DimensionTableProps {
  items: DimensionItem[];
  dimension: AnalyticsDimension;
  sparklines: Map<string, TrendPoint[]>;
  sort: string;
  onSort: (field: string) => void;
  onSelect: (key: string) => void;
  selectedKey: string | null;
  loading: boolean;
}

type SortField = 'tokens' | 'cost' | 'calls' | 'avgTokens' | 'maxTokens' | 'p90Tokens';

const COLUMNS: { key: SortField; label: string; className?: string }[] = [
  { key: 'tokens', label: 'Tokens' },
  { key: 'cost', label: 'Cost' },
  { key: 'calls', label: 'Calls' },
  { key: 'avgTokens', label: 'Avg' },
  { key: 'maxTokens', label: 'Max' },
  { key: 'p90Tokens', label: 'P90' },
];

export default function DimensionTable({ items, dimension, sparklines, sort, onSort, onSelect, selectedKey, loading }: DimensionTableProps) {
  const sorted = useMemo(() => {
    const sorted = [...items];
    sorted.sort((a, b) => {
      switch (sort) {
        case 'cost': return b.costUsd - a.costUsd;
        case 'calls': return b.callCount - a.callCount;
        case 'avgTokens': return b.avgTotalTokens - a.avgTotalTokens;
        case 'maxTokens': return b.maxTotalTokens - a.maxTotalTokens;
        case 'p90Tokens': return b.p90TotalTokens - a.p90TotalTokens;
        default: return b.totalTokens - a.totalTokens;
      }
    });
    return sorted;
  }, [items, sort]);

  if (loading) {
    return (
      <div className="py-12 text-center text-sm animate-pulse" style={{ color: 'var(--color-text-secondary)' }}>
        Loading...
      </div>
    );
  }

  if (sorted.length === 0) {
    return (
      <div className="py-12 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
        No data for this dimension.
      </div>
    );
  }

  const globalTokens = sorted.reduce((s, i) => s + i.totalTokens, 0);

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
            <th className="text-left py-2 px-3 font-semibold text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              #
            </th>
            <th className="text-left py-2 px-3 font-semibold text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {dimLabel(dimension)}
            </th>
            <th className="text-left py-2 px-3 font-semibold text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              Trend
            </th>
            {COLUMNS.map(col => (
              <SortHeader key={col.key} label={col.label}
                active={sort === col.key} onClick={() => onSort(col.key)} />
            ))}
            <th className="text-right py-2 px-3 font-semibold text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              Share
            </th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((item, i) => (
            <tr key={item.key}
              onClick={() => onSelect(item.key)}
              className="border-b last:border-b-0 cursor-pointer transition-colors hover:bg-[var(--color-bg-tertiary)]"
              style={{
                borderColor: 'var(--color-border)',
                background: selectedKey === item.key ? 'var(--color-bg-tertiary)' : undefined,
              }}>
              <td className="py-2 px-3 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {i + 1}
              </td>
              <td className="py-2 px-3 font-medium">
                <div className="flex items-center gap-2">
                  {dimension === 'source' && (
                    <span className="w-2 h-2 rounded-full flex-shrink-0"
                      style={{ background: sourceColors(item.key).color }} />
                  )}
                  <span className="truncate">{item.label || item.key}</span>
                </div>
              </td>
              <td className="py-2 px-3">
                {sparklines.has(item.key) ? (
                  <Sparkline data={sparklines.get(item.key)!} color={dimension === 'source' ? sourceColors(item.key).color : '#6366f1'} />
                ) : (
                  <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>—</span>
                )}
              </td>
              <td className="py-2 px-3 text-right font-mono tabular-nums">
                {formatCompact(item.totalTokens)}
              </td>
              <td className="py-2 px-3 text-right font-mono tabular-nums" style={{ color: '#d97706' }}>
                {formatCostUsd(item.costUsd)}
              </td>
              <td className="py-2 px-3 text-right font-mono tabular-nums">
                {formatCompact(item.callCount)}
              </td>
              <td className="py-2 px-3 text-right font-mono tabular-nums" style={{ color: 'var(--color-text-secondary)' }}>
                {formatCompact(Math.round(item.avgTotalTokens))}
              </td>
              <td className="py-2 px-3 text-right font-mono tabular-nums">
                {formatCompact(item.maxTotalTokens)}
              </td>
              <td className="py-2 px-3 text-right font-mono tabular-nums" style={{ color: 'var(--color-text-secondary)' }}>
                {formatCompact(Math.round(item.p90TotalTokens))}
              </td>
              <td className="py-2 px-3 text-right text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {globalTokens > 0 ? (item.totalTokens / globalTokens * 100).toFixed(1) : '0.0'}%
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SortHeader({ label, active, onClick }: {
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <th className="text-right py-2 px-3 cursor-pointer select-none" onClick={onClick}>
      <span className="inline-flex items-center gap-1 text-xs font-semibold"
        style={{ color: active ? 'var(--color-text)' : 'var(--color-text-secondary)' }}>
        {label}
        {active ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
      </span>
    </th>
  );
}

function dimLabel(dim: AnalyticsDimension): string {
  switch (dim) {
    case 'source': return 'Source';
    case 'agent': return 'Agent';
    case 'user': return 'User';
    case 'provider': return 'Provider';
    case 'model': return 'Model';
    default: return dim;
  }
}
