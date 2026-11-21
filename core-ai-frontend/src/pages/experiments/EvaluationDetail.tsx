import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ChevronLeft, Database, FlaskConical, Play, Save, ScrollText, ShieldCheck, Upload } from 'lucide-react';
import {
  evaluationApi,
  type DatasetRevision,
  type EvalCase,
  type EvalRun,
  type EvalSpec,
  type Evaluation,
  type GraderSuite,
} from '../../api/evaluation';

const DEFAULT_SUITE = JSON.stringify({
  graders: [
    { id: 'answer-exact', kind: 'EXACT', required: true, mode: 'BOOLEAN', normalization: 'TRIM' },
  ],
  success_policy: 'ALL_APPLICABLE_REQUIRED_PASS',
}, null, 2);

type Tab = 'data' | 'graders' | 'runs';

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString() : '-';
}

function statusColor(status?: string) {
  if (!status) return 'var(--color-text-secondary)';
  if (status === 'COMPLETED' || status === 'SUCCESS') return '#22c55e';
  if (status === 'RUNNING' || status === 'CANCELLING') return '#eab308';
  if (status === 'COMPLETED_WITH_ERRORS' || status === 'BUDGET_EXHAUSTED') return '#f97316';
  if (status === 'FAILED' || status === 'CANCELLED') return '#ef4444';
  return 'var(--color-text-secondary)';
}

