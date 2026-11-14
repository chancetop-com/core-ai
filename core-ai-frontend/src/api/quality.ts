import { getAuthHeaders } from './client';

export interface QualityIssue {
  rule_id: string;
  rule_revision: number;
  rule_name: string;
  rule_requirement: string;
  status: 'PASS' | 'FAIL' | 'INDETERMINATE' | 'ERROR' | 'SKIPPED';
  enforcement: 'BLOCK' | 'WARN' | 'SHADOW';
  field_path?: string;
  evidence_kind?: 'CODE_FACT' | 'SEMANTIC_ASSESSMENT';
  evidence_text?: string;
  location_precision?: string;
  message: string;
  suggestion: string;
  decision_type?: string;
  question_id?: string;
  predicate?: string;
  polarity?: string;
  decision_metric?: string;
  affirmative_probability?: number;
  affirmative_threshold?: number;
  negative_threshold?: number;
  decision_mapping?: string;
  issue_reason?: string;
  retryable?: boolean;
  recommended_action?: 'EDIT_INPUT' | 'RETRY' | 'CONTACT_ADMIN' | 'REFRESH';
  actual_count?: number;
  max_count?: number;
  category?: string;
  resource_type?: string;
  resource_id?: string;
  subagent_id?: string;
}

export interface QualityReport {
  run_id: string;
  agent_id: string;
  definition_revision?: number;
  execution_candidate_hash?: string;
  metadata_hash?: string;
  policy_revision?: number;
  route_fingerprint?: string;
  gate_result: 'PASSED' | 'REJECTED' | 'UNAVAILABLE';
  checks_complete?: boolean;
  run_status?: string;
  mode?: string;
  warn_count?: number;
  block_fail_count?: number;
  incomplete_count?: number;
  rules_evaluated?: number;
  rules_applicable?: number;
  issues?: QualityIssue[];
  started_at?: string;
  finished_at?: string;
}

export class ApiError extends Error {
  status: number;
  errorCode?: string;
  qualityReport?: QualityReport;

  constructor(message: string, status: number, errorCode?: string, qualityReport?: QualityReport) {
    super(message);
    this.status = status;
    this.errorCode = errorCode;
    this.qualityReport = qualityReport;
  }
}

async function parseError(res: Response): Promise<ApiError> {
  try {
    const body = await res.json();
    return new ApiError(body.message || `${res.status} ${res.statusText}`, res.status, body.errorCode, body.quality_report);
  } catch {
    return new ApiError(`${res.status} ${res.statusText}`, res.status);
  }
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(url, { headers: getAuthHeaders(), ...options });
  if (!res.ok) throw await parseError(res);
  const text = await res.text();
  return text ? JSON.parse(text) : (undefined as T);
}

export interface PublishResponse {
  agent?: unknown;
  runId: string | null;
  gateResult: string | null;
  warnCount: number;
}

export async function publishAgent(id: string, expectedRevision?: number, idempotencyKey?: string): Promise<PublishResponse> {
  const headers: Record<string, string> = { ...getAuthHeaders() };
  if (expectedRevision !== undefined && expectedRevision !== null) headers['If-Match'] = String(expectedRevision);
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  const res = await fetch(`/api/agents/${id}/publish`, { method: 'POST', headers });
  if (!res.ok) throw await parseError(res);
  const text = await res.text();
  return {
    agent: text ? JSON.parse(text) : undefined,
    runId: res.headers.get('X-Quality-Run-Id'),
    gateResult: res.headers.get('X-Quality-Gate-Result'),
    warnCount: Number(res.headers.get('X-Quality-Warn-Count') || '0'),
  };
}

