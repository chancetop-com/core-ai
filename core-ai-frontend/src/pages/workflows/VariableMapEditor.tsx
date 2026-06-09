import { useState, type CSSProperties } from 'react';
import { Plus, X } from 'lucide-react';
import VariablePicker from './VariablePicker';
import { selectorMeta } from './variables';
import type { WorkflowRFNode } from './graph';

interface Props {
  // The stored value is a JSON-object template string, e.g. {"q":"{{ nodes.x.output }}","limit":10}.
  value: string;
  onChange: (next: string) => void;
  nodes: WorkflowRFNode[];
  selfId: string;
}

interface Row { key: string; value: string; } // value is either a {{ selector }} token or a raw literal

/** Structured argument editor: each named param is bound to a variable (chip) or a typed literal. Generates a
 *  flat JSON-object template string. Falls back to no rows if the stored value isn't a flat object. */
export default function VariableMapEditor({ value, onChange, nodes, selfId }: Props) {
  const [rows, setRows] = useState<Row[]>(() => parse(value));

  const sync = (next: Row[]) => {
    setRows(next);
    onChange(build(next));
  };
  const setRow = (i: number, patch: Partial<Row>) => sync(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  return (
    <>
      {rows.map((row, i) => {
        const token = row.value.match(/^\{\{\s*(.+?)\s*\}\}$/);
        const chip = token ? selectorMeta(nodes, token[1]) : null;
        return (
          <div key={i} style={rowBox}>
            <input value={row.key} placeholder="param" onChange={(e) => setRow(i, { key: e.target.value })} style={{ ...input, flex: 1 }} />
            {chip ? (
              <span style={{ ...chipStyle(chip.color), flex: 1.4 }} title={token![1]}>
                {chip.label}
                <button onClick={() => setRow(i, { value: '' })} style={chipX} title="Clear">×</button>
              </span>
            ) : (
              <input value={row.value} placeholder="value or pick a variable →" onChange={(e) => setRow(i, { value: e.target.value })} style={{ ...input, flex: 1.4 }} />
            )}
            <VariablePicker nodes={nodes} selfId={selfId} label="var" onPick={(sel) => setRow(i, { value: `{{ ${sel} }}` })} style={{ flexShrink: 0 }} />
            <button onClick={() => sync(rows.filter((_, j) => j !== i))} style={delBtn} title="Remove"><X size={14} /></button>
          </div>
        );
      })}
      <button onClick={() => setRows([...rows, { key: '', value: '' }])} style={addBtn}><Plus size={14} /> Add argument</button>
    </>
  );
}

// Parse a flat JSON-object template into rows. Values keep their raw form: a string that is exactly a {{...}}
// token stays a variable; other JSON scalars become their literal text (10, true, "hi" -> 10/true/hi).
function parse(value: string): Row[] {
  if (!value || !value.trim()) return [];
  try {
    const obj = JSON.parse(value) as Record<string, unknown>;
    if (typeof obj !== 'object' || obj === null || Array.isArray(obj)) return [];
    return Object.entries(obj).map(([key, v]) => ({ key, value: typeof v === 'string' ? v : JSON.stringify(v) }));
  } catch {
    return [];   // not a flat object (hand-written nested JSON) -> start empty rather than corrupt it
  }
}

// Build a JSON-object template string from rows. A {{...}} value is emitted as a JSON string (rendered later);
// a literal is emitted as JSON if it parses (number/bool/object), else as a quoted string.
function build(rows: Row[]): string {
  const parts: string[] = [];
  for (const r of rows) {
    const key = r.key.trim();
    if (!key) continue;
    const v = r.value.trim();
    const isVar = /^\{\{\s*.+?\s*\}\}$/.test(v);
    parts.push(`${JSON.stringify(key)}: ${isVar ? JSON.stringify(v) : literal(v)}`);
  }
  return parts.length ? `{ ${parts.join(', ')} }` : '';
}

function literal(v: string): string {
  if (v === '') return '""';
  try {
    JSON.parse(v);
    return v;   // valid JSON literal (number/bool/null/string/object) -> keep as-is
  } catch {
    return JSON.stringify(v);   // bare text -> quote it
  }
}

const rowBox: CSSProperties = { display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 };
const input: CSSProperties = {
  boxSizing: 'border-box', padding: '6px 9px', fontSize: 12, minWidth: 0,
  border: '1px solid var(--color-border)', borderRadius: 6, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
function chipStyle(color: string): CSSProperties {
  return {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'space-between', gap: 4, padding: '0 4px 0 8px', height: 30,
    fontSize: 12, borderRadius: 6, background: `${color}22`, color, border: `1px solid ${color}55`, overflow: 'hidden', whiteSpace: 'nowrap',
  };
}
const chipX: CSSProperties = { border: 'none', background: 'transparent', color: 'inherit', cursor: 'pointer', fontSize: 14, lineHeight: 1, padding: '0 2px' };
const addBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 5, padding: '5px 10px', fontSize: 12,
  border: '1px dashed var(--color-border)', borderRadius: 7, background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
const delBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 30, height: 30, flexShrink: 0,
  border: '1px solid var(--color-border)', borderRadius: 6, background: 'var(--color-bg)', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
