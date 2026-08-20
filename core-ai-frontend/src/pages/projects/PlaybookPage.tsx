import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Check, Save } from 'lucide-react';
import { api } from '../../api/client';
import type { ProjectMember, ProjectView } from '../../api/client';

export default function PlaybookPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [project, setProject] = useState<ProjectView | null>(null);
  const [memberOptions, setMemberOptions] = useState<{ type: 'agent' | 'workflow'; id: string; name: string }[]>([]);
  const [playbook, setPlaybook] = useState('');
  const [sources, setSources] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');

  if (!id) return null;

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([
      api.projects.get(id),
      api.projects.memberOptions(id).catch(e => {
        console.error('member options failed', e);
        setError(`Failed to load member options: ${String((e as Error).message || e)}`);
        return { agents: [], workflows: [] };
      }),
    ])
      .then(([p, options]) => {
        setProject(p);
        setPlaybook(p.playbook || '');
        setSources((p.report_sources || []).map(s => `${s.type}:${s.id}`));
        const memberList: { type: 'agent' | 'workflow'; id: string; name: string }[] = [
          ...(options.agents || []).map((a: ProjectMember) => ({ type: 'agent' as const, id: a.id, name: a.name })),
          ...(options.workflows || []).map((w: ProjectMember) => ({ type: 'workflow' as const, id: w.id, name: w.name })),
        ];
        setMemberOptions(memberList);
      })
      .catch(e => setError(String((e as Error).message || e)))
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const save = async () => {
    setSaving(true);
    setSaved(false);
    try {
      await api.projects.update(id, {
        playbook,
        report_sources: sources.map(key => {
          const [type, sourceId] = key.split(':');
          return { type: type as 'agent' | 'workflow', id: sourceId };
        }),
      });
      setSaved(true);
      setTimeout(() => setSaved(false), 2500);
      setError('');
    } catch (e) {
      setError(String((e as Error).message || e));
    } finally {
      setSaving(false);
    }
  };

  const inputStyle = {
    background: 'var(--color-bg-secondary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  if (loading) {
    return <div className="p-6 text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  }
  if (!project) {
    return (
      <div className="p-6">
        <div className="p-3 rounded-lg text-sm" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger)' }}>
          {error || 'Project not found'}
        </div>
      </div>
    );
  }

  return (
    <div className="p-6 max-w-5xl">
      <div className="flex items-center gap-3 mb-2">
        <button onClick={() => navigate(`/projects/${id}`)} className="p-1.5 rounded-lg border cursor-pointer"
          style={{ borderColor: 'var(--color-border)' }} title="Back to project">
          <ArrowLeft size={16} />
        </button>
        <div>
          <h1 className="text-2xl font-semibold">Playbook</h1>
          <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{project.name}</div>
        </div>
        <div className="flex items-center gap-2 ml-auto">
          {saved && (
            <span className="flex items-center gap-1 text-sm" style={{ color: 'var(--color-success, var(--color-primary))' }}>
              <Check size={14} /> Saved
            </span>
          )}
          <button onClick={save} disabled={saving}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
            style={{ background: 'var(--color-primary)' }}>
            <Save size={14} /> {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>
      <div className="text-xs mb-6" style={{ color: 'var(--color-text-secondary)' }}>
        The project itself holds no state — this playbook defines the overall process and the KPI evaluation methodology
        (what to measure, how to score, how often). Agents of this project follow it when writing subject state.
      </div>

      {error && (
        <div className="mb-4 p-3 rounded-lg text-sm" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger)' }}>
          {error}
        </div>
      )}

      <div className="mb-6">
        <div className="text-sm font-medium mb-2">Playbook content</div>
        <textarea
          rows={20}
          placeholder={'Overall process:\n1. Monthly audit ...\n2. ...\n\nKPI evaluation methodology:\n- audit_score: 0-10 across broken links / content quality / page speed; record after every audit\n- ranking: primary keyword position; record weekly\n- traffic: organic sessions; record weekly'}
          value={playbook}
          onChange={e => setPlaybook(e.target.value)}
          className="w-full px-3 py-3 rounded-lg border text-sm leading-relaxed resize-y"
          style={inputStyle}
        />
      </div>

      <div>
        <div className="text-sm font-medium mb-1">Report sources</div>
        <div className="text-xs mb-2" style={{ color: 'var(--color-text-secondary)' }}>
          Members whose artifacts are the reports this project evaluates (e.g. the SEO audit agent).
        </div>
        <div className="flex flex-wrap gap-2">
          {memberOptions.map(m => {
            const key = `${m.type}:${m.id}`;
            const checked = sources.includes(key);
            return (
              <label key={key} className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg border text-sm cursor-pointer"
                style={{ background: checked ? 'var(--color-bg-tertiary)' : 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
                <input type="checkbox" checked={checked}
                  onChange={e => setSources(e.target.checked ? [...sources, key] : sources.filter(k => k !== key))} />
                {m.name}
              </label>
            );
          })}
          {memberOptions.length === 0 && (
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              No addable members yet — add agents/workflows to the project first.
            </span>
          )}
        </div>
      </div>
    </div>
  );
}
