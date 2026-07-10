import { useCallback, useEffect, useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Sparkles, ChevronLeft, ChevronRight, Search, RefreshCw, FileUp, X, Package, Download, Loader2, Store, Trash2, Plus } from 'lucide-react';
import { api } from '../../api/client';
import type { SkillDefinition, MarketplaceRepoView } from '../../api/client';

const DEFAULT_SEARCH_IN = 'name_description';

const SEARCH_IN_OPTIONS = [
    { value: DEFAULT_SEARCH_IN, label: 'Name & description', placeholder: 'Search names and descriptions...' },
    { value: 'name', label: 'Name only', placeholder: 'Search skill names...' },
    { value: 'metadata', label: 'All metadata', placeholder: 'Search metadata...' },
    { value: 'content', label: 'Content', placeholder: 'Search SKILL.md content...' },
];

type Tab = 'uploaded' | 'marketplace';

export default function SkillList() {
    const [activeTab, setActiveTab] = useState<Tab>('uploaded');

    // --- uploaded tab state ---
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

    // --- marketplace tab state ---
    const [repos, setRepos] = useState<MarketplaceRepoView[]>([]);
    const [marketLoading, setMarketLoading] = useState(false);
    const [installing, setInstalling] = useState<string | null>(null);
    const [showAddForm, setShowAddForm] = useState(false);
    const [formRepoUrl, setFormRepoUrl] = useState('');
    const [formBranch, setFormBranch] = useState('main');
    const [submitting, setSubmitting] = useState(false);

    const navigate = useNavigate();

    // --- uploaded tab logic ---
    const load = useCallback(() => {
        if (activeTab !== 'uploaded') return;
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
    }, [activeTab, sourceFilter, search, searchIn, ownerFilter, offset, limit]);

    useEffect(() => {
        if (activeTab !== 'uploaded') return;
        if (debounceRef.current) clearTimeout(debounceRef.current);
        debounceRef.current = setTimeout(load, 300);
        return () => {
            if (debounceRef.current) clearTimeout(debounceRef.current);
        };
    }, [load, activeTab]);

    useEffect(() => {
        if (total > 0 && offset >= total) {
            setOffset(Math.floor((total - 1) / limit) * limit);
        }
    }, [total, limit, offset]);

    // --- marketplace tab logic ---
    const loadMarketplace = useCallback(() => {
        if (activeTab !== 'marketplace') return;
        setMarketLoading(true);
        api.skills.getMarketplace()
            .then(res => setRepos(res.repos || []))
            .catch(err => console.error('Failed to load marketplace:', err))
            .finally(() => setMarketLoading(false));
    }, [activeTab]);

    useEffect(() => {
        if (activeTab === 'marketplace') loadMarketplace();
    }, [activeTab, loadMarketplace]);

    const handleInstall = async (repo: MarketplaceRepoView, e: React.MouseEvent) => {
        e.stopPropagation();
        if (installing) return;
        setInstalling(repo.id);
        try {
            await api.skills.installMarketplaceRepo(repo.id);
            const res = await api.skills.getMarketplace();
            setRepos(res.repos || []);
        } catch (err) {
            alert('Install failed: ' + (err instanceof Error ? err.message : 'unknown error'));
        } finally {
            setInstalling(null);
        }
    };

    const handleDeleteRepo = async (repoId: string, e: React.MouseEvent) => {
        e.stopPropagation();
        if (!confirm('Remove this repository from marketplace?')) return;
        try {
            await api.skills.deleteMarketplaceRepo(repoId);
            setRepos(prev => prev.filter(r => r.id !== repoId));
        } catch (err) {
            alert('Delete failed: ' + (err instanceof Error ? err.message : 'unknown error'));
        }
    };

    const handleCreateRepo = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!formRepoUrl.trim()) return;
        setSubmitting(true);
        try {
            const created = await api.skills.createMarketplaceRepo({
                repo_url: formRepoUrl.trim(),
                branch: formBranch.trim() || undefined,
            });
            setRepos(prev => [created, ...prev]);
            setShowAddForm(false);
            resetForm();
        } catch (err) {
            alert('Failed to add repo: ' + (err instanceof Error ? err.message : 'unknown error'));
        } finally {
            setSubmitting(false);
        }
    };

    const resetForm = () => {
        setFormRepoUrl('');
        setFormBranch('main');
    };

    // --- shared helpers ---
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
            {/* Header */}
            <div className="flex items-center justify-between mb-4">
                <div>
                    <h1 className="text-2xl font-semibold">Skills</h1>
                    <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                        Browse and manage skills
                    </p>
                </div>
                <div className="flex items-center gap-2">
                    {activeTab === 'uploaded' && (
                        <>
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
                        </>
                    )}
                    {activeTab === 'marketplace' && (
                        <>
                            <button onClick={() => setShowAddForm(!showAddForm)}
                                className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
                                style={{ borderColor: 'var(--color-primary)', color: 'var(--color-primary)' }}>
                                <Plus size={14} /> Add Repo
                            </button>
                            <button onClick={loadMarketplace}
                                className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
                                style={{ borderColor: 'var(--color-border)' }}>
                                <RefreshCw size={14} /> Refresh
                            </button>
                        </>
                    )}
                </div>
            </div>

            {/* Tab bar — pill toggle-group (matches Agents page pattern) */}
            <div className="flex gap-1 p-1 rounded-lg mb-6 w-fit" style={{ background: 'var(--color-bg-secondary)' }}>
                <button onClick={() => setActiveTab('uploaded')}
                    className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
                    style={{
                        background: activeTab === 'uploaded' ? 'var(--color-bg-tertiary)' : 'transparent',
                        color: activeTab === 'uploaded' ? 'var(--color-text)' : 'var(--color-text-secondary)',
                    }}>
                    <Sparkles size={14} />
                    Uploaded
                </button>
                <button onClick={() => setActiveTab('marketplace')}
                    className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
                    style={{
                        background: activeTab === 'marketplace' ? 'var(--color-bg-tertiary)' : 'transparent',
                        color: activeTab === 'marketplace' ? 'var(--color-text)' : 'var(--color-text-secondary)',
                    }}>
                    <Store size={14} />
                    Marketplace
                </button>
            </div>

            {/* =========== UPLOADED TAB =========== */}
            {activeTab === 'uploaded' && (
                <>
                    {/* Filters bar */}
                    <div className="flex flex-wrap items-center gap-3 mb-4">
                        <div className="relative flex-1 max-w-sm">
                            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: 'var(--color-text-secondary)' }} />
                            <input type="text" placeholder={searchOption.placeholder} value={search}
                                onChange={e => { setSearch(e.target.value); setOffset(0); }}
                                className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm"
                                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                        </div>
                        <select value={searchIn}
                            onChange={e => { setSearchIn(e.target.value); setOffset(0); }}
                            className="px-3 py-2 rounded-lg border text-sm min-w-48"
                            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                            {SEARCH_IN_OPTIONS.map(option => <option key={option.value} value={option.value}>Search in: {option.label}</option>)}
                        </select>
                        <select value={sourceFilter}
                            onChange={e => { setSourceFilter(e.target.value); setOffset(0); }}
                            className="px-3 py-2 rounded-lg border text-sm min-w-36"
                            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                            <option value="">All Sources</option>
                            {['UPLOAD', 'REPO'].map(source => <option key={source} value={source}>{formatSource(source)}</option>)}
                        </select>
                        <input type="text" value={ownerFilter}
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
                                    ? 'No skills yet. Upload a skill folder or install from the Marketplace.'
                                    : 'No skills match your filters.'}
                            </div>
                        ) : skills.map(s => (
                            <div key={s.id}
                                onClick={() => navigate(`/skills/${s.id}/edit`)}
                                className="rounded-xl border p-4 cursor-pointer transition-colors"
                                style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
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
                                                className="p-1.5 rounded border cursor-pointer" style={{ borderColor: 'var(--color-border)' }} title="Sync from repo">
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
                                    {s.allowed_tools?.length > 0 && <span>Tools: {s.allowed_tools.length}</span>}
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
                </>
            )}

            {/* =========== MARKETPLACE TAB =========== */}
            {activeTab === 'marketplace' && (
                <>
                    {/* Add Repo form */}
                    {showAddForm && (
                        <form onSubmit={handleCreateRepo}
                            className="rounded-xl border p-4 mb-4"
                            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
                            <div className="flex items-center justify-between mb-3">
                                <h3 className="text-sm font-medium">Add Repository</h3>
                                <button type="button" onClick={() => { setShowAddForm(false); resetForm(); }}
                                    className="p-1 rounded" style={{ color: 'var(--color-text-secondary)' }}>
                                    <X size={16} />
                                </button>
                            </div>
                            <p className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>
                                Paste any Git repo URL containing SKILL.md files — auto-detected & installed.
                            </p>
                            <div className="flex flex-wrap gap-2 mb-3">
                                <SupportedBadge label="Claude Code" active />
                                <SupportedBadge label="Codex CLI" active />
                                <SupportedBadge label="Cursor" />
                                <SupportedBadge label="GitHub Copilot" />
                            </div>
                            <div className="grid grid-cols-3 gap-3 mb-3">
                                <div className="col-span-2">
                                    <input type="text" value={formRepoUrl} onChange={e => setFormRepoUrl(e.target.value)}
                                        placeholder="https://github.com/owner/repo"
                                        className="w-full px-3 py-2 rounded-lg border text-sm"
                                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }} />
                                </div>
                                <div>
                                    <input type="text" value={formBranch} onChange={e => setFormBranch(e.target.value)}
                                        placeholder="main"
                                        className="w-full px-3 py-2 rounded-lg border text-sm"
                                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }} />
                                </div>
                            </div>
                            <div className="flex justify-end">
                                <button type="submit" disabled={submitting}
                                    className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm cursor-pointer disabled:opacity-50"
                                    style={{ background: 'var(--color-primary)', color: '#fff' }}>
                                    {submitting ? <><Loader2 size={14} className="animate-spin" /> Adding...</> : <><Plus size={14} /> Add & Install</>}
                                </button>
                            </div>
                        </form>
                    )}

                    {marketLoading ? (
                        <div className="flex items-center justify-center py-20" style={{ color: 'var(--color-text-secondary)' }}>
                            <Loader2 size={20} className="animate-spin mr-2" /> Loading marketplace...
                        </div>
                    ) : repos.length === 0 ? (
                        <div className="text-center py-12 rounded-xl border"
                            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                            No community repositories available yet.
                        </div>
                    ) : (
                        <div className="grid gap-4">
                            {repos.map(repo => (
                                <div key={repo.id}
                                    onClick={() => navigate(`/skills/marketplace/${repo.id}`)}
                                    className="rounded-xl border p-4 cursor-pointer transition-colors"
                                    style={{
                                        background: 'var(--color-bg-secondary)',
                                        borderColor: repo.installed ? 'var(--color-primary)' : 'var(--color-border)',
                                    }}
                                    onMouseEnter={e => { e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
                                    onMouseLeave={e => { e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}>
                                    <div className="flex items-center justify-between">
                                        <div className="flex items-center gap-3 flex-1 min-w-0">
                                            <div className="w-10 h-10 rounded-lg flex items-center justify-center shrink-0"
                                                style={{ background: 'var(--color-bg-tertiary)' }}>
                                                <Package size={20} style={{ color: 'var(--color-primary)' }} />
                                            </div>
                                            <div className="min-w-0">
                                                <div className="flex items-center gap-2">
                                                    <span className="font-medium truncate">{repo.name}</span>
                                                    {repo.featured && (
                                                        <span className="px-1.5 py-0.5 rounded text-xs shrink-0"
                                                            style={{ background: '#78350f', color: '#fcd34d' }}>Featured</span>
                                                    )}
                                                    {repo.installed && (
                                                        <span className="px-1.5 py-0.5 rounded text-xs shrink-0"
                                                            style={{ background: '#064e3b', color: '#6ee7b7' }}>Installed</span>
                                                    )}
                                                </div>
                                                <p className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-secondary)' }}>
                                                    {repo.description}
                                                </p>
                                            </div>
                                        </div>
                                        <div className="flex items-center gap-3 shrink-0 ml-4">
                                            <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                                                {repo.skill_count ?? '?'} skill{(repo.skill_count ?? 0) !== 1 ? 's' : ''}
                                            </span>
                                            <button onClick={e => handleInstall(repo, e)}
                                                disabled={installing === repo.id || repo.installed}
                                                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm border cursor-pointer disabled:opacity-50"
                                                style={{
                                                    borderColor: 'var(--color-border)',
                                                    background: repo.installed ? 'var(--color-bg-tertiary)' : 'var(--color-bg-secondary)',
                                                    color: repo.installed ? 'var(--color-text-secondary)' : 'var(--color-text)',
                                                }}>
                                                {installing === repo.id ? (
                                                    <><Loader2 size={14} className="animate-spin" /> Installing...</>
                                                ) : repo.installed ? (
                                                    <><Download size={14} /> Installed</>
                                                ) : (
                                                    <><Download size={14} /> Install All</>
                                                )}
                                            </button>
                                            <button onClick={e => handleDeleteRepo(repo.id, e)}
                                                className="p-1.5 rounded-lg border cursor-pointer"
                                                style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}
                                                title="Remove from marketplace">
                                                <Trash2 size={14} />
                                            </button>
                                        </div>
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </>
            )}
        </div>
    );
}

function formatSource(source: string) {
    return source.charAt(0) + source.slice(1).toLowerCase();
}

function SupportedBadge({ label, active }: { label: string; active?: boolean }) {
    return (
        <span className="px-2 py-0.5 rounded text-xs"
            style={{
                background: active ? '#064e3b' : 'var(--color-bg-tertiary)',
                color: active ? '#6ee7b7' : 'var(--color-text-secondary)',
                border: active ? '1px solid #065f46' : '1px solid var(--color-border)',
            }}>
            {label}
        </span>
    );
}
