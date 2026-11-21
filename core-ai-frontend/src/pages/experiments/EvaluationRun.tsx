import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ChevronLeft, GitCompare, Square, Star } from 'lucide-react';
import {
  evaluationApi,
  type Comparison,
  type EvalRun,
  type Evaluation,
  type RunReport,
  type Trial,
  type TrialDetail,
} from '../../api/evaluation';

const TERMINAL = new Set(['COMPLETED', 'COMPLETED_WITH_ERRORS', 'BUDGET_EXHAUSTED', 'CANCELLED', 'FAILED']);

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString() : '-';
}

function percent(value?: number) {
  return value === null || value === undefined ? '-' : `${(value * 100).toFixed(1)}%`;
}

function statusColor(status?: string) {
  if (!status) return 'var(--color-text-secondary)';
  if (status === 'SUCCESS' || status === 'COMPLETED') return '#22c55e';
  if (status === 'FAILURE' || status === 'FAILED' || status === 'CANCELLED') return '#ef4444';
  if (status === 'TIMEOUT' || status === 'AGENT_FAILED' || status === 'COMPLETED_WITH_ERRORS') return '#f97316';
  if (status === 'RUNNING' || status === 'CANCELLING' || status === 'PENDING') return '#eab308';
  return 'var(--color-text-secondary)';
}

