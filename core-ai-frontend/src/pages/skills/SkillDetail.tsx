import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Sparkles, RefreshCw, Trash2, Copy, Check, Pencil } from 'lucide-react';
import { api } from '../../api/client';
import type { SkillDefinition, SkillDownloadResponse } from '../../api/client';

export default function SkillDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [skill, setSkill] = useState<SkillDefinition | null>(null);
  const [downloaded, setDownloaded] = useState<SkillDownloadResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [downloading, setDownloading] = useState(false);
  const [showContent, setShowContent] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    api.skills.get(id)
      .then(res => setSkill(res))
      .catch(() => setSkill(null))
      .finally(() => setLoading(false));
  }, [id]);

  const handleDownload = async () => {
    if (!id) return;
    setDownloading(true);
    try {
      const res = await api.skills.download(id);
      setDownloaded(res);
      setShowContent(true);
    } catch (err) {
      console.error('Failed to download skill:', err);
    } finally {
      setDownloading(false);
    }
  };

  const handleCopy = () => {
    if (!downloaded) return;
    navigator.clipboard.writeText(downloaded.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDelete = async () => {
    if (!id) return;
    if (!confirm('Delete this skill? This action cannot be undone.')) return;
    await api.skills.delete(id);
    navigate('/skills');
  };

  const handleSync = async () => {
    if (!id) return;
    await api.skills.sync(id);
    // Refresh skill data
    api.skills.get(id).then(res => setSkill(res));
  };

  const formatTime = (iso: string) => {
    if (!iso) return '-';
    return new Date(iso).toLocaleString();
  };

  if (loading) {
    return (
      <div className="p-6">
        <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
      </div>
    );
  }

  if (!skill) {
    return (
      <div className="p-6">
        <div className="text-center py-12 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          <p className="text-lg mb-4">Skill not found</p>
          <button onClick={() => navigate('/skills')}
            className="px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            Back to Skills
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="p-6">
      {/* Header */}
      <div className="flex items-center gap-3 mb-6">
        <button onClick={() => navigate('/skills')}
          className="p-2 rounded-lg border cursor-pointer"
          style={{ borderColor: 'var(--color-border)' }}>
          <ArrowLeft size={18} />
        </button>
        <Sparkles size={24} style={{ color: 'var(--color-primary)' }} />
        <div className="flex-1">
          <h1 className="text-2xl font-semibold">{skill.name}</h1>
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            {skill.namespace}/{skill.name}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="px-2 py-1 rounded text-xs"
            style={{ background: skill.source_type === 'REPO' ? '#064e3b' : 'var(--color-bg-tertiary)', color: skill.source_type === 'REPO' ? '#6ee7b7' : 'var(--color-text-secondary)' }}>
            {skill.source_type}
          </span>
          {skill.version && (
            <span className="px-2 py-1 rounded text-xs"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
              v{skill.version}
            </span>
          )}
          <button onClick={() => navigate(`/skills/${id}/edit`)}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Pencil size={14} /> Edit
          </button>
          {skill.source_type === 'REPO' && (
            <button onClick={handleSync}
              className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
              style={{ borderColor: 'var(--color-border)' }}>
              <RefreshCw size={14} /> Sync
            </button>
          )}
          <button onClick={handleDelete}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)', color: '#f87171' }}>
            <Trash2 size={14} /> Delete
          </button>
        </div>
      </div>

      {/* Meta info */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
        <InfoCard label="Namespace" value={skill.namespace} />
        <InfoCard label="User" value={skill.user_id} />
        <InfoCard label="Created" value={formatTime(skill.created_at)} />
        <InfoCard label="Updated" value={formatTime(skill.updated_at)} />
      </div>

      {/* Description */}
      {skill.description && (
        <div className="mb-6 p-4 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <h3 className="text-sm font-medium mb-2" style={{ color: 'var(--color-text-secondary)' }}>Description</h3>
          <p className="text-sm">{skill.description}</p>
        </div>
      )}

      {/* Allowed tools */}
      {skill.allowed_tools && skill.allowed_tools.length > 0 && (
        <div className="mb-6 p-4 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <h3 className="text-sm font-medium mb-2" style={{ color: 'var(--color-text-secondary)' }}>Allowed Tools</h3>
          <div className="flex flex-wrap gap-2">
            {skill.allowed_tools.map(t => (
              <span key={t} className="px-2 py-1 rounded text-xs"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                {t}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Metadata */}
      {skill.metadata && Object.keys(skill.metadata).length > 0 && (
        <div className="mb-6 p-4 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <h3 className="text-sm font-medium mb-2" style={{ color: 'var(--color-text-secondary)' }}>Metadata</h3>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
            {Object.entries(skill.metadata).map(([k, v]) => (
              <div key={k} className="flex items-center gap-2 text-sm">
                <span style={{ color: 'var(--color-text-secondary)' }}>{k}:</span>
                <span>{v}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Content download */}
      <div className="mb-6 p-4 rounded-xl border"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-medium" style={{ color: 'var(--color-text-secondary)' }}>Skill Content</h3>
          <button onClick={handleDownload} disabled={downloading}
            className="px-3 py-1.5 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
            style={{ background: 'var(--color-primary)' }}>
            {downloading ? 'Downloading...' : 'Download Content'}
          </button>
        </div>

        {showContent && downloaded && (
          <div>
            {downloaded.resources && downloaded.resources.length > 0 && (
              <div className="mb-3">
                <h4 className="text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Resources ({downloaded.resources.length})
                </h4>
                <div className="flex flex-wrap gap-2">
                  {downloaded.resources.map(r => (
                    <span key={r.path} className="px-2 py-1 rounded text-xs"
                      style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                      {r.path}
                    </span>
                  ))}
                </div>
              </div>
            )}
            <div className="relative">
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>SKILL.md</span>
                <button onClick={handleCopy}
                  className="flex items-center gap-1 px-2 py-1 rounded text-xs border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}>
                  {copied ? <><Check size={12} /> Copied</> : <><Copy size={12} /> Copy</>}
                </button>
              </div>
              <pre className="p-4 rounded-lg text-xs whitespace-pre-wrap overflow-auto max-h-96"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                {downloaded.content}
              </pre>
            </div>
          </div>
        )}

        {!showContent && (
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            Click "Download Content" to view the SKILL.md content and resources.
          </p>
        )}
      </div>
    </div>
  );
}

function InfoCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="p-3 rounded-xl border"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>{label}</div>
      <div className="text-sm font-medium truncate" title={value}>{value}</div>
    </div>
  );
}
