import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type DragEvent } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ReactFlow, Background, Controls, addEdge,
  useNodesState, useEdgesState,
  type Connection, type Edge, type ReactFlowInstance,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { ArrowLeft, Rocket, FlaskConical, Code2 } from 'lucide-react';
import { api, type WorkflowNodeRunView } from '../../api/client';
import { useTheme } from '../../hooks/useTheme';
import WorkflowNode from './WorkflowNode';
import NodePalette from './NodePalette';
import NodeConfigPanel from './NodeConfigPanel';
import RunPanel from './RunPanel';
import ApiAccessPanel from './ApiAccessPanel';
import ResizablePanel from './ResizablePanel';
import {
  toReactFlow, fromReactFlow, newGraph, nodeMeta, defaultNodeConfig, ensureStart, ensureEnd,
  branchLabel, edgeArrow, RUN_STATUS_COLOR, TERMINAL_RUN_STATUS,
  type WorkflowGraph, type WorkflowNodeData, type WorkflowRFNode,
} from './graph';
import { nodeIssues } from './validation';

const nodeTypes = { workflowNode: WorkflowNode };

function elapsedMs(nr: WorkflowNodeRunView): number | undefined {
  if (!nr.started_at) return undefined;
  const end = nr.completed_at ? new Date(nr.completed_at).getTime() : Date.now();
  const ms = end - new Date(nr.started_at).getTime();
  return Number.isNaN(ms) || ms < 0 ? undefined : ms;
}


