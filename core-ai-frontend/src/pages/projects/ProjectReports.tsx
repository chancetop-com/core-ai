import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ChevronDown, ChevronRight, ExternalLink, FileText, Link2, Loader2, Upload } from 'lucide-react';
import { api } from '../../api/client';
import type { ProjectReport, ProjectSubject } from '../../api/client';
import type { ArtifactSpec } from '../chat/components/artifactTypes';

const ArtifactDrawer = lazy(() => import('../chat/components/ArtifactDrawer'));

const UNASSIGNED = '__unassigned__';

interface Props {
  projectId: string;
  // fixed subject (subject page): no subject grouping, no "unassigned" toggle, upload enabled
  subjectId?: string;
  subjects: ProjectSubject[];
  onChanged?: () => void;
}

function formatBytes(n?: number) {
  if (n === undefined || n === null) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

function monthKey(iso: string) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return 'unknown';
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function monthLabel(key: string) {
  if (key === 'unknown') return 'Unknown date';
  const [y, m] = key.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString(undefined, { year: 'numeric', month: 'long' });
}

// same spec shape as the For You artifact list: the drawer loads /api/files/:id/content with the session's auth
function toSpec(r: ProjectReport): ArtifactSpec {
  return {
    kind: 'file',
    title: r.file_name,
    fileId: r.file_id,
    fileName: r.file_name,
    contentType: r.content_type,
    size: r.size,
  };
}

/**
 * Report directory of a project or of one subject: reports grouped by subject (project level) and by month,
 * openable in the artifact drawer, shareable by link, re-homeable to another subject, plus a manual upload
 * for reports produced outside the platform.
 */
export default function ProjectReports({ projectId, subjectId, subjects, onChanged }: Props) {
  const [reports, setReports] = useState<ProjectReport[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [unassignedOnly, setUnassignedOnly] = useState(false);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [active, setActive] = useState<ArtifactSpec | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const fileInput = useRef<HTMLInputElement | null>(null);

  const subjectName = useMemo(() => {
    const m: Record<string, string> = {};
    for (const s of subjects) m[s.id] = s.name;
    return m;
  }, [subjects]);

  const load = useCallback(() => {
    setLoading(true);
    const filter = {
      subjectId,
      from: from ? new Date(`${from}T00:00:00`).toISOString() : undefined,
      to: to ? new Date(`${to}T23:59:59.999`).toISOString() : undefined,
      unassigned: !subjectId && unassignedOnly ? true : undefined,
    };
    return api.projects.reports(projectId, filter)
      .then(res => { setReports(res.reports || []); setError(''); })
      .catch(e => setError(String((e as Error).message || e)))
      .finally(() => setLoading(false));
  }, [projectId, subjectId, from, to, unassignedOnly]);

  useEffect(() => { load(); }, [load]);

  // subject -> month -> reports (project level); month -> reports (subject level)
  const groups = useMemo(() => {
    const bySubject = new Map<string, Map<string, ProjectReport[]>>();
    for (const r of reports) {
      const sKey = subjectId ? subjectId : (r.subject_id || UNASSIGNED);
      const months = bySubject.get(sKey) ?? new Map<string, ProjectReport[]>();
      const mKey = monthKey(r.created_at);
      months.set(mKey, [...(months.get(mKey) ?? []), r]);
      bySubject.set(sKey, months);
    }
    // unassigned first (it needs attention), then subjects in project order
    const order = [UNASSIGNED, ...subjects.map(s => s.id), ...Array.from(bySubject.keys())];
    const seen = new Set<string>();
    const result: { key: string; label: string; months: { key: string; label: string; reports: ProjectReport[] }[] }[] = [];
    for (const key of order) {
      if (seen.has(key) || !bySubject.has(key)) continue;
      seen.add(key);
      const months = Array.from(bySubject.get(key)!.entries())
        .sort((a, b) => b[0].localeCompare(a[0]))
        .map(([mKey, rows]) => ({ key: mKey, label: monthLabel(mKey), reports: rows }));
      result.push({ key, label: key === UNASSIGNED ? 'Unassigned' : (subjectName[key] ?? key), months });
    }
    return result;
  }, [reports, subjects, subjectName, subjectId]);

  const toggle = (key: string) => setCollapsed(prev => ({ ...prev, [key]: !prev[key] }));

  const copyLink = async (r: ProjectReport) => {
    if (!r.share_token) return;
    const url = `${window.location.origin}/shared/artifacts/${r.share_token}`;
    try {
      await navigator.clipboard.writeText(url);
      setCopiedId(r.file_id);
      window.setTimeout(() => setCopiedId(null), 1500);
    } catch {
      window.prompt('Copy the share link', url);
    }
  };

  const move = async (r: ProjectReport, target: string) => {
    const next = target === UNASSIGNED ? null : target;
    if ((r.subject_id || null) === next) return;
    setBusyId(r.file_id);
    try {
      await api.projects.moveReport(projectId, r.file_id, next);
      await load();
      onChanged?.();
    } catch (e) {
      alert(`Move failed: ${e instanceof Error ? e.message : e}`);
    } finally {
      setBusyId(null);
    }
  };

  const upload = async (file: File | undefined) => {
    if (!file || !subjectId) return;
    setUploading(true);
    try {
      await api.projects.uploadReport(projectId, subjectId, file);
      await load();
      onChanged?.();
    } catch (e) {
      alert(`Upload failed: ${e instanceof Error ? e.message : e}`);
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const inputStyle = { background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' };

  return (
    <div>
      <div className="flex flex-wrap items-center gap-2 mb-3">
        <label className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>From</label>
        <input type="date" value={from} onChange={e => setFrom(e.target.value)}
          className="px-2 py-1 rounded-lg border text-xs" style={inputStyle} />
        <label className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>To</label>
        <input type="date" value={to} onChange={e => setTo(e.target.value)}
          className="px-2 py-1 rounded-lg border text-xs" style={inputStyle} />
        {(from || to) && (
          <button onClick={() => { setFrom(''); setTo(''); }} className="text-xs underline cursor-pointer"
            style={{ color: 'var(--color-text-secondary)' }}>Clear</button>
        )}
        {!subjectId && (
          <label className="flex items-center gap-1 text-xs cursor-pointer ml-2" style={{ color: 'var(--color-text-secondary)' }}>
            <input type="checkbox" checked={unassignedOnly} onChange={e => setUnassignedOnly(e.target.checked)} />
            Unassigned only
          </label>
        )}
        <span className="text-xs ml-auto" style={{ color: 'var(--color-text-secondary)' }}>
          {loading ? 'Loading...' : `${reports.length} report${reports.length === 1 ? '' : 's'}`}
        </span>
        {subjectId && (
          <>
            <input ref={fileInput} type="file" className="hidden" onChange={e => upload(e.target.files?.[0])} />
            <button onClick={() => fileInput.current?.click()} disabled={uploading}
              className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs border cursor-pointer disabled:opacity-50"
              style={{ borderColor: 'var(--color-border)' }} title="Upload a report generated outside the platform into this subject">
              {uploading ? <Loader2 size={12} className="animate-spin" /> : <Upload size={12} />}
              Upload report
            </button>
          </>
        )}
      </div>

      {error && (
        <div className="p-3 rounded-lg text-sm mb-3" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger)' }}>{error}</div>
      )}

      {!loading && reports.length === 0 && (
        <div className="text-sm py-6 text-center" style={{ color: 'var(--color-text-secondary)' }}>
          {subjectId
            ? 'No reports yet. Reports submitted by runs bound to this subject, attributed conversations, or uploaded here appear in this list.'
            : 'No reports yet. Member agents’ artifacts and uploaded reports appear here, grouped by subject and month.'}
        </div>
      )}

      {groups.map(group => {
        const groupKey = `s:${group.key}`;
        const groupCollapsed = !!collapsed[groupKey];
        const total = group.months.reduce((n, m) => n + m.reports.length, 0);
        return (
          <div key={group.key} className="mb-3 rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
            {!subjectId && (
              <button onClick={() => toggle(groupKey)}
                className="w-full flex items-center gap-2 px-3 py-2 text-sm font-medium cursor-pointer"
                style={{ background: 'var(--color-bg-secondary)' }}>
                {groupCollapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}
                <span style={group.key === UNASSIGNED ? { color: 'var(--color-warning, #b45309)' } : undefined}>{group.label}</span>
                <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>{total}</span>
                {group.key === UNASSIGNED && (
                  <span className="text-xs font-normal ml-2" style={{ color: 'var(--color-text-secondary)' }}>
                    not yet filed under a subject: pick one in the row
                  </span>
                )}
              </button>
            )}
            {!groupCollapsed && group.months.map(month => {
              const monthKeyFull = `${groupKey}:${month.key}`;
              const monthCollapsed = !!collapsed[monthKeyFull];
              return (
                <div key={month.key}>
                  <button onClick={() => toggle(monthKeyFull)}
                    className="w-full flex items-center gap-2 px-3 py-1.5 text-xs cursor-pointer"
                    style={{ color: 'var(--color-text-secondary)', borderTop: '1px solid var(--color-border)' }}>
                    {monthCollapsed ? <ChevronRight size={12} /> : <ChevronDown size={12} />}
                    {month.label}
                    <span>({month.reports.length})</span>
                  </button>
                  {!monthCollapsed && month.reports.map(r => (
                    <div key={r.file_id} className="flex items-center gap-3 px-3 py-2 text-sm cursor-pointer transition-colors"
                      style={{ borderTop: '1px solid var(--color-border)' }}
                      onClick={() => setActive(toSpec(r))} title="Open report preview"
                      onMouseEnter={e => { e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
                      onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; }}>
                      <FileText size={14} className="shrink-0" style={{ color: 'var(--color-text-secondary)' }} />
                      <span className="min-w-0 flex-1 truncate text-left">{r.file_name}</span>
                      <span className="text-xs shrink-0 px-1.5 rounded"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        {r.source === 'upload' ? 'upload' : (r.agent_name || 'agent')}
                      </span>
                      <span className="text-xs shrink-0 w-16 text-right" style={{ color: 'var(--color-text-secondary)' }}>{formatBytes(r.size)}</span>
                      <span className="text-xs shrink-0 w-36 text-right" style={{ color: 'var(--color-text-secondary)' }}>
                        {new Date(r.created_at).toLocaleString()}
                      </span>
                      <select value={r.subject_id || UNASSIGNED} disabled={busyId === r.file_id}
                        onClick={e => e.stopPropagation()}
                        onChange={e => move(r, e.target.value)} title="Move to another subject"
                        className="text-xs px-1.5 py-1 rounded border cursor-pointer disabled:opacity-50 max-w-40" style={inputStyle}>
                        <option value={UNASSIGNED}>Unassigned</option>
                        {subjects.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                      </select>
                      <button onClick={e => { e.stopPropagation(); copyLink(r); }} disabled={!r.share_token}
                        title={r.share_token ? 'Copy share link' : 'Not shared'}
                        className="p-1 rounded cursor-pointer disabled:opacity-30" style={{ color: copiedId === r.file_id ? 'var(--color-primary)' : 'var(--color-text-secondary)' }}>
                        <Link2 size={14} />
                      </button>
                      {r.share_token ? (
                        <a href={`/shared/artifacts/${r.share_token}`} target="_blank" rel="noreferrer" title="Open shared page"
                          onClick={e => e.stopPropagation()}
                          className="p-1 rounded cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
                          <ExternalLink size={14} />
                        </a>
                      ) : <span className="w-6" />}
                    </div>
                  ))}
                </div>
              );
            })}
          </div>
        );
      })}

      {active && (
        <Suspense fallback={null}>
          <ArtifactDrawer artifact={active} onClose={() => setActive(null)} />
        </Suspense>
      )}
    </div>
  );
}
