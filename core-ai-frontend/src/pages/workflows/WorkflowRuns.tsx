import { useCallback, useEffect, useState, type CSSProperties } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Paperclip, RefreshCw, History, Clock, CheckCircle2, XCircle, Hash } from 'lucide-react';
import { api, type WorkflowRunView, type WorkflowNodeRunView } from '../../api/client';
import { RUN_STATUS_COLOR, toReactFlow, type WorkflowGraph, type WorkflowRFNode } from './graph';
import RunTrace from './RunTrace';

interface Loaded {
  nodes: WorkflowRFNode[];                              // resolved from the run's own graph snapshot
  nodeRuns: Record<string, WorkflowNodeRunView>;
}

/** Standalone, read-only run history for a workflow (outside the editor — viewing runs can never mutate the graph).
 *  Master-detail layout: run list on the left, the selected run's full trace on the right.
 *  Each run's trace renders against the graph snapshot that actually executed, not the live draft. */
export default function WorkflowRuns() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [name, setName] = useState('');
  const [runs, setRuns] = useState<WorkflowRunView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const [loaded, setLoaded] = useState<Record<string, Loaded>>({});
  const [loadingTrace, setLoadingTrace] = useState(false);

  const fetchTrace = useCallback(async (runId: string) => {
    setLoadingTrace(true);
    try {
      const nodeRes = await api.workflows.nodeRuns(runId);
      const map: Record<string, WorkflowNodeRunView> = {};
      (nodeRes.node_runs || []).forEach((nr) => { map[nr.node_id] = nr; });
      // The graph is the run's pinned version snapshot; for preview runs it is TTL'd after a day (404). The trace
      // is the real content, so fall back to nodes reconstructed from the node-runs rather than failing the page.
      let nodes: WorkflowRFNode[];
      try {
        nodes = toReactFlow(JSON.parse((await api.workflows.runGraph(runId)).graph) as WorkflowGraph).nodes;
      } catch {
        nodes = Object.values(map).map((nr, i) => ({
          id: nr.node_id, type: 'workflowNode', position: { x: 0, y: i * 80 },
          data: { nodeType: nr.node_type ?? '', name: nr.node_id, config: {} },
        }));
      }
      setLoaded((prev) => ({ ...prev, [runId]: { nodes, nodeRuns: map } }));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoadingTrace(false);
    }
  }, []);

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

  // Open the linked run on first load, then keep manual selection stable.
  useEffect(() => {
    if (runs.length === 0) { setSelected(null); return; }
    const requested = searchParams.get('run');
    if (selected && runs.some((r) => r.id === selected)) return;
    if (requested && runs.some((r) => r.id === requested)) {
      setSelected(requested);
      return;
    }
    setSelected(runs[0].id);
  }, [runs, searchParams, selected]);

  useEffect(() => {
    if (selected && !loaded[selected]) void fetchTrace(selected);
  }, [selected, loaded, fetchTrace]);

  const run = runs.find((r) => r.id === selected);
  const data = selected ? loaded[selected] : undefined;
  const stats = summarize(runs);

  return (
    <div style={{ height: 'calc(100vh - 56px)', display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '16px 24px 12px' }}>
        <button onClick={() => navigate('/workflows')} style={iconBtn} title="Back"><ArrowLeft size={16} /></button>
        <h1 style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 18, fontWeight: 600, margin: 0, color: 'var(--color-text)' }}>
          <History size={18} /> {name || 'Workflow'} · Run history
        </h1>
        <div style={{ flex: 1 }} />
        {runs.length > 0 && (
          <div style={statsRow}>
            <span style={statItem} title="Total runs"><Hash size={13} /> {stats.total}</span>
            <span style={{ ...statItem, color: '#16a34a' }} title="Completed"><CheckCircle2 size={13} /> {stats.completed}</span>
            <span style={{ ...statItem, color: stats.failed > 0 ? '#dc2626' : 'var(--color-text-secondary)' }} title="Failed"><XCircle size={13} /> {stats.failed}</span>
            {stats.avg && <span style={statItem} title="Average duration of completed runs"><Clock size={13} /> {stats.avg}</span>}
          </div>
        )}
        <button onClick={load} style={iconBtn} title="Refresh"><RefreshCw size={15} /></button>
      </div>

      {error && <div style={{ ...errText, padding: '0 24px' }}>{error}</div>}
      {loading ? (
        <div style={{ ...dim, padding: '8px 24px' }}>Loading…</div>
      ) : runs.length === 0 ? (
        <div style={emptyState}>
          <History size={32} style={{ opacity: 0.35 }} />
          <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--color-text)' }}>No runs yet</div>
          <div style={dim}>Use Test in the editor to run this workflow.</div>
        </div>
      ) : (
        <div style={{ flex: 1, display: 'flex', minHeight: 0, borderTop: '1px solid var(--color-border)' }}>
          <div style={listPane}>
            {runs.map((r) => {
              const active = r.id === selected;
              const color = r.status ? RUN_STATUS_COLOR[r.status] : 'var(--color-text-secondary)';
              return (
                <div key={r.id} style={{ ...runItem, ...(active ? runItemActive : {}) }} onClick={() => setSelected(r.id)}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ width: 8, height: 8, borderRadius: '50%', background: color, flexShrink: 0 }} />
                    <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--color-text)', flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {r.status || 'PENDING'}
                    </span>
                    {(r.artifacts?.length ?? 0) > 0 && (
                      <span style={fileBadge} title={`${r.artifacts!.length} deliverable file(s)`}>
                        <Paperclip size={11} /> {r.artifacts!.length}
                      </span>
                    )}
                    <span style={visibilityBadge}>{r.visibility === 'PUBLIC' ? 'Public' : 'Private'}</span>
                    <span style={dim}>{duration(r)}</span>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 3, paddingLeft: 16 }}>
                    <span style={{ ...dim, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {preview(r.input) || '—'}
                    </span>
                    <span style={{ ...dim, flexShrink: 0 }}>{when(r.started_at)}</span>
                  </div>
                </div>
              );
            })}
          </div>

          <div style={detailPane}>
            {!run ? (
              <div style={{ ...dim, padding: 24 }}>Select a run to view its trace.</div>
            ) : (
              <>
                <div style={detailHead}>
                  <span style={{ ...statusPill, background: `${RUN_STATUS_COLOR[run.status ?? ''] ?? 'var(--color-text-secondary)'}1a`, color: RUN_STATUS_COLOR[run.status ?? ''] ?? 'var(--color-text-secondary)' }}>
                    {run.status || 'PENDING'}
                  </span>
                  <span style={metaItem} title={run.id}>#{run.id.slice(0, 8)}</span>
                  {run.started_at && <span style={metaItem}><Clock size={12} /> {fullWhen(run.started_at)}</span>}
                  {duration(run) && <span style={metaItem}>{duration(run)}</span>}
                </div>
                {run.input && (
                  <div style={{ marginTop: 12 }}>
                    <div style={fieldLabel}>Input</div>
                    <pre style={inputPre}>{run.input}</pre>
                  </div>
                )}
                {loadingTrace && !data ? <div style={{ ...dim, marginTop: 14 }}>Loading trace…</div>
                  : data ? <RunTrace nodes={data.nodes} runStatus={run.status || ''} runError={run.error} nodeRuns={data.nodeRuns} />
                  : <div style={{ ...dim, marginTop: 14 }}>No trace.</div>}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function summarize(runs: WorkflowRunView[]) {
  const completed = runs.filter((r) => r.status === 'COMPLETED');
  const failed = runs.filter((r) => r.status === 'FAILED' || r.status === 'TIMEOUT').length;
  const durations = completed
    .map((r) => (r.started_at && r.completed_at ? new Date(r.completed_at).getTime() - new Date(r.started_at).getTime() : NaN))
    .filter((ms) => !Number.isNaN(ms) && ms >= 0);
  const avgMs = durations.length > 0 ? durations.reduce((a, b) => a + b, 0) / durations.length : null;
  return {
    total: runs.length,
    completed: completed.length,
    failed,
    avg: avgMs === null ? '' : avgMs < 1000 ? `${Math.round(avgMs)}ms` : `${(avgMs / 1000).toFixed(1)}s`,
  };
}

// One-line preview of the run input; JSON inputs collapse to a compact single line.
function preview(input?: string): string {
  if (!input) return '';
  try { return JSON.stringify(JSON.parse(input)); } catch { return input.replace(/\s+/g, ' '); }
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

function fullWhen(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleString([], { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

const listPane: CSSProperties = {
  width: 340, flexShrink: 0, overflowY: 'auto', padding: 12,
  borderRight: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)',
};
const detailPane: CSSProperties = { flex: 1, minWidth: 0, overflowY: 'auto', padding: '16px 24px 24px' };
const runItem: CSSProperties = {
  padding: '10px 12px', marginBottom: 6, borderRadius: 8, cursor: 'pointer',
  border: '1px solid var(--color-border)', background: 'var(--color-bg)',
};
const runItemActive: CSSProperties = {
  border: '1px solid var(--color-primary)',
  boxShadow: '0 0 0 1px var(--color-primary)',
};
const detailHead: CSSProperties = { display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' };
const statusPill: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', padding: '3px 10px',
  fontSize: 12, fontWeight: 600, borderRadius: 999,
};
const metaItem: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 5,
  fontSize: 12, color: 'var(--color-text-secondary)',
};
const fieldLabel: CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', marginBottom: 4 };
const inputPre: CSSProperties = {
  margin: 0, padding: '7px 9px', fontFamily: 'monospace', fontSize: 11.5, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
  border: '1px solid var(--color-border)', borderRadius: 6, background: 'var(--color-bg-secondary)', maxHeight: 160, overflowY: 'auto', color: 'var(--color-text)',
};
const statsRow: CSSProperties = { display: 'flex', alignItems: 'center', gap: 14, marginRight: 6 };
const statItem: CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12, color: 'var(--color-text-secondary)' };
const emptyState: CSSProperties = {
  flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
  gap: 8, color: 'var(--color-text-secondary)',
};
const dim: CSSProperties = { fontSize: 12, color: 'var(--color-text-secondary)' };
const fileBadge: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 3, padding: '1px 7px', fontSize: 11,
  borderRadius: 999, border: '1px solid var(--color-border)', color: 'var(--color-text-secondary)',
};
const visibilityBadge: CSSProperties = {
  padding: '1px 7px', fontSize: 11, borderRadius: 999,
  border: '1px solid var(--color-border)', color: 'var(--color-text-secondary)',
};
const errText: CSSProperties = { color: '#dc2626', fontSize: 12, marginBottom: 8 };
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 32, height: 32,
  border: '1px solid var(--color-border)', borderRadius: 8, background: 'var(--color-bg-secondary)',
  color: 'var(--color-text)', cursor: 'pointer',
};
