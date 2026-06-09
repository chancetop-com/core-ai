import type { Edge } from '@xyflow/react';
import type { WorkflowRFNode } from './graph';

interface InputVar { name?: string }
interface Case { edge_id?: string; conditions?: { selector?: string }[] }

/**
 * Config-time issues for a node — advisory warnings surfaced in the config panel and as a canvas marker.
 * A superset of the backend's publish-time required-config rules (WorkflowValidator); the backend stays the
 * source of truth at publish, this just catches problems earlier while the user is still editing.
 */
export function nodeIssues(node: WorkflowRFNode, edges: Edge[]): string[] {
  const cfg = node.data.config ?? {};
  const str = (key: string) => String(cfg[key] ?? '').trim();
  const issues: string[] = [];
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

function ifElseIssues(cfg: Record<string, unknown>, nodeId: string, edges: Edge[], issues: string[]): void {
  const cases = Array.isArray(cfg.cases) ? (cfg.cases as Case[]) : [];
  if (edges.every((e) => e.source !== nodeId)) issues.push('Draw edges to the branch targets first.');
  if (cases.length === 0) issues.push('Add at least one branch.');
  cases.forEach((c, i) => {
    if (!c.edge_id) issues.push(`Branch ${i + 1} has no target.`);
    if (!(c.conditions ?? []).some((cond) => String(cond.selector ?? '').trim())) issues.push(`Branch ${i + 1} has no condition.`);
  });
}
