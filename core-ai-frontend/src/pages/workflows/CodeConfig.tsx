import { useState, type CSSProperties } from 'react';
import { Plus, X } from 'lucide-react';
import CodeMirrorEditor from '../../components/CodeMirrorEditor';
import type { WorkflowNodeData, WorkflowRFNode } from './graph';
import { availableVariables, widgetInput, smallBtn, iconBtnSmall } from './configWidgets';

interface Row { name: string; selector: string; }

interface Props {
  node: WorkflowRFNode;
  nodes: WorkflowRFNode[];
  onChange: (partial: Partial<WorkflowNodeData>) => void;
}

/** CODE node form: a Python editor plus named inputs (each a variable selector). Local state keyed off node.id
 *  avoids re-deriving rows from the config map on every keystroke (which would remap keys and fight the cursor);
 *  the config (code + inputs map the backend reads) is synced on every edit. */
export default function CodeConfig({ node, nodes, onChange }: Props) {
  // The panel is keyed by node.id at the call site, so this remounts per node and seeds once in the initializers
  // below — local state never desyncs from a different node and there is no stale-render window.
  const [code, setCode] = useState<string>(() => {
    const c = node.data.config?.code;
    return typeof c === 'string' ? c : '';
  });
  const [rows, setRows] = useState<Row[]>(() => {
    const inputs = node.data.config?.inputs;
    const map = inputs && typeof inputs === 'object' ? inputs as Record<string, unknown> : {};
    return Object.entries(map).map(([name, selector]) => ({ name, selector: String(selector) }));
  });
  const vars = availableVariables(nodes, node.id);

  const sync = (nextCode: string, nextRows: Row[]) => {
    setCode(nextCode);
    setRows(nextRows);
    const inputs: Record<string, string> = {};
    for (const r of nextRows) {
      if (r.name.trim()) inputs[r.name.trim()] = r.selector;
    }
    onChange({ config: { ...node.data.config, code: nextCode, inputs } });
  };

  return (
    <div>
      <label style={label}>Code (Python)</label>
      <div style={editorWrap}>
        <CodeMirrorEditor value={code} filename="script.py" onChange={(v: string) => sync(v, rows)} />
      </div>

      <label style={label}>Inputs</label>
      {rows.map((row, i) => (
        <div key={i} style={inputRow}>
          <input
            placeholder="name"
            value={row.name}
            onChange={(e) => sync(code, rows.map((r, j) => (j === i ? { ...r, name: e.target.value } : r)))}
            style={{ ...widgetInput, width: 80 }}
          />
          <span style={{ color: 'var(--color-text-secondary)' }}>=</span>
          <select
            value={row.selector}
            onChange={(e) => sync(code, rows.map((r, j) => (j === i ? { ...r, selector: e.target.value } : r)))}
            style={{ ...widgetInput, flex: 1, minWidth: 0 }}
          >
            <option value="">— select variable —</option>
            {vars.map((v) => <option key={v.selector} value={v.selector}>{v.label}</option>)}
          </select>
          <button onClick={() => sync(code, rows.filter((_, j) => j !== i))} style={iconBtnSmall} title="Remove input"><X size={12} /></button>
        </div>
      ))}
      <button onClick={() => sync(code, [...rows, { name: '', selector: 'sys.input' }])} style={smallBtn}><Plus size={12} /> input</button>
      <div style={hint}>The script reads inputs via <code>inputs['name']</code>; its stdout becomes the node output.</div>
    </div>
  );
}

const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px' };
const editorWrap: CSSProperties = { border: '1px solid var(--color-border)', borderRadius: 7, overflow: 'hidden' };
const inputRow: CSSProperties = { display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 };
const hint: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 8, lineHeight: 1.5 };
