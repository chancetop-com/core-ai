import { useState, type CSSProperties } from 'react';
import { Maximize2, Plus, X } from 'lucide-react';
import CodeMirrorEditor from '../../components/CodeMirrorEditor';
import CodeEditorModal from './CodeEditorModal';
import { CODE_TEMPLATES, applyTemplate } from './codeTemplates';
import type { WorkflowNodeData, WorkflowRFNode } from './graph';
import { widgetInput, smallBtn, iconBtnSmall } from './configWidgets';
import VariablePicker from './VariablePicker';
import { selectorMeta } from './variables';
import type { Edge } from '@xyflow/react';

interface Row { name: string; selector: string; }

interface Props {
  node: WorkflowRFNode;
  nodes: WorkflowRFNode[];
  edges: Edge[];
  onChange: (partial: Partial<WorkflowNodeData>) => void;
}

/** CODE node form: a Python editor plus named inputs (each a variable selector). Local state keyed off node.id
 *  avoids re-deriving rows from the config map on every keystroke (which would remap keys and fight the cursor);
 *  the config (code + inputs map the backend reads) is synced on every edit. */
export default function CodeConfig({ node, nodes, edges, onChange }: Props) {
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
  const [expanded, setExpanded] = useState(false);
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
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <label style={{ ...label, flex: 1 }}>Code (Python)</label>
        <select
          value=""
          onChange={(e) => { if (e.target.value) sync(applyTemplate(code, CODE_TEMPLATES.find((t) => t.key === e.target.value)?.code ?? ''), rows); }}
          style={{ ...widgetInput, fontSize: 11 }}
        >
          <option value="">+ template</option>
          {CODE_TEMPLATES.map((t) => <option key={t.key} value={t.key}>{t.label}</option>)}
        </select>
        <button onClick={() => setExpanded(true)} style={iconBtnSmall} title="Expand editor"><Maximize2 size={13} /></button>
      </div>
      {/* the panel editor and the modal never mount together — one CodeMirror instance owns the doc at a time */}
      {!expanded && (
        <div style={editorWrap}>
          <CodeMirrorEditor value={code} filename="script.py" onChange={(v: string) => sync(v, rows)} />
        </div>
      )}
      {expanded && (
        <>
          <div style={{ ...editorWrap, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--color-text-secondary)', fontSize: 12 }}>
            Editing in fullscreen…
          </div>
          <CodeEditorModal title={node.data.name} code={code} onChange={(v) => sync(v, rows)} onClose={() => setExpanded(false)} />
        </>
      )}

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
          {row.selector ? (
            <span style={varChip(selectorMeta(nodes, row.selector).color)} title={row.selector}>
              {selectorMeta(nodes, row.selector).label}
              <button onClick={() => sync(code, rows.map((r, j) => (j === i ? { ...r, selector: '' } : r)))} style={chipX} title="Clear">×</button>
            </span>
          ) : (
            <span style={{ flex: 1, minWidth: 0, fontSize: 12, color: 'var(--color-text-secondary)' }}>no variable</span>
          )}
          <VariablePicker nodes={nodes} edges={edges} selfId={node.id} label="var" onPick={(sel) => sync(code, rows.map((r, j) => (j === i ? { ...r, selector: sel } : r)))} />
          <button onClick={() => sync(code, rows.filter((_, j) => j !== i))} style={iconBtnSmall} title="Remove input"><X size={12} /></button>
        </div>
      ))}
      <button onClick={() => sync(code, [...rows, { name: '', selector: 'sys.input' }])} style={smallBtn}><Plus size={12} /> input</button>
      <div style={hint}>Read mapped variables via <code>inputs['name']</code>; assign <code>result</code> to set the node output — <code>print()</code> is debug-only.</div>
    </div>
  );
}

const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px' };
// Fixed height: real scripts need stable editor space (CodeMirror scrolls inside); the modal is the big surface.
const editorWrap: CSSProperties = { border: '1px solid var(--color-border)', borderRadius: 7, overflow: 'hidden', height: 280 };
const inputRow: CSSProperties = { display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 };
const hint: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 8, lineHeight: 1.5 };
function varChip(color: string): CSSProperties {
  return {
    flex: 1, minWidth: 0, display: 'inline-flex', alignItems: 'center', justifyContent: 'space-between', gap: 4,
    padding: '0 4px 0 8px', height: 28, fontSize: 12, borderRadius: 6, overflow: 'hidden', whiteSpace: 'nowrap',
    background: `${color}22`, color, border: `1px solid ${color}55`,
  };
}
const chipX: CSSProperties = { border: 'none', background: 'transparent', color: 'inherit', cursor: 'pointer', fontSize: 14, lineHeight: 1, padding: '0 2px' };
