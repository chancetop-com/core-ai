import { type CSSProperties } from 'react';
import { Plus, X } from 'lucide-react';
import type { WorkflowNodeData, WorkflowRFNode } from './graph';
import { INPUT_VAR_TYPES, widgetInput, smallBtn, iconBtnSmall, type InputVar } from './configWidgets';

interface Props {
  node: WorkflowRFNode;
  onChange: (partial: Partial<WorkflowNodeData>) => void;
}

/** START node form: declare the workflow's run-input variables. These drive the typed run form in the preview
 *  panel and are readable downstream as sys.input.<name>. Stored in config.inputs as an ordered array. */
export default function StartConfig({ node, onChange }: Props) {
  const inputs: InputVar[] = Array.isArray(node.data.config?.inputs) ? (node.data.config.inputs as InputVar[]) : [];
  const commit = (next: InputVar[]) => onChange({ config: { ...node.data.config, inputs: next } });
  const patch = (i: number, p: Partial<InputVar>) => commit(inputs.map((v, j) => (j === i ? { ...v, ...p } : v)));

  return (
    <div>
      <label style={label}>Input variables</label>
      {inputs.length === 0 && <div style={hint}>No inputs — the run form will accept raw JSON. Add a variable to get a typed form.</div>}
      {inputs.map((v, i) => (
        <div key={i} style={card}>
          <div style={row}>
            <input placeholder="name" value={v.name} onChange={(e) => patch(i, { name: e.target.value })} style={{ ...widgetInput, flex: 1, minWidth: 0 }} />
            <select value={v.type || 'text'} onChange={(e) => patch(i, { type: e.target.value })} style={{ ...widgetInput, width: 96 }}>
              {INPUT_VAR_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
            <button onClick={() => commit(inputs.filter((_, j) => j !== i))} style={iconBtnSmall} title="Remove"><X size={12} /></button>
          </div>
          <input placeholder="label (optional)" value={v.label ?? ''} onChange={(e) => patch(i, { label: e.target.value })} style={{ ...widgetInput, width: '100%', boxSizing: 'border-box', marginTop: 5 }} />
          {v.type === 'select' && (
            <input placeholder="options, comma-separated" value={v.options ?? ''} onChange={(e) => patch(i, { options: e.target.value })} style={{ ...widgetInput, width: '100%', boxSizing: 'border-box', marginTop: 5 }} />
          )}
          <label style={checkRow}>
            <input type="checkbox" checked={!!v.required} onChange={(e) => patch(i, { required: e.target.checked })} /> required
          </label>
        </div>
      ))}
      <button onClick={() => commit([...inputs, { name: '', type: 'text' }])} style={smallBtn}><Plus size={12} /> Add input variable</button>
    </div>
  );
}

const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px' };
const card: CSSProperties = { border: '1px solid var(--color-border)', borderRadius: 8, padding: 10, marginBottom: 8 };
const row: CSSProperties = { display: 'flex', alignItems: 'center', gap: 6 };
const checkRow: CSSProperties = { display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--color-text-secondary)', marginTop: 6 };
const hint: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)', marginBottom: 8, lineHeight: 1.5 };
