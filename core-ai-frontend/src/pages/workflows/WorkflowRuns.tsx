import { useCallback, useEffect, useState, type CSSProperties } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, ChevronRight, ChevronDown, RefreshCw, History } from 'lucide-react';
import { api, type WorkflowRunView, type WorkflowNodeRunView } from '../../api/client';
import { RUN_STATUS_COLOR, toReactFlow, type WorkflowGraph, type WorkflowRFNode } from './graph';
import RunTrace from './RunTrace';

interface Loaded {
  nodes: WorkflowRFNode[];                              // resolved from the run's own graph snapshot
  nodeRuns: Record<string, WorkflowNodeRunView>;
}

/** Standalone, read-only run history for a workflow (outside the editor — viewing runs can never mutate the graph).
 *  Each run's trace renders against the graph snapshot that actually executed, not the live draft. */
export default function WorkflowRuns() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [runs, setRuns] = useState<WorkflowRunView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [openRun, setOpenRun] = useState<string | null>(null);
  const [loaded, setLoaded] = useState<Record<string, Loaded>>({});
  const [loadingTrace, setLoadingTrace] = useState(false);

  const load = useCallback(() => {
    if (!id) return;
    setLoading(true);
    api.workflows.runs(id)
      .then((res) => setRuns(res.runs || []))
      .catch((e) => setError((e as Error).message))
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(() => {
    if (!id) return;
    api.workflows.get(id).then((wf) => setName(wf.name)).catch(() => { /* name is cosmetic */ });
    load();
  }, [id, load]);

  const select = async (runId: string) => {
    if (openRun === runId) { setOpenRun(null); return; }
    setOpenRun(runId);
    if (loaded[runId]) return;   // cached — graphs are immutable, no need to refetch
    setLoadingTrace(true);
    try {
      const [graphRes, nodeRes] = await Promise.all([api.workflows.runGraph(runId), api.workflows.nodeRuns(runId)]);
      const graph = JSON.parse(graphRes.graph) as WorkflowGraph;
      const { nodes } = toReactFlow(graph);
      const map: Record<string, WorkflowNodeRunView> = {};
      (nodeRes.node_runs || []).forEach((nr) => { map[nr.node_id] = nr; });
      setLoaded((prev) => ({ ...prev, [runId]: { nodes, nodeRuns: map } }));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoadingTrace(false);
    }
  };

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
        <button onClick={() => navigate('/workflows')} style={iconBtn} title="Back"><ArrowLeft size={16} /></button>
        <h1 style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 18, fontWeight: 600, margin: 0, color: 'var(--color-text)' }}>
          <History size={18} /> {name || 'Workflow'} · Run history
        </h1>
        <div style={{ flex: 1 }} />
        <button onClick={load} style={iconBtn} title="Refresh"><RefreshCw size={15} /></button>
      </div>

      {error && <div style={errText}>{error}</div>}
      {loading ? (
        <div style={dim}>Loading…</div>
      ) : runs.length === 0 ? (
        <div style={dim}>No runs yet. Use Test in the editor to run this workflow.</div>
      ) : (
        <div style={{ maxWidth: 760 }}>
          {runs.map((run) => {
            const open = openRun === run.id;
            const color = run.status ? RUN_STATUS_COLOR[run.status] : 'var(--color-text-secondary)';
            const data = loaded[run.id];
            return (
              <div key={run.id} style={runCard}>
                <div style={runHead} onClick={() => select(run.id)}>
                  {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                  <span style={{ width: 8, height: 8, borderRadius: '50%', background: color }} />
                  <span style={{ fontWeight: 500, color: 'var(--color-text)', flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {run.status || 'PENDING'}
                  </span>
                  <span style={dim}>{duration(run)}</span>
                  <span style={{ ...dim, marginLeft: 10 }}>{when(run.started_at)}</span>
                </div>
                {open && (
                  <div style={runBody}>
                    {loadingTrace && !data ? <div style={dim}>Loading trace…</div>
                      : data ? <RunTrace nodes={data.nodes} runStatus={run.status || ''} nodeRuns={data.nodeRuns} />
                      : <div style={dim}>No trace.</div>}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function duration(run: WorkflowRunView): string {
  if (!run.started_at || !run.completed_at) return '';
  const ms = new Date(run.completed_at).getTime() - new Date(run.started_at).getTime();
  if (Number.isNaN(ms) || ms < 0) return '';
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}

function when(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

const runCard: CSSProperties = { border: '1px solid var(--color-border)', borderRadius: 8, marginBottom: 8, background: 'var(--color-bg-secondary)' };
const runHead: CSSProperties = { display: 'flex', alignItems: 'center', gap: 8, padding: '11px 12px', cursor: 'pointer' };
const runBody: CSSProperties = { padding: '0 12px 12px' };
const dim: CSSProperties = { fontSize: 12, color: 'var(--color-text-secondary)' };
const errText: CSSProperties = { color: '#dc2626', fontSize: 12, marginBottom: 8 };
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 32, height: 32,
  border: '1px solid var(--color-border)', borderRadius: 8, background: 'var(--color-bg-secondary)',
  color: 'var(--color-text)', cursor: 'pointer',
};
