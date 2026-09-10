import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Activity, Archive, CheckCircle2, ChevronDown, ChevronLeft, ChevronRight, CircleDot, Clock, Loader2, Plus, RefreshCw, RotateCcw, Search, Trash2 } from 'lucide-react';
import { api } from '../../api/client';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type {
  ProjectMember,
  ProjectStatsView,
  ProjectSubject,
  ProjectView,
} from '../../api/client';
import ProjectReports from './ProjectReports';

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
// the attribution cursor: member records up to this time have been offered to the attributor at
// least once; the forward pass keeps moving it every 10 minutes as new records arrive
function attributionCaughtUp(at?: string) {
  return !!at && Date.now() - new Date(at).getTime() < 24 * 60 * 60 * 1000;
}

export default function ProjectDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [project, setProject] = useState<ProjectView | null>(null);
  const [stats, setStats] = useState<ProjectStatsView | null>(null);
  const [members, setMembers] = useState<{ agents: ProjectMember[]; workflows: ProjectMember[] }>({ agents: [], workflows: [] });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [memberWarning, setMemberWarning] = useState('');

  // all sections collapsed by default so the subject list is not pushed below the fold
  const [showMembers, setShowMembers] = useState(false);
  const [showPlaybook, setShowPlaybook] = useState(false);
  const [showReports, setShowReports] = useState(false);
  // report counts for the subject cards and the unassigned badge (one list call, aggregated client-side)
  const [reportStats, setReportStats] = useState<{ unassigned: number; bySubject: Record<string, { count: number; latest?: string }> } | null>(null);

  const [subjectModal, setSubjectModal] = useState(false);
  const [subjectName, setSubjectName] = useState('');
  const [subjectDesc, setSubjectDesc] = useState('');
  const [subjectLink, setSubjectLink] = useState('');

  // paginated + searchable subject list
  const [subjectList, setSubjectList] = useState<ProjectSubject[]>([]);
  const [subjectTotal, setSubjectTotal] = useState(0);
  const [subjectQuery, setSubjectQuery] = useState('');
  const [subjectOffset, setSubjectOffset] = useState(0);
  const [subjectLimit, setSubjectLimit] = useState(10);

  const [editModal, setEditModal] = useState(false);
  const [editName, setEditName] = useState('');
  const [editDesc, setEditDesc] = useState('');
  const [editGoal, setEditGoal] = useState('');
  const [editAutoSubjects, setEditAutoSubjects] = useState('propose');

  // pending auto-discovered proposals: the merge target picked per proposal row
  const [mergePicks, setMergePicks] = useState<Record<string, string>>({});
  const [reviewBusy, setReviewBusy] = useState('');

  const [allAgents, setAllAgents] = useState<ProjectMember[]>([]);
  const [allWorkflows, setAllWorkflows] = useState<ProjectMember[]>([]);
  const [agentPick, setAgentPick] = useState('');
  const [workflowPick, setWorkflowPick] = useState('');
  const [refreshing, setRefreshing] = useState(false);

  if (!id) return null;

  const load = useCallback(() => {
    setLoading(true);
    return Promise.all([
      api.projects.get(id),
      api.projects.stats(id).catch(e => { console.error('project stats failed', e); return null as ProjectStatsView | null; }),
      api.projects.members(id).catch(e => { console.error('project members failed', e); return { agents: [], workflows: [] }; }),
      api.projects.memberOptions(id).catch(e => {
        const message = String((e as Error).message || e);
        console.error('member options failed', e);
        setMemberWarning(message);
        return { agents: [], workflows: [] };
      }),
      api.projects.subjects(id, subjectOffset, subjectLimit, subjectQuery),
      api.projects.reportStats(id).catch(e => { console.error('project report stats failed', e); return null; }),
    ])
      .then(([p, s, m, options, subjectPage, reportStatsView]) => {
        setProject(p);
        setStats(s);
        setMembers(m);
        setAllAgents(options.agents || []);
        setAllWorkflows(options.workflows || []);
        setSubjectList(subjectPage.subjects || []);
        setSubjectTotal(subjectPage.total ?? 0);
        if (reportStatsView) {
          // server-side counts: the project page never lists reports, so it stays cheap regardless of history size
          const bySubject: Record<string, { count: number; latest?: string }> = {};
          for (const s of reportStatsView.subjects || []) bySubject[s.subject_id] = { count: s.count, latest: s.latest_at };
          setReportStats({ unassigned: reportStatsView.unassigned ?? 0, bySubject });
        }
        setError('');
      })
      .catch(e => {
        console.error('failed to load project', e);
        setError(String((e as Error).message || e));
      })
      .finally(() => setLoading(false));
  }, [id, subjectOffset, subjectLimit, subjectQuery]);

  useEffect(() => { load(); }, [load]);

  const refresh = () => {
    setRefreshing(true);
    load().finally(() => setRefreshing(false));
  };

  const addAgent = async () => {
    if (!agentPick) return;
    await api.projects.addMember(id, 'agent', agentPick);
    setAgentPick('');
    load();
  };

  const addWorkflow = async () => {
    if (!workflowPick) return;
    await api.projects.addMember(id, 'workflow', workflowPick);
    setWorkflowPick('');
    load();
  };

  const removeMember = async (type: 'agent' | 'workflow', member: ProjectMember) => {
    await api.projects.removeMember(id, type, member.id);
    load();
  };

  const renderMemberChip = (m: ProjectMember, type: 'agent' | 'workflow') => (
    <span key={`${type}:${m.id}`} className="flex items-center gap-2 px-2 py-1 rounded-lg border text-sm"
      style={{ borderColor: 'var(--color-border)' }}>
      <span className="cursor-pointer" onClick={() => navigate(type === 'agent' ? `/agents/${m.id}` : `/workflows/${m.id}`)}>
        <Activity size={13} className="inline mr-1" style={{ color: 'var(--color-text-secondary)' }} />
        {m.name}
      </span>
      <button onClick={() => removeMember(type, m)} className="cursor-pointer"
        style={{ color: 'var(--color-text-secondary)' }} title="Remove from project">
        <Trash2 size={12} />
      </button>
    </span>
  );

  const createSubject = async () => {
    if (!subjectName.trim()) return;
    await api.projects.createSubject(id, { name: subjectName.trim(), description: subjectDesc, external_link: subjectLink });
    setSubjectModal(false);
    setSubjectName('');
    setSubjectDesc('');
    setSubjectLink('');
    load();
  };

  const deleteSubject = async (subject: ProjectSubject) => {
    if (!confirm(`Delete subject "${subject.name}"? It is only allowed while nothing references it.`)) return;
    await api.projects.deleteSubject(id, subject.id);
    load();
  };

  const archiveProject = async () => {
    if (project?.status === 'archived') {
      await api.projects.activate(id);
    } else {
      if (!confirm(`Archive "${project?.name}"? Nothing is deleted.`)) return;
      await api.projects.archive(id);
    }
    load();
  };

  const [analyzing, setAnalyzing] = useState(false);
  const [analyzeMessage, setAnalyzeMessage] = useState('');
  const runAnalysis = async () => {
    setAnalyzing(true);
    setAnalyzeMessage('Attributing new material and analyzing all started subjects (1-3 min)...');
    try {
      const res = await api.projects.analyze(id);
      setAnalyzeMessage(res.status === 'running'
        ? 'Analysis started in the background — attributing new material and analyzing all started subjects. This page refreshes when it finishes.'
        : 'Analysis triggered.');
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

  const resetBuiltins = async () => {
    if (!confirm('Reset the builtin project-attributor / project-subject-analyzer / project-report-renderer definitions to their default prompts and schemas? Your edits will be overwritten.')) return;
    try {
      await api.projects.resetBuiltinAgents();
      setAnalyzeMessage('Builtin agents reset.');
    } catch (e) {
      setAnalyzeMessage(String((e as Error).message || e));
    }
  };

  const openEdit = () => {
    setEditName(project?.name || '');
    setEditDesc(project?.description || '');
    setEditGoal(project?.goal || '');
    setEditAutoSubjects(project?.auto_subjects || 'propose');
    setEditModal(true);
  };

  const saveEdit = async () => {
    await api.projects.update(id, {
      name: editName,
      description: editDesc,
      goal: editGoal,
      auto_subjects: editAutoSubjects,
    });
    setEditModal(false);
    load();
  };

  // proposals are subjects in status=proposed: accept promotes them, reject deletes them and
  // remembers the name, merge re-homes their material onto a real subject
  const reviewProposal = async (action: 'accept' | 'reject' | 'merge', subject: ProjectSubject) => {
    setReviewBusy(subject.id);
    try {
      if (action === 'reject' && !confirm(`Reject proposal "${subject.name}"? It is deleted, its material goes back to unassigned and the name is remembered so it will not be proposed again.`)) return;
      if (action === 'accept') {
        await api.projects.acceptSubject(id, subject.id);
      } else if (action === 'reject') {
        await api.projects.rejectSubject(id, subject.id);
      } else {
        const into = mergePicks[subject.id];
        if (!into) return;
        const target = project?.subjects.find(s => s.id === into);
        if (!confirm(`Merge "${subject.name}" into "${target?.name || into}"? Its material moves there and the name becomes an alias of the target.`)) return;
        await api.projects.mergeSubject(id, subject.id, into);
      }
      load();
    } catch (e) {
      setError(String((e as Error).message || e));
    } finally {
      setReviewBusy('');
    }
  };

  const rescanUnassigned = async () => {
    if (!confirm('Re-offer every material that never got attributed? This re-runs the attributor over history — a real token cost on a large project.')) return;
    setReviewBusy('rescan');
    try {
      const res = await api.projects.rescanUnassigned(id);
      const dropped = res.dropped ?? 0;
      setAnalyzeMessage(dropped > 0
        ? `${dropped} material marker(s) cleared and the attribution cursor rewound — the next round (every 10 minutes) re-offers that range.`
        : 'Every scanned material already carries an attribution — nothing to re-offer.');
      load();
    } catch (e) {
      setError(String((e as Error).message || e));
    } finally {
      setReviewBusy('');
    }
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
          {error || 'Project not found'}
        </div>
      </div>
    );
  }

  const memberCount = members.agents.length + members.workflows.length;
  const proposals = project.subjects.filter(s => s.status === 'proposed');
  const mergeCandidates = project.subjects.filter(s => s.status !== 'proposed');
  const autoSubjectsMode = project.auto_subjects || 'propose';

  return (
    <div className="p-6">
      <div className="flex items-start justify-between mb-6 gap-3">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-semibold">{project.name}</h1>
            {project.status === 'archived' && (
              <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>archived</span>
            )}
          </div>
          {project.goal && <div className="text-sm mt-1">{project.goal}</div>}
          {project.description && <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>{project.description}</div>}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button onClick={resetBuiltins}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            Reset builtins
          </button>
          <button onClick={() => setSubjectModal(true)}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={14} /> Subject
          </button>
          <button onClick={openEdit}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            Edit
          </button>
          <button onClick={archiveProject}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            {project.status === 'archived' ? <RotateCcw size={14} /> : <Archive size={14} />}
            {project.status === 'archived' ? 'Activate' : 'Archive'}
          </button>
          <button onClick={refresh} className="p-1.5 rounded-lg border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }} title="Refresh">
            {refreshing ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-4 p-3 rounded-lg text-sm" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger)' }}>
          {error}
        </div>
      )}

      {analyzeMessage && (
        <div className="mb-4 p-3 rounded-lg text-sm flex items-center gap-2" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
          {analyzing && <Loader2 size={14} className="animate-spin shrink-0" />}
          {analyzeMessage}
        </div>
      )}

      {project.analysis_error && project.analysis_status !== 'running' && (
        <div className="mb-4 p-3 rounded-lg text-sm" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger)' }}>
          Last analysis failed: {project.analysis_error}
        </div>
      )}

      {project.last_analyzed_at && (
        <div className="mb-4 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          Last analyzed: {new Date(project.last_analyzed_at).toLocaleString()}
        </div>
      )}

      {/* Attribution backfill overview: how far the incremental attribution has covered, with a
          one-click way to keep going */}
      {project.attribution_backfilled_at && (
        <div className="p-4 rounded-xl border mb-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2.5 min-w-0">
              {attributionCaughtUp(project.attribution_backfilled_at) ? (
                <CheckCircle2 size={16} style={{ color: 'var(--color-success, #10b981)' }} className="shrink-0" />
              ) : (
                <Clock size={16} className="shrink-0" style={{ color: 'var(--color-text-secondary)' }} />
              )}
              <div className="min-w-0">
                <div className="text-sm font-medium">Attribution scan</div>
                <div className="text-xs truncate" style={{ color: 'var(--color-text-secondary)' }}>
                  {attributionCaughtUp(project.attribution_backfilled_at)
                    ? `Caught up — member records through ${new Date(project.attribution_backfilled_at).toLocaleString()} have been offered to the attributor; new records are picked up every 10 minutes.`
                    : `Scanned through ${new Date(project.attribution_backfilled_at).toLocaleDateString()} — older records are still being worked through. A batch runs automatically every 10 minutes.`}
                </div>
              </div>
            </div>
            {!attributionCaughtUp(project.attribution_backfilled_at) && (
              <button onClick={runAnalysis} disabled={analyzing}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50 shrink-0"
                style={{ borderColor: 'var(--color-border)' }}>
                <Activity size={14} />
                {analyzing ? 'Analyzing...' : 'Continue analysis'}
              </button>
            )}
          </div>
        </div>
      )}

      {/* Cost overview on top: project-level averages and cross-subject comparison; details live in the subject page */}
      <div className="p-4 rounded-xl border mb-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <div className="flex items-center justify-between mb-3">
          <div className="text-sm font-medium">Cost overview</div>
          <div className="flex items-center gap-3">
            {stats?.computed_at && (
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                Updated {new Date(stats.computed_at).toLocaleTimeString()}
              </span>
            )}
          </div>
        </div>
        <div className="grid grid-cols-3 gap-3">
          <div className="p-3 rounded-xl border text-center" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Traces</div>
            <div className="font-medium">{stats?.trace_count ?? 0}</div>
          </div>
          <div className="p-3 rounded-xl border text-center" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Tokens</div>
            <div className="font-medium">{formatTokens(stats?.total_tokens)}</div>
          </div>
          <div className="p-3 rounded-xl border text-center" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Total cost</div>
            <div className="font-medium">{formatCost(stats?.total_cost_usd)}</div>
          </div>
        </div>
      </div>

      {/* Members: collapsed by default so it never crowds the page */}
      <div className="rounded-xl border mb-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <button onClick={() => setShowMembers(!showMembers)}
          className="w-full flex items-center justify-between p-4 cursor-pointer">
          <span className="text-sm font-medium">Agents & Workflows ({memberCount})</span>
          {showMembers ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </button>
        {showMembers && (
          <div className="px-4 pb-4">
            {memberWarning && (
              <div className="mb-3 p-2 rounded-lg text-xs" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger)' }}>
                Failed to load member options: {memberWarning}
              </div>
            )}
            <div className="flex items-center gap-2 mb-3">
              <select value={agentPick} onChange={e => setAgentPick(e.target.value)}
                className="px-2 py-1.5 rounded-lg border text-sm" style={inputStyle}>
                <option value="">Add agent...</option>
                {allAgents.filter(a => !members.agents.some(m => m.id === a.id)).map(a => (
                  <option key={a.id} value={a.id}>{a.name}</option>
                ))}
              </select>
              <button onClick={addAgent} disabled={!agentPick}
                className="px-3 py-1.5 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-primary)' }}>
                <Plus size={13} className="inline mr-1" />Add
              </button>
              <select value={workflowPick} onChange={e => setWorkflowPick(e.target.value)}
                className="px-2 py-1.5 rounded-lg border text-sm" style={inputStyle}>
                <option value="">Add workflow...</option>
                {allWorkflows.filter(w => !members.workflows.some(m => m.id === w.id)).map(w => (
                  <option key={w.id} value={w.id}>{w.name}</option>
                ))}
              </select>
              <button onClick={addWorkflow} disabled={!workflowPick}
                className="px-3 py-1.5 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-primary)' }}>
                <Plus size={13} className="inline mr-1" />Add
              </button>
            </div>
            <div className="flex flex-col gap-3">
              <div>
                <div className="text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Agents ({members.agents.length})
                </div>
                {members.agents.length === 0 ? (
                  <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>No agents attached.</span>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {members.agents.map(m => renderMemberChip(m, 'agent'))}
                  </div>
                )}
              </div>
              <div>
                <div className="text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Workflows ({members.workflows.length})
                </div>
                {members.workflows.length === 0 ? (
                  <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>No workflows attached.</span>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {members.workflows.map(m => renderMemberChip(m, 'workflow'))}
                  </div>
                )}
              </div>
              {memberCount === 0 && (
                <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>No members attached yet.</span>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Playbook: collapsed by default */}
      <div className="rounded-xl border mb-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <button onClick={() => setShowPlaybook(!showPlaybook)}
          className="w-full flex items-center justify-between p-4 cursor-pointer">
          <span className="text-sm font-medium">Playbook</span>
          {showPlaybook ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </button>
        {showPlaybook && (
          <div className="px-4 pb-4">
            {!project.playbook && autoSubjectsMode !== 'off' && (
              <div className="mb-3 p-2 rounded-lg text-xs"
                style={{ background: 'rgba(234, 179, 8, 0.1)', color: '#b45309' }}>
                Auto subjects is on ({autoSubjectsMode === 'create' ? 'create' : 'propose'}) but the playbook is empty —
                describe what a subject is (merchant, campaign, business line) so the attributor has a basis for discovering new ones.
              </div>
            )}
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Process + KPI evaluation methodology</span>
              <button onClick={() => navigate(`/projects/${id}/playbook`)} className="text-xs px-2 py-1 rounded-lg border cursor-pointer"
                style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                Open editor
              </button>
            </div>
            <div className="text-sm" style={{ color: project.playbook ? 'var(--color-text)' : 'var(--color-text-secondary)' }}>
              {project.playbook ? (
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{project.playbook}</ReactMarkdown>
              ) : (
                'No playbook yet. Define the overall process and the KPI evaluation methodology (what to measure, how to score, how often).'
              )}
            </div>
            <div className="flex flex-wrap gap-2 mt-3">
              {(project.report_sources || []).map((s, i) => (
                <span key={i} className="text-xs px-2 py-0.5 rounded"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  {s.type === 'agent' ? 'Agent' : 'Workflow'}: {s.name || s.id}
                </span>
              ))}
              {(project.report_sources || []).length === 0 && (
                <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  No report sources defined (whose artifacts are the reports to evaluate).
                </span>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Unassigned reports: the triage inbox. Reports already filed live on their subject page (see the
          counts on the subject cards); listing them here again would only duplicate that view */}
      <div className="p-4 rounded-xl border mb-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <button onClick={() => setShowReports(!showReports)}
          className="w-full flex items-center justify-between cursor-pointer">
          <span className="flex items-center gap-2 text-sm font-medium">
            Unassigned reports
            {reportStats && (
              <span className="text-[10px] px-1.5 py-0.5 rounded font-normal"
                style={reportStats.unassigned > 0
                  ? { background: 'rgba(234, 179, 8, 0.15)', color: '#b45309' }
                  : { background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                {reportStats.unassigned}
              </span>
            )}
            <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>
              reports the attributor could not place; file them under a subject
            </span>
          </span>
          <span className="flex items-center gap-2">
            <button onClick={e => { e.stopPropagation(); rescanUnassigned(); }} disabled={reviewBusy === 'rescan'}
              className="px-2 py-1 rounded-lg text-xs border cursor-pointer disabled:opacity-50"
              style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}
              title="Re-offer material that was scanned but never attributed (runs the attributor over history)">
              {reviewBusy === 'rescan' ? 'Rescanning...' : 'Rescan unassigned'}
            </button>
            {showReports ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
          </span>
        </button>
        {showReports && (
          <div className="mt-3">
            <ProjectReports projectId={id} inbox subjects={project.subjects} onChanged={load} />
          </div>
        )}
      </div>

      {/* Auto-discovered proposals: always rendered so the review entry point stays discoverable — the
          panel used to disappear when nothing was pending, hiding the whole accept/reject flow */}
      <div className="p-4 rounded-xl border mb-4"
        style={{ background: 'var(--color-bg-secondary)', borderColor: proposals.length > 0 ? 'rgba(234, 179, 8, 0.4)' : 'var(--color-border)' }}>
        <div className="flex items-center gap-2 mb-1">
          <span className="text-sm font-medium">Proposed subjects</span>
          <span className="text-[10px] px-1.5 py-0.5 rounded"
            style={proposals.length > 0
              ? { background: 'rgba(234, 179, 8, 0.15)', color: '#b45309' }
              : { background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>{proposals.length}</span>
          <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            discovered by the attributor — they are not analyzed or reported until you accept them
          </span>
        </div>
        {proposals.length === 0 ? (
          <div className="text-xs mt-2" style={{ color: 'var(--color-text-secondary)' }}>
            {autoSubjectsMode === 'off'
              ? 'Auto discovery is off for this project: set "Auto subjects" to Propose (or Create) in Edit to have the attributor propose subjects it finds.'
              : 'Nothing waiting for review. The attributor scans new material every 10 minutes and proposes a subject as soon as the material clearly concerns an entity that is not tracked yet.'}
          </div>
        ) : (
          <div className="grid gap-2 mt-3">
            {proposals.map(s => (
              <div key={s.id} className="p-3 rounded-lg border flex flex-wrap items-center gap-2"
                style={{ borderColor: 'var(--color-border)' }}>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-sm">{s.name}</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded"
                      style={{ background: 'rgba(234, 179, 8, 0.15)', color: '#b45309' }}>proposed</span>
                  </div>
                  {s.proposal_reason && (
                    <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>{s.proposal_reason}</div>
                  )}
                  <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
                    {s.attributed_count ?? 0} attributed item{s.attributed_count === 1 ? '' : 's'}
                    {s.proposed_at && ` · ${new Date(s.proposed_at).toLocaleString()}`}
                  </div>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <button onClick={() => reviewProposal('accept', s)} disabled={reviewBusy === s.id}
                    className="px-2.5 py-1.5 rounded-lg text-xs font-medium text-white cursor-pointer disabled:opacity-50"
                    style={{ background: 'var(--color-primary)' }}>
                    Accept
                  </button>
                  <select value={mergePicks[s.id] || ''} onChange={e => setMergePicks({ ...mergePicks, [s.id]: e.target.value })}
                    className="px-2 py-1.5 rounded-lg border text-xs" style={inputStyle}>
                    <option value="">Merge into...</option>
                    {mergeCandidates.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
                  <button onClick={() => reviewProposal('merge', s)} disabled={!mergePicks[s.id] || reviewBusy === s.id}
                    className="px-2.5 py-1.5 rounded-lg text-xs border cursor-pointer disabled:opacity-50"
                    style={{ borderColor: 'var(--color-border)' }}>
                    Merge
                  </button>
                  <button onClick={() => reviewProposal('reject', s)} disabled={reviewBusy === s.id}
                    className="px-2.5 py-1.5 rounded-lg text-xs border cursor-pointer disabled:opacity-50"
                    style={{ borderColor: 'var(--color-border)', color: 'var(--color-danger)' }}>
                    Reject
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Subjects at the bottom: the growing list, never pushed down by the sections above */}
      <div>
        <div className="flex items-center justify-between mb-4">
          <div className="text-sm font-medium">Subjects</div>
          <div className="relative max-w-sm">
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2"
              style={{ color: 'var(--color-text-secondary)' }} />
            <input
              type="text"
              placeholder="Search subjects..."
              value={subjectQuery}
              onChange={e => { setSubjectQuery(e.target.value); setSubjectOffset(0); }}
              className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm"
              style={inputStyle} />
          </div>
        </div>

        <div className="grid gap-4">
          {subjectList.length === 0 ? (
            <div className="text-center py-12 rounded-xl border"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
              {subjectQuery ? 'No subjects match your search.' : 'No subjects yet. State, executions and reports belong to subjects — create one to start tracking.'}
            </div>
          ) : subjectList.map(s => {
            const status = project.subject_statuses.find(st => st.subject_id === s.id);
            const kpis = project.kpis.filter(k => k.subject_id === s.id);
            const latest = kpis[kpis.length - 1];
            return (
              <div key={s.id}
                onClick={() => navigate(`/projects/${id}/subjects/${s.id}`)}
                className="rounded-xl border p-4 cursor-pointer transition-colors"
                style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
                onMouseEnter={e => { e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
                onMouseLeave={e => { e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <CircleDot size={18} style={{ color: 'var(--color-primary)' }} />
                    <span className="font-medium">{s.name}</span>
                    {s.status === 'proposed' && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded"
                        style={{ background: 'rgba(234, 179, 8, 0.15)', color: '#b45309' }}>proposed</span>
                    )}
                    {s.source === 'auto' && s.status !== 'proposed' && (
                      <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>auto</span>
                    )}
                    {status?.summary && (
                      <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{status.summary}</span>
                    )}
                    {latest && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        {latest.key}: {latest.value}
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-2">
                    <button onClick={e => { e.stopPropagation(); deleteSubject(s); }}
                      className="px-2 py-1 rounded text-xs border cursor-pointer"
                      style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                      <Trash2 size={12} />
                    </button>
                    <span className="text-xs" style={{ color: 'var(--color-text-secondary)', minWidth: 80, textAlign: 'right' }}>
                      {s.created_at ? new Date(s.created_at).toLocaleDateString() : '-'}
                    </span>
                  </div>
                </div>
                {s.description && (
                  <p className="text-sm mt-1 ml-7" style={{ color: 'var(--color-text-secondary)' }}>{s.description}</p>
                )}
                {reportStats && (
                  <p className="text-xs mt-1 ml-7" style={{ color: 'var(--color-text-secondary)' }}>
                    {reportStats.bySubject[s.id]?.count
                      ? <>
                          <button onClick={e => { e.stopPropagation(); navigate(`/projects/${id}/subjects/${s.id}?tab=artifacts`); }}
                            className="underline cursor-pointer" style={{ color: 'var(--color-primary)' }}>
                            {reportStats.bySubject[s.id].count} report{reportStats.bySubject[s.id].count === 1 ? '' : 's'}
                          </button>
                          {reportStats.bySubject[s.id].latest && ` · latest ${new Date(reportStats.bySubject[s.id].latest!).toLocaleDateString()}`}
                        </>
                      : 'No reports yet'}
                  </p>
                )}
                {s.external_link && (
                  <p className="text-xs mt-1 ml-7 truncate" style={{ color: 'var(--color-text-tertiary)' }}>{s.external_link}</p>
                )}
              </div>
            );
          })}
        </div>

        {subjectTotal > 0 && (
          <div className="flex items-center justify-between mt-4">
            <div className="flex items-center gap-3">
              <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                Showing {subjectOffset + 1}-{Math.min(subjectOffset + subjectLimit, subjectTotal)} of {subjectTotal}
              </span>
              <select value={subjectLimit}
                onChange={e => { setSubjectLimit(Number(e.target.value)); setSubjectOffset(0); }}
                className="px-2 py-1 rounded-lg border text-xs"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                {[10, 20, 50].map(n => <option key={n} value={n}>{n} / page</option>)}
              </select>
            </div>
            <div className="flex gap-2">
              <button onClick={() => setSubjectOffset(Math.max(0, subjectOffset - subjectLimit))} disabled={subjectOffset === 0}
                className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
                <ChevronLeft size={14} /> Prev
              </button>
              <button onClick={() => setSubjectOffset(subjectOffset + subjectLimit)} disabled={subjectOffset + subjectLimit >= subjectTotal}
                className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
                Next <ChevronRight size={14} />
              </button>
            </div>
          </div>
        )}
      </div>

      {subjectModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.4)' }}>
          <div className="w-full max-w-md p-4 rounded-xl border" style={{ background: 'var(--color-bg-primary)', borderColor: 'var(--color-border)' }}>
            <div className="font-medium mb-3">New Subject</div>
            <div className="grid gap-3">
              <input type="text" placeholder="Subject name (e.g. merchant / site / product)" value={subjectName}
                onChange={e => setSubjectName(e.target.value)} className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
              <input type="text" placeholder="Description (optional)" value={subjectDesc}
                onChange={e => setSubjectDesc(e.target.value)} className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
              <input type="text" placeholder="External link (optional)" value={subjectLink}
                onChange={e => setSubjectLink(e.target.value)} className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
              <div className="flex justify-end gap-2">
                <button onClick={() => setSubjectModal(false)} className="px-3 py-1.5 rounded-lg text-sm border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}>Cancel</button>
                <button onClick={createSubject} disabled={!subjectName.trim()}
                  className="px-3 py-1.5 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                  style={{ background: 'var(--color-primary)' }}>Create</button>
              </div>
            </div>
          </div>
        </div>
      )}

      {editModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.4)' }}>
          <div className="w-full max-w-md p-4 rounded-xl border" style={{ background: 'var(--color-bg-primary)', borderColor: 'var(--color-border)' }}>
            <div className="font-medium mb-3">Edit Project</div>
            <div className="grid gap-3">
              <input type="text" placeholder="Project name" value={editName}
                onChange={e => setEditName(e.target.value)} className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
              <input type="text" placeholder="Description (optional)" value={editDesc}
                onChange={e => setEditDesc(e.target.value)} className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
              <input type="text" placeholder="Goal (optional)" value={editGoal}
                onChange={e => setEditGoal(e.target.value)} className="px-3 py-2 rounded-lg border text-sm" style={inputStyle} />
              <div>
                <div className="text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Auto subjects</div>
                <select value={editAutoSubjects} onChange={e => setEditAutoSubjects(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg border text-sm" style={inputStyle}>
                  <option value="off">Off — never propose new subjects</option>
                  <option value="propose">Propose — create them as pending proposals (default)</option>
                  <option value="create">Create — start tracking them immediately</option>
                </select>
                <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  The attributor can discover new subjects from the material. Proposals wait for your review; created ones are analyzed right away.
                </div>
                {editAutoSubjects !== 'off' && !project.playbook && (
                  <div className="text-xs mt-1" style={{ color: '#b45309' }}>
                    No playbook yet — without it the attributor has no definition of what a subject is, so proposals will be rough.
                  </div>
                )}
              </div>
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
