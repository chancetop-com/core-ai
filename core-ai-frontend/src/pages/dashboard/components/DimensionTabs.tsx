import type { AnalyticsDimension } from '../../../api/client';

interface DimensionTabsProps {
  active: AnalyticsDimension;
  onChange: (dim: AnalyticsDimension) => void;
}

const TABS: { key: AnalyticsDimension; label: string }[] = [
  { key: 'source', label: 'Source' },
  { key: 'agent', label: 'Agent' },
  { key: 'user', label: 'User' },
  { key: 'provider', label: 'Provider' },
  { key: 'model', label: 'Model' },
];

export default function DimensionTabs({ active, onChange }: DimensionTabsProps) {
  return (
    <div className="flex border-b" style={{ borderColor: 'var(--color-border)' }}>
      {TABS.map(tab => (
        <button key={tab.key} onClick={() => onChange(tab.key)}
          className="relative px-4 py-2.5 text-sm font-medium cursor-pointer transition-colors"
          style={{
            color: active === tab.key ? 'var(--color-text)' : 'var(--color-text-secondary)',
          }}>
          {tab.label}
          {active === tab.key && (
            <span className="absolute bottom-0 left-2 right-2 h-0.5 rounded-full"
              style={{ background: 'var(--color-primary)' }} />
          )}
        </button>
      ))}
    </div>
  );
}
