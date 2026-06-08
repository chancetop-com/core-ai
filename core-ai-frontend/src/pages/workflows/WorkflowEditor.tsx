import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type DragEvent } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ReactFlow, Background, Controls, addEdge,
  useNodesState, useEdgesState,
  type Connection, type Edge, type ReactFlowInstance,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { ArrowLeft, Save, Rocket, Play, Square } from 'lucide-react';
import { api, type WorkflowNodeRunView } from '../../api/client';
import { useTheme } from '../../hooks/useTheme';
import WorkflowNode from './WorkflowNode';
import NodePalette from './NodePalette';
import NodeConfigPanel from './NodeConfigPanel';
import NodeRunDetail from './NodeRunDetail';
import RunInputDialog from './RunInputDialog';
import {
  toReactFlow, fromReactFlow, newGraph, nodeMeta, RUN_STATUS_COLOR, TERMINAL_RUN_STATUS, TERMINAL_NODE_STATUS,
  type WorkflowGraph, type WorkflowNodeData, type WorkflowRFNode,
} from './graph';

const nodeTypes = { workflowNode: WorkflowNode };

export default function WorkflowEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { dark } = useTheme();
  const rfRef = useRef<ReactFlowInstance<WorkflowRFNode, Edge> | null>(null);
  const [name, setName] = useState('');
  const [mode, setMode] = useState('WORKFLOW');
  const [status, setStatus] = useState('DRAFT');
  const [nodes, setNodes, onNodesChange] = useNodesState<WorkflowRFNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [agents, setAgents] = useState<{ id: string; name: string }[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState('');
  // run overlay state
  const [runId, setRunId] = useState<string | null>(null);
  const [runStatus, setRunStatus] = useState('');
  const [nodeRuns, setNodeRuns] = useState<Record<string, WorkflowNodeRunView>>({});
  const [showRunDialog, setShowRunDialog] = useState(false);

  useEffect(() => {
    if (!id) return;
    api.workflows.get(id).then((wf) => {
      setName(wf.name);
      setMode(wf.mode || 'WORKFLOW');
      setStatus(wf.status || 'DRAFT');
      let graph: WorkflowGraph;
      try {
        graph = wf.draft_graph ? JSON.parse(wf.draft_graph) : newGraph();
        if (!Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) throw new Error('invalid graph shape');
      } catch {
        graph = newGraph();
        setMsg('Draft graph was invalid; loaded an empty workflow.');
      }
      const { nodes: rfNodes, edges: rfEdges } = toReactFlow(graph);
      setNodes(rfNodes);
      setEdges(rfEdges);
    }).catch((e) => {
      setMsg(`Failed to load workflow: ${(e as Error).message}`);
    }).finally(() => setLoading(false));
  }, [id, setNodes, setEdges]);

  useEffect(() => {
    api.agents.list(true)
      .then((res) => setAgents((res.agents || []).filter((a) => a.status === 'PUBLISHED').map((a) => ({ id: a.id, name: a.name }))))
      .catch(() => { /* agents are optional for non-agent workflows */ });
  }, []);

  // poll the active run until it reaches a terminal status. The interval handle is local to this effect run,
  // so each runId owns exactly one interval and cleanup can never clear the wrong one.
  useEffect(() => {
    if (!runId) return;
    let stopped = false;
    let timer: ReturnType<typeof setInterval> | null = null;
    let consecutiveErrors = 0;
    const tick = async () => {
      try {
        const [runView, nodeList] = await Promise.all([api.workflows.getRun(runId), api.workflows.nodeRuns(runId)]);
        if (stopped) return;
        consecutiveErrors = 0;
        setRunStatus(runView.status || 'RUNNING');
        const map: Record<string, WorkflowNodeRunView> = {};
        (nodeList.node_runs || []).forEach((nr) => { map[nr.node_id] = nr; });
        setNodeRuns(map);
        if (TERMINAL_RUN_STATUS.has(runView.status || '') && timer) { clearInterval(timer); timer = null; }
      } catch (e) {
        if (stopped) return;
        if (++consecutiveErrors >= 5) setMsg(`Polling failed: ${(e as Error).message}`);
      }
    };
    timer = setInterval(tick, 1000);
    tick();
    return () => { stopped = true; if (timer) clearInterval(timer); };
  }, [runId]);

  const onConnect = useCallback((c: Connection) => setEdges((eds) => {
    const exists = eds.some((e) =>
      e.source === c.source && e.target === c.target
      && (e.sourceHandle ?? null) === (c.sourceHandle ?? null)
      && (e.targetHandle ?? null) === (c.targetHandle ?? null));
    return exists ? eds : addEdge({ ...c, id: `e_${crypto.randomUUID()}` }, eds);
  }), [setEdges]);

  const onDragOver = useCallback((e: DragEvent) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; }, []);
  const onDrop = useCallback((e: DragEvent) => {
    e.preventDefault();
    if (runId) return; // canvas is read-only during a run
    const type = e.dataTransfer.getData('application/workflow-node');
    const inst = rfRef.current;
    if (!type || !inst) return;
    const position = inst.screenToFlowPosition({ x: e.clientX, y: e.clientY });
    const nodeId = `n_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;
    setNodes((nds) => nds.concat({ id: nodeId, type: 'workflowNode', position, data: { nodeType: type, name: nodeMeta(type).label, config: {} } }));
    setSelectedId(nodeId);
  }, [setNodes, runId]);

  const updateNodeData = useCallback((nodeId: string, partial: Partial<WorkflowNodeData>) => {
    setNodes((nds) => nds.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, ...partial } } : n)));
  }, [setNodes]);

  const deleteNode = useCallback((nodeId: string) => {
    setNodes((nds) => nds.filter((n) => n.id !== nodeId));
    setEdges((eds) => eds.filter((e) => e.source !== nodeId && e.target !== nodeId));
    setSelectedId(null);
  }, [setNodes, setEdges]);

  const selectedNode = useMemo(() => nodes.find((n) => n.id === selectedId) ?? null, [nodes, selectedId]);

  // during a run, tint each node with its run status (display-only, never persisted). Preserve object identity
  // for nodes whose status is unchanged so React Flow doesn't re-diff every node each poll.
  const displayNodes = useMemo(() => {
    if (!runId) return nodes;
    return nodes.map((n) => {
      const runStatus = nodeRuns[n.id]?.status;
      return runStatus === n.data.runStatus ? n : { ...n, data: { ...n.data, runStatus } };
    });
  }, [nodes, nodeRuns, runId]);

  const finishedCount = useMemo(() => Object.values(nodeRuns).filter((nr) => nr.status && TERMINAL_NODE_STATUS.has(nr.status)).length, [nodeRuns]);
  const startedCount = Object.keys(nodeRuns).length;

  const saveDraft = useCallback(async (): Promise<boolean> => {
    if (!id) return false;
    try {
      await api.workflows.update(id, { name, graph: JSON.stringify(fromReactFlow(nodes, edges)) });
      return true;
    } catch (e) {
      setMsg(`Save failed: ${(e as Error).message}`);
      return false;
    }
  }, [id, name, nodes, edges]);

  const save = async () => {
    if (busy) return;
    setBusy(true); setMsg('');
    try { if (await saveDraft()) setMsg('Saved'); } finally { setBusy(false); }
  };

  const publish = async () => {
    if (!id || busy) return;
    setBusy(true); setMsg('');
    try {
      if (!(await saveDraft())) return;
      const result = await api.workflows.validate(id);
      if (!result.valid) { setMsg(`Cannot publish: ${result.errors.join('; ')}`); return; }
      const wf = await api.workflows.publish(id);
      setStatus(wf.status || 'PUBLISHED');
      setMsg(`Published v${wf.published_version}`);
    } catch (e) {
      setMsg(`Publish failed: ${(e as Error).message}`);
    } finally { setBusy(false); }
  };

  const startRun = async (input: string) => {
    if (!id || runId) return;
    setShowRunDialog(false);
    setBusy(true); setMsg('');
    try {
      const res = await api.workflows.createRun(id, input);
      setSelectedId(null);
      setNodeRuns({});
      setRunStatus(res.status || 'PENDING');
      setRunId(res.run_id);
    } catch (e) {
      setMsg(`Run failed: ${(e as Error).message}`);
    } finally { setBusy(false); }
  };

  const exitRun = () => {
    // setting runId to null triggers the polling effect's cleanup, which owns and stops the interval
    setRunId(null);
    setRunStatus('');
    setNodeRuns({});
    setSelectedId(null);
  };

  if (loading) return <div style={{ padding: 24, color: 'var(--color-text-secondary)' }}>Loading…</div>;
  const running = !!runId;
  return (
    <div style={{ height: 'calc(100vh - 56px)', display: 'flex', flexDirection: 'column' }}>
      <div style={toolbar}>
        <button onClick={() => navigate('/workflows')} style={iconBtn} title="Back"><ArrowLeft size={16} /></button>
        <input value={name} onChange={(e) => setName(e.target.value)} disabled={running} style={nameInput} />
        <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{mode} · {status}</span>
        <div style={{ flex: 1 }} />
        {msg && <span style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginRight: 8 }}>{msg}</span>}
        <button onClick={save} disabled={busy || running} style={btn}><Save size={15} /> Save</button>
        <button onClick={publish} disabled={busy || running} style={btn}><Rocket size={15} /> Publish</button>
        <button onClick={() => setShowRunDialog(true)} disabled={busy || running} style={btnPrimary}><Play size={15} /> Run</button>
      </div>

      {running && (
        <div style={runStrip}>
          <span style={{ width: 9, height: 9, borderRadius: '50%', background: RUN_STATUS_COLOR[runStatus] ?? 'var(--color-text-secondary)' }} />
          <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--color-text)' }}>Run {runStatus}</span>
          <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{finishedCount}/{startedCount || nodes.length} nodes</span>
          <span style={{ fontSize: 11, color: 'var(--color-text-secondary)' }}>{runId}</span>
          <div style={{ flex: 1 }} />
          <button onClick={exitRun} style={btn}><Square size={13} /> Exit run</button>
        </div>
      )}

      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        {!running && <NodePalette />}
        <div style={{ flex: 1 }} onDragOver={onDragOver} onDrop={onDrop}>
          <ReactFlow
            colorMode={dark ? 'dark' : 'light'}
            nodes={displayNodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onInit={(inst) => { rfRef.current = inst; }}
            onNodeClick={(_, node) => setSelectedId(node.id)}
            onPaneClick={() => setSelectedId(null)}
            nodesDraggable={!running}
            nodesConnectable={!running}
            nodeTypes={nodeTypes}
            fitView
          >
            <Background />
            <Controls />
          </ReactFlow>
        </div>
        {!running && selectedNode && (
          <NodeConfigPanel
            node={selectedNode}
            nodes={nodes}
            edges={edges}
            agents={agents}
            onChange={(partial) => updateNodeData(selectedNode.id, partial)}
            onDelete={() => deleteNode(selectedNode.id)}
            onClose={() => setSelectedId(null)}
          />
        )}
        {running && selectedNode && (
          <NodeRunDetail node={selectedNode} nodeRun={nodeRuns[selectedNode.id]} onClose={() => setSelectedId(null)} />
        )}
      </div>

      {showRunDialog && <RunInputDialog onRun={startRun} onClose={() => setShowRunDialog(false)} busy={busy} />}
    </div>
  );
}

const toolbar: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10, padding: '8px 16px',
  borderBottom: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)',
};
const runStrip: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 12, padding: '6px 16px',
  borderBottom: '1px solid var(--color-border)', background: 'var(--color-bg-tertiary)',
};
const nameInput: CSSProperties = {
  fontSize: 15, fontWeight: 600, border: '1px solid transparent', borderRadius: 6,
  padding: '4px 8px', outline: 'none', minWidth: 200, background: 'transparent', color: 'var(--color-text)',
};
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 32, height: 32,
  border: '1px solid var(--color-border)', borderRadius: 8, background: 'var(--color-bg-secondary)',
  color: 'var(--color-text)', cursor: 'pointer',
};
const btn: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px',
  border: '1px solid var(--color-border)', borderRadius: 8, background: 'var(--color-bg-secondary)',
  color: 'var(--color-text)', cursor: 'pointer', fontWeight: 500,
};
const btnPrimary: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px',
  border: 'none', borderRadius: 8, background: 'var(--color-primary)', color: '#fff', cursor: 'pointer', fontWeight: 500,
};