export interface QualityRule {
  id: string;
  key: string;
  name: string;
  category?: string;
  lifecycle?: string;
  revision?: number;
  description?: string;
  rationale?: string;
  kind?: string;
  evaluator_key?: string;
  evaluator_version?: number;
  parameters?: Record<string, string>;
  input_selector?: string[];
  decision_type?: string;
  decision_question?: string;
  decision_instruction?: string;
  decision_criteria?: Record<string, string>;
  decision_levels?: string[];
  decision_polarity?: string;
  affirmative_threshold?: number;
  negative_threshold?: number;
  model_alias?: string;
  expected_route_fingerprint?: string;
  message_template?: string;
  remediation_template?: string;
  enforcement?: string;
  built_in?: boolean;
  editable_parameters?: string[];
  tested?: boolean;
  tested_at?: string;
  test_summary?: string;
  updated_by?: string;
  updated_at?: string;
  change_reason?: string;
}

export interface RuleParameter {
  name: string;
  type?: string;
  required?: boolean;
  description?: string;
  default_value?: string;
  options?: string[];
}

export interface RuleTemplate {
  evaluator_key: string;
  name: string;
  kind: string;
  category: string;
  description: string;
  parameters: RuleParameter[];
  input_options: string[];
  supports_decision: boolean;
}

export interface PolicyBinding {
  rule_id: string;
  rule_revision: number;
  enforcement: 'BLOCK' | 'WARN' | 'SHADOW';
}

export interface QualityPolicy {
  revision: number;
  generation: number;
  scope?: string;
  active?: boolean;
  change_reason?: string;
  activated_by?: string;
  activated_at?: string;
  bindings?: PolicyBinding[];
}

export interface RuleTestResponse {
  rule_id: string;
  rule_revision: number;
  result: string;
  matches_expected: boolean;
  issues: QualityIssue[];
  duration_ms: number;
  route_fingerprint?: string;
  lifecycle?: string;
}

export const qualityApi = {
  check: (agentId: string, idempotencyKey?: string) =>
    request<QualityReport>(`/api/agents/${agentId}/quality-checks`, {
      method: 'POST',
      headers: { ...getAuthHeaders(), ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}) },
    }),
  report: (agentId: string, runId = 'latest') =>
    request<QualityReport>(`/api/agents/${agentId}/quality-checks/${encodeURIComponent(runId)}`),
  templates: () =>
    request<{ templates: RuleTemplate[]; input_options: string[] }>('/api/admin/agent-quality/rule-templates'),
  rules: () =>
    request<{ rules: QualityRule[]; policy_revision: number }>('/api/admin/agent-quality/rules'),
  createRule: (data: Record<string, unknown>) =>
    request<QualityRule>('/api/admin/agent-quality/rules', { method: 'POST', body: JSON.stringify(data) }),
  updateRule: (id: string, data: Record<string, unknown>) =>
    request<QualityRule>(`/api/admin/agent-quality/rules/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  testRule: (id: string, data: { agent_id: string; sample_name?: string; description?: string; system_prompt?: string; expected_result?: string }) =>
    request<RuleTestResponse>(`/api/admin/agent-quality/rules/${encodeURIComponent(id)}/test`, {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  retireRule: (id: string) =>
    request<QualityRule>(`/api/admin/agent-quality/rules/${encodeURIComponent(id)}/retire`, { method: 'POST' }),
  policies: () =>
    request<{ active: QualityPolicy; history: QualityPolicy[] }>('/api/admin/agent-quality/policies'),
  activate: (data: Record<string, unknown>) =>
    request<QualityPolicy>('/api/admin/agent-quality/policies/activate', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
};

export function issueTargetSection(fieldPath?: string): string | null {
  if (!fieldPath) return null;
  if (fieldPath.startsWith('/description')) return 'overview';
  if (fieldPath.startsWith('/system_prompt') || fieldPath.startsWith('/input_template')) return 'prompt';
  if (fieldPath.startsWith('/tools')) return 'tools';
  if (fieldPath.startsWith('/skill_ids')) return 'skills';
  if (fieldPath.startsWith('/subagent_ids')) return 'subagents';
  if (fieldPath.startsWith('/dataset_config')) return 'datasets';
  return null;
}
