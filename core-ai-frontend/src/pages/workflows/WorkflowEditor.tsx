import { useCallback, useEffect, useState, type CSSProperties } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ReactFlow, Background, Controls, addEdge,
  useNodesState, useEdgesState,
  type Connection, type Edge,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { ArrowLeft, Save, Rocket, Play } from 'lucide-react';
import { api } from '../../api/client';
import { useTheme } from '../../hooks/useTheme';
import WorkflowNode from './WorkflowNode';
import { toReactFlow, fromReactFlow, newGraph, type WorkflowGraph, type WorkflowRFNode } from './graph';

const nodeTypes = { workflowNode: WorkflowNode };

export default function WorkflowEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { dark } = useTheme();
  const [name, setName] = useState('');
  const [mode, setMode] = useState('WORKFLOW');
  const [status, setStatus] = useState('DRAFT');
  const [nodes, setNodes, onNodesChange] = useNodesState<WorkflowRFNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState('');

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

  const onConnect = useCallback((c: Connection) => setEdges((eds) => {
    const exists = eds.some((e) =>
      e.source === c.source && e.target === c.target
      && (e.sourceHandle ?? null) === (c.sourceHandle ?? null)
      && (e.targetHandle ?? null) === (c.targetHandle ?? null));
    return exists ? eds : addEdge({ ...c, id: `e_${crypto.randomUUID()}` }, eds);
  }), [setEdges]);

  const saveDraft = useCallback(async (): Promise<boolean> => {
    if (!id) return false;
    try {
      const graph = fromReactFlow(nodes, edges);
      await api.workflows.update(id, { name, graph: JSON.stringify(graph) });
      return true;
    } catch (e) {
      setMsg(`Save failed: ${(e as Error).message}`);
      return false;
    }
  }, [id, name, nodes, edges]);

  const save = async () => {
    if (busy) return;
    setBusy(true);
    setMsg('');
    try {
      if (await saveDraft()) setMsg('Saved');
    } finally {
      setBusy(false);
    }
  };

  const publish = async () => {
    if (!id || busy) return;
    setBusy(true);
    setMsg('');
    try {
      if (!(await saveDraft())) return;
      const result = await api.workflows.validate(id);
      if (!result.valid) {
        setMsg(`Cannot publish: ${result.errors.join('; ')}`);
        return;
      }
      const wf = await api.workflows.publish(id);
      setStatus(wf.status || 'PUBLISHED');
      setMsg(`Published v${wf.published_version}`);
    } catch (e) {
      setMsg(`Publish failed: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const run = async () => {
    if (!id || busy) return;
    setBusy(true);
    setMsg('');
    try {
      const res = await api.workflows.createRun(id, '{}');
      setMsg(`Run started: ${res.run_id}`);
    } catch (e) {
      setMsg(`Run failed: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  if (loading) return <div style={{ padding: 24, color: 'var(--color-text-secondary)' }}>Loading…</div>;
  return (
    <div style={{ height: 'calc(100vh - 56px)', display: 'flex', flexDirection: 'column' }}>
      <div style={toolbar}>
        <button onClick={() => navigate('/workflows')} style={iconBtn} title="Back"><ArrowLeft size={16} /></button>
        <input value={name} onChange={(e) => setName(e.target.value)} style={nameInput} />
        <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{mode} · {status}</span>
        <div style={{ flex: 1 }} />
        {msg && <span style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginRight: 8 }}>{msg}</span>}
        <button onClick={save} disabled={busy} style={btn}><Save size={15} /> Save</button>
        <button onClick={publish} disabled={busy} style={btn}><Rocket size={15} /> Publish</button>
        <button onClick={run} disabled={busy} style={btnPrimary}><Play size={15} /> Run</button>
      </div>
      <div style={{ flex: 1 }}>
        <ReactFlow
          colorMode={dark ? 'dark' : 'light'}
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          nodeTypes={nodeTypes}
          fitView
        >
          <Background />
          <Controls />
        </ReactFlow>
      </div>
    </div>
  );
}

const toolbar: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10, padding: '8px 16px',
  borderBottom: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)',
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