export default function EvaluationRun() {
  const { id, runId } = useParams<{ id: string; runId: string }>();
  const navigate = useNavigate();
  const evaluationId = id || '';
  const currentRunId = runId || '';

  const [evaluation, setEvaluation] = useState<Evaluation | null>(null);
  const [run, setRun] = useState<EvalRun | null>(null);
  const [report, setReport] = useState<RunReport | null>(null);
  const [trials, setTrials] = useState<Trial[]>([]);
  const [trialTotal, setTrialTotal] = useState(0);
  const [trialFilter, setTrialFilter] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<TrialDetail | null>(null);

  const [candidateRuns, setCandidateRuns] = useState<EvalRun[]>([]);
  const [compareRunId, setCompareRunId] = useState('');
  const [comparison, setComparison] = useState<Comparison | null>(null);

  const load = useCallback(async (quiet = false) => {
    if (!evaluationId || !currentRunId) return;
    if (!quiet) setLoading(true);
    try {
      const [evaluationResponse, runResponse, reportResponse, trialsResponse] = await Promise.all([
        evaluationApi.get(evaluationId),
        evaluationApi.getRun(evaluationId, currentRunId),
        evaluationApi.report(evaluationId, currentRunId).catch(() => null),
        evaluationApi.listTrials(evaluationId, currentRunId, { task_result: trialFilter || undefined, limit: 200 }),
      ]);
      setEvaluation(evaluationResponse);
      setRun(runResponse);
      setReport(reportResponse);
      setTrials(trialsResponse.trials);
      setTrialTotal(trialsResponse.total);
      if (!compareRunId) {
        const runs = await evaluationApi.listRuns(evaluationId, 0, 50);
        setCandidateRuns(runs.runs.filter(item => item.id !== currentRunId));
      }
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (!quiet) setLoading(false);
    }
  }, [evaluationId, currentRunId, trialFilter, compareRunId]);

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [evaluationId, currentRunId, trialFilter]);

  useEffect(() => {
    if (!run || TERMINAL.has(run.status)) return;
    const timer = setInterval(() => load(true), 3000);
    return () => clearInterval(timer);
  }, [run, load]);

  const openTrial = async (trial: Trial) => {
    try {
      setDetail(await evaluationApi.getTrial(evaluationId, currentRunId, trial.id));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const cancel = async () => {
    if (!confirm('Cancel this run? Already dispatched trials may still be terminating.')) return;
    try {
      await evaluationApi.cancel(evaluationId, currentRunId);
      await load(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const setBaseline = async () => {
    if (!evaluation || !run) return;
    if (!confirm('Select this run as the evaluation baseline? This changes the comparison reference.')) return;
    try {
      await evaluationApi.setBaseline(evaluationId, {
        run_id: currentRunId,
        reason: 'selected from run report',
        acknowledge_unpinned: true,
        expected_generation: evaluation.baseline_generation ?? 0,
      });
      await load(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const compare = async () => {
    if (!compareRunId) return;
    try {
      setComparison(await evaluationApi.compare(evaluationId, {
        baseline_run_id: compareRunId,
        candidate_run_id: currentRunId,
      }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const terminal = run ? TERMINAL.has(run.status) : false;
  const isBaseline = evaluation?.baseline_run_id === currentRunId;
  const cases = useMemo(() => report?.cases?.slice(0, 200) ?? [], [report]);

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  if (!run) return <div className="p-6" style={{ color: '#ef4444' }}>{error || 'Run not found'}</div>;

  return (
    <div className="p-6">
      <button onClick={() => navigate(`/experiments/evaluations/${evaluationId}`)}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-text-secondary)' }}>
        <ChevronLeft size={14} /> Evaluation
      </button>

      <div className="flex items-start justify-between mb-5">
        <div>
          <h1 className="text-2xl font-semibold flex items-center gap-3">
            Run {run.id.slice(0, 8)}
            <span className="text-sm font-medium px-2 py-0.5 rounded" style={{ color: statusColor(run.status), background: 'var(--color-bg-secondary)' }}>
              {run.status}
            </span>
            {isBaseline && <span className="text-sm px-2 py-0.5 rounded" style={{ color: '#22c55e', background: 'rgba(34,197,94,0.1)' }}>baseline</span>}
          </h1>
          <div className="flex items-center gap-3 mt-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            <span>purpose {run.purpose}</span>
            <span>spec {run.spec_id.slice(0, 10)}</span>
            <span>snapshot {run.snapshot_id.slice(0, 10)}</span>
            <span>started {formatTime(run.started_at)}</span>
            <span>deadline {formatTime(run.deadline_at)}</span>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {!terminal && (
            <button onClick={cancel}
              className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm border cursor-pointer"
              style={{ borderColor: 'var(--color-border)', color: '#ef4444' }}>
              <Square size={14} /> Cancel
            </button>
          )}
          <button onClick={setBaseline} disabled={isBaseline}
            className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
            style={{ background: 'var(--color-primary)' }}>
            <Star size={14} /> {isBaseline ? 'Baseline' : 'Set baseline'}
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm"
          style={{ borderColor: 'rgba(239,68,68,0.4)', color: '#ef4444', background: 'rgba(239,68,68,0.06)' }}>
          {error}
        </div>
      )}

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-5">
        <Stat label="Planned trials" value={String(report?.planned_trials ?? run.planned_trials ?? '-')} />
        <Stat label="Success / Failure / Unmeasured" value={report ? `${report.success} / ${report.failure} / ${report.unmeasured}` : '-'} />
        <Stat label="Measurement coverage" value={percent(report?.measurement_coverage)} />
        <Stat label="Success floor → ceiling" value={`${percent(report?.success_lower_bound)} → ${percent(report?.success_upper_bound)}`} />
        <Stat label="Measured success (diagnostic)" value={percent(report?.measured_success_rate)} />
        <Stat label="p50 / p95 latency" value={report ? `${report.p50_duration_ms ?? '-'} / ${report.p95_duration_ms ?? '-'} ms` : '-'} />
        <Stat label="Tokens in / out" value={report ? `${report.input_tokens ?? '-'} / ${report.output_tokens ?? '-'}` : '-'} />
        <Stat label="Cost" value={report?.cost_known ? 'known' : 'unknown'} />
      </div>

      {report?.notes && report.notes.length > 0 && (
        <div className="rounded-xl border p-3 mb-5 text-sm"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          {report.notes.map(note => <div key={note}>· {note}</div>)}
        </div>
      )}

      {report?.graders && report.graders.length > 0 && (
        <div className="rounded-xl border overflow-hidden mb-5" style={{ borderColor: 'var(--color-border)' }}>
          <div className="px-4 py-2 text-sm font-medium" style={{ background: 'var(--color-bg-secondary)' }}>Graders</div>
          <table className="w-full text-sm">
            <thead style={{ color: 'var(--color-text-secondary)' }}>
              <tr>
                <th className="text-left px-4 py-2">Grader</th>
                <th className="text-left px-4 py-2">Required</th>
                <th className="text-right px-4 py-2">Applicable</th>
                <th className="text-right px-4 py-2">Pass</th>
                <th className="text-right px-4 py-2">Fail</th>
                <th className="text-right px-4 py-2">Indeterminate</th>
                <th className="text-right px-4 py-2">Error</th>
                <th className="text-right px-4 py-2">Not run</th>
                <th className="text-right px-4 py-2">Pass rate</th>
              </tr>
            </thead>
            <tbody>
              {report.graders.map(grader => (
                <tr key={grader.grader_id} style={{ borderTop: '1px solid var(--color-border)' }}>
                  <td className="px-4 py-2 font-mono text-xs">{grader.grader_id}</td>
                  <td className="px-4 py-2">{grader.required ? 'yes' : 'no'}</td>
                  <td className="px-4 py-2 text-right">{grader.applicable}</td>
                  <td className="px-4 py-2 text-right">{grader.pass}</td>
                  <td className="px-4 py-2 text-right">{grader.fail}</td>
                  <td className="px-4 py-2 text-right">{grader.indeterminate}</td>
                  <td className="px-4 py-2 text-right">{grader.error}</td>
                  <td className="px-4 py-2 text-right">{grader.not_run_target_failed ?? 0}</td>
                  <td className="px-4 py-2 text-right">{percent(grader.pass_rate)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="rounded-xl border p-4 mb-5" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <div className="flex items-center gap-2 mb-2">
          <GitCompare size={16} />
          <span className="font-medium text-sm">Compare with another run</span>
        </div>
        <div className="flex items-center gap-2">
          <select value={compareRunId} onChange={e => setCompareRunId(e.target.value)}
            className="px-3 py-2 rounded-lg border text-sm cursor-pointer min-w-[260px]"
            style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
            <option value="">Select baseline run...</option>
            {candidateRuns.map(item => (
              <option key={item.id} value={item.id}>
                {item.id.slice(0, 8)} · {item.status} · {item.succeeded_trials}/{item.planned_trials}
              </option>
            ))}
          </select>
          <button onClick={compare} disabled={!compareRunId}
            className="px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
            style={{ background: 'var(--color-primary)' }}>
            Compare
          </button>
        </div>
        {comparison && (
          <div className="mt-3 text-sm">
            <div className="flex items-center gap-3">
              <span className="px-2 py-0.5 rounded text-xs font-medium"
                style={{
                  background: comparison.grade === 'STRICT' ? 'rgba(34,197,94,0.1)' : 'rgba(234,179,8,0.1)',
                  color: comparison.grade === 'STRICT' ? '#22c55e' : '#eab308',
                }}>
                {comparison.grade}
              </span>
              {comparison.mean_delta !== undefined && (
                <span>mean Δ {percent(comparison.mean_delta)} · 95% CI [{percent(comparison.ci_low)}, {percent(comparison.ci_high)}]</span>
              )}
              <span>wins {comparison.candidate_wins ?? '-'} · ties {comparison.ties ?? '-'} · losses {comparison.baseline_wins ?? '-'}</span>
              <span>regressions {comparison.regressions ?? '-'}</span>
            </div>
            {comparison.differences?.length > 0 && (
              <div className="mt-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {comparison.differences.map(item => <div key={item}>· {item}</div>)}
              </div>
            )}
          </div>
        )}
      </div>

      <div className="flex items-center justify-between mb-2">
        <h2 className="font-medium">Cases</h2>
        <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{cases.length} shown</span>
      </div>
      <div className="rounded-xl border overflow-hidden mb-5" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
            <tr>
              <th className="text-left px-4 py-2">Case</th>
              <th className="text-left px-4 py-2">Split</th>
              <th className="text-right px-4 py-2">Success fraction</th>
              <th className="text-right px-4 py-2">Any / All</th>
              <th className="text-right px-4 py-2">S / F / U</th>
            </tr>
          </thead>
          <tbody>
            {cases.map(item => (
              <tr key={item.case_id} style={{ borderTop: '1px solid var(--color-border)' }}>
                <td className="px-4 py-2 font-mono text-xs">{item.case_id}</td>
                <td className="px-4 py-2">{item.split}</td>
                <td className="px-4 py-2 text-right">{percent(item.success_fraction)}</td>
                <td className="px-4 py-2 text-right">
                  {item.any_success === null || item.any_success === undefined ? '?' : item.any_success ? 'yes' : 'no'}
                  {' / '}
                  {item.all_success === null || item.all_success === undefined ? '?' : item.all_success ? 'yes' : 'no'}
                </td>
                <td className="px-4 py-2 text-right">{item.success} / {item.failure} / {item.unmeasured}</td>
              </tr>
            ))}
            {cases.length === 0 && (
              <tr><td colSpan={5} className="px-4 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>No case results yet.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between mb-2">
        <h2 className="font-medium">Trials</h2>
        <select value={trialFilter} onChange={e => setTrialFilter(e.target.value)}
          className="px-2 py-1 rounded-lg border text-xs cursor-pointer"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
          <option value="">All results</option>
          <option value="SUCCESS">SUCCESS</option>
          <option value="FAILURE">FAILURE</option>
          <option value="UNMEASURED">UNMEASURED</option>
        </select>
      </div>
      <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
            <tr>
              <th className="text-left px-4 py-2">Case</th>
              <th className="text-right px-4 py-2">Rep</th>
              <th className="text-left px-4 py-2">Execution</th>
              <th className="text-left px-4 py-2">Task</th>
              <th className="text-left px-4 py-2">Error</th>
              <th className="text-right px-4 py-2">Duration</th>
              <th className="text-right px-4 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {trials.map(trial => {
              const duration = trial.started_at && trial.completed_at
                ? Math.max(0, new Date(trial.completed_at).getTime() - new Date(trial.started_at).getTime())
                : null;
              return (
                <tr key={trial.id} style={{ borderTop: '1px solid var(--color-border)' }}>
                  <td className="px-4 py-2 font-mono text-xs">{trial.case_id}</td>
                  <td className="px-4 py-2 text-right">{trial.repetition_index}</td>
                  <td className="px-4 py-2" style={{ color: statusColor(trial.execution_result) }}>{trial.execution_result || trial.status}</td>
                  <td className="px-4 py-2" style={{ color: statusColor(trial.task_result) }}>{trial.task_result || '-'}</td>
                  <td className="px-4 py-2 text-xs truncate max-w-[260px]" style={{ color: 'var(--color-text-secondary)' }}>{trial.error || '-'}</td>
                  <td className="px-4 py-2 text-right">{duration === null ? '-' : `${duration} ms`}</td>
                  <td className="px-4 py-2 text-right">
                    <button onClick={() => openTrial(trial)} className="text-xs cursor-pointer" style={{ color: 'var(--color-primary)' }}>View</button>
                  </td>
                </tr>
              );
            })}
            {trials.length === 0 && (
              <tr><td colSpan={7} className="px-4 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>No trials.</td></tr>
            )}
          </tbody>
        </table>
        <div className="px-4 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>{trialTotal} trials total</div>
      </div>

      {detail && (
        <div className="fixed inset-0 z-50 flex items-center justify-center"
          style={{ background: 'rgba(0,0,0,0.5)' }} onClick={() => setDetail(null)}>
          <div className="rounded-2xl shadow-2xl w-[900px] max-h-[85vh] overflow-auto p-5"
            style={{ background: 'var(--color-bg)' }} onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-lg font-semibold font-mono">{detail.case_id} · rep {detail.repetition_index}</h2>
              <button onClick={() => setDetail(null)} className="text-sm cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>Close</button>
            </div>
            <div className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>
              {detail.execution_result} · {detail.task_result} · agent run {detail.agent_run_id || '-'}
            </div>
            {detail.error && (
              <pre className="text-xs p-3 rounded mb-3 whitespace-pre-wrap" style={{ background: 'rgba(239,68,68,0.06)', color: '#ef4444' }}>{detail.error}</pre>
            )}
            <Section title="Case input" value={detail.case_input_json} />
            <Section title="Reference" value={detail.case_reference_json} />
            <Section title="Actual output" value={detail.actual_output} />
            <Section title="Grading" value={detail.grading_json} />
            <Section title="Usage" value={detail.usage_json} />
            <Section title="Trace" value={detail.trace_json} />
            <Section title="Artifacts" value={detail.artifacts_json} />
          </div>
        </div>
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border p-3" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{label}</div>
      <div className="text-sm font-medium mt-1">{value}</div>
    </div>
  );
}

function Section({ title, value }: { title: string; value?: string }) {
  if (!value) return null;
  return (
    <div className="mb-3">
      <div className="text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>{title}</div>
      <pre className="text-xs p-3 rounded overflow-auto whitespace-pre-wrap" style={{ background: 'var(--color-bg-secondary)' }}>{value}</pre>
    </div>
  );
}
