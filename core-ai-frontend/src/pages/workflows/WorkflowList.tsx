import { useEffect, useState, type CSSProperties, type MouseEvent as ReactMouseEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Trash2, Workflow as WorkflowIcon } from 'lucide-react';
import { api, type WorkflowView } from '../../api/client';
import { newGraph } from './graph';

export default function WorkflowList() {
  const [workflows, setWorkflows] = useState<WorkflowView[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');
  const navigate = useNavigate();

  useEffect(() => {
    api.workflows.list()
      .then((res) => setWorkflows(res.workflows || []))
      .catch((e) => setError((e as Error).message))
      .finally(() => setLoading(false));
  }, []);

  const create = async () => {
    setCreating(true);
    setError('');
    try {
      const wf = await api.workflows.create({ name: 'Untitled workflow', mode: 'WORKFLOW', graph: JSON.stringify(newGraph()) });
      navigate(`/workflows/${wf.id}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setCreating(false);
    }
  };

  const del = async (e: ReactMouseEvent, wf: WorkflowView) => {
    e.stopPropagation();
    if (!window.confirm(`Delete "${wf.name}"? This cannot be undone.`)) return;
    try {
      await api.workflows.delete(wf.id);
      setWorkflows((ws) => ws.filter((w) => w.id !== wf.id));
    } catch (err) {
      setError((err as Error).message);
    }
  };

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h1 style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 20, fontWeight: 600, margin: 0, color: 'var(--color-text)' }}>
          <WorkflowIcon size={20} /> Workflows
        </h1>
        <button onClick={create} disabled={creating} style={btnPrimary}>
          <Plus size={16} /> New workflow
        </button>
      </div>
      {error && <div style={{ color: '#dc2626', marginBottom: 12 }}>{error}</div>}
      {loading ? (
        <div style={{ color: 'var(--color-text-secondary)' }}>Loading…</div>
      ) : workflows.length === 0 ? (
        <div style={{ color: 'var(--color-text-secondary)' }}>No workflows yet. Create one to get started.</div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 12 }}>
          {workflows.map((wf) => (
            <div key={wf.id} onClick={() => navigate(`/workflows/${wf.id}`)} style={card}>
              <button onClick={(e) => del(e, wf)} style={delBtn} title="Delete workflow"><Trash2 size={14} /></button>
              <div style={{ fontWeight: 600, color: 'var(--color-text)', paddingRight: 22 }}>{wf.name}</div>
              <div style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginTop: 4 }}>
                {wf.mode} · {wf.status}
                {wf.published_version ? ` · v${wf.published_version}` : ''}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

const btnPrimary: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, padding: '8px 14px',
  background: 'var(--color-primary)', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer', fontWeight: 500,
};
const card: CSSProperties = {
  position: 'relative', border: '1px solid var(--color-border)', borderRadius: 10, padding: 16, cursor: 'pointer', background: 'var(--color-bg-secondary)',
};
const delBtn: CSSProperties = {
  position: 'absolute', top: 10, right: 10, display: 'flex', alignItems: 'center', justifyContent: 'center',
  width: 26, height: 26, border: 'none', borderRadius: 6, background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
