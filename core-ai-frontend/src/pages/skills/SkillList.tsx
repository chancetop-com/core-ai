import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Sparkles, ChevronLeft, ChevronRight, Search, RefreshCw, Pencil } from 'lucide-react';
import { api } from '../../api/client';
import type { SkillDefinition } from '../../api/client';

export default function SkillList() {
  const [skills, setSkills] = useState<SkillDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [offset, setOffset] = useState(0);
  const [limit, setLimit] = useState(10);
  const [search, setSearch] = useState('');
  const [sourceFilter, setSourceFilter] = useState<string>('');
  const navigate = useNavigate();

  const load = () => {
    setLoading(true);
    api.skills.list(undefined, sourceFilter || undefined, search || undefined)
      .then(res => setSkills(res.skills || []))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const filteredSkills = skills.filter(s => {
    const matchSearch = !search ||
      s.name.toLowerCase().includes(search.toLowerCase()) ||
      s.description?.toLowerCase().includes(search.toLowerCase()) ||
      s.namespace.toLowerCase().includes(search.toLowerCase());
    const matchSource = !sourceFilter || s.source_type === sourceFilter;
    return matchSearch && matchSource;
  });

  const pagedSkills = filteredSkills.slice(offset, offset + limit);

  const formatTime = (iso: string) => {
    if (!iso) return '-';
    const d = new Date(iso);
    const now = Date.now();
    const diff = now - d.getTime();
    if (diff < 60_000) return 'just now';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
    return d.toLocaleDateString();
  };

  const handleDelete = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!confirm('Delete this skill?')) return;
    await api.skills.delete(id);
    load();
  };

  const handleSync = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    await api.skills.sync(id);
    load();
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Skills</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Browse and manage available skills
          </p>
        </div>
        <button onClick={load}
          className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
          style={{ borderColor: 'var(--color-border)' }}>
          <RefreshCw size={14} /> Refresh
        </button>
      </div>

      {/* Filters bar */}
      <div className="flex items-center gap-3 mb-4">
        <div className="relative flex-1 max-w-sm">
          <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2"
            style={{ color: 'var(--color-text-secondary)' }} />
          <input
            type="text"
            placeholder="Search skills..."
            value={search}
            onChange={e => { setSearch(e.target.value); setOffset(0); }}
            className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm"
            style={{
              borderColor: 'var(--color-border)',
              background: 'var(--color-bg-secondary)',
              color: 'var(--color-text)',
            }} />
        </div>
        <select
          value={sourceFilter}
          onChange={e => { setSourceFilter(e.target.value); setOffset(0); }}
          className="px-3 py-2 rounded-lg border text-sm"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
          <option value="">All Sources</option>
          <option value="UPLOAD">Upload</option>
          <option value="REPO">Repo</option>
        </select>
      </div>

      {/* Skill cards */}
      <div className="grid gap-4">
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : filteredSkills.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            {skills.length === 0
              ? 'No skills found. Upload or register skills from a repo to get started.'
              : 'No skills match your filters.'}
          </div>
        ) : pagedSkills.map(s => (
          <div key={s.id}
            onClick={() => navigate(`/skills/${s.id}`)}
            className="rounded-xl border p-4 cursor-pointer transition-colors"
            style={{
              background: 'var(--color-bg-secondary)',
              borderColor: 'var(--color-border)',
            }}
            onMouseEnter={e => { e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
            onMouseLeave={e => { e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <Sparkles size={18} style={{ color: 'var(--color-primary)' }} />
                <span className="font-medium">{s.name}</span>
                <span className="px-2 py-0.5 rounded text-xs"
                  style={{ background: s.source_type === 'REPO' ? '#064e3b' : 'var(--color-bg-tertiary)', color: s.source_type === 'REPO' ? '#6ee7b7' : 'var(--color-text-secondary)' }}>
                  {s.source_type}
                </span>
                {s.version && (
                  <span className="px-2 py-0.5 rounded text-xs"
                    style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                    v{s.version}
                  </span>
                )}
              </div>
              <div className="flex items-center gap-2">
                <button onClick={e => { e.stopPropagation(); navigate(`/skills/${s.id}/edit`); }}
                  className="px-2 py-1 rounded text-xs border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}>
                  <Pencil size={12} />
                </button>
                {s.source_type === 'REPO' && (
                  <button onClick={e => handleSync(s.id, e)}
                    className="p-1.5 rounded border cursor-pointer"
                    style={{ borderColor: 'var(--color-border)' }}
                    title="Sync from repo">
                    <RefreshCw size={14} />
                  </button>
                )}
                <button onClick={e => handleDelete(s.id, e)}
                  className="px-2 py-1 rounded text-xs border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                  Delete
                </button>
                <span className="text-xs" style={{ color: 'var(--color-text-secondary)', minWidth: 80, textAlign: 'right' }}>
                  {formatTime(s.updated_at)}
                </span>
              </div>
            </div>
            <div className="mt-1 ml-7 flex items-center gap-4 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              <span>Namespace: {s.namespace}</span>
              {s.allowed_tools?.length > 0 && (
                <span>Tools: {s.allowed_tools.length}</span>
              )}
              {s.user_id && <span>By: {s.user_id}</span>}
            </div>
            {s.description && (
              <p className="text-sm mt-1 ml-7" style={{ color: 'var(--color-text-secondary)' }}>{s.description}</p>
            )}
          </div>
        ))}
      </div>

      {/* Pagination */}
      {filteredSkills.length > 0 && (
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-3">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {offset + 1}-{Math.min(offset + limit, filteredSkills.length)} of {filteredSkills.length}
            </span>
            <select value={limit}
              onChange={e => { setLimit(Number(e.target.value)); setOffset(0); }}
              className="px-2 py-1 rounded-lg border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
              {[10, 20, 50].map(n => <option key={n} value={n}>{n} / page</option>)}
            </select>
          </div>
          <div className="flex gap-2">
            <button onClick={() => setOffset(Math.max(0, offset - limit))} disabled={offset === 0}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button onClick={() => setOffset(offset + limit)} disabled={offset + limit >= filteredSkills.length}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
