import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type DragEvent } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import {
  ReactFlow, Background, Controls, addEdge,
  useNodesState, useEdgesState,
  type Connection, type Edge, type ReactFlowInstance,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { ArrowLeft, Rocket, FlaskConical, Code2, Save, Download, FileUp, Copy, Lock, History } from 'lucide-react';
import { api, type WorkflowNodeRunView, type UnresolvedReferenceView } from '../../api/client';
import { useTheme } from '../../hooks/useTheme';
import WorkflowNode from './WorkflowNode';
import NodePalette from './NodePalette';
import NodeConfigPanel from './NodeConfigPanel';
import RunPanel from './RunPanel';
import ApiAccessPanel from './ApiAccessPanel';
import ResizablePanel from './ResizablePanel';
import {
  toReactFlow, fromReactFlow, newGraph, uniqueNodeName, defaultNodeConfig, ensureStart, ensureEnd,
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
  const location = useLocation();
  const { dark } = useTheme();
  const rfRef = useRef<ReactFlowInstance<WorkflowRFNode, Edge> | null>(null);
  const importInputRef = useRef<HTMLInputElement>(null);
  const [name, setName] = useState('');
  const [status, setStatus] = useState('DRAFT');
  const [readOnly, setReadOnly] = useState(false);   // viewing another user's published workflow: run-only, no edits
  const [authorName, setAuthorName] = useState('');
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
  const [resumedFrom, setResumedFrom] = useState<{ runId: string; nodeId: string } | null>(null);   // lineage of a resumed run
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
      setReadOnly(wf.editable === false);
      setAuthorName(wf.user_name || '');
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
    // Load both my agents and other users' agents so any published agent can be selected for a node.
    Promise.all([api.agents.list(true), api.agents.list(false)])
      .then(([mine, others]) => {
        const seen = new Set<string>();
        const published = [...(mine.agents || []), ...(others.agents || [])]
          .filter((a) => a.status === 'PUBLISHED')
          .filter((a) => (seen.has(a.id) ? false : (seen.add(a.id), true)));
        setAgents(published.map((a) => ({ id: a.id, name: a.name })));
      })
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
        setRunError(runView.error || '');
        setResumedFrom(runView.resumed_from_run_id
          ? { runId: runView.resumed_from_run_id, nodeId: runView.resume_from_node_id || '' } : null);
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

  const onConnect = useCallback((c: Connection) => {
    if (readOnly) return;   // defense-in-depth: never mutate a non-owner's read-only canvas
    setEdges((eds) => {
      const exists = eds.some((e) =>
        e.source === c.source && e.target === c.target
        && (e.sourceHandle ?? null) === (c.sourceHandle ?? null)
        && (e.targetHandle ?? null) === (c.targetHandle ?? null));
      return exists ? eds : addEdge({ ...c, id: `e_${crypto.randomUUID()}` }, eds);
    });
  }, [setEdges, readOnly]);

  const onDragOver = useCallback((e: DragEvent) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; }, []);
  const onDrop = useCallback((e: DragEvent) => {
    e.preventDefault();
    if (runId || readOnly) return; // canvas is read-only during a run, and for non-owner viewers
    const type = e.dataTransfer.getData('application/workflow-node');
    const inst = rfRef.current;
    if (!type || !inst) return;
    const position = inst.screenToFlowPosition({ x: e.clientX, y: e.clientY });
    const nodeId = `n_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;
    setNodes((nds) => nds.concat({
      id: nodeId,
      type: 'workflowNode',
      position,
      data: { nodeType: type, name: uniqueNodeName(type, nds), config: defaultNodeConfig(type) },
    }));
    setSelectedId(nodeId);
  }, [setNodes, runId, readOnly]);

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
    if (loading || showRun || !id || readOnly) return;   // read-only viewers never persist (update is owner-only)
    const graph = JSON.stringify(fromReactFlow(nodes, edges));
    const snapshot = name + '\n' + graph;
    if (!initSaveRef.current) { initSaveRef.current = true; lastSavedRef.current = snapshot; return; }
    if (snapshot === lastSavedRef.current) return;
    setSaveState('dirty');
    const timer = setTimeout(() => { void persistDraft(name, graph, snapshot); }, 1200);
    return () => clearTimeout(timer);
  }, [nodes, edges, name, loading, showRun, id, readOnly, persistDraft]);

  // Surface unresolved references handed over from the list page's import, then clear the router state so a
  // refresh or back-navigation doesn't replay the notice.
  useEffect(() => {
    const state = location.state as { importNotice?: UnresolvedReferenceView[]; cloneWarnings?: string[] } | null;
    const importNotice = state?.importNotice;
    const cloneWarnings = state?.cloneWarnings;
    if (importNotice && importNotice.length > 0) {
      setMsg(`Imported with ${importNotice.length} unresolved reference(s) — fix before publishing.`);
      navigate(location.pathname, { replace: true, state: null });
    } else if (cloneWarnings && cloneWarnings.length > 0) {
      // The cloned graph references agents the caller doesn't own — replace those nodes before Test/Publish.
      setMsg(`Cloned — replace the agent nodes you don't own before Test/Publish: ${cloneWarnings.join('; ')}`);
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location.pathname, location.state, navigate]);

  // Manual save (the draft also auto-saves on change; this is an explicit flush).
  const manualSave = async () => {
    if (!id || busy) return;
    setBusy(true); setMsg('');
    try {
      setMsg((await saveDraft()) ? 'Saved' : 'Save failed');
    } finally { setBusy(false); }
  };

  // Export the current canvas: flush the draft first so the downloaded envelope matches what is on screen.
  const exportCurrent = async () => {
    if (!id || busy) return;
    setBusy(true); setMsg('');
    try {
      if (!(await saveDraft())) { setMsg('Save failed; export aborted'); return; }
      const envelope = await api.workflows.export(id);
      const json = JSON.stringify(envelope, null, 2);
      const blob = new Blob([json], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${(name || 'workflow').replace(/\s+/g, '-').toLowerCase()}.workflow.json`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setMsg(`Export failed: ${(e as Error).message}`);
    } finally { setBusy(false); }
  };

  // Import replaces the current canvas with the file's graph (in place); the auto-save then persists it. Accepts
  // either a full export envelope ({ format, graph, ... }) or a raw graph ({ nodes, edges }).
  const importFromFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file || busy) return;
    if (!window.confirm('Importing will replace the current canvas. Continue?')) return;
    setBusy(true); setMsg('');
    try {
      const parsed = JSON.parse(await file.text());
      let graph: WorkflowGraph;
      if (typeof parsed.graph === 'string') graph = JSON.parse(parsed.graph);
      else if (Array.isArray(parsed.nodes) && Array.isArray(parsed.edges)) graph = parsed;
      else throw new Error('not a workflow export file');
      if (!Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) throw new Error('invalid graph shape');
      const { nodes: rfNodes, edges: rfEdges } = toReactFlow(graph);
      setNodes(ensureEnd(ensureStart(rfNodes)));
      setEdges(rfEdges);
      setSelectedId(null);
      setMsg('Imported. Review and Publish when ready.');
    } catch (err) {
      setMsg(`Import failed: ${(err as Error).message}`);
    } finally { setBusy(false); }
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

  // Run = save the draft, validate it, then run the DRAFT (a throwaway preview snapshot) — no publish required,
  // like Dify. Validation errors surface in the run panel. Publishing stays a separate, explicit action.
  const startRun = async (input: string) => {
    if (!id || runId) return;
    setBusy(true); setMsg(''); setRunError('');
    try {
      // read-only viewer: run the PUBLISHED version directly (there's no draft to save/validate, and both are owner-only)
      const res = readOnly
        ? await api.workflows.createRun(id, input)
        : await (async () => {
            if (!(await saveDraft())) throw new Error('Save failed');
            const validation = await api.workflows.validate(id);
            if (!validation.valid) throw new Error(`Cannot run: ${validation.errors.join('; ')}`);
            return api.workflows.previewRun(id, input);
          })();
      setSelectedId(null);
      setNodeRuns({});
      setResumedFrom(null);
      setRunStatus(res.status || 'PENDING');
      setRunId(res.run_id);
    } catch (e) {
      setRunError(`Run failed: ${(e as Error).message}`);
    } finally { setBusy(false); }
  };

  // Clone this (another user's published) workflow into a fresh draft owned by the caller, then open it.
  const cloneAndOpen = async () => {
    if (!id || busy) return;
    setBusy(true); setMsg('');
    try {
      const res = await api.workflows.clone(id);
      navigate(`/workflows/${res.workflow.id}`, { state: { cloneWarnings: res.warnings || [] } });
    } catch (e) {
      setMsg(`Clone failed: ${(e as Error).message}`);
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

  // Rerun from an executed node of a terminal run: the backend seeds the upstream prefix and returns a NEW run id;
  // re-point the trace to it (the polling effect keys on runId, so setting it switches polling automatically).
  const startResumeFromNode = async (nodeId: string) => {
    if (!runId) return;
    setBusy(true); setRunError('');
    try {
      const res = await api.workflows.resumeFromNode(runId, nodeId);
      setSelectedId(null);
      setNodeRuns({});
      setResumedFrom(null);   // cleared now; the next poll repopulates it from the new run's own lineage
      setRunStatus(res.status || 'PENDING');
      setRunId(res.run_id);
    } catch (e) {
      setRunError(`Rerun from node failed: ${(e as Error).message}`);
    } finally { setBusy(false); }
  };

  const exitRun = () => {
    // setting runId to null triggers the polling effect's cleanup, which owns and stops the interval
    setRunId(null);
    setRunStatus('');
    setNodeRuns({});
    setResumedFrom(null);
    setSelectedId(null);
    setShowRun(false);
    setShowApi(false);   // panels are mutually exclusive — closing Test must not resurrect a stale API panel
    setRunError('');
  };

  if (loading) return <div style={{ padding: 24, color: 'var(--color-text-secondary)' }}>Loading…</div>;
  const preview = showRun;   // run/preview mode: canvas read-only, palette hidden, run panel on the right
  return (
    <div style={{ height: 'calc(100vh - 56px)', display: 'flex', flexDirection: 'column' }}>
      <div style={toolbar}>
        <button onClick={() => navigate('/workflows')} style={iconBtn} title="Back"><ArrowLeft size={16} /></button>
        <input value={name} onChange={(e) => setName(e.target.value)} disabled={preview || readOnly} style={nameInput} />
        <span style={statusBadge(status)}>{status}</span>
        {readOnly ? (
          <span style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, color: 'var(--color-text-secondary)' }}>
            <Lock size={12} /> Read-only{authorName ? ` · by ${authorName}` : ''}
          </span>
        ) : (
          <span style={{ fontSize: 11, color: 'var(--color-text-secondary)' }}>
            {saveState === 'saving' ? 'Saving…' : saveState === 'dirty' ? 'Unsaved changes' : savedAt ? `Saved ${savedAt}` : 'Saved'}
          </span>
        )}
        <div style={{ flex: 1 }} />
        {msg && <span style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginRight: 8 }}>{msg}</span>}
        {readOnly ? (
          <>
            <button onClick={() => navigate(`/workflows/${id}/runs`)} disabled={busy} style={btn} title="View your run history"><History size={15} /> Run history</button>
            <button onClick={cloneAndOpen} disabled={busy} style={btn} title="Clone into my workflows to edit"><Copy size={15} /> Clone to edit</button>
            <button onClick={() => { setShowApi(false); setShowRun(true); }} disabled={busy || preview} style={btnPrimary}><FlaskConical size={15} /> Run</button>
          </>
        ) : (
          <>
            <input ref={importInputRef} type="file" accept=".json" style={{ display: 'none' }} onChange={importFromFile} />
            <button onClick={() => importInputRef.current?.click()} disabled={busy || preview} style={btn} title="Import a workflow file into the canvas"><FileUp size={15} /> Import</button>
            <button onClick={exportCurrent} disabled={busy || preview} style={btn} title="Export this workflow"><Download size={15} /> Export</button>
            <button onClick={manualSave} disabled={busy || preview} style={btn} title="Save draft"><Save size={15} /> Save</button>
            <button onClick={() => { setSelectedId(null); setShowApi((v) => !v); }} disabled={busy || preview} style={showApi ? btnActive : btn}><Code2 size={15} /> API</button>
            <button onClick={publish} disabled={busy || preview} style={btn}><Rocket size={15} /> Publish</button>
            <button onClick={() => { setShowApi(false); setShowRun(true); }} disabled={busy || preview} style={btnPrimary}><FlaskConical size={15} /> Test</button>
          </>
        )}
      </div>

      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        {!preview && !readOnly && <NodePalette />}
        <div style={{ flex: 1 }} onDragOver={onDragOver} onDrop={onDrop}>
          <ReactFlow
            colorMode={dark ? 'dark' : 'light'}
            nodes={displayNodes}
            edges={displayEdges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onInit={(inst) => { rfRef.current = inst; }}
            onNodeClick={(_, node) => { if (preview || !readOnly) setSelectedId(node.id); }}
            onPaneClick={() => { if (!preview && !readOnly) setSelectedId(null); }}
            nodesDraggable={!preview && !readOnly}
            nodesConnectable={!preview && !readOnly}
            deleteKeyCode={preview || readOnly ? null : 'Backspace'}
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
                onResumeFromNode={startResumeFromNode}
                resumedFrom={resumedFrom ?? undefined}
                onClose={exitRun}
              />
            ) : selectedNode && !readOnly ? (
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
