import { nodeMeta, visibleSourceIds, type WorkflowRFNode } from './graph';
import { startInputVars } from './configWidgets';
import type { Edge } from '@xyflow/react';

export interface OutputField { selector: string; label: string; type: string; }
export interface VarGroup { key: string; nodeName: string; color: string; fields: OutputField[]; }

// The output fields each node type exposes downstream — the static "schema" the picker offers and chips label.
// This is the lean, frontend-only output model (no backend type model yet); whole-output nodes expose one field.
export function nodeOutputFields(node: WorkflowRFNode): OutputField[] {
  const base = `nodes.${node.id}.output`;
  const artifacts = `nodes.${node.id}.artifacts`;
  switch (node.data.nodeType) {
    case 'HTTP':
      return [
        { selector: `${base}.status`, label: 'status', type: 'number' },
        { selector: `${base}.body`, label: 'body', type: 'any' },
        { selector: `${base}.headers`, label: 'headers', type: 'object' },
      ];
    case 'AGENT':
    case 'LLM':
      return [
        { selector: base, label: 'output', type: 'text' },
        { selector: artifacts, label: 'artifacts', type: 'array' },
      ];
    case 'AGGREGATOR':
      return [
        { selector: base, label: 'output', type: 'any' },
        { selector: artifacts, label: 'artifacts', type: 'array' },
      ];
    default:
      return [{ selector: base, label: 'output', type: 'any' }];
  }
}

// The run-input fields: the whole input plus each typed field declared on the START node.
export function runInputFields(nodes: WorkflowRFNode[]): OutputField[] {
  const fields: OutputField[] = [{ selector: 'sys.input', label: 'whole input', type: 'any' }];
  for (const iv of startInputVars(nodes)) {
    if (iv.name) fields.push({ selector: `sys.input.${iv.name}`, label: iv.name, type: iv.type });
  }
  return fields;
}

// Grouped variables a node can reference: the run input, then each node that DOMINATES this node (so the picker
// only offers references the publish-time dominator check will accept — END/AGGREGATOR see every node).
export function variableGroups(nodes: WorkflowRFNode[], edges: Edge[], selfId: string): VarGroup[] {
  const visible = visibleSourceIds(nodes, edges, selfId);
  const groups: VarGroup[] = [{ key: 'sys', nodeName: 'Run input', color: '#16a34a', fields: runInputFields(nodes) }];
  for (const n of nodes) {
    if (n.id === selfId || !visible.has(n.id)) continue;
    const t = n.data.nodeType;
    if (t === 'START' || t === 'END' || t === 'NOTE') continue;
    groups.push({ key: n.id, nodeName: n.data.name, color: nodeMeta(t).color, fields: nodeOutputFields(n) });
  }
  return groups;
}

// Resolve a stored selector to a display label + color, live — so a node rename reflects immediately while
// storage stays id-based (rename-safe). Unknown/dangling selectors fall back to the raw selector in grey.
export function selectorMeta(nodes: WorkflowRFNode[], selector: string): { label: string; color: string } {
  const node = selector.match(/^nodes\.([^.]+)\.(output|artifacts)(?:\.(.+))?$/);
  if (node) {
    const found = nodes.find((n) => n.id === node[1]);
    const name = found?.data.name ?? node[1];
    const color = found ? nodeMeta(found.data.nodeType).color : '#64748b';
    // output is the node's main value (shown as just the name); artifacts is a distinct file channel.
    const path = node[3];
    const suffix = node[2] === 'artifacts' ? (path ? `artifacts.${path}` : 'artifacts') : (path ?? '');
    return { label: suffix ? `${name}.${suffix}` : name, color };
  }
  if (selector === 'sys.input' || selector === 'input') return { label: 'input', color: '#16a34a' };
  const input = selector.match(/^sys\.input\.(.+)$/);
  if (input) return { label: `input.${input[1]}`, color: '#16a34a' };
  return { label: selector, color: '#64748b' };
}

// Split a template string into literal text + variable tokens, for chip rendering.
export interface Segment { text?: string; selector?: string; }
export function parseSegments(value: string): Segment[] {
  const segments: Segment[] = [];
  const token = /\{\{\s*([^}]+?)\s*\}\}/g;
  let last = 0;
  let match: RegExpExecArray | null;
  while ((match = token.exec(value)) !== null) {
    if (match.index > last) segments.push({ text: value.slice(last, match.index) });
    segments.push({ selector: match[1] });
    last = match.index + match[0].length;
  }
  if (last < value.length) segments.push({ text: value.slice(last) });
  return segments;
}
