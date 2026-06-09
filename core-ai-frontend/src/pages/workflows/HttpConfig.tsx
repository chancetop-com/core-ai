import { useState, type CSSProperties } from 'react';
import { Plus, X } from 'lucide-react';
import VariableChipField from './VariableChipField';
import type { WorkflowRFNode } from './graph';

interface Props {
  config: Record<string, unknown>;
  onConfig: (partial: Record<string, unknown>) => void;
  nodes: WorkflowRFNode[];
  selfId: string;
}

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

interface Header { key: string; value: string; }

/** HTTP node config: method, templated url, header rows, and a templated body (for methods that send one). */
export default function HttpConfig({ config, onConfig, nodes, selfId }: Props) {
  const method = String(config.method ?? 'GET');
  const sendsBody = method !== 'GET' && method !== 'DELETE';

  const [rows, setRows] = useState<Header[]>(() => {
    const h = (config.headers && typeof config.headers === 'object') ? config.headers as Record<string, unknown> : {};
    return Object.entries(h).map(([key, value]) => ({ key, value: String(value) }));
  });

  const sync = (next: Header[]) => {
    setRows(next);
    const obj: Record<string, string> = {};
    next.forEach((r) => { if (r.key.trim()) obj[r.key.trim()] = r.value; });
    onConfig({ headers: obj });
  };

  return (
    <>
      <label style={label}>Method</label>
      <select value={method} onChange={(e) => onConfig({ method: e.target.value })} style={input}>
        {METHODS.map((m) => <option key={m} value={m}>{m}</option>)}
      </select>

      <label style={label}>URL</label>
      <VariableChipField
        value={String(config.url ?? '')}
        onChange={(v) => onConfig({ url: v })}
        nodes={nodes}
        selfId={selfId}
        placeholder="https://api.example.com/v1/…"
      />

      <label style={label}>Headers</label>
      {rows.map((row, i) => (
        <div key={i} style={{ display: 'flex', gap: 6, marginBottom: 6 }}>
          <input value={row.key} placeholder="Header" onChange={(e) => sync(rows.map((r, idx) => idx === i ? { ...r, key: e.target.value } : r))} style={{ ...input, flex: 1 }} />
          <input value={row.value} placeholder="value" onChange={(e) => sync(rows.map((r, idx) => idx === i ? { ...r, value: e.target.value } : r))} style={{ ...input, flex: 1 }} />
          <button onClick={() => sync(rows.filter((_, idx) => idx !== i))} style={delBtn} title="Remove"><X size={14} /></button>
        </div>
      ))}
      <button onClick={() => setRows([...rows, { key: '', value: '' }])} style={addBtn}><Plus size={14} /> Add header</button>

      {sendsBody && (
        <>
          <label style={label}>Body (JSON)</label>
          <VariableChipField
            value={String(config.body ?? '')}
            onChange={(v) => onConfig({ body: v })}
            nodes={nodes}
            selfId={selfId}
            placeholder='{"q": "…"}'
            multiline
          />
        </>
      )}

      <div style={hint}>Output: <code>{'{ status, headers, body }'}</code> — read e.g. <code>{'{{ nodes.' + selfId + '.output.body }}'}</code> downstream.</div>
    </>
  );
}

const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px' };
const hint: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 8, lineHeight: 1.5 };
const input: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '7px 10px', fontSize: 13,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
const addBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 5, padding: '5px 10px', fontSize: 12,
  border: '1px dashed var(--color-border)', borderRadius: 7, background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
const delBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 32, flexShrink: 0,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
