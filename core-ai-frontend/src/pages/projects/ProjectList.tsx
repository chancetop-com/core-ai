import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { FolderKanban, Plus, RefreshCw, Archive, RotateCcw } from 'lucide-react';
import { api } from '../../api/client';
import type { ProjectSummary } from '../../api/client';

export default function ProjectList() {
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [showArchived, setShowArchived] = useState(false);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [goal, setGoal] = useState('');
  const [error, setError] = useState('');
  const navigate = useNavigate();

  const load = useCallback(() => {
    setLoading(true);
    api.projects.list(0, 100, showArchived)
      .then(res => {
        setProjects(res.projects || []);
        setTotal(res.total || 0);
        setError('');
      })
      .catch(e => setError(String(e.message || e)))
      .finally(() => setLoading(false));
  }, [showArchived]);

  useEffect(() => { load(); }, [load]);

  const create = async () => {
    if (!name.trim()) return;
    try {
      const res = await api.projects.create({ name: name.trim(), description, goal });
      setCreating(false);
      setName('');
      setDescription('');
      setGoal('');
      navigate(`/projects/${res.id}`);
    } catch (e) {
      setError(String((e as Error).message || e));
    }
  };

  const toggleArchive = async (p: ProjectSummary) => {
    if (p.status === 'archived') {
      await api.projects.activate(p.id);
    } else {
      if (!confirm(`Archive "${p.name}"? Nothing is deleted.`)) return;
      await api.projects.archive(p.id);
    }
    load();
  };

  const inputStyle = {
    background: 'var(--color-bg-secondary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Projects</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Campaign containers that loosely organize agents, workflows, sessions, reports and traces
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={() => setCreating(true)}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={14} /> New Project
          </button>
          <button onClick={() => setShowArchived(!showArchived)}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            {showArchived ? <RotateCcw size={14} /> : <Archive size={14} />}
            {showArchived ? 'Active' : 'Archived'}
          </button>
          <button onClick={load}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            <RefreshCw size={14} /> Refresh
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-4 p-3 rounded-lg text-sm" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger)' }}>
          {error}
        </div>
      )}

      {creating && (
        <div className="mb-6 p-4 rounded-xl border" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <div className="grid gap-3">
            <input type="text" placeholder="Project name" value={name} onChange={e => setName(e.target.value)}
              className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
            <input type="text" placeholder="Description (optional)" value={description} onChange={e => setDescription(e.target.value)}
              className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
            <input type="text" placeholder="Goal (optional, e.g. improve local search visibility)" value={goal} onChange={e => setGoal(e.target.value)}
              className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
            <div className="flex justify-end gap-2">
              <button onClick={() => setCreating(false)} className="px-3 py-1.5 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>Cancel</button>
              <button onClick={create} disabled={!name.trim()}
                className="px-3 py-1.5 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-primary)' }}>Create</button>
            </div>
          </div>
        </div>
      )}

      <div className="grid gap-4">
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : projects.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            {total === 0 ? 'No projects yet. Create one to organize a business campaign.' : 'No projects match the filter.'}
          </div>
        ) : projects.map(p => (
          <div key={p.id}
            onClick={() => navigate(`/projects/${p.id}`)}
            className="p-4 rounded-xl border cursor-pointer hover:border-[var(--color-primary)]"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-3 min-w-0">
                <FolderKanban size={18} className="mt-0.5 shrink-0" style={{ color: 'var(--color-primary)' }} />
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium truncate">{p.name}</span>
                    {p.status === 'archived' && (
                      <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>archived</span>
                    )}
                  </div>
                  {p.goal && <div className="text-sm mt-0.5 truncate">{p.goal}</div>}
                  {p.description && <div className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-secondary)' }}>{p.description}</div>}
                </div>
              </div>
              <button
                onClick={e => { e.stopPropagation(); toggleArchive(p); }}
                className="p-1.5 rounded-lg border shrink-0 cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}
                title={p.status === 'archived' ? 'Activate' : 'Archive'}>
                {p.status === 'archived' ? <RotateCcw size={14} /> : <Archive size={14} />}
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
