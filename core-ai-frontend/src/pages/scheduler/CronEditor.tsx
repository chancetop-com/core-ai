import { useEffect, useMemo, useState } from 'react';

type Mode = 'hourly' | 'daily' | 'weekly' | 'monthly' | 'custom';

interface Simple {
  mode: Mode;
  minute: number;
  hour: number;
  weekdays: number[];
  dayOfMonth: number;
}

const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

function defaultSimple(): Simple {
  return { mode: 'daily', minute: 0, hour: 9, weekdays: [1], dayOfMonth: 1 };
}

function parseInt0(s: string): number | null {
  if (!/^\d+$/.test(s)) return null;
  return Number(s);
}

function parseWeekdays(field: string): number[] | null {
  if (field === '*') return [0, 1, 2, 3, 4, 5, 6];
  const parts = field.split(',');
  const out: number[] = [];
  for (const p of parts) {
    if (p.includes('-')) {
      const [a, b] = p.split('-').map(parseInt0);
      if (a === null || b === null || a > b) return null;
      for (let i = a; i <= b; i++) out.push(i === 7 ? 0 : i);
    } else {
      const n = parseInt0(p);
      if (n === null) return null;
      out.push(n === 7 ? 0 : n);
    }
  }
  return Array.from(new Set(out)).sort();
}

export function parseCron(expr: string): Simple {
  const parts = expr.trim().split(/\s+/);
  if (parts.length !== 5) return { ...defaultSimple(), mode: 'custom' };
  const [min, hour, dom, month, dow] = parts;
  const minN = parseInt0(min);
  const hourN = parseInt0(hour);

  if (month !== '*') return { ...defaultSimple(), mode: 'custom' };

  if (minN !== null && hour === '*' && dom === '*' && dow === '*') {
    return { ...defaultSimple(), mode: 'hourly', minute: minN };
  }
  if (minN !== null && hourN !== null && dom === '*' && dow === '*') {
    return { ...defaultSimple(), mode: 'daily', minute: minN, hour: hourN };
  }
  if (minN !== null && hourN !== null && dom === '*' && dow !== '*') {
    const wd = parseWeekdays(dow);
    if (wd) return { ...defaultSimple(), mode: 'weekly', minute: minN, hour: hourN, weekdays: wd };
  }
  const domN = parseInt0(dom);
  if (minN !== null && hourN !== null && domN !== null && dow === '*') {
    return { ...defaultSimple(), mode: 'monthly', minute: minN, hour: hourN, dayOfMonth: domN };
  }
  return { ...defaultSimple(), mode: 'custom' };
}

export function buildCron(s: Simple): string {
  switch (s.mode) {
    case 'hourly': return `${s.minute} * * * *`;
    case 'daily': return `${s.minute} ${s.hour} * * *`;
    case 'weekly': {
      const days = s.weekdays.length === 0 ? '*' : [...s.weekdays].sort().join(',');
      return `${s.minute} ${s.hour} * * ${days}`;
    }
    case 'monthly': return `${s.minute} ${s.hour} ${s.dayOfMonth} * *`;
    default: return '';
  }
}

export function describeCron(expr: string): string {
  const s = parseCron(expr);
  const pad = (n: number) => String(n).padStart(2, '0');
  switch (s.mode) {
    case 'hourly': return `Every hour at minute ${s.minute}`;
    case 'daily': return `Every day at ${pad(s.hour)}:${pad(s.minute)}`;
    case 'weekly': {
      if (s.weekdays.length === 7) return `Every day at ${pad(s.hour)}:${pad(s.minute)}`;
      const names = s.weekdays.map(d => WEEKDAY_LABELS[d]).join(', ');
      return `Every ${names} at ${pad(s.hour)}:${pad(s.minute)}`;
    }
    case 'monthly': return `Day ${s.dayOfMonth} of every month at ${pad(s.hour)}:${pad(s.minute)}`;
    default: return 'Custom cron expression';
  }
}

interface Props {
  value: string;
  onChange: (cron: string) => void;
}

