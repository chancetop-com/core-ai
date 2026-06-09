import { type CSSProperties } from 'react';

interface Props {
  config: Record<string, unknown>;
  onConfig: (partial: Record<string, unknown>) => void;   // merge into node config
}

/** Retry-on-failure config for AGENT/LLM/MCP/API nodes: max retries + interval. Empty = backend defaults (3 / 1000ms). */
export default function RetryFields({ config, onConfig }: Props) {
  const num = (v: unknown) => (v === undefined || v === null || v === '' ? '' : String(v));
  const set = (key: string, raw: string) => {
    if (raw.trim() === '') { onConfig({ [key]: undefined }); return; }   // clear -> fall back to default
    const n = Number(raw);
    if (!Number.isNaN(n) && n >= 0) onConfig({ [key]: Math.floor(n) });
  };
  return (
    <>
      <label style={label}>Retry on failure</label>
      <div style={{ display: 'flex', gap: 8 }}>
        <div style={{ flex: 1 }}>
          <div style={hint}>Max retries</div>
          <input type="number" min={0} max={10} value={num(config.max_retries)} placeholder="3"
            onChange={(e) => set('max_retries', e.target.value)} style={input} />
        </div>
        <div style={{ flex: 1 }}>
          <div style={hint}>Interval (ms)</div>
          <input type="number" min={0} value={num(config.retry_interval_ms)} placeholder="1000"
            onChange={(e) => set('retry_interval_ms', e.target.value)} style={input} />
        </div>
      </div>
    </>
  );
}

const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px' };
const hint: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)', marginBottom: 3 };
const input: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '7px 10px', fontSize: 13,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
