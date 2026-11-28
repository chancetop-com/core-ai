import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Sparkles, RefreshCw, Trash2, Copy, Check, Pencil, AlertTriangle, Shield } from 'lucide-react';
import { api } from '../../api/client';
import type { SkillDefinition, SkillDownloadResponse } from '../../api/client';
import { useAuth } from '../../api/auth';
import { skillQualityApi, type QualityIssue } from '../../api/quality';
import SkillTierBadge from './SkillTierBadge';
import SkillTierModal from './SkillTierModal';
import SkillHealthPanel from './SkillHealthPanel';

const DOMAIN_WARN_RULES = new Set(['SKILL.PROGRESSIVE_DISCLOSURE', 'SKILL.EVAL_DATASET']);

export default function SkillDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useAuth();
  const isAdmin = user?.role === 'admin';
  const [skill, setSkill] = useState<SkillDefinition | null>(null);
  const [downloaded, setDownloaded] = useState<SkillDownloadResponse | null>(null);
  const [domainWarnings, setDomainWarnings] = useState<QualityIssue[]>([]);
  const [showTierModal, setShowTierModal] = useState(false);
  const [loading, setLoading] = useState(true);
  const [downloading, setDownloading] = useState(false);
  const [showContent, setShowContent] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!id) return;
    let active = true;
    setLoading(true);
    setDomainWarnings([]);
    api.skills.get(id)
      .then(res => {
        if (!active) return;
        setSkill(res);
        const resolvedTier = res.tier || (res.source_type === 'REPO' ? 'IMPORTED' : 'DOMAIN');
        if (resolvedTier === 'DOMAIN') {
          skillQualityApi.check(id, {})
            .then(report => {
              if (!active) return;
              const warns = (report.issues || []).filter(
                issue => issue.enforcement === 'WARN' && DOMAIN_WARN_RULES.has(issue.rule_id),
              );
              setDomainWarnings(warns);
            })
            .catch(() => {
              if (active) setDomainWarnings([]);
            });
        }
      })
      .catch(() => {
        if (active) setSkill(null);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
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

  const resolvedTier = skill.tier || (skill.source_type === 'REPO' ? 'IMPORTED' : 'DOMAIN');

  return (
    <div className="p-6">
      {showTierModal && (
        <SkillTierModal
          skill={skill}
          onClose={() => setShowTierModal(false)}
          onUpdated={updated => {
            setSkill(updated);
            if (updated.tier !== 'DOMAIN') setDomainWarnings([]);
          }}
        />
      )}

      {resolvedTier === 'DOMAIN' && domainWarnings.length > 0 && (
        <div
          data-testid="domain-quality-warn-banner"
          role="status"
          className="mb-5 p-4 rounded-xl border"
          style={{ background: 'rgba(245,158,11,0.12)', borderColor: '#f59e0b', color: '#fde68a' }}
        >
          <div className="flex items-center gap-2 font-medium text-sm mb-2">
            <AlertTriangle size={16} style={{ color: '#fbbf24' }} />
            Quality gaps before CORE promotion ({domainWarnings.length})
          </div>
          <ul className="text-xs space-y-1 list-disc list-inside">
            {domainWarnings.map((issue, idx) => (
              <li key={`${issue.rule_id}-${idx}`}>
                <span className="font-mono">{issue.rule_id}</span>: {issue.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {skill.last_sync_status === 'REJECTED' && (
        <div
          data-testid="repo-sync-rejected-banner"
          role="alert"
          className="mb-5 p-4 rounded-xl border text-xs"
          style={{ background: 'rgba(220,38,38,0.12)', borderColor: '#ef4444', color: '#fca5a5' }}
        >
          <div className="font-medium mb-1">
            Sync rejected at commit {skill.last_rejected_commit_hash || 'unknown'}
          </div>
          {skill.last_sync_error && <div>{skill.last_sync_error}</div>}
        </div>
      )}

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
          <SkillTierBadge tier={resolvedTier} />
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
          {isAdmin && (
            <button
              onClick={() => setShowTierModal(true)}
              className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
              style={{ borderColor: 'var(--color-primary)', color: 'var(--color-primary)' }}
            >
              <Shield size={14} /> Manage Tier
            </button>
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

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
        <InfoCard label="Namespace" value={skill.namespace} />
        <InfoCard label="User" value={skill.user_id} />
        <InfoCard label="Created" value={formatTime(skill.created_at)} />
        <InfoCard label="Updated" value={formatTime(skill.updated_at)} />
      </div>

      {skill.description && (
        <div className="mb-6 p-4 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <h3 className="text-sm font-medium mb-2" style={{ color: 'var(--color-text-secondary)' }}>Description</h3>
          <p className="text-sm">{skill.description}</p>
        </div>
      )}

      <SkillHealthPanel skillId={skill.id} canManage={isAdmin || user?.userId === skill.user_id} />

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

      {skill.sdk_capabilities && skill.sdk_capabilities.length > 0 && (
        <div className="mb-6 p-4 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <h3 className="text-sm font-medium mb-2" style={{ color: 'var(--color-text-secondary)' }}>
            SDK Capabilities
            <span className="ml-2 text-xs font-normal" style={{ color: 'var(--color-text-muted)' }}>script-only, not model tools</span>
          </h3>
          <div className="flex flex-wrap gap-2">
            {skill.sdk_capabilities.map(cap => (
              <span key={`${cap.kind}:${cap.path}`} className="px-2 py-1 rounded text-xs"
                title={cap.supported ? undefined : 'Not granted in this deployment'}
                style={{
                  background: cap.supported ? 'var(--color-primary)' + '12' : 'var(--color-warning)' + '12',
                  color: cap.supported ? 'var(--color-text)' : 'var(--color-warning)',
                  textDecoration: cap.supported ? undefined : 'line-through',
                }}>
                {cap.kind}: {cap.path}
              </span>
            ))}
          </div>
        </div>
      )}

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
