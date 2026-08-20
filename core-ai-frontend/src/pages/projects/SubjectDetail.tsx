import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Activity, ArrowLeft, CheckSquare, ExternalLink, FileText, ListChecks, Loader2, RefreshCw, RotateCcw, UserRound } from 'lucide-react';
import { api } from '../../api/client';
import type { ProjectEvent, ProjectExecution, ProjectReport, ProjectStatsView, ProjectView, TimelineEntry } from '../../api/client';

type Tab = 'report' | 'current' | 'executions' | 'artifacts' | 'cost' | 'timeline';

function formatNumber(n?: number) {
  if (n === undefined || n === null) return '-';
  return n.toLocaleString();
}

function formatTime(iso?: string) {
  if (!iso) return '-';
  return new Date(iso).toLocaleString();
}

function parseProfile(json?: string): [string, string][] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return Object.entries(parsed).map(([k, v]) => [k, typeof v === 'string' ? v : JSON.stringify(v)]);
    }
  } catch {
    // ignore malformed profile text
  }
  return [];
}

function formatCost(cost?: number) {
  if (cost === undefined || cost === null) return '-';
  return `$${cost.toFixed(2)}`;
}

function formatTokens(n?: number) {
  if (n === undefined || n === null) return '-';
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

// backfill watermark: FUTURE sentinel (year 2099) = every member record has been scanned for attribution
function attributionProgress(at?: string) {
  if (!at) return null;
  const d = new Date(at);
  if (d.getFullYear() >= 2099) return 'Attribution backfill: all history processed';
  return `Attribution backfill: processed through ${d.toLocaleDateString()}`;
}

function numericKpis(kpis: ProjectView['kpis'], key: string): number[] {
  return kpis
    .filter(k => k.key === key)
    .map(k => Number.parseFloat(k.value))
    .filter(n => !Number.isNaN(n));
}

export default function SubjectDetail() {
  const { id, subjectId } = useParams<{ id: string; subjectId: string }>();
  const navigate = useNavigate();
  const [project, setProject] = useState<ProjectView | null>(null);
  const [timeline, setTimeline] = useState<TimelineEntry[]>([]);
  const [phaseEvents, setPhaseEvents] = useState<ProjectEvent[]>([]);
  const [executions, setExecutions] = useState<ProjectExecution[]>([]);
  const [reports, setReports] = useState<ProjectReport[]>([]);
  const [stats, setStats] = useState<ProjectStatsView | null>(null);
  const [tab, setTab] = useState<Tab>('report');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [editModal, setEditModal] = useState(false);
  const [editName, setEditName] = useState('');
  const [editDesc, setEditDesc] = useState('');
  const [editLink, setEditLink] = useState('');

  const [analyzing, setAnalyzing] = useState(false);
  const [analyzeMessage, setAnalyzeMessage] = useState('');
  const [regenerating, setRegenerating] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  if (!id || !subjectId) return null;

  const regenerateReport = async () => {
    setRegenerating(true);
    try {
      await api.projects.report(id, subjectId);
      setAnalyzeMessage('Report render submitted — the renderer agent is writing the report, it will appear when finished...');
      pollUntilReportDone();
    } catch (e) {
      setAnalyzeMessage(String((e as Error).message || e));
      setRegenerating(false);
    }
  };

  // the render is an async agent run: poll the subject until report_run_id clears (the server
  // assembles the sections into the report and updates generated_at / report_error)
  const pollUntilReportDone = () => {
    let attempts = 0;
    const timer = window.setInterval(async () => {
      attempts++;
      try {
        const p = await api.projects.get(id, subjectId);
        const s = p.subjects.find(x => x.id === subjectId);
        setProject(p);
        if (!s?.report_run_id || attempts >= 180) {
          window.clearInterval(timer);
          setRegenerating(false);
          setAnalyzeMessage('');
          load();
        }
      } catch (e) {
        window.clearInterval(timer);
        setRegenerating(false);
        setAnalyzeMessage('');
      }
    }, 10000);
  };

  const runAnalysis = async () => {
    setAnalyzing(true);
    setAnalyzeMessage('Attributing new material and analyzing this subject (1-3 min)...');
    try {
      const res = await api.projects.analyze(id, subjectId);
      const total = (res.attributed ?? 0) + (res.analyzed ?? 0) + (res.updated ?? 0);
      setAnalyzeMessage(total === 0
        ? 'Analysis complete: no new material to attribute/analyze'
        : `Analysis complete: attributed ${res.attributed ?? 0}, analyzed ${res.analyzed ?? 0}, updated ${res.updated ?? 0}`);
      pollUntilIdle();
    } catch (e) {
      setAnalyzeMessage(String((e as Error).message || e));
      setAnalyzing(false);
    }
  };

  // after triggering a run, refresh until the project leaves the running state so the
  // button does not stay stuck on "Analyzing..." (a run can take a minute or more)
  const pollUntilIdle = () => {
    let attempts = 0;
    const timer = window.setInterval(async () => {
      attempts++;
      try {
        const p = await api.projects.get(id);
        setProject(p);
        if (p.analysis_status !== 'running' || attempts >= 120) {
          window.clearInterval(timer);
          setAnalyzing(false);
          load();
        }
      } catch (e) {
        window.clearInterval(timer);
        setAnalyzing(false);
      }
    }, 5000);
  };

  const load = useCallback(() => {
    setLoading(true);
    return Promise.all([
      api.projects.get(id, subjectId),
      api.projects.timeline(id, subjectId),
      api.projects.executions(id, undefined, 0, 50, subjectId),
      api.projects.reports(id, subjectId),
      api.projects.stats(id, subjectId),
      api.projects.events(id, subjectId, 'phase').catch(e => { console.error('phase events failed', e); return { events: [] }; }),
    ])
      .then(([p, t, e, r, s, ev]) => {
        setProject(p);
        setTimeline(t.entries || []);
        setPhaseEvents(ev.events || []);
        setExecutions(e.executions || []);
        setReports(r.reports || []);
        setStats(s);
        setError('');
      })
      .catch(e => setError(String((e as Error).message || e)))
      .finally(() => setLoading(false));
  }, [id, subjectId]);

  useEffect(() => { load(); }, [load]);

  const refresh = () => {
    setRefreshing(true);
    load().finally(() => setRefreshing(false));
  };

  const openEdit = () => {
    const subject = project?.subjects.find(s => s.id === subjectId);
    setEditName(subject?.name || '');
    setEditDesc(subject?.description || '');
    setEditLink(subject?.external_link || '');
    setEditModal(true);
  };

  const resetAnalysis = async () => {
    if (!confirm('Reset ALL analysis data for this subject? Events, attributions, status/KPIs/action items/notes and the report are cleared and the project re-scans its material from scratch — you can then run a fresh analysis.')) return;
    try {
      await api.projects.resetAnalysis(id, subjectId);
      setAnalyzeMessage('Analysis data reset — run "Analyze now" to start fresh.');
      load();
    } catch (e) {
      setAnalyzeMessage(String((e as Error).message || e));
    }
  };

  const saveEdit = async () => {
    await api.projects.updateSubject(id, subjectId, {
      name: editName,
      description: editDesc,
      external_link: editLink,
    });
    setEditModal(false);
    load();
  };

  const inputStyle = {
    background: 'var(--color-bg-secondary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  if (loading && !project) {
    return <div className="p-6 text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  }
  if (!project) {
    return (
      <div className="p-6">
        <div className="p-3 rounded-lg text-sm" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger)' }}>
          {error || 'Subject not found'}
        </div>
      </div>
    );
  }

  const subject = project.subjects.find(s => s.id === subjectId);
  const status = project.subject_statuses.find(s => s.subject_id === subjectId);
  const kpis = project.kpis.filter(k => k.subject_id === subjectId);
  const actionItems = project.action_items.filter(i => i.subject_id === subjectId);
  const notes = project.notes.filter(n => n.subject_id === subjectId);
  const kpiKeys = [...new Set(kpis.map(k => k.key))];
  const started = subject?.status === 'started';
  // scheduled subject analysis runs hourly; the next run lands roughly one hour after the last one
  const nextAnalysisAt = project.last_analyzed_at
    ? new Date(new Date(project.last_analyzed_at).getTime() + 60 * 60 * 1000)
    : null;
  const profileEntries = parseProfile(subject?.profile);

  return (
    <div className="p-6">
      <div className="flex items-center gap-3 mb-6">
        <button onClick={() => navigate(`/projects/${id}`)} className="p-1.5 rounded-lg border cursor-pointer"
          style={{ borderColor: 'var(--color-border)' }} title="Back to project">
          <ArrowLeft size={16} />
        </button>
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-semibold">{subject?.name || subjectId}</h1>
            {subject?.external_link && (
              <a href={subject.external_link} target="_blank" rel="noreferrer" title="External link"
                className="cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
                <ExternalLink size={14} />
              </a>
            )}
            <span className="text-xs px-1.5 py-0.5 rounded"
              style={{ background: 'var(--color-bg-tertiary)', color: started ? 'var(--color-success, #10b981)' : 'var(--color-text-secondary)' }}>
              {started ? 'started' : 'not started'}
            </span>
          </div>
          <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            {project.name}{subject?.description ? ` · ${subject.description}` : ''}
            {subject?.analyzed_at ? ` · Last analyzed ${formatTime(subject.analyzed_at)}` : ' · Not analyzed yet'}
          </div>
        </div>
        <div className="flex items-center gap-2 ml-auto">
          <button onClick={runAnalysis} disabled={analyzing}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50"
            style={{ borderColor: 'var(--color-border)' }}>
            <Activity size={14} />
            {analyzing ? 'Analyzing...' : 'Analyze now'}
          </button>
          <button onClick={openEdit} className="px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            Edit
          </button>
          <button onClick={resetAnalysis} title="Reset analysis data"
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-danger)' }}>
            <RotateCcw size={14} />
            Reset data
          </button>
          <button onClick={refresh} className="p-1.5 rounded-lg border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }} title="Refresh">
            {refreshing ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
          </button>
        </div>
      </div>

      {analyzeMessage && (
        <div className="mb-4 p-3 rounded-lg text-sm flex items-center gap-2"
          style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
          {analyzing && <Loader2 size={14} className="animate-spin shrink-0" />}
          {analyzeMessage}
        </div>
      )}

      {error && (
        <div className="mb-4 p-3 rounded-lg text-sm" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger)' }}>
          {error}
        </div>
      )}

      {/* Overview: quick stats + current phase at the page level, stays outside the tabs */}
      <div className="p-4 rounded-xl border mb-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <div className="flex items-center justify-between mb-3">
          <div className="text-sm font-medium">Overview</div>
          <div className="flex items-center gap-3">
            {attributionProgress(project.attribution_backfilled_at) && (
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {attributionProgress(project.attribution_backfilled_at)}
              </span>
            )}
            {stats?.computed_at && (
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                Updated {new Date(stats.computed_at).toLocaleTimeString()}
              </span>
            )}
          </div>
        </div>
        <div className="grid grid-cols-3 gap-3">
          <div className="p-3 rounded-xl border" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Phase</div>
            <div className="font-medium truncate" title={status?.updated_at ? formatTime(status.updated_at) : undefined}>
              {status?.phase || '-'}
            </div>
          </div>
          <div className="p-3 rounded-xl border" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Attributed</div>
            <div className="font-medium">{formatNumber(subject?.attributed_count)}</div>
          </div>
          <div className="p-3 rounded-xl border" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Traces</div>
            <div className="font-medium">{formatNumber(stats?.trace_count)}</div>
          </div>
          <div className="p-3 rounded-xl border" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Tokens</div>
            <div className="font-medium">{formatTokens(stats?.total_tokens)}</div>
          </div>
          <div className="p-3 rounded-xl border" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Total cost</div>
            <div className="font-medium">{formatCost(stats?.total_cost_usd)}</div>
          </div>
          <div className="p-3 rounded-xl border" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Next analysis</div>
            <div className="font-medium">
              {nextAnalysisAt ? nextAnalysisAt.toLocaleTimeString() : '-'}
            </div>
          </div>
        </div>
      </div>

      <div className="flex items-center gap-1 mb-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
        {(['report', 'current', 'executions', 'artifacts', 'cost', 'timeline'] as Tab[]).map(t => (
          <button key={t} onClick={() => setTab(t)}
            className="px-3 py-2 text-sm cursor-pointer capitalize"
            style={{
              color: tab === t ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              borderBottom: tab === t ? '2px solid var(--color-primary)' : '2px solid transparent',
            }}>
            {t}
          </button>
        ))}
      </div>

      {tab === 'report' && (
        /* report = the HTML campaign report (this subject's story with time nodes — phase
           timeline, per-KPI trends, action items, notes). Sandboxed iframe with an opaque origin
           (no allow-same-origin) so report scripts cannot touch app cookies/storage. */
        <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
          <div className="flex items-center justify-between p-3"
            style={{ background: 'var(--color-bg-secondary)', borderBottom: '1px solid var(--color-border)' }}>
            <div className="flex items-center gap-3 min-w-0">
              <span className="text-sm font-medium">Campaign report</span>
              {subject?.report_generated_at && (
                <span className="text-xs truncate" style={{ color: 'var(--color-text-secondary)' }}>
                  Generated {new Date(subject.report_generated_at).toLocaleString()}
                </span>
              )}
            </div>
            <button onClick={regenerateReport} disabled={regenerating}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm border cursor-pointer disabled:opacity-50 shrink-0"
              style={{ borderColor: 'var(--color-border)' }}>
              {regenerating ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />}
              Regenerate
            </button>
          </div>
          {subject?.report_error && (
            <div className="p-3 text-xs" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger)' }}>
              Last report render failed: {subject.report_error}
            </div>
          )}
          {subject?.report_share_token ? (
            <iframe
              key={subject.report_share_token}
              src={`/api/public/artifacts/${subject.report_share_token}/content`}
              sandbox="allow-scripts allow-popups"
              title="Campaign report"
              className="w-full border-0"
              style={{ height: 720, background: 'var(--color-bg-primary)' }}
            />
          ) : (
            <div className="p-6 text-sm text-center" style={{ color: 'var(--color-text-secondary)' }}>
              No report yet. Click Regenerate to generate the report from this subject's event
              history (phase timeline, per-KPI trends, action items, notes).
            </div>
          )}
        </div>
      )}

      {tab === 'current' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div className="p-4 rounded-xl border" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center gap-2 mb-2 text-sm font-medium">
              <FileText size={14} /> Summary
            </div>
            {status?.summary ? (
              <div className="text-sm">{status.summary}</div>
            ) : (
              <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>No summary recorded yet.</div>
            )}
            {status?.updated_at && <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(status.updated_at)}</div>}
          </div>

          {/* Phase history (D7): the transition series — when the subject entered each phase */}
          <div className="p-4 rounded-xl border lg:col-span-2" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center gap-2 mb-2 text-sm font-medium">
              <Activity size={14} /> Phase history
            </div>
            {phaseEvents.length === 0 ? (
              <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                No phase transitions recorded yet — they appear here after the analysis runs.
              </div>
            ) : (
              <div className="flex flex-col">
                {phaseEvents.map((event, i) => (
                  <div key={event.id || i} className="flex items-start gap-3 py-2 border-b last:border-b-0"
                    style={{ borderColor: 'var(--color-border)' }}>
                    <div className="flex flex-col items-center self-stretch">
                      <div className="w-2 h-2 rounded-full mt-1.5"
                        style={{ background: i === 0 ? 'var(--color-primary)' : 'var(--color-text-tertiary)' }} />
                      {i < phaseEvents.length - 1 && (
                        <div className="w-px flex-1 my-1" style={{ background: 'var(--color-border)' }} />
                      )}
                    </div>
                    <div className="min-w-0">
                      <div className="text-sm">{event.value}</div>
                      <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(event.at)}</div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="p-4 rounded-xl border lg:col-span-2" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center gap-2 mb-2 text-sm font-medium">
              <UserRound size={14} /> Profile
            </div>
            {profileEntries.length === 0 ? (
              <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                No profile extracted yet — run the analysis to extract stable facts about this subject.
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-x-6">
                {profileEntries.map(([key, value]) => (
                  <div key={key} className="flex items-baseline gap-2 py-1 text-sm border-b"
                    style={{ borderColor: 'var(--color-border)' }}>
                    <span className="text-xs shrink-0 w-28" style={{ color: 'var(--color-text-secondary)' }}>{key}</span>
                    <span className="min-w-0 flex-1">{value}</span>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="p-4 rounded-xl border" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center gap-2 mb-2 text-sm font-medium">
              <Activity size={14} /> KPIs
            </div>
            {kpiKeys.length === 0 ? (
              <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>No KPI snapshots yet.</div>
            ) : kpiKeys.map(key => {
              const values = numericKpis(kpis, key);
              return (
                <div key={key} className="mb-2">
                  <div className="text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>{key}</div>
                  {values.length >= 2 ? (
                    <div className="flex items-end gap-1 h-16">
                      {values.map((v, i) => {
                        const max = Math.max(...values);
                        const min = Math.min(...values);
                        const span = max - min || 1;
                        const height = 8 + ((v - min) / span) * 48;
                        return (
                          <div key={i} className="flex-1 rounded-t" title={String(v)}
                            style={{ height: `${height}px`, background: 'var(--color-primary)', opacity: 0.7 }} />
                        );
                      })}
                    </div>
                  ) : (
                    <div className="text-sm">{values[0]}</div>
                  )}
                  <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                    {values[0]} → {values[values.length - 1]}
                  </div>
                </div>
              );
            })}
          </div>

          <div className="p-4 rounded-xl border" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center gap-2 mb-2 text-sm font-medium">
              <ListChecks size={14} /> Action Items
            </div>
            {actionItems.length === 0 ? (
              <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>No action items.</div>
            ) : actionItems.map(item => (
              <div key={item.id} className="flex items-center gap-2 py-1 text-sm">
                <CheckSquare size={14} style={{ color: item.status === 'done' ? 'var(--color-primary)' : 'var(--color-text-secondary)' }} />
                <span className={item.status === 'done' ? 'line-through' : ''} style={item.status === 'done' ? { color: 'var(--color-text-secondary)' } : undefined}>
                  {item.title}
                </span>
                {item.status !== 'done' && (
                  <span className="text-xs px-1.5 rounded" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>{item.status}</span>
                )}
              </div>
            ))}
          </div>

          <div className="p-4 rounded-xl border" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center gap-2 mb-2 text-sm font-medium">
              <FileText size={14} /> Notes
            </div>
            {notes.length === 0 ? (
              <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>No notes.</div>
            ) : notes.slice(-10).reverse().map((n, i) => (
              <div key={i} className="py-1 text-sm">{n.content}</div>
            ))}
          </div>
        </div>
      )}

      {tab === 'executions' && (
        <div>
          {executions.length === 0 ? (
            <div className="text-sm py-6 text-center" style={{ color: 'var(--color-text-secondary)' }}>No executions yet.</div>
          ) : executions.map(e => (
            <div key={`${e.type}-${e.id}`} className="flex items-center gap-3 py-2 text-sm border-b cursor-pointer"
              style={{ borderColor: 'var(--color-border)' }}
              onClick={() => {
                if (e.trace_id) navigate(`/traces/${e.trace_id}`);
                else if (e.type === 'run') navigate(`/runs/${e.id}`);
              }}>
              <span className="text-xs w-20 px-1.5 rounded text-center"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>{e.type}</span>
              <span className="min-w-0 flex-1 truncate">{e.title || e.id}</span>
              {e.agent_name && <span className="text-xs shrink-0" style={{ color: 'var(--color-text-secondary)' }}>{e.agent_name}</span>}
              <span className="text-xs shrink-0 w-24 text-right" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(e.started_at)}</span>
              <span className="text-xs shrink-0 w-20 text-right">{formatCost(e.cost_usd)}</span>
            </div>
          ))}
        </div>
      )}

      {tab === 'artifacts' && (
        <div>
          {reports.length === 0 ? (
            <div className="text-sm py-6 text-center" style={{ color: 'var(--color-text-secondary)' }}>No reports yet.</div>
          ) : reports.map(r => (
            <div key={r.file_id} className="flex items-center gap-3 py-2 text-sm border-b"
              style={{ borderColor: 'var(--color-border)' }}>
              <FileText size={14} style={{ color: 'var(--color-text-secondary)' }} />
              <span className="min-w-0 flex-1 truncate">{r.file_name}</span>
              {r.agent_name && (
                <span className="text-xs shrink-0 px-1.5 rounded"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>{r.agent_name}</span>
              )}
              <span className="text-xs shrink-0" style={{ color: 'var(--color-text-secondary)' }}>{r.size ? `${r.size} B` : ''}</span>
              <span className="text-xs shrink-0 w-24 text-right" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(r.created_at)}</span>
            </div>
          ))}
        </div>
      )}

      {tab === 'cost' && stats && (
        <div>
          <div className="text-sm font-medium mb-2">By agent</div>
          {(stats.by_agent || []).map((a, i) => (
            <div key={i} className="flex items-center gap-3 py-1 text-sm border-b" style={{ borderColor: 'var(--color-border)' }}>
              <span className="min-w-0 flex-1 truncate">{a.agent_name || a.agent_id || 'unknown'}</span>
              <span className="text-xs shrink-0" style={{ color: 'var(--color-text-secondary)' }}>{formatTokens(a.tokens)} tokens</span>
              <span className="text-xs shrink-0 w-20 text-right">{formatCost(a.cost_usd)}</span>
            </div>
          ))}
        </div>
      )}

      {tab === 'timeline' && (
        <div>
          {timeline.length === 0 ? (
            <div className="text-sm py-6 text-center" style={{ color: 'var(--color-text-secondary)' }}>No events yet.</div>
          ) : timeline.slice(0, 50).map((entry, i) => (
            <div key={i} className="flex items-start gap-2 py-1 text-sm">
              <span className="text-xs shrink-0 w-24" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(entry.at)}</span>
              <span className="text-xs shrink-0 w-20 px-1.5 rounded text-center"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>{entry.type}</span>
              <span className="min-w-0 break-words">
                {entry.title}
                {entry.detail && <span className="text-xs ml-1" style={{ color: 'var(--color-text-secondary)' }}>({entry.detail})</span>}
              </span>
            </div>
          ))}
        </div>
      )}

      {editModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.4)' }}>
          <div className="w-full max-w-md p-4 rounded-xl border" style={{ background: 'var(--color-bg-primary)', borderColor: 'var(--color-border)' }}>
            <div className="font-medium mb-3">Edit Subject</div>
            <div className="grid gap-3">
              <input type="text" placeholder="Subject name" value={editName}
                onChange={e => setEditName(e.target.value)} className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
              <input type="text" placeholder="Description (optional)" value={editDesc}
                onChange={e => setEditDesc(e.target.value)} className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
              <input type="text" placeholder="External link (optional)" value={editLink}
                onChange={e => setEditLink(e.target.value)} className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
              <div className="flex justify-end gap-2">
                <button onClick={() => setEditModal(false)} className="px-3 py-1.5 rounded-lg text-sm border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}>Cancel</button>
                <button onClick={saveEdit} disabled={!editName.trim()}
                  className="px-3 py-1.5 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                  style={{ background: 'var(--color-primary)' }}>Save</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
