import { type CSSProperties } from 'react';
import type { Edge } from '@xyflow/react';
import type { WorkflowRFNode } from './graph';

// Operators mirror the backend IfElseExecutor; unary ones compare against no value.
export const OPERATORS: { value: string; label: string; unary?: boolean }[] = [
  { value: 'eq', label: 'equals' },
  { value: 'ne', label: 'not equals' },
  { value: 'contains', label: 'contains' },
  { value: 'not_contains', label: 'not contains' },
  { value: 'gt', label: '>' },
  { value: 'lt', label: '<' },
  { value: 'gte', label: '≥' },
  { value: 'lte', label: '≤' },
  { value: 'is_empty', label: 'is empty' },
  { value: 'is_not_empty', label: 'is not empty' },
];

export function isUnary(operator: string): boolean {
  return operator === 'is_empty' || operator === 'is_not_empty';
}

// Base variable selectors a node can read: the run input plus every other node's output.
export function availableVariables(nodes: WorkflowRFNode[], selfId: string): { selector: string; label: string }[] {
  const vars = [{ selector: 'sys.input', label: 'Run input' }];
  for (const n of nodes) {
    if (n.id === selfId || n.data.nodeType === 'END' || n.data.nodeType === 'NOTE') continue;
    vars.push({ selector: `nodes.${n.id}.output`, label: `${n.data.name} · output` });
  }
  return vars;
}

// Split a stored selector into its base variable and the optional dotted field path, for the two-control editor.
export function splitSelector(selector: string): { base: string; field: string } {
  if (!selector) return { base: '', field: '' };
  const m = selector.match(/^(nodes\.[^.]+\.output|sys\.input|input)(?:\.(.+))?$/);
  if (!m) return { base: '', field: selector };
  return { base: m[1] === 'input' ? 'sys.input' : m[1], field: m[2] ?? '' };
}

export function composeSelector(base: string, field: string): string {
  const f = field.trim();
  return f ? `${base}.${f}` : base;
}

export function outEdges(edges: Edge[], nodeId: string): Edge[] {
  return edges.filter((e) => e.source === nodeId);
}

export function nodeName(nodes: WorkflowRFNode[], id: string): string {
  return nodes.find((n) => n.id === id)?.data.name ?? id;
}

// An out-edge dropdown labeled by the target node's name — the edge id (a UUID) stays hidden from the user.
export function EdgeSelect({ edges, nodes, value, onChange }: {
  edges: Edge[]; nodes: WorkflowRFNode[]; value: string; onChange: (edgeId: string) => void;
}) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} style={{ ...widgetInput, minWidth: 130 }}>
      <option value="">— select branch —</option>
      {edges.map((e) => <option key={e.id} value={e.id}>{nodeName(nodes, e.target)}</option>)}
    </select>
  );
}

export const widgetInput: CSSProperties = {
  padding: '5px 8px', fontSize: 12, border: '1px solid var(--color-border)', borderRadius: 6,
  background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
export const smallBtn: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 4, padding: '4px 8px', fontSize: 12,
  border: '1px dashed var(--color-border)', borderRadius: 6, background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
export const iconBtnSmall: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 24, height: 24,
  border: '1px solid var(--color-border)', borderRadius: 6, background: 'var(--color-bg)', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
