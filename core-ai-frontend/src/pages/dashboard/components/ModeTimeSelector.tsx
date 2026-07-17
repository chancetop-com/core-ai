import { Clock, History } from 'lucide-react';
import type { ReactNode } from 'react';
import type { AnalyticsMode } from '../../../api/client';

interface ModeTimeSelectorProps {
  mode: AnalyticsMode;
  range: string;
  onModeChange: (mode: AnalyticsMode) => void;
  onRangeChange: (range: string) => void;
}

const RANGES = [
  { key: '7d', label: '7 days' },
  { key: '30d', label: '30 days' },
] as const;

export default function ModeTimeSelector({ mode, range, onModeChange, onRangeChange }: ModeTimeSelectorProps) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <div className="inline-flex rounded-lg border p-1"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <ModeButton active={mode === 'history'} onClick={() => onModeChange('history')}
          icon={<History size={14} />} label="History" />
        <ModeButton active={mode === 'realtime'} onClick={() => onModeChange('realtime')}
          icon={<Clock size={14} />} label="Real-time" />
      </div>

      {mode === 'history' && (
        <div className="inline-flex rounded-lg border p-1"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          {RANGES.map(r => (
            <button key={r.key} onClick={() => onRangeChange(r.key)}
              className="px-3 py-1.5 rounded-md text-sm cursor-pointer transition-colors"
              style={{
                background: range === r.key ? 'var(--color-bg-tertiary)' : 'transparent',
                color: range === r.key ? 'var(--color-text)' : 'var(--color-text-secondary)',
                fontWeight: range === r.key ? 600 : 400,
              }}>
              {r.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function ModeButton({ active, onClick, icon, label }: {
  active: boolean;
  onClick: () => void;
  icon: ReactNode;
  label: string;
}) {
  return (
    <button onClick={onClick}
      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm cursor-pointer transition-colors"
      style={{
        background: active ? 'var(--color-bg-tertiary)' : 'transparent',
        color: active ? 'var(--color-text)' : 'var(--color-text-secondary)',
        fontWeight: active ? 600 : 400,
      }}>
      {icon}
      {label}
    </button>
  );
}
