import type { Node, Edge } from '@xyflow/react';

/** The persisted graph JSON shape (matches the backend WorkflowGraphParser). */
export interface GraphNode {
  id: string;
  type: string;
  name?: string;
  config?: Record<string, unknown>;
  position?: { x: number; y: number };
}
export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string | null;
}
export interface WorkflowGraph {
  format?: string;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export const NODE_TYPES = [
  'START', 'END', 'AGENT', 'LLM', 'CODE', 'HTTP', 'IF_ELSE', 'AGGREGATOR', 'ITERATION', 'LOOP', 'ANSWER', 'NOTE',
] as const;
export type NodeType = typeof NODE_TYPES[number];

export interface NodeTypeMeta { label: string; color: string; }

// The registry: one entry per node type. Adding a node type = one entry here (mirrors the backend executor registry).
export const NODE_TYPE_META: Record<string, NodeTypeMeta> = {
  START: { label: 'Start', color: '#16a34a' },
  END: { label: 'End', color: '#dc2626' },
  AGENT: { label: 'Agent', color: '#7c3aed' },
  LLM: { label: 'LLM', color: '#2563eb' },
  CODE: { label: 'Code', color: '#0891b2' },
  HTTP: { label: 'HTTP', color: '#ca8a04' },
  IF_ELSE: { label: 'If / Else', color: '#ea580c' },
  AGGREGATOR: { label: 'Aggregator', color: '#0d9488' },
  ITERATION: { label: 'Iteration', color: '#9333ea' },
  LOOP: { label: 'Loop', color: '#c026d3' },
  ANSWER: { label: 'Answer', color: '#059669' },
  NOTE: { label: 'Note', color: '#64748b' },
};
export function nodeMeta(type: string): NodeTypeMeta {
  return NODE_TYPE_META[type] ?? { label: type, color: '#64748b' };
}

export function newGraph(): WorkflowGraph {
  return {
    format: 'core-ai-workflow/v1',
    nodes: [
      { id: 'start', type: 'START', name: 'Start', position: { x: 120, y: 160 } },
      { id: 'end', type: 'END', name: 'End', position: { x: 520, y: 160 } },
    ],
    edges: [{ id: 'e_start_end', source: 'start', target: 'end' }],
  };
}

export interface WorkflowNodeData extends Record<string, unknown> {
  nodeType: string;
  name: string;
  config: Record<string, unknown>;
  runStatus?: string; // display-only, injected during a run overlay; never persisted (fromReactFlow drops it)
  runMs?: number;     // display-only run elapsed (ms), injected during a run overlay
}
export type WorkflowRFNode = Node<WorkflowNodeData>;

// A one-line summary of a node's config, shown on its canvas card (the "develop" glance).
export function nodeSummary(nodeType: string, config: Record<string, unknown>): string {
  const str = (v: unknown) => (typeof v === 'string' ? v : '');
  switch (nodeType) {
    case 'START': {
      const n = Array.isArray(config.inputs) ? config.inputs.length : 0;
      return n ? `${n} input${n > 1 ? 's' : ''}` : 'no inputs';
    }
    case 'AGENT':
    case 'LLM':
      return str(config.agent_name) || str(config.agent_id) || 'no agent selected';
    case 'CODE': {
      const lines = str(config.code).split('\n').filter((l) => l.trim()).length;
      return lines ? `Python · ${lines} line${lines > 1 ? 's' : ''}` : 'empty';
    }
    case 'IF_ELSE': {
      const n = Array.isArray(config.cases) ? config.cases.length : 0;
      return `${n} branch${n === 1 ? '' : 'es'}`;
    }
    case 'HTTP':
      return str(config.url) ? `${str(config.method) || 'GET'} ${str(config.url).slice(0, 22)}` : 'no url';
    case 'END':
    case 'ANSWER':
      return str(config.output) ? 'mapped output' : '';
    default:
      return '';
  }
}

// Status -> accent color. Covers BOTH backend enums: NodeRunStatus (node tint: RUNNING/COMPLETED/SKIPPED/
// FAILED_RETRYABLE/WAITING) and RunStatus (run strip: PENDING/RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED).
export const RUN_STATUS_COLOR: Record<string, string> = {
  PENDING: '#94a3b8',
  RUNNING: '#2563eb',
  WAITING: '#ca8a04',
  COMPLETED: '#16a34a',
  SKIPPED: '#94a3b8',
  FAILED: '#dc2626',
  FAILED_RETRYABLE: '#dc2626',
  TIMEOUT: '#dc2626',
  CANCELLED: '#6b7280',
};
// Terminal RUN statuses (RunStatus) — stop polling once reached.
export const TERMINAL_RUN_STATUS = new Set(['COMPLETED', 'FAILED', 'TIMEOUT', 'CANCELLED']);
// Terminal NODE statuses (NodeRunStatus) — count toward progress.
export const TERMINAL_NODE_STATUS = new Set(['COMPLETED', 'SKIPPED', 'FAILED_RETRYABLE']);

export function toReactFlow(graph: WorkflowGraph): { nodes: WorkflowRFNode[]; edges: Edge[] } {
  const nodes: WorkflowRFNode[] = graph.nodes.map((n, i) => ({
    id: n.id,
    type: 'workflowNode',
    position: n.position ?? { x: 120 + (i % 4) * 220, y: 120 + Math.floor(i / 4) * 150 },
    data: { nodeType: n.type, name: n.name ?? n.id, config: n.config ?? {} },
  }));
  const edges: Edge[] = graph.edges.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle ?? undefined,
  }));
  return { nodes, edges };
}

export function fromReactFlow(nodes: WorkflowRFNode[], edges: Edge[]): WorkflowGraph {
  return {
    format: 'core-ai-workflow/v1',
    nodes: nodes.map((n) => ({
      id: n.id,
      type: n.data.nodeType,
      name: n.data.name,
      config: n.data.config,
      position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    })),
    edges: edges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle ?? null,
    })),
  };
}