function newRequestKey() {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `eval-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export default function EvaluationDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const evaluationId = id || '';

  const [tab, setTab] = useState<Tab>('data');
  const [evaluation, setEvaluation] = useState<Evaluation | null>(null);
  const [datasets, setDatasets] = useState<DatasetRevision[]>([]);
  const [suites, setSuites] = useState<GraderSuite[]>([]);
  const [specs, setSpecs] = useState<EvalSpec[]>([]);
  const [runs, setRuns] = useState<EvalRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [suiteJson, setSuiteJson] = useState(DEFAULT_SUITE);
  const [suiteName, setSuiteName] = useState('');
  const [testReceipt, setTestReceipt] = useState<string | null>(null);

  const [datasetId, setDatasetId] = useState('');
  const [suiteId, setSuiteId] = useState('');
  const [split, setSplit] = useState('DEV');
  const [repetitions, setRepetitions] = useState(3);
  const [concurrency, setConcurrency] = useState(2);
  const [trialDeadline, setTrialDeadline] = useState(180);
  const [maxTurns, setMaxTurns] = useState(30);
  const [source, setSource] = useState('PUBLISHED');
  const [purpose, setPurpose] = useState('FORMAL');

  const [cases, setCases] = useState<EvalCase[]>([]);
  const [caseTotal, setCaseTotal] = useState(0);
  const [caseDataset, setCaseDataset] = useState<DatasetRevision | null>(null);
  const [caseSplit, setCaseSplit] = useState('');

  const load = useCallback(async () => {
    if (!evaluationId) return;
    setLoading(true);
    setError(null);
    try {
      const [evaluationResponse, datasetsResponse, suitesResponse, specsResponse, runsResponse] = await Promise.all([
        evaluationApi.get(evaluationId),
        evaluationApi.listDatasets(evaluationId),
        evaluationApi.listSuites(evaluationId),
        evaluationApi.listSpecs(evaluationId),
        evaluationApi.listRuns(evaluationId, 0, 50),
      ]);
      setEvaluation(evaluationResponse);
      setDatasets(datasetsResponse.datasets);
      setSuites(suitesResponse.suites);
      setSpecs(specsResponse.specs);
      setRuns(runsResponse.runs);
      if (!datasetId && datasetsResponse.datasets.length > 0) setDatasetId(datasetsResponse.datasets[0].id);
      if (!suiteId && suitesResponse.suites.length > 0) setSuiteId(suitesResponse.suites[0].id);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [evaluationId, datasetId, suiteId]);

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [evaluationId]);

  const latestSpec = useMemo(() => specs[0], [specs]);

  const uploadDataset = async (file: File | null) => {
    if (!file) return;
    setError(null);
    try {
      const uploaded = await evaluationApi.uploadFile(file);
      await evaluationApi.importDataset(evaluationId, { file_id: uploaded.id, name: file.name });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const testSuite = async () => {
    setError(null);
    try {
      const response = await evaluationApi.testSuite(evaluationId, suiteJson);
      setTestReceipt(response.receipt_json);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setTestReceipt(null);
    }
  };

  const saveSuite = async () => {
    setError(null);
    try {
      await evaluationApi.createSuite(evaluationId, { name: suiteName || undefined, body_json: suiteJson });
      setSuiteName('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const createSpec = async () => {
    if (!datasetId || !suiteId) return;
    setError(null);
    try {
      const created = await evaluationApi.createSpec(evaluationId, {
        dataset_id: datasetId,
        grader_suite_id: suiteId,
        profile: 'OFFLINE_TEXT',
        repetitions,
        agent_concurrency: concurrency,
        trial_deadline_seconds: trialDeadline,
        trial_max_turns: maxTurns,
        split,
      });
      await load();
      setTab('runs');
      return created;
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return null;
    }
  };

  const startRun = async (spec: EvalSpec) => {
    setError(null);
    try {
      const run = await evaluationApi.startRun(evaluationId, {
        spec_id: spec.id,
        source,
        purpose,
        request_key: newRequestKey(),
      });
      navigate(`/experiments/evaluations/${evaluationId}/runs/${run.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const openCases = async (dataset: DatasetRevision, caseSplitFilter = '') => {
    setCaseDataset(dataset);
    setCaseSplit(caseSplitFilter);
    try {
      const response = await evaluationApi.listCases(evaluationId, dataset.id, caseSplitFilter || undefined, 0, 50);
      setCases(response.cases);
      setCaseTotal(response.total);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  if (loading) {
    return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  }
  if (!evaluation) {
    return <div className="p-6" style={{ color: '#ef4444' }}>{error || 'Evaluation not found'}</div>;
  }

  return (
    <div className="p-6">
      <button onClick={() => navigate('/experiments/evaluations')}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-text-secondary)' }}>
        <ChevronLeft size={14} /> Evaluations
      </button>

      <div className="flex items-start justify-between mb-5">
        <div>
          <h1 className="text-2xl font-semibold">{evaluation.name}</h1>
          <div className="flex items-center gap-3 mt-1 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            <span className="font-mono">{evaluation.agent_id}</span>
            <span>Updated {formatTime(evaluation.updated_at)}</span>
            {evaluation.baseline_run_id
              ? <span style={{ color: '#22c55e' }}>Baseline: {evaluation.baseline_run_id.slice(-12)}</span>
              : <span>No baseline</span>}
          </div>
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm"
          style={{ borderColor: 'rgba(239,68,68,0.4)', color: '#ef4444', background: 'rgba(239,68,68,0.06)' }}>
          {error}
        </div>
      )}

      <div className="flex gap-1 p-1 rounded-lg mb-5 w-fit" style={{ background: 'var(--color-bg-secondary)' }}>
        {([['data', 'Dataset', Database], ['graders', 'Graders', ShieldCheck], ['runs', 'Specs & Runs', FlaskConical]] as const).map(([key, label, Icon]) => (
          <button key={key} onClick={() => setTab(key)}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium cursor-pointer"
            style={{
              background: tab === key ? 'var(--color-bg-tertiary)' : 'transparent',
              color: tab === key ? 'var(--color-text)' : 'var(--color-text-secondary)',
            }}>
            <Icon size={14} /> {label}
          </button>
        ))}
      </div>

      {tab === 'data' && (
        <div className="grid gap-4">
          <div className="rounded-xl border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center justify-between">
              <div>
                <h2 className="font-medium">Import dataset</h2>
                <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Upload a v1 JSONL file. Invalid lines fail the whole import; nothing is silently dropped.
                </p>
              </div>
              <label className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                style={{ background: 'var(--color-primary)' }}>
                <Upload size={16} /> Upload JSONL
                <input type="file" accept=".jsonl,.json,application/jsonl,application/json" className="hidden"
                  onChange={e => uploadDataset(e.target.files?.[0] || null)} />
              </label>
            </div>
          </div>

          <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
            <table className="w-full text-sm">
              <thead style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
                <tr>
                  <th className="text-left px-4 py-2">Revision</th>
                  <th className="text-left px-4 py-2">Status</th>
                  <th className="text-right px-4 py-2">Cases</th>
                  <th className="text-left px-4 py-2">Hash</th>
                  <th className="text-left px-4 py-2">Created</th>
                  <th className="text-right px-4 py-2"></th>
                </tr>
              </thead>
              <tbody>
                {datasets.map(dataset => (
                  <tr key={dataset.id} style={{ borderTop: '1px solid var(--color-border)' }}>
                    <td className="px-4 py-2">v{dataset.revision}</td>
                    <td className="px-4 py-2" style={{ color: statusColor(dataset.status) }}>{dataset.status}</td>
                    <td className="px-4 py-2 text-right">{dataset.case_count}</td>
                    <td className="px-4 py-2 font-mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      {dataset.content_hash.slice(0, 12)}
                    </td>
                    <td className="px-4 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(dataset.created_at)}</td>
                    <td className="px-4 py-2 text-right">
                      <button onClick={() => openCases(dataset)} className="text-xs cursor-pointer" style={{ color: 'var(--color-primary)' }}>
                        View cases
                      </button>
                    </td>
                  </tr>
                ))}
                {datasets.length === 0 && (
                  <tr><td colSpan={6} className="px-4 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>No dataset revisions.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === 'graders' && (
        <div className="grid gap-4">
          <div className="rounded-xl border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center justify-between mb-3">
              <div>
                <h2 className="font-medium">Grader suite</h2>
                <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Built-in graders only in this phase: EXACT, CONTAINS, REGEX, JSON_ASSERTION. Save creates an immutable revision.
                </p>
              </div>
              <input value={suiteName} onChange={e => setSuiteName(e.target.value)} placeholder="suite name (optional)"
                className="px-3 py-2 rounded-lg border text-sm outline-none"
                style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
            </div>
            <textarea value={suiteJson} onChange={e => setSuiteJson(e.target.value)} rows={12} spellCheck={false}
              className="w-full px-3 py-2 rounded-lg border text-xs font-mono outline-none resize-y"
              style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
            <div className="flex items-center gap-2 mt-3">
              <button onClick={testSuite}
                className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                <ScrollText size={14} /> Test
              </button>
              <button onClick={saveSuite}
                className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                style={{ background: 'var(--color-primary)' }}>
                <Save size={14} /> Save revision
              </button>
              {testReceipt && (
                <span className="text-xs font-mono truncate" style={{ color: 'var(--color-text-secondary)' }}>
                  {testReceipt}
                </span>
              )}
            </div>
          </div>

          <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
            <table className="w-full text-sm">
              <thead style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
                <tr>
                  <th className="text-left px-4 py-2">Revision</th>
                  <th className="text-left px-4 py-2">Name</th>
                  <th className="text-left px-4 py-2">Fingerprint</th>
                  <th className="text-left px-4 py-2">Created</th>
                </tr>
              </thead>
              <tbody>
                {suites.map(suite => (
                  <tr key={suite.id} style={{ borderTop: '1px solid var(--color-border)' }}>
                    <td className="px-4 py-2">v{suite.revision}</td>
                    <td className="px-4 py-2">{suite.name}</td>
                    <td className="px-4 py-2 font-mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>{suite.fingerprint.slice(0, 16)}</td>
                    <td className="px-4 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(suite.created_at)}</td>
                  </tr>
                ))}
                {suites.length === 0 && (
                  <tr><td colSpan={4} className="px-4 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>No grader suites.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === 'runs' && (
        <div className="grid gap-4">
          <div className="rounded-xl border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h2 className="font-medium mb-3">Freeze a measurement spec</h2>
            <div className="grid grid-cols-2 gap-3">
              <label className="text-sm">
                <span style={{ color: 'var(--color-text-secondary)' }}>Dataset</span>
                <select value={datasetId} onChange={e => setDatasetId(e.target.value)}
                  className="w-full mt-1 px-3 py-2 rounded-lg border text-sm cursor-pointer"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                  <option value="">Select...</option>
                  {datasets.map(dataset => <option key={dataset.id} value={dataset.id}>v{dataset.revision} · {dataset.case_count} cases</option>)}
                </select>
              </label>
              <label className="text-sm">
                <span style={{ color: 'var(--color-text-secondary)' }}>Grader suite</span>
                <select value={suiteId} onChange={e => setSuiteId(e.target.value)}
                  className="w-full mt-1 px-3 py-2 rounded-lg border text-sm cursor-pointer"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                  <option value="">Select...</option>
                  {suites.map(suite => <option key={suite.id} value={suite.id}>v{suite.revision} · {suite.name}</option>)}
                </select>
              </label>
              <label className="text-sm">
                <span style={{ color: 'var(--color-text-secondary)' }}>Split</span>
                <select value={split} onChange={e => setSplit(e.target.value)}
                  className="w-full mt-1 px-3 py-2 rounded-lg border text-sm cursor-pointer"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                  {['DEV', 'VALIDATION', 'HOLDOUT'].map(item => <option key={item}>{item}</option>)}
                </select>
              </label>
              <label className="text-sm">
                <span style={{ color: 'var(--color-text-secondary)' }}>Candidate source</span>
                <select value={source} onChange={e => setSource(e.target.value)}
                  className="w-full mt-1 px-3 py-2 rounded-lg border text-sm cursor-pointer"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                  <option value="PUBLISHED">PUBLISHED</option>
                  <option value="DRAFT">DRAFT</option>
                </select>
              </label>
              <label className="text-sm">
                <span style={{ color: 'var(--color-text-secondary)' }}>Run purpose</span>
                <select value={purpose} onChange={e => setPurpose(e.target.value)}
                  className="w-full mt-1 px-3 py-2 rounded-lg border text-sm cursor-pointer"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                  <option value="FORMAL">FORMAL</option>
                  <option value="SMOKE">SMOKE</option>
                  <option value="DIAGNOSTIC">DIAGNOSTIC</option>
                </select>
              </label>
              <label className="text-sm">
                <span style={{ color: 'var(--color-text-secondary)' }}>Repetitions</span>
                <input type="number" min={1} max={10} value={repetitions} onChange={e => setRepetitions(Number(e.target.value))}
                  className="w-full mt-1 px-3 py-2 rounded-lg border text-sm"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
              </label>
              <label className="text-sm">
                <span style={{ color: 'var(--color-text-secondary)' }}>Agent concurrency</span>
                <input type="number" min={1} max={10} value={concurrency} onChange={e => setConcurrency(Number(e.target.value))}
                  className="w-full mt-1 px-3 py-2 rounded-lg border text-sm"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
              </label>
              <label className="text-sm">
                <span style={{ color: 'var(--color-text-secondary)' }}>Trial deadline (s)</span>
                <input type="number" min={1} max={900} value={trialDeadline} onChange={e => setTrialDeadline(Number(e.target.value))}
                  className="w-full mt-1 px-3 py-2 rounded-lg border text-sm"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
              </label>
              <label className="text-sm">
                <span style={{ color: 'var(--color-text-secondary)' }}>Trial max turns</span>
                <input type="number" min={1} max={100} value={maxTurns} onChange={e => setMaxTurns(Number(e.target.value))}
                  className="w-full mt-1 px-3 py-2 rounded-lg border text-sm"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
              </label>
            </div>
            <div className="flex items-center gap-2 mt-4">
              <button onClick={createSpec} disabled={!datasetId || !suiteId}
                className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
                style={{ background: 'var(--color-primary)' }}>
                <Save size={14} /> Create spec revision
              </button>
              {latestSpec && (
                <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  Latest: v{latestSpec.revision} · {latestSpec.planned_trials ?? latestSpec.case_count * latestSpec.repetitions} planned trials
                </span>
              )}
            </div>
          </div>

          <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
            <table className="w-full text-sm">
              <thead style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
                <tr>
                  <th className="text-left px-4 py-2">Spec</th>
                  <th className="text-left px-4 py-2">Dataset</th>
                  <th className="text-left px-4 py-2">Suite</th>
                  <th className="text-right px-4 py-2">R × cases</th>
                  <th className="text-right px-4 py-2"></th>
                </tr>
              </thead>
              <tbody>
                {specs.map(spec => (
                  <tr key={spec.id} style={{ borderTop: '1px solid var(--color-border)' }}>
                    <td className="px-4 py-2">v{spec.revision} <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{spec.split}</span></td>
                    <td className="px-4 py-2 font-mono text-xs">{spec.dataset_revision_id.slice(0, 10)}</td>
                    <td className="px-4 py-2 font-mono text-xs">{spec.grader_suite_id.slice(0, 10)}</td>
                    <td className="px-4 py-2 text-right">{spec.repetitions} × {spec.case_count}</td>
                    <td className="px-4 py-2 text-right">
                      <button onClick={() => startRun(spec)}
                        className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-medium text-white cursor-pointer"
                        style={{ background: 'var(--color-primary)' }}>
                        <Play size={12} /> Start run
                      </button>
                    </td>
                  </tr>
                ))}
                {specs.length === 0 && (
                  <tr><td colSpan={5} className="px-4 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>No specs yet.</td></tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
            <table className="w-full text-sm">
              <thead style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
                <tr>
                  <th className="text-left px-4 py-2">Run</th>
                  <th className="text-left px-4 py-2">Purpose</th>
                  <th className="text-left px-4 py-2">Status</th>
                  <th className="text-right px-4 py-2">Planned</th>
                  <th className="text-right px-4 py-2">S / F / U</th>
                  <th className="text-left px-4 py-2">Started</th>
                </tr>
              </thead>
              <tbody>
                {runs.map(run => (
                  <tr key={run.id} onClick={() => navigate(`/experiments/evaluations/${evaluationId}/runs/${run.id}`)}
                    className="cursor-pointer" style={{ borderTop: '1px solid var(--color-border)' }}>
                    <td className="px-4 py-2 font-mono text-xs">
                      {run.id.slice(0, 8)}
                      {evaluation.baseline_run_id === run.id && <span className="ml-2" style={{ color: '#22c55e' }}>baseline</span>}
                    </td>
                    <td className="px-4 py-2">{run.purpose}</td>
                    <td className="px-4 py-2" style={{ color: statusColor(run.status) }}>{run.status}</td>
                    <td className="px-4 py-2 text-right">{run.planned_trials}</td>
                    <td className="px-4 py-2 text-right">{run.succeeded_trials} / {run.failed_trials} / {run.unmeasured_trials}</td>
                    <td className="px-4 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(run.started_at || run.created_at)}</td>
                  </tr>
                ))}
                {runs.length === 0 && (
                  <tr><td colSpan={6} className="px-4 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>No runs yet.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {caseDataset && (
        <div className="fixed inset-0 z-50 flex items-center justify-center"
          style={{ background: 'rgba(0,0,0,0.5)' }} onClick={() => setCaseDataset(null)}>
          <div className="rounded-2xl shadow-2xl w-[900px] max-h-[80vh] overflow-hidden flex flex-col"
            style={{ background: 'var(--color-bg)' }} onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between p-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
              <div>
                <h2 className="text-lg font-semibold">Dataset v{caseDataset.revision}</h2>
                <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  {caseTotal} cases{caseSplit ? ` · ${caseSplit}` : ''} · reference is only shown with oracle permission
                </p>
              </div>
              <select value={caseSplit} onChange={e => openCases(caseDataset, e.target.value)}
                className="px-2 py-1 rounded-lg border text-xs cursor-pointer"
                style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                <option value="">All splits</option>
                {['DEV', 'VALIDATION', 'HOLDOUT'].map(item => <option key={item}>{item}</option>)}
              </select>
            </div>
            <div className="overflow-auto p-4 grid gap-2">
              {cases.map(item => (
                <div key={item.case_id} className="rounded-lg border p-3"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
                  <div className="flex items-center gap-2 text-xs mb-2" style={{ color: 'var(--color-text-secondary)' }}>
                    <span className="font-mono">{item.case_id}</span>
                    <span>{item.split}</span>
                    <span>weight {item.weight}</span>
                    <span className="font-mono">{item.grader_refs_json}</span>
                  </div>
                  <div className="text-sm whitespace-pre-wrap">{item.input_text}</div>
                  {item.reference_json && (
                    <pre className="mt-2 text-xs p-2 rounded overflow-auto" style={{ background: 'var(--color-bg)' }}>{item.reference_json}</pre>
                  )}
                </div>
              ))}
              {cases.length === 0 && <div className="text-center py-8" style={{ color: 'var(--color-text-secondary)' }}>No cases.</div>}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
