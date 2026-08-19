import { Clock, History } from 'lucide-react';
import { useRef, type ReactNode, type RefObject } from 'react';
import type { AnalyticsMode } from '../../../api/client';

interface ModeTimeSelectorProps {
  mode: AnalyticsMode;
  range: string;
  from: string;
  to: string;
  onModeChange: (mode: AnalyticsMode) => void;
  onRangeChange: (range: string) => void;
  onCustomChange: (from: string, to: string) => void;
}

const RANGES = [
  { key: '1d', label: 'Yesterday' },
  { key: '7d', label: '7 days' },
  { key: '30d', label: '30 days' },
  { key: 'custom', label: 'Custom' },
] as const;

export default function ModeTimeSelector({ mode, range, from, to, onModeChange, onRangeChange, onCustomChange }: ModeTimeSelectorProps) {
  const fromRef = useRef<HTMLInputElement>(null);
  const toRef = useRef<HTMLInputElement>(null);
  // clicking the field doesn't open the native picker in every browser/window state;
  // force it inside the click gesture so the calendar always pops up
  const openPicker = (ref: RefObject<HTMLInputElement | null>) => () => {
    try {
      ref.current?.showPicker();
    } catch {
      // already open or showPicker unsupported — native behavior still applies
    }
  };

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
        <>
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

          {range === 'custom' && (
            <div className="inline-flex items-center gap-1.5 rounded-lg border px-2 py-1"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <input ref={fromRef} type="date" value={from} max={to || undefined}
                onChange={event => onCustomChange(event.target.value, to)}
                onClick={openPicker(fromRef)}
                className="bg-transparent text-sm outline-none cursor-pointer"
                style={{ color: 'var(--color-text)' }} />
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>→</span>
              <input ref={toRef} type="date" value={to} min={from || undefined}
                onChange={event => onCustomChange(from, event.target.value)}
                onClick={openPicker(toRef)}
                className="bg-transparent text-sm outline-none cursor-pointer"
                style={{ color: 'var(--color-text)' }} />
            </div>
          )}
        </>
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
