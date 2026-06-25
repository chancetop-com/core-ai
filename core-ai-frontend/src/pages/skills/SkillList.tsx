import { useCallback, useEffect, useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Sparkles, ChevronLeft, ChevronRight, Search, RefreshCw, FileUp, X } from 'lucide-react';
import { api } from '../../api/client';
import type { SkillDefinition } from '../../api/client';

const DEFAULT_SEARCH_IN = 'name_description';

const SEARCH_IN_OPTIONS = [
  { value: DEFAULT_SEARCH_IN, label: 'Name & description', placeholder: 'Search names and descriptions...' },
  { value: 'name', label: 'Name only', placeholder: 'Search skill names...' },
  { value: 'metadata', label: 'All metadata', placeholder: 'Search metadata...' },
  { value: 'content', label: 'Content', placeholder: 'Search SKILL.md content...' },
];

export default function SkillList() {
  const [skills, setSkills] = useState<SkillDefinition[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [offset, setOffset] = useState(0);
  const [limit, setLimit] = useState(10);
  const [search, setSearch] = useState('');
  const [searchIn, setSearchIn] = useState(DEFAULT_SEARCH_IN);
  const [sourceFilter, setSourceFilter] = useState<string>('');
  const [ownerFilter, setOwnerFilter] = useState<string>('');
  const [uploading, setUploading] = useState(false);
  const uploadRef = useRef<HTMLInputElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reqIdRef = useRef(0);
  const navigate = useNavigate();

  const load = useCallback(() => {
    const requestId = ++reqIdRef.current;
    setLoading(true);
    api.skills.list(
      undefined,
      sourceFilter || undefined,
      search.trim() || undefined,
      ownerFilter.trim() || undefined,
      offset,
      limit,
      search.trim() ? searchIn : undefined
    )
      .then(res => {
        if (requestId !== reqIdRef.current) return;
        setSkills(res.skills || []);
        setTotal(res.total || 0);
      })
      .catch(err => {
        if (requestId !== reqIdRef.current) return;
        console.error('Failed to load skills:', err);
        setSkills([]);
        setTotal(0);
      })
      .finally(() => {
        if (requestId === reqIdRef.current) setLoading(false);
      });
  }, [sourceFilter, search, searchIn, ownerFilter, offset, limit]);

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(load, 300);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [load]);

  useEffect(() => {
    if (total > 0 && offset >= total) {
      setOffset(Math.floor((total - 1) / limit) * limit);
    }
  }, [total, limit, offset]);

  const hasSearch = Boolean(search.trim());
  const hasActiveFilters = Boolean(hasSearch || sourceFilter || ownerFilter);
  const searchOption = SEARCH_IN_OPTIONS.find(option => option.value === searchIn) || SEARCH_IN_OPTIONS[0];

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
    try {
      await api.skills.delete(id);
      if (skills.length === 1 && offset > 0) {
        setOffset(Math.max(0, offset - limit));
      } else {
        void load();
      }
    } catch (err) {
      alert('Delete failed: ' + (err instanceof Error ? err.message : 'unknown error'));
    }
  };

  const handleSync = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    await api.skills.sync(id);
    load();
  };

  const clearFilters = () => {
    setSearch('');
    setSearchIn(DEFAULT_SEARCH_IN);
    setSourceFilter('');
    setOwnerFilter('');
    setOffset(0);
  };

  const handleFolderUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0) return;

    let skillFile: File | null = null;
    const resourceFiles: File[] = [];

    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      const relativePath = file.webkitRelativePath || file.name;
      const parts = relativePath.split('/');
      // skip root folder name, get path relative to skill dir
      const innerPath = parts.slice(1).join('/');
      if (innerPath === 'SKILL.md') {
        skillFile = file;
      } else if (innerPath && !innerPath.startsWith('.') && !innerPath.includes('/.')) {
        resourceFiles.push(new File([file], innerPath, { type: file.type }));
      }
    }

    if (!skillFile) {
      alert('No SKILL.md found in the selected folder');
      e.target.value = '';
      return;
    }

    setUploading(true);
    try {
      const created = await api.skills.upload(skillFile, resourceFiles.length > 0 ? resourceFiles : undefined);
      if (created?.id) navigate(`/skills/${created.id}/edit`);
    } catch (err) {
      alert('Upload failed: ' + (err instanceof Error ? err.message : 'unknown error'));
    } finally {
      setUploading(false);
      e.target.value = '';
    }
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
        <div className="flex items-center gap-2">
          <label className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)', opacity: uploading ? 0.5 : 1 }}>
            <FileUp size={14} /> {uploading ? 'Uploading...' : 'Import Skill Folder'}
            {/* @ts-expect-error webkitdirectory is non-standard but widely supported */}
            <input ref={uploadRef} type="file" webkitdirectory="" className="hidden" onChange={handleFolderUpload} disabled={uploading} />
          </label>
          <button onClick={load}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            <RefreshCw size={14} /> Refresh
          </button>
        </div>
      </div>

      {/* Filters bar */}
      <div className="flex flex-wrap items-center gap-3 mb-4">
        <div className="relative flex-1 max-w-sm">
          <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2"
            style={{ color: 'var(--color-text-secondary)' }} />
          <input
            type="text"
            placeholder={searchOption.placeholder}
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
          value={searchIn}
          onChange={e => { setSearchIn(e.target.value); setOffset(0); }}
          className="px-3 py-2 rounded-lg border text-sm min-w-48"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
          {SEARCH_IN_OPTIONS.map(option => <option key={option.value} value={option.value}>Search in: {option.label}</option>)}
        </select>
        <select
          value={sourceFilter}
          onChange={e => { setSourceFilter(e.target.value); setOffset(0); }}
          className="px-3 py-2 rounded-lg border text-sm min-w-36"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
          <option value="">All Sources</option>
          {['UPLOAD', 'REPO'].map(source => <option key={source} value={source}>{formatSource(source)}</option>)}
        </select>
        <input
          type="text"
          value={ownerFilter}
          onChange={e => { setOwnerFilter(e.target.value); setOffset(0); }}
          placeholder="Creator..."
          className="px-3 py-2 rounded-lg border text-sm min-w-40"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
        {hasActiveFilters && (
          <button onClick={clearFilters}
            className="h-9 px-3 inline-flex items-center gap-1.5 rounded-lg border text-sm cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
            <X size={14} /> Clear
          </button>
        )}
      </div>

      {/* Skill cards */}
      <div className="grid gap-4">
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : skills.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            {total === 0 && !hasActiveFilters
              ? 'No skills found. Upload or register skills from a repo to get started.'
              : 'No skills match your filters.'}
          </div>
        ) : skills.map(s => (
          <div key={s.id}
            onClick={() => navigate(`/skills/${s.id}/edit`)}
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
      {total > 0 && (
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-3">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {offset + 1}-{Math.min(offset + limit, total)} of {total}
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
            <button onClick={() => setOffset(offset + limit)} disabled={offset + limit >= total}
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

function formatSource(source: string) {
  return source.charAt(0) + source.slice(1).toLowerCase();
}