export default function CronEditor({ value, onChange }: Props) {
  const parsed = useMemo(() => parseCron(value), [value]);
  const [mode, setMode] = useState<Mode>(parsed.mode);
  const [simple, setSimple] = useState<Simple>(parsed);
  const [customExpr, setCustomExpr] = useState(value);

  useEffect(() => {
    setMode(parsed.mode);
    setSimple(parsed);
    setCustomExpr(value);
  }, [value, parsed]);

  const updateSimple = (patch: Partial<Simple>) => {
    const next = { ...simple, ...patch, mode };
    setSimple(next);
    onChange(buildCron(next));
  };

  const switchMode = (m: Mode) => {
    setMode(m);
    if (m === 'custom') {
      onChange(customExpr || value);
    } else {
      const next = { ...simple, mode: m };
      setSimple(next);
      onChange(buildCron(next));
    }
  };

  const toggleWeekday = (d: number) => {
    const has = simple.weekdays.includes(d);
    const weekdays = has ? simple.weekdays.filter(x => x !== d) : [...simple.weekdays, d];
    updateSimple({ weekdays });
  };

  const MODES: Array<{ key: Mode; label: string }> = [
    { key: 'hourly', label: 'Hourly' },
    { key: 'daily', label: 'Daily' },
    { key: 'weekly', label: 'Weekly' },
    { key: 'monthly', label: 'Monthly' },
    { key: 'custom', label: 'Custom' },
  ];

  const inputStyle = { borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' };

  return (
    <div className="space-y-3">
      <div className="flex gap-1 flex-wrap">
        {MODES.map(m => (
          <button key={m.key} type="button" onClick={() => switchMode(m.key)}
            className="px-3 py-1.5 rounded-lg border text-xs cursor-pointer"
            style={{
              borderColor: mode === m.key ? 'var(--color-primary)' : 'var(--color-border)',
              background: mode === m.key ? 'var(--color-primary)' : 'transparent',
              color: mode === m.key ? '#fff' : 'var(--color-text)',
            }}>
            {m.label}
          </button>
        ))}
      </div>

      {mode === 'hourly' && (
        <div className="flex items-center gap-2 text-sm">
          <span style={{ color: 'var(--color-text-secondary)' }}>At minute</span>
          <input type="number" min={0} max={59} value={simple.minute}
            onChange={e => updateSimple({ minute: Math.max(0, Math.min(59, Number(e.target.value) || 0)) })}
            className="w-20 px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
          <span style={{ color: 'var(--color-text-secondary)' }}>of every hour</span>
        </div>
      )}

      {(mode === 'daily' || mode === 'weekly' || mode === 'monthly') && (
        <div className="flex items-center gap-2 text-sm">
          <span style={{ color: 'var(--color-text-secondary)' }}>At</span>
          <input type="time" value={`${String(simple.hour).padStart(2, '0')}:${String(simple.minute).padStart(2, '0')}`}
            onChange={e => {
              const [h, m] = e.target.value.split(':').map(Number);
              updateSimple({ hour: h || 0, minute: m || 0 });
            }}
            className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
        </div>
      )}

      {mode === 'weekly' && (
        <div>
          <div className="text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>On</div>
          <div className="flex gap-1">
            {WEEKDAY_LABELS.map((label, i) => {
              const active = simple.weekdays.includes(i);
              return (
                <button key={i} type="button" onClick={() => toggleWeekday(i)}
                  className="flex-1 px-2 py-1.5 rounded-lg border text-xs cursor-pointer"
                  style={{
                    borderColor: active ? 'var(--color-primary)' : 'var(--color-border)',
                    background: active ? 'var(--color-primary)' : 'transparent',
                    color: active ? '#fff' : 'var(--color-text)',
                  }}>
                  {label}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {mode === 'monthly' && (
        <div className="flex items-center gap-2 text-sm">
          <span style={{ color: 'var(--color-text-secondary)' }}>On day</span>
          <input type="number" min={1} max={31} value={simple.dayOfMonth}
            onChange={e => updateSimple({ dayOfMonth: Math.max(1, Math.min(31, Number(e.target.value) || 1)) })}
            className="w-20 px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
          <span style={{ color: 'var(--color-text-secondary)' }}>of every month</span>
        </div>
      )}

      {mode === 'custom' && (
        <input value={customExpr}
          onChange={e => { setCustomExpr(e.target.value); onChange(e.target.value); }}
          placeholder="0 9 * * 1-5"
          className="w-full px-3 py-2 rounded-lg border text-sm font-mono" style={inputStyle} />
      )}

      <div className="text-xs px-3 py-2 rounded-lg"
        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
        <span className="font-medium" style={{ color: 'var(--color-text)' }}>{describeCron(value)}</span>
        <span className="ml-2 font-mono">· {value || '(empty)'}</span>
      </div>
    </div>
  );
}
