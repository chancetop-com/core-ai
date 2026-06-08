import { useCallback, useEffect, useState, type CSSProperties } from 'react';
import { X, ChevronRight, ChevronDown, RefreshCw } from 'lucide-react';
import { api, type WorkflowRunView, type WorkflowNodeRunView } from '../../api/client';
import { RUN_STATUS_COLOR, type WorkflowRFNode } from './graph';
import RunTrace from './RunTrace';

interface Props {
  workflowId: string;
  nodes: WorkflowRFNode[];          // for resolving node names/types in the trace
  onClose: () => void;
}

/** Run-history panel: lists past runs of this workflow; expanding one loads its node-run trace (reusing RunTrace). */
export default function RunHistoryPanel({ workflowId, nodes, onClose }: Props) {
  const [runs, setRuns] = useState<WorkflowRunView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [openRun, setOpenRun] = useState<string | null>(null);
  const [nodeRuns, setNodeRuns] = useState<Record<string, WorkflowNodeRunView>>({});
  const [loadingTrace, setLoadingTrace] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    api.workflows.runs(workflowId)
      .then((res) => setRuns(res.runs || []))
      .catch((e) => setError((e as Error).message))
      .finally(() => setLoading(false));
  }, [workflowId]);
  useEffect(load, [load]);

  const select = async (runId: string) => {
    if (openRun === runId) { setOpenRun(null); return; }
    setOpenRun(runId);
    setNodeRuns({});
    setLoadingTrace(true);
    try {
      const res = await api.workflows.nodeRuns(runId);
      const map: Record<string, WorkflowNodeRunView> = {};
      (res.node_runs || []).forEach((nr) => { map[nr.node_id] = nr; });
      setNodeRuns(map);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoadingTrace(false);
    }
  };

  return (
    <div style={panel}>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14, color: 'var(--color-text)' }}>Run history</span>
        <div style={{ flex: 1 }} />
        <button onClick={load} style={iconBtn} title="Refresh"><RefreshCw size={14} /></button>
        <button onClick={onClose} style={{ ...iconBtn, marginLeft: 6 }} title="Close"><X size={15} /></button>
      </div>

      {error && <div style={errText}>{error}</div>}
      {loading ? (
        <div style={dim}>Loading…</div>
      ) : runs.length === 0 ? (
        <div style={dim}>No runs yet. Use Test to run this workflow.</div>
      ) : (
        runs.map((run) => {
          const open = openRun === run.id;
          const color = run.status ? RUN_STATUS_COLOR[run.status] : 'var(--color-text-secondary)';
          return (
            <div key={run.id} style={runCard}>
              <div style={runHead} onClick={() => select(run.id)}>
                {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: color }} />
                <span style={{ fontWeight: 500, color: 'var(--color-text)', flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {run.status || 'PENDING'}
                </span>
                <span style={dim}>{duration(run)}</span>
                <span style={{ ...dim, marginLeft: 8 }}>{when(run.started_at)}</span>
              </div>
              {open && (
                <div style={runBody}>
                  {loadingTrace ? <div style={dim}>Loading trace…</div>
                    : <RunTrace nodes={nodes} runStatus={run.status || ''} nodeRuns={nodeRuns} />}
                </div>
              )}
            </div>
          );
        })
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

const panel: CSSProperties = {
  width: '100%', height: '100%', boxSizing: 'border-box', padding: 16, overflowY: 'auto',
  background: 'var(--color-bg-secondary)',
};
const runCard: CSSProperties = { border: '1px solid var(--color-border)', borderRadius: 8, marginBottom: 6, background: 'var(--color-bg)' };
const runHead: CSSProperties = { display: 'flex', alignItems: 'center', gap: 8, padding: '9px 10px', cursor: 'pointer' };
const runBody: CSSProperties = { padding: '0 10px 10px' };
const dim: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)' };
const errText: CSSProperties = { color: '#dc2626', fontSize: 12, marginBottom: 8 };
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
