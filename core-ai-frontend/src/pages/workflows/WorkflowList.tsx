import { useEffect, useState, useRef, type CSSProperties, type MouseEvent as ReactMouseEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Trash2, History, Download, FileUp, Workflow as WorkflowIcon } from 'lucide-react';
import { api, type WorkflowView } from '../../api/client';
import { newGraph } from './graph';

export default function WorkflowList() {
  const [workflows, setWorkflows] = useState<WorkflowView[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');
  const navigate = useNavigate();
  const fileRef = useRef<HTMLInputElement>(null);
  const [importing, setImporting] = useState(false);

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

  const openRuns = (e: ReactMouseEvent, wf: WorkflowView) => {
    e.stopPropagation();
    navigate(`/workflows/${wf.id}/runs`);
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

  const exportWorkflow = async (e: ReactMouseEvent, wf: WorkflowView) => {
    e.stopPropagation();
    try {
      const envelope = await api.workflows.export(wf.id);
      const json = JSON.stringify(envelope, null, 2);
      const blob = new Blob([json], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${wf.name.replace(/\s+/g, '-').toLowerCase()}.workflow.json`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError((err as Error).message);
    }
  };

  const importWorkflow = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setImporting(true);
    setError('');
    try {
      const text = await file.text();
      const res = await api.workflows.import(text);
      const unresolved = res.unresolved_references || [];
      if (unresolved.length > 0) {
        alert(`Imported with ${unresolved.length} unresolved reference(s). Fix them before publishing:\n` +
          unresolved.map((u) => `• ${u.node_id} (${u.ref_type}): ${u.message}`).join('\n'));
      }
      navigate(`/workflows/${res.workflow.id}`);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setImporting(false);
      e.target.value = '';
    }
  };

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h1 style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 20, fontWeight: 600, margin: 0, color: 'var(--color-text)' }}>
          <WorkflowIcon size={20} /> Workflows
        </h1>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <label style={{ ...btnSecondary, opacity: importing ? 0.5 : 1 }}>
            <FileUp size={16} /> {importing ? 'Importing…' : 'Import'}
            <input ref={fileRef} type="file" accept=".json" style={{ display: 'none' }} onChange={importWorkflow} disabled={importing} />
          </label>
          <button onClick={create} disabled={creating} style={btnPrimary}>
            <Plus size={16} /> New workflow
          </button>
        </div>
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
              <button onClick={(e) => exportWorkflow(e, wf)} style={exportBtn} title="Export workflow"><Download size={14} /></button>
              <button onClick={(e) => openRuns(e, wf)} style={histBtn} title="Run history"><History size={14} /></button>
              <button onClick={(e) => del(e, wf)} style={delBtn} title="Delete workflow"><Trash2 size={14} /></button>
              <div style={{ fontWeight: 600, color: 'var(--color-text)', paddingRight: 92 }}>{wf.name}</div>
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
const btnSecondary: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, padding: '8px 14px',
  background: 'transparent', color: 'var(--color-text)', border: '1px solid var(--color-border)',
  borderRadius: 8, cursor: 'pointer', fontWeight: 500,
};
const card: CSSProperties = {
  position: 'relative', border: '1px solid var(--color-border)', borderRadius: 10, padding: 16, cursor: 'pointer', background: 'var(--color-bg-secondary)',
};
const delBtn: CSSProperties = {
  position: 'absolute', top: 10, right: 10, display: 'flex', alignItems: 'center', justifyContent: 'center',
  width: 26, height: 26, border: 'none', borderRadius: 6, background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
const histBtn: CSSProperties = {
  position: 'absolute', top: 10, right: 38, display: 'flex', alignItems: 'center', justifyContent: 'center',
  width: 26, height: 26, border: 'none', borderRadius: 6, background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
const exportBtn: CSSProperties = {
  position: 'absolute', top: 10, right: 66, display: 'flex', alignItems: 'center', justifyContent: 'center',
  width: 26, height: 26, border: 'none', borderRadius: 6, background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
