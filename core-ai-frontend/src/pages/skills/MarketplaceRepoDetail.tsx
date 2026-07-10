import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Download, ExternalLink, Loader2, Package, Sparkles, RefreshCw } from 'lucide-react';
import { api } from '../../api/client';
import type { MarketplaceRepoDetailResponse, SkillDefinition } from '../../api/client';

export default function MarketplaceRepoDetail() {
    const { repoId } = useParams<{ repoId: string }>();
    const [repo, setRepo] = useState<MarketplaceRepoDetailResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [installing, setInstalling] = useState(false);
    const navigate = useNavigate();

    const load = () => {
        if (!repoId) return;
        setLoading(true);
        api.skills.getMarketplaceRepo(repoId)
            .then(setRepo)
            .catch(err => {
                console.error('Failed to load repo:', err);
                setRepo(null);
            })
            .finally(() => setLoading(false));
    };

    useEffect(() => { load(); }, [repoId]);

    const handleInstall = async () => {
        if (!repoId || installing) return;
        setInstalling(true);
        try {
            await api.skills.installMarketplaceRepo(repoId);
            load(); // Refresh to show installed skills
        } catch (err) {
            alert('Install failed: ' + (err instanceof Error ? err.message : 'unknown error'));
        } finally {
            setInstalling(false);
        }
    };

    if (loading) {
        return (
            <div className="p-6">
                <div className="flex items-center justify-center py-20" style={{ color: 'var(--color-text-secondary)' }}>
                    <Loader2 size={20} className="animate-spin mr-2" /> Loading...
                </div>
            </div>
        );
    }

    if (!repo) {
        return (
            <div className="p-6">
                <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>
                    Repository not found.
                </div>
            </div>
        );
    }

    const skills = repo.skills || [];
    const formatTime = (iso: string) => {
        if (!iso) return '-';
        const d = new Date(iso);
        return d.toLocaleDateString();
    };

    return (
        <div className="p-6">
            {/* Header */}
            <div className="flex items-center gap-3 mb-6">
                <button onClick={() => navigate('/skills')}
                    className="p-1.5 rounded-lg border cursor-pointer"
                    style={{ borderColor: 'var(--color-border)' }}>
                    <ArrowLeft size={18} />
                </button>
                <div className="flex-1">
                    <div className="flex items-center gap-2">
                        <Package size={22} style={{ color: 'var(--color-primary)' }} />
                        <h1 className="text-2xl font-semibold">{repo.name}</h1>
                        {repo.installed && (
                            <span className="px-2 py-0.5 rounded text-xs"
                                style={{ background: '#064e3b', color: '#6ee7b7' }}>
                                Installed
                            </span>
                        )}
                    </div>
                    <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                        {repo.description}
                    </p>
                </div>
                <div className="flex items-center gap-2">
                    <a href={repo.repo_url} target="_blank" rel="noopener noreferrer"
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm border cursor-pointer no-underline"
                        style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                        <ExternalLink size={14} /> View Repo
                    </a>
                    {!repo.installed && (
                        <button onClick={handleInstall} disabled={installing}
                            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm border cursor-pointer disabled:opacity-50"
                            style={{ borderColor: 'var(--color-primary)', color: 'var(--color-primary)' }}>
                            {installing ? (
                                <><Loader2 size={14} className="animate-spin" /> Installing...</>
                            ) : (
                                <><Download size={14} /> Install All Skills</>
                            )}
                        </button>
                    )}
                </div>
            </div>

            {/* Stats */}
            <div className="grid grid-cols-3 gap-4 mb-6">
                <div className="rounded-xl border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
                    <div className="text-2xl font-semibold">{repo.skill_count ?? skills.length}</div>
                    <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>Skills</div>
                </div>
                <div className="rounded-xl border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
                    <div className="text-2xl font-semibold">{repo.branch}</div>
                    <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>Branch</div>
                </div>
                <div className="rounded-xl border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
                    <div className="text-2xl font-semibold">{repo.category || 'general'}</div>
                    <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>Category</div>
                </div>
            </div>

            {/* Skills list */}
            <h2 className="text-lg font-medium mb-4">Skills</h2>

            {skills.length === 0 ? (
                <div className="text-center py-12 rounded-xl border"
                    style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                    {repo.installed
                        ? 'No skills were found in this repository.'
                        : 'Install this repository to see its skills.'}
                </div>
            ) : (
                <div className="grid gap-3">
                    {skills.map((s: SkillDefinition) => (
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
                                <div className="flex items-center gap-4 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                                    <span>Namespace: {s.namespace}</span>
                                    <span>{formatTime(s.updated_at)}</span>
                                </div>
                            </div>
                            {s.description && (
                                <p className="text-sm mt-1 ml-7" style={{ color: 'var(--color-text-secondary)' }}>
                                    {s.description.length > 120 ? s.description.slice(0, 120) + '...' : s.description}
                                </p>
                            )}
                        </div>
                    ))}
                </div>
            )}

            {/* Refresh button for installed repos */}
            {repo.installed && skills.length > 0 && (
                <div className="mt-4 flex justify-end">
                    <button onClick={() => load()}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm border cursor-pointer"
                        style={{ borderColor: 'var(--color-border)' }}>
                        <RefreshCw size={14} /> Refresh
                    </button>
                </div>
            )}
        </div>
    );
}
