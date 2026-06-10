import type { Edge } from '@xyflow/react';
import type { WorkflowRFNode } from './graph';

interface InputVar { name?: string }
interface Case { edge_id?: string; conditions?: { selector?: string }[] }

/**
 * Config-time issues for a node — advisory warnings surfaced in the config panel and as a canvas marker.
 * A superset of the backend's publish-time required-config rules (WorkflowValidator); the backend stays the
 * source of truth at publish, this just catches problems earlier while the user is still editing.
 */
// Mirrors the backend SelectorScanner: the node ids a node's templates reference via {{ nodes.<id>... }}.
const NODE_SELECTOR = /\{\{\s*nodes\.([A-Za-z_][A-Za-z0-9_]*)/g;

export function nodeIssues(node: WorkflowRFNode, nodes: WorkflowRFNode[], edges: Edge[]): string[] {
  const cfg = node.data.config ?? {};
  const str = (key: string) => String(cfg[key] ?? '').trim();
  const issues: string[] = [];
  danglingReferenceIssues(node, nodes, issues);
  switch (node.data.nodeType) {
    case 'AGENT':
    case 'LLM':
      if (!str('agent_id')) issues.push('Select an agent.');
      break;
    case 'MCP_TOOL':
      if (!str('server_id')) issues.push('Select an MCP server.');
      else if (!str('tool_name')) issues.push('Select a tool.');
      break;
    case 'API_TOOL':
      if (!str('app_name')) issues.push('Select an API app.');
      else if (!str('tool_name')) issues.push('Select an operation.');
      break;
    case 'HTTP':
      if (!str('url')) issues.push('Set a request URL.');
      break;
    case 'CODE':
      if (!str('code')) issues.push('Add the Python code to run.');
      break;
    case 'START':
      startIssues(cfg, issues);
      break;
    case 'IF_ELSE':
      ifElseIssues(cfg, node.id, edges, issues);
      break;
    case 'HUMAN_INPUT':
      humanInputIssues(cfg, node.id, edges, issues);
      break;
    default:
      break;
  }
  return issues;
}

function startIssues(cfg: Record<string, unknown>, issues: string[]): void {
  const inputs = Array.isArray(cfg.inputs) ? (cfg.inputs as InputVar[]) : [];
  if (inputs.some((v) => !String(v.name ?? '').trim())) issues.push('Every input variable needs a name.');
  const names = inputs.map((v) => String(v.name ?? '').trim()).filter(Boolean);
  if (new Set(names).size !== names.length) issues.push('Input variable names must be unique.');
}

// Flag templates referencing a node that no longer exists (deleted/replaced) — the backend rejects these at
// publish/run with a cryptic "references unknown node X"; surfacing them on the canvas catches them early.
function danglingReferenceIssues(node: WorkflowRFNode, nodes: WorkflowRFNode[], issues: string[]): void {
  const text = JSON.stringify({ name: node.data.name, config: node.data.config ?? {} });
  const known = new Set(nodes.map((n) => n.id));
  const seen = new Set<string>();
  let match: RegExpExecArray | null;
  NODE_SELECTOR.lastIndex = 0;
  while ((match = NODE_SELECTOR.exec(text)) !== null) {
    const ref = match[1];
    if (ref !== node.id && !known.has(ref) && !seen.has(ref)) {
      seen.add(ref);
      issues.push(`References a deleted node (${ref}) — clear that variable.`);
    }
  }
}

function humanInputIssues(cfg: Record<string, unknown>, nodeId: string, edges: Edge[], issues: string[]): void {
  const outs = edges.filter((e) => e.source === nodeId).map((e) => e.id);
  if (cfg.mode === 'input') {
    const fields = Array.isArray(cfg.fields) ? (cfg.fields as InputVar[]) : [];
    if (fields.length === 0) issues.push('Add at least one form field.');
    if (fields.some((f) => !String(f.name ?? '').trim())) issues.push('Every form field needs a name.');
  } else {
    const approve = String(cfg.approve_edge_id ?? '');
    const reject = String(cfg.reject_edge_id ?? '');
    if (!approve || !outs.includes(approve)) issues.push('Pick the approve target edge.');
    if (!reject || !outs.includes(reject)) issues.push('Pick the reject target edge.');
  }
}

function ifElseIssues(cfg: Record<string, unknown>, nodeId: string, edges: Edge[], issues: string[]): void {
  const cases = Array.isArray(cfg.cases) ? (cfg.cases as Case[]) : [];
  if (edges.every((e) => e.source !== nodeId)) issues.push('Draw edges to the branch targets first.');
  if (cases.length === 0) issues.push('Add at least one branch.');
  cases.forEach((c, i) => {
    if (!c.edge_id) issues.push(`Branch ${i + 1} has no target.`);
    if (!(c.conditions ?? []).some((cond) => String(cond.selector ?? '').trim())) issues.push(`Branch ${i + 1} has no condition.`);
  });
}