export default function WorkflowEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { dark } = useTheme();
  const rfRef = useRef<ReactFlowInstance<WorkflowRFNode, Edge> | null>(null);
  const [name, setName] = useState('');
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
  const [showRun, setShowRun] = useState(false);   // test panel open
  const [showApi, setShowApi] = useState(false);           // API-access panel open
  const [runError, setRunError] = useState('');
  const [panelWidth, setPanelWidth] = useState(320);
  const [saveState, setSaveState] = useState<'saved' | 'saving' | 'dirty'>('saved');
  const [savedAt, setSavedAt] = useState('');
  const lastSavedRef = useRef('');
  const initSaveRef = useRef(false);
  const saveGenRef = useRef(0);                       // generation of the latest intended save
  const saveChainRef = useRef<Promise<unknown>>(Promise.resolve());   // serializes writes so they can't land out of order

  useEffect(() => {
    if (!id) return;
    api.workflows.get(id).then((wf) => {
      setName(wf.name);
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
      setNodes(ensureEnd(ensureStart(rfNodes)));
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
    setNodes((nds) => nds.concat({ id: nodeId, type: 'workflowNode', position, data: { nodeType: type, name: nodeMeta(type).label, config: defaultNodeConfig(type) } }));
    setSelectedId(nodeId);
  }, [setNodes, runId]);

  const updateNodeData = useCallback((nodeId: string, partial: Partial<WorkflowNodeData>) => {
    setNodes((nds) => nds.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, ...partial } } : n)));
  }, [setNodes]);

  const deleteNode = useCallback((nodeId: string) => {
    const t = nodes.find((n) => n.id === nodeId)?.data.nodeType;
    if (t === 'START' || t === 'END') return;   // START/END are the fixed entry/exit — protected
    setNodes((nds) => nds.filter((n) => n.id !== nodeId));
    setEdges((eds) => eds.filter((e) => e.source !== nodeId && e.target !== nodeId));
    setSelectedId(null);
  }, [nodes, setNodes, setEdges]);

  const selectedNode = useMemo(() => nodes.find((n) => n.id === selectedId) ?? null, [nodes, selectedId]);

  // during a run, tint each node with its run status (display-only, never persisted). Preserve object identity
  // for nodes whose status is unchanged so React Flow doesn't re-diff every node each poll.
  const displayNodes = useMemo(() => {
    if (runId) {
      return nodes.map((n) => {
        const nr = nodeRuns[n.id];
        const runStatus = nr?.status;
        const runMs = nr ? elapsedMs(nr) : undefined;
        return runStatus === n.data.runStatus && runMs === n.data.runMs ? n : { ...n, data: { ...n.data, runStatus, runMs } };
      });
    }
    // editing: flag nodes that still need configuration (advisory; the backend validates at publish)
    return nodes.map((n) => {
      const hasIssue = nodeIssues(n, nodes, edges).length > 0;
      return hasIssue === !!n.data.hasIssue ? n : { ...n, data: { ...n.data, hasIssue } };
    });
  }, [nodes, edges, nodeRuns, runId]);

  // Decorate edges for display only (never persisted — fromReactFlow drops these props): an arrow marker, an
  // IF_ELSE branch label, and — during a run — a traversed/skipped tint so the executed path is readable.
  const displayEdges = useMemo(() => {
    const byId = new Map(nodes.map((n) => [n.id, n] as const));
    return edges.map((e) => {
      const label = branchLabel(byId.get(e.source), e.id);
      const base: Edge = label
        ? { ...e, type: 'smoothstep', markerEnd: edgeArrow(), label, labelStyle: BRANCH_LABEL, labelBgStyle: BRANCH_LABEL_BG, labelBgPadding: [5, 3], labelBgBorderRadius: 4 }
        : { ...e, type: 'smoothstep', markerEnd: edgeArrow() };
      if (!runId) return base;
      const src = nodeRuns[e.source]?.status;
      const tgt = nodeRuns[e.target]?.status;
      if (src === 'SKIPPED' || tgt === 'SKIPPED') {
        return { ...base, animated: false, markerEnd: edgeArrow(RUN_STATUS_COLOR.SKIPPED), style: { stroke: RUN_STATUS_COLOR.SKIPPED, strokeDasharray: '5 4', opacity: 0.4 } };
      }
      if (src === 'COMPLETED' && tgt && tgt !== 'PENDING') {
        return { ...base, animated: tgt === 'RUNNING', markerEnd: edgeArrow(RUN_STATUS_COLOR.COMPLETED), style: { stroke: RUN_STATUS_COLOR.COMPLETED, strokeWidth: 2 } };
      }
      return base;
    });
  }, [edges, nodes, nodeRuns, runId]);

  // Single serialized writer for the draft. Writes are chained (never overlap → never land out of order on the
  // server) and generation-guarded (only the latest intended save updates lastSavedRef / the UI), so a slow
  // in-flight save completing after a newer edit can't falsely flip the indicator to "Saved" or persist stale.
  const persistDraft = useCallback((nameSnapshot: string, graph: string, snapshot: string): Promise<boolean> => {
    if (!id) return Promise.resolve(false);
    const gen = ++saveGenRef.current;
    setSaveState('saving');
    const run = saveChainRef.current.then(async (): Promise<boolean> => {
      try {
        await api.workflows.update(id, { name: nameSnapshot, graph });
        if (gen === saveGenRef.current) {
          lastSavedRef.current = snapshot;
          setSaveState('saved');
          setSavedAt(new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
        }
        return true;
      } catch (e) {
        if (gen === saveGenRef.current) { setSaveState('dirty'); setMsg(`Save failed: ${(e as Error).message}`); }
        return false;
      }
    });
    saveChainRef.current = run.catch(() => undefined);
    return run;
  }, [id]);

  const saveDraft = useCallback((): Promise<boolean> => {
    const graph = JSON.stringify(fromReactFlow(nodes, edges));
    return persistDraft(name, graph, name + '\n' + graph);
  }, [name, nodes, edges, persistDraft]);

  // debounced auto-save of the draft on any content change — Dify-style, no manual Save button
  useEffect(() => {
    if (loading || showRun || !id) return;
    const graph = JSON.stringify(fromReactFlow(nodes, edges));
    const snapshot = name + '\n' + graph;
    if (!initSaveRef.current) { initSaveRef.current = true; lastSavedRef.current = snapshot; return; }
    if (snapshot === lastSavedRef.current) return;
    setSaveState('dirty');
    const timer = setTimeout(() => { void persistDraft(name, graph, snapshot); }, 1200);
    return () => clearTimeout(timer);
  }, [nodes, edges, name, loading, showRun, id, persistDraft]);

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

  // Run = save the draft, validate it, then run the DRAFT (a throwaway preview snapshot) — no publish required,
  // like Dify. Validation errors surface in the run panel. Publishing stays a separate, explicit action.
  const startRun = async (input: string) => {
    if (!id || runId) return;
    setBusy(true); setMsg(''); setRunError('');
    try {
      if (!(await saveDraft())) { setRunError('Save failed'); return; }
      const validation = await api.workflows.validate(id);
      if (!validation.valid) { setRunError(`Cannot run: ${validation.errors.join('; ')}`); return; }
      const res = await api.workflows.previewRun(id, input);
      setSelectedId(null);
      setNodeRuns({});
      setRunStatus(res.status || 'PENDING');
      setRunId(res.run_id);
    } catch (e) {
      setRunError(`Run failed: ${(e as Error).message}`);
    } finally { setBusy(false); }
  };

  // Resume a paused run by settling the human-input node. Polling keeps running (PAUSED isn't terminal), so the
  // trace picks up the run going RUNNING again on the next tick.
  const startResume = async (nodeId: string, body: { approve?: boolean; input?: string }) => {
    if (!runId) return;
    setBusy(true); setRunError('');
    try {
      await api.workflows.resume(runId, { node_id: nodeId, ...body });
    } catch (e) {
      setRunError(`Resume failed: ${(e as Error).message}`);
    } finally { setBusy(false); }
  };

  const exitRun = () => {
    // setting runId to null triggers the polling effect's cleanup, which owns and stops the interval
    setRunId(null);
    setRunStatus('');
    setNodeRuns({});
    setSelectedId(null);
    setShowRun(false);
    setRunError('');
  };

  if (loading) return <div style={{ padding: 24, color: 'var(--color-text-secondary)' }}>Loading…</div>;
  const preview = showRun;   // run/preview mode: canvas read-only, palette hidden, run panel on the right
  return (
    <div style={{ height: 'calc(100vh - 56px)', display: 'flex', flexDirection: 'column' }}>
      <div style={toolbar}>
        <button onClick={() => navigate('/workflows')} style={iconBtn} title="Back"><ArrowLeft size={16} /></button>
        <input value={name} onChange={(e) => setName(e.target.value)} disabled={preview} style={nameInput} />
        <span style={statusBadge(status)}>{status}</span>
        <span style={{ fontSize: 11, color: 'var(--color-text-secondary)' }}>
          {saveState === 'saving' ? 'Saving…' : saveState === 'dirty' ? 'Unsaved changes' : savedAt ? `Saved ${savedAt}` : 'Saved'}
        </span>
        <div style={{ flex: 1 }} />
        {msg && <span style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginRight: 8 }}>{msg}</span>}
        <button onClick={() => { setSelectedId(null); setShowApi((v) => !v); }} disabled={busy || preview} style={showApi ? btnActive : btn}><Code2 size={15} /> API</button>
        <button onClick={publish} disabled={busy || preview} style={btn}><Rocket size={15} /> Publish</button>
        <button onClick={() => setShowRun(true)} disabled={busy || preview} style={btnPrimary}><FlaskConical size={15} /> Test</button>
      </div>

      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        {!preview && <NodePalette />}
        <div style={{ flex: 1 }} onDragOver={onDragOver} onDrop={onDrop}>
          <ReactFlow
            colorMode={dark ? 'dark' : 'light'}
            nodes={displayNodes}
            edges={displayEdges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onInit={(inst) => { rfRef.current = inst; }}
            onNodeClick={(_, node) => setSelectedId(node.id)}
            onPaneClick={() => { if (!preview) setSelectedId(null); }}
            nodesDraggable={!preview}
            nodesConnectable={!preview}
            deleteKeyCode={preview ? null : 'Backspace'}
            nodeTypes={nodeTypes}
            fitView
          >
            <Background />
            <Controls />
          </ReactFlow>
        </div>
        {(preview || selectedNode || showApi) && (
          <ResizablePanel width={panelWidth} onWidthChange={setPanelWidth}>
            {preview ? (
              <RunPanel
                nodes={nodes}
                runId={runId}
                runStatus={runStatus}
                nodeRuns={nodeRuns}
                busy={busy}
                error={runError}
                focusNodeId={selectedId}
                onRun={startRun}
                onResume={startResume}
                onClose={exitRun}
              />
            ) : selectedNode ? (
              <NodeConfigPanel
                key={selectedNode.id}
                node={selectedNode}
                nodes={nodes}
                edges={edges}
                agents={agents}
                onChange={(partial) => updateNodeData(selectedNode.id, partial)}
                onDelete={() => deleteNode(selectedNode.id)}
                onClose={() => setSelectedId(null)}
              />
            ) : showApi ? (
              <ApiAccessPanel workflowId={id || ''} status={status} nodes={nodes} onClose={() => setShowApi(false)} />
            ) : null}
          </ResizablePanel>
        )}
      </div>
    </div>
  );
}

// Edge label styling targets the SVG <text>/<rect> React Flow renders, so colors use `fill`, not `color`.
const BRANCH_LABEL: CSSProperties = { fontSize: 10, fontWeight: 700, fill: 'var(--color-text-secondary)' };
const BRANCH_LABEL_BG: CSSProperties = { fill: 'var(--color-bg-secondary)' };

const toolbar: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10, padding: '8px 16px',
  borderBottom: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)',
};
function statusBadge(status: string): CSSProperties {
  const published = status === 'PUBLISHED';
  return {
    fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 10,
    background: published ? 'rgba(22,163,74,0.14)' : 'var(--color-bg-tertiary)',
    color: published ? '#16a34a' : 'var(--color-text-secondary)',
  };
}
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
const btnActive: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px',
  border: '1px solid var(--color-primary)', borderRadius: 8, background: 'var(--color-bg-tertiary)',
  color: 'var(--color-primary)', cursor: 'pointer', fontWeight: 500,
};
const btnPrimary: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px',
  border: 'none', borderRadius: 8, background: 'var(--color-primary)', color: '#fff', cursor: 'pointer', fontWeight: 500,
};
