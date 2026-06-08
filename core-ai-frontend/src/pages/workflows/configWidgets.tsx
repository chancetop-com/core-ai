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

// A run-input variable declared on the START node — drives the typed run form (P2 variable model).
export interface InputVar { name: string; type: string; label?: string; required?: boolean; options?: string }

export const INPUT_VAR_TYPES = [
  { value: 'text', label: 'Text' },
  { value: 'paragraph', label: 'Paragraph' },
  { value: 'number', label: 'Number' },
  { value: 'boolean', label: 'Boolean' },
  { value: 'select', label: 'Select' },
];

export function startInputVars(nodes: WorkflowRFNode[]): InputVar[] {
  const start = nodes.find((n) => n.data.nodeType === 'START');
  const inputs = start?.data.config?.inputs;
  return Array.isArray(inputs) ? (inputs as InputVar[]) : [];
}

// Base variable selectors a node can read: the run input (and each declared input field) plus every other
// node's output.
export function availableVariables(nodes: WorkflowRFNode[], selfId: string): { selector: string; label: string }[] {
  const vars = [{ selector: 'sys.input', label: 'Run input' }];
  for (const iv of startInputVars(nodes)) {
    if (iv.name) vars.push({ selector: `sys.input.${iv.name}`, label: `Input · ${iv.label || iv.name}` });
  }
  for (const n of nodes) {
    if (n.id === selfId || n.data.nodeType === 'END' || n.data.nodeType === 'NOTE' || n.data.nodeType === 'START') continue;
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
  if (!base) return f;   // no recognized base variable -> don't compose a leading-dot garbage selector
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

// A text template field with an "insert variable" dropdown — the user picks an upstream variable and a
// {{ selector }} token is appended, so the template syntax never has to be typed or memorized.
export function TemplateField({ value, onChange, nodes, selfId, placeholder, rows }: {
  value: string; onChange: (next: string) => void; nodes: WorkflowRFNode[]; selfId: string; placeholder?: string; rows?: number;
}) {
  const vars = availableVariables(nodes, selfId);
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 4 }}>
        <select
          value=""
          onChange={(e) => { if (e.target.value) onChange(`${value ?? ''}{{ ${e.target.value} }}`); }}
          style={{ ...widgetInput, fontSize: 11 }}
        >
          <option value="">+ insert variable</option>
          {vars.map((v) => <option key={v.selector} value={v.selector}>{v.label}</option>)}
        </select>
      </div>
      <textarea
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        rows={rows ?? 3}
        spellCheck={false}
        style={{ ...widgetInput, width: '100%', boxSizing: 'border-box', fontFamily: 'monospace', resize: 'vertical' }}
      />
    </div>
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
