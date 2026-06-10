import { type CSSProperties } from 'react';
import type { Edge } from '@xyflow/react';
import { Plus, X } from 'lucide-react';
import type { WorkflowNodeData, WorkflowRFNode } from './graph';
import { EdgeSelect, INPUT_VAR_TYPES, outEdges, widgetInput, smallBtn, iconBtnSmall, type InputVar } from './configWidgets';
import VariableChipField from './VariableChipField';

interface Props {
  node: WorkflowRFNode;
  nodes: WorkflowRFNode[];
  edges: Edge[];
  onChange: (partial: Partial<WorkflowNodeData>) => void;
}

/** HUMAN_INPUT node config. Two modes: approval (the human picks an approve/reject out-edge — a human-decided
 *  branch) or input (the human fills a form whose object becomes the node output). The prompt is templated so it
 *  can show an upstream value to confirm. */
export default function HumanInputConfig({ node, nodes, edges, onChange }: Props) {
  const config = (node.data.config ?? {}) as Record<string, unknown>;
  const mode = config.mode === 'input' ? 'input' : 'approval';
  const branches = outEdges(edges, node.id);
  const fields: InputVar[] = Array.isArray(config.fields) ? (config.fields as InputVar[]) : [];
  const set = (partial: Record<string, unknown>) => onChange({ config: { ...config, ...partial } });
  const patchField = (i: number, p: Partial<InputVar>) => set({ fields: fields.map((f, j) => (j === i ? { ...f, ...p } : f)) });

  return (
    <div>
      <label style={label}>Mode</label>
      <select value={mode} onChange={(e) => set({ mode: e.target.value })} style={input}>
        <option value="approval">Approval — human approves or rejects</option>
        <option value="input">Input — human fills a form</option>
      </select>

      <label style={label}>Prompt</label>
      <VariableChipField
        value={String(config.prompt ?? '')}
        onChange={(v) => set({ prompt: v })}
        nodes={nodes}
        selfId={node.id}
        placeholder="what the reviewer sees, e.g. Confirm sending {{ draft.output }}"
        multiline
      />

      {mode === 'approval' ? (
        branches.length === 0 ? (
          <div style={hint}>Draw edges from this node to the approve and reject targets first, then pick them here.</div>
        ) : (
          <>
            <label style={label}>On approve, go to</label>
            <EdgeSelect edges={branches} nodes={nodes} value={String(config.approve_edge_id ?? '')} onChange={(id) => set({ approve_edge_id: id })} />
            <label style={label}>On reject, go to</label>
            <EdgeSelect edges={branches} nodes={nodes} value={String(config.reject_edge_id ?? '')} onChange={(id) => set({ reject_edge_id: id })} />
          </>
        )
      ) : (
        <>
          <label style={label}>Form fields</label>
          {fields.length === 0 && <div style={hint}>Add the fields the human should fill; the collected object becomes this node's output.</div>}
          {fields.map((f, i) => (
            <div key={i} style={card}>
              <div style={row}>
                <input placeholder="name" value={f.name} onChange={(e) => patchField(i, { name: e.target.value })} style={{ ...widgetInput, flex: 1, minWidth: 0 }} />
                <select value={f.type || 'text'} onChange={(e) => patchField(i, { type: e.target.value })} style={{ ...widgetInput, width: 96 }}>
                  {INPUT_VAR_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
                <button onClick={() => set({ fields: fields.filter((_, j) => j !== i) })} style={iconBtnSmall} title="Remove"><X size={12} /></button>
              </div>
              <input placeholder="label (optional)" value={f.label ?? ''} onChange={(e) => patchField(i, { label: e.target.value })} style={{ ...widgetInput, width: '100%', boxSizing: 'border-box', marginTop: 5 }} />
              <label style={checkRow}>
                <input type="checkbox" checked={!!f.required} onChange={(e) => patchField(i, { required: e.target.checked })} /> required
              </label>
            </div>
          ))}
          <button onClick={() => set({ fields: [...fields, { name: '', type: 'text' }] })} style={smallBtn}><Plus size={12} /> Add field</button>
        </>
      )}
    </div>
  );
}

const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px' };
const input: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '7px 10px', fontSize: 13,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
const card: CSSProperties = { border: '1px solid var(--color-border)', borderRadius: 8, padding: 10, marginBottom: 8 };
const row: CSSProperties = { display: 'flex', alignItems: 'center', gap: 6 };
const checkRow: CSSProperties = { display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--color-text-secondary)', marginTop: 6 };
const hint: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)', margin: '6px 0', lineHeight: 1.5 };
