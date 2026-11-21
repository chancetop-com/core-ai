import { getAuthHeaders } from './client';

const BASE = '';

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    headers: getAuthHeaders(),
    ...options,
  });
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      if (body && typeof body.message === 'string' && body.message) message = body.message;
    } catch {
      // keep the HTTP status text
    }
    throw new Error(message);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : (undefined as T);
}

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

export interface Evaluation {
  id: string;
  owner_id: string;
  agent_id: string;
  name: string;
  description?: string;
  baseline_run_id?: string;
  baseline_generation: number;
  archived_at?: string;
  created_at: string;
  updated_at: string;
}

export interface ListEvaluationsResponse {
  evaluations: Evaluation[];
  total: number;
}

export interface DatasetRevision {
  id: string;
  evaluation_id: string;
  revision: number;
  status: string;
  case_count: number;
  content_hash: string;
  manifest_json: string;
  created_by: string;
  created_at: string;
}

export interface EvalCase {
  case_id: string;
  split: string;
  weight: number;
  input_text: string;
  grader_refs_json: string;
  tags_json?: string;
  environment_json?: string;
  reference_json?: string;
}

export interface GraderSuite {
  id: string;
  revision: number;
  name: string;
  fingerprint: string;
  body_json: string;
  test_receipt_json?: string;
  created_at: string;
}

export interface EvalSpec {
  id: string;
  revision: number;
  dataset_revision_id: string;
  dataset_fingerprint?: string;
  grader_suite_id: string;
  fingerprint: string;
  profile: string;
  repetitions: number;
  agent_concurrency: number;
  trial_deadline_seconds: number;
  trial_max_turns: number;
  run_deadline_seconds: number;
  default_clock: string;
  split: string;
  case_count: number;
  planned_trials: number;
  body_json: string;
  created_at: string;
}

export interface EvalRun {
  id: string;
  evaluation_id: string;
  actor_id: string;
  spec_id: string;
  snapshot_id: string;
  purpose: string;
  experiment_label?: string;
  status: string;
  planned_trials: number;
  started_trials: number;
  succeeded_trials: number;
  failed_trials: number;
  unmeasured_trials: number;
  cancel_requested_by?: string;
  deadline_at: string;
  created_at: string;
  started_at?: string;
  completed_at?: string;
  error?: string;
  request_key: string;
}

export interface Trial {
  id: string;
  run_id: string;
  case_id: string;
  repetition_index: number;
  split: string;
  weight: number;
  status: string;
  execution_result?: string;
  task_result?: string;
  agent_run_id?: string;
  actual_output?: string;
  grading_json?: string;
  usage_json?: string;
  error?: string;
  started_at?: string;
  completed_at?: string;
}

export interface TrialDetail extends Trial {
  case_input_json?: string;
  case_reference_json?: string;
  trace_json?: string;
  artifacts_json?: string;
}

export interface RunReport {
  planned_trials: number;
  success: number;
  failure: number;
  unmeasured: number;
  success_lower_bound?: number;
  success_upper_bound?: number;
  measurement_coverage?: number;
  measured_success_rate?: number;
  weighted_success_lower_bound?: number;
  weighted_success_upper_bound?: number;
  p50_duration_ms?: number;
  p95_duration_ms?: number;
  input_tokens?: number;
  output_tokens?: number;
  cost_known: boolean;
  notes: string[];
  cases: CaseReport[];
  graders: GraderReport[];
}

export interface CaseReport {
  case_id: string;
  split: string;
  weight: number;
  success: number;
  failure: number;
  unmeasured: number;
  success_fraction?: number;
  any_success?: boolean;
  all_success?: boolean;
  trials: TrialSummary[];
}

export interface TrialSummary {
  repetition_index: number;
  execution_result?: string;
  task_result?: string;
  duration_ms?: number;
  input_tokens?: number;
  output_tokens?: number;
}

export interface GraderReport {
  grader_id: string;
  required?: boolean;
  applicable: number;
  completed: number;
  pass: number;
  fail: number;
  indeterminate: number;
  error: number;
  not_run_target_failed?: number;
  pass_rate?: number;
}

export interface Comparison {
  grade: string;
  differences: string[];
  paired_cases?: number;
  candidate_wins?: number;
  ties?: number;
  baseline_wins?: number;
  mean_delta?: number;
  ci_low?: number;
  ci_high?: number;
  regressions?: number;
  new_hard_failures?: number;
  statistics_version?: string;
  cases?: CaseDelta[];
}

export interface CaseDelta {
  case_id: string;
  baseline_success_fraction?: number;
  candidate_success_fraction?: number;
  delta?: number;
}

