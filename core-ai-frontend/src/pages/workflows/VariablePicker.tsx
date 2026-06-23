import { type CSSProperties } from 'react';
import { variableGroups } from './variables';
import type { WorkflowRFNode } from './graph';
import type { Edge } from '@xyflow/react';

/** A grouped variable dropdown: the nodes that dominate selfId (by name) -> their output fields (by name + type).
 *  Picking one calls onPick with the dotted selector; the host (chip field / map row) decides how to embed it. */
export default function VariablePicker({ nodes, edges, selfId, onPick, label, style }: {
  nodes: WorkflowRFNode[]; edges: Edge[]; selfId: string; onPick: (selector: string) => void; label?: string; style?: CSSProperties;
}) {
  const groups = variableGroups(nodes, edges, selfId);
  return (
    <select
      value=""
      onChange={(e) => { const v = e.target.value; e.currentTarget.selectedIndex = 0; if (v) onPick(v); }}
      style={{ ...picker, ...style }}
    >
      <option value="">{label ?? '+ insert variable'}</option>
      {groups.map((g) => (
        <optgroup key={g.key} label={g.nodeName}>
          {g.fields.map((f) => <option key={f.selector} value={f.selector}>{f.label} · {f.type}</option>)}
        </optgroup>
      ))}
    </select>
  );
}

const picker: CSSProperties = {
  fontSize: 11, padding: '4px 8px', border: '1px solid var(--color-border)', borderRadius: 6,
  background: 'var(--color-bg)', color: 'var(--color-text-secondary)', outline: 'none', cursor: 'pointer',
};