export const evaluationApi = {
  list: (agentId?: string, offset = 0, limit = 20) =>
    request<ListEvaluationsResponse>(`/api/evaluations${query({ agentId, offset, limit })}`),
  create: (data: { name: string; agent_id: string; description?: string }) =>
    request<Evaluation>('/api/evaluations', { method: 'POST', body: JSON.stringify(data) }),
  get: (id: string) => request<Evaluation>(`/api/evaluations/${id}`),
  update: (id: string, data: { name?: string; description?: string }) =>
    request<Evaluation>(`/api/evaluations/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  archive: (id: string) => request<void>(`/api/evaluations/${id}/archive`, { method: 'POST' }),
  listDatasets: (id: string) =>
    request<{ datasets: DatasetRevision[] }>(`/api/evaluations/${id}/datasets`),
  importDataset: (id: string, data: { file_id: string; name?: string }) =>
    request<DatasetRevision>(`/api/evaluations/${id}/dataset-imports`, { method: 'POST', body: JSON.stringify(data) }),
  listCases: (id: string, datasetId: string, split?: string, offset = 0, limit = 50) =>
    request<{ cases: EvalCase[]; total: number }>(
      `/api/evaluations/${id}/datasets/${datasetId}/cases${query({ split, offset, limit })}`),
  testSuite: (id: string, bodyJson: string) =>
    request<{ receipt_json: string }>(`/api/evaluations/${id}/grader-tests`, {
      method: 'POST',
      body: JSON.stringify({ body_json: bodyJson }),
    }),
  createSuite: (id: string, data: { name?: string; body_json: string }) =>
    request<GraderSuite>(`/api/evaluations/${id}/grader-suites`, { method: 'POST', body: JSON.stringify(data) }),
  listSuites: (id: string) =>
    request<{ suites: GraderSuite[] }>(`/api/evaluations/${id}/grader-suites`),
  createSpec: (id: string, data: {
    dataset_id: string;
    grader_suite_id: string;
    profile?: string;
    repetitions?: number;
    agent_concurrency?: number;
    trial_deadline_seconds?: number;
    trial_max_turns?: number;
    run_deadline_seconds?: number;
    split?: string;
    case_ids?: string[];
    default_clock?: string;
  }) => request<EvalSpec>(`/api/evaluations/${id}/spec-revisions`, { method: 'POST', body: JSON.stringify(data) }),
  listSpecs: (id: string) => request<{ specs: EvalSpec[] }>(`/api/evaluations/${id}/spec-revisions`),
  startRun: (id: string, data: {
    spec_id: string;
    source?: string;
    snapshot_id?: string;
    purpose?: string;
    experiment_label?: string;
    request_key: string;
  }) => request<EvalRun>(`/api/evaluations/${id}/runs`, { method: 'POST', body: JSON.stringify(data) }),
  listRuns: (id: string, offset = 0, limit = 20) =>
    request<{ runs: EvalRun[]; total: number }>(`/api/evaluations/${id}/runs${query({ offset, limit })}`),
  getRun: (id: string, runId: string) => request<EvalRun>(`/api/evaluations/${id}/runs/${runId}`),
  report: (id: string, runId: string) => request<RunReport>(`/api/evaluations/${id}/runs/${runId}/report`),
  listTrials: (id: string, runId: string, filters: { status?: string; case_id?: string; task_result?: string; offset?: number; limit?: number } = {}) =>
    request<{ trials: Trial[]; total: number }>(
      `/api/evaluations/${id}/runs/${runId}/trials${query({ ...filters })}`),
  getTrial: (id: string, runId: string, trialId: string) =>
    request<TrialDetail>(`/api/evaluations/${id}/runs/${runId}/trials/${trialId}`),
  cancel: (id: string, runId: string) =>
    request<void>(`/api/evaluations/${id}/runs/${runId}/cancel`, { method: 'POST' }),
  setBaseline: (id: string, data: { run_id: string; reason?: string; acknowledge_unpinned?: boolean; expected_generation: number }) =>
    request<Evaluation>(`/api/evaluations/${id}/baseline`, { method: 'PUT', body: JSON.stringify(data) }),
  compare: (id: string, data: { baseline_run_id: string; candidate_run_id: string }) =>
    request<Comparison>(`/api/evaluations/${id}/comparisons`, { method: 'POST', body: JSON.stringify(data) }),
  uploadFile: async (file: File): Promise<{ id: string; file_name: string; size: number }> => {
    const headers = getAuthHeaders();
    delete headers['Content-Type'];
    const form = new FormData();
    form.append('file', file, file.name);
    const res = await fetch(`${BASE}/api/files`, { method: 'POST', headers, body: form });
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
    return res.json();
  },
};
