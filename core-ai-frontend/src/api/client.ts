const BASE = '';

function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const apiKey = localStorage.getItem('apiKey');
  if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`;
  return headers;
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    headers: getAuthHeaders(),
    ...options,
  });
  if (res.status === 401) {
    // Don't redirect to login in CLI mode (apiKey = 'local')
    const apiKey = localStorage.getItem('apiKey');
    if (apiKey !== 'local') {
      localStorage.removeItem('apiKey');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    throw new Error('Unauthorized');
  }
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  const text = await res.text();
  return text ? JSON.parse(text) : (undefined as T);
}

export interface LoginResponse {
  api_key: string;
  user_id: string;
  name: string;
  role?: string;
}

export const authApi = {
  login: (email: string, password: string) =>
    request<LoginResponse>('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  register: (email: string, password: string, name?: string) =>
    request<{ api_key: string; user_id: string }>('/api/auth/register', { method: 'POST', body: JSON.stringify({ email, password, name }) }),
};

export interface ApiKeyInfo {
  api_key: string;
  created_at: string;
}

export interface GenerateApiKeyResponse {
  api_key: string;
}

export const userApi = {
  getApiKey: () =>
    requestWithAuth<ApiKeyInfo>('/api/user/api-key'),
  generateApiKey: () =>
    requestWithAuth<GenerateApiKeyResponse>('/api/user/api-key', { method: 'POST' }),
  changePassword: (currentPassword: string, newPassword: string) =>
    requestWithAuth<void>('/api/user/change-password',
      { method: 'POST', body: JSON.stringify({ current_password: currentPassword, new_password: newPassword }) }),
};

export interface UserStatus {
  email: string;
  name: string;
  role: string;
  status: string;
  created_at: string;
  has_api_key: boolean;
  api_key_created_at: string;
  api_key: string;
}

export interface TraceAccount {
  userId: string;
  name?: string;
  email?: string;
  role?: string;
  status?: string;
}

export interface ListUsersResponse {
  users: UserStatus[];
}

export interface GenerateApiKeyForUserResponse {
  api_key: string;
}

async function requestWithAuth<T>(url: string, options?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const apiKey = localStorage.getItem('apiKey');
  if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`;
  const res = await fetch(`${BASE}${url}`, { headers, ...options });
  if (res.status === 401) {
    const apiKeyStored = localStorage.getItem('apiKey');
    if (apiKeyStored !== 'local') {
      localStorage.removeItem('apiKey');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    throw new Error('Unauthorized');
  }
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  const text = await res.text();
  return text ? JSON.parse(text) : (undefined as T);
}

export const adminApi = {
  listUsers: () =>
    requestWithAuth<ListUsersResponse>('/api/auth/users'),
  updateUserStatus: (email: string, status: string) =>
    requestWithAuth<void>('/api/auth/users/update-status', { method: 'POST', body: JSON.stringify({ email, status }) }),
  deleteUser: (email: string) =>
    requestWithAuth<void>('/api/auth/users/delete', { method: 'POST', body: JSON.stringify({ email }) }),
  generateApiKeyForUser: (email: string) =>
    requestWithAuth<GenerateApiKeyForUserResponse>('/api/auth/users/generate-api-key',
      { method: 'POST', body: JSON.stringify({ email }) }),
  revokeApiKey: (email: string) =>
    requestWithAuth<void>('/api/auth/users/revoke-api-key',
      { method: 'POST', body: JSON.stringify({ email }) }),
  updateUserRole: (email: string, role: string) =>
    requestWithAuth<void>('/api/auth/users/update-role',
      { method: 'POST', body: JSON.stringify({ email, role }) }),
  resetUserPassword: (email: string, password: string) =>
    requestWithAuth<void>('/api/auth/users/reset-password',
      { method: 'POST', body: JSON.stringify({ email, password }) }),
};

export interface Trace {
  id: string;
  traceId: string;
  name: string;
  type?: string;     // agent | llm_call | external
  source?: string;   // chat | test | api | a2a | scheduled | workflow | llm_test | llm_api | external
  agentName?: string;
  model?: string;
  sessionId: string;
  userId: string;
  account?: TraceAccount;
  status: 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'ERROR';
  errorMessage?: string;
  // input/output are omitted in list responses; the server sends a short preview instead
  input?: string;
  output?: string;
  preview?: string;
  metadata: Record<string, string>;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cachedTokens?: number;
  costUsd?: number;
  durationMs: number;
  startedAt: string;
  completedAt: string;
  createdAt: string;
}

export interface Span {
  id: string;
  traceId: string;
  userId?: string;
  spanId: string;
  parentSpanId: string | null;
  name: string;
  type: 'LLM' | 'AGENT' | 'TOOL' | 'FLOW' | 'GROUP';
  model: string;
  input: string;
  output: string;
  inputTokens: number;
  outputTokens: number;
  cachedTokens?: number;
  costUsd?: number;
  durationMs: number;
  status: 'OK' | 'CANCELLED' | 'ERROR';
  errorMessage?: string;
  attributes: Record<string, string>;
  startedAt: string;
  completedAt: string;
}

export interface TraceFilter {
  q?: string;           // smart search: IDs, user account, trace name, or agent name
  name?: string;        // advanced raw regex on name
  type?: string;        // agent | llm_call | external
  source?: string;      // chat | a2a | api | scheduled | workflow
  agentName?: string;
  model?: string;
  status?: string;
  sessionId?: string;
  userId?: string;
  range?: string;       // 15m | 1h | 24h | 7d | 30d (overrides startFrom/startTo when present)
  startFrom?: string;
  startTo?: string;
}

export interface TraceFacet {
  value: string;
  count: number;
}

export interface TraceListResponse {
  traces: Trace[];
  // -1 when the server could not count this filter combination; UI falls back to prev/next paging
  total: number;
}

export interface SessionSummary {
  session_id: string;
  trace_count: number;
  total_tokens: number;
  total_cached_tokens?: number;
  total_cost_usd?: number;
  total_duration_ms: number;
  error_count: number;
  user_id: string;
  account?: TraceAccount;
  last_trace_at: string;
  first_trace_at: string;
  first_request?: string;
}

export interface PromptTemplate {
  id?: string;
  name: string;
  description: string;
  template: string;
  variables: string[];
  model: string;
  model_parameters: Record<string, string>;
  version: number;
  published_version: number | null;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  tags: string[];
  created_by: string;
  created_at?: string;
  updated_at?: string;
}

export interface SystemPrompt {
  id: string;
  promptId: string;
  name: string;
  description: string;
  content: string;
  variables: string[];
  version: number;
  changelog: string;
  tags: string[];
  userId: string;
  createdAt: string;
}

export interface SystemPromptVersion {
  version: number;
  changelog: string;
  content: string;
  createdAt: string;
}

export interface SystemPromptTestResult {
  output: string;
  inputTokens: number;
  outputTokens: number;
  resolvedPrompt: string;
}

export interface ToolRef {
  id: string;
  type?: string;
  source?: string;
}

export interface SandboxConfig {
  enabled?: boolean;
  image?: string;
  memory_limit_mb?: number;
  cpu_limit_millicores?: number;
  timeout_seconds?: number;
  network_enabled?: boolean;
  git_repo_url?: string;
  git_branch?: string;
  tmp_size_limit?: string;
  max_async_tasks?: number;
  env_vars?: Record<string, string>;
}

export interface AgentDefinition {
  id: string;
  name: string;
  description: string;
  system_prompt: string;
  system_prompt_id: string;
  model: string;
  multi_modal_model?: string;
  temperature: number;
  max_turns: number;
  timeout_seconds: number;
  tools: ToolRef[];
  input_template: string;
  variables: Record<string, string>;
  system_default: boolean;
  type: string;
  response_schema: unknown;
  created_by: string;
  status: string;
  published_at: string;
  created_at: string;
  updated_at: string;
  subagent_ids?: string[];
  skill_ids?: string[];
  sub_agents?: { id: string; name: string }[];
  skills?: { id: string; name: string }[];
  sandbox_config?: SandboxConfig;
  dataset_config?: AgentDatasetConfig[];
  enable_memory?: boolean;
}

export interface AgentMemoryView {
  id: string;
  agent_id: string;
  type: string;
  content: string;
  source_trace_ids: string[];
  created_at: string;
  updated_at: string;
}

export interface ListAgentMemoriesResponse {
  memories: AgentMemoryView[];
}

export interface AgentDatasetConfig {
  dataset_id: string;
  permission: 'READ' | 'WRITE' | 'FULL';
  is_output?: boolean;
}

export interface ListAgentsResponse {
  agents: AgentDefinition[];
  total: number;
  page?: number;
  limit?: number;
}

export interface TriggerRunResponse {
  run_id: string;
}

export interface LLMCallResponse {
  output: string;
  token_usage: Record<string, number>;
}

export interface ConvertJavaToSchemaResponse {
  schema: string | null;
  error: string | null;
}

export interface AgentRun {
  id: string;
  agent_id: string;
  user_id: string;
  triggered_by: string;
  status: string;
  input: string;
  output: string;
  error: string;
  token_usage: Record<string, number>;
  trace_id?: string;
  started_at: string;
  completed_at: string;
}

export interface AgentRunDetail extends AgentRun {
  transcript: TranscriptEntry[];
}

export interface TranscriptEntry {
  ts: string;
  role: string;
  content: string;
  name: string;
  args: string;
  status: string;
  result: string;
}

export interface ListRunsResponse {
  runs: AgentRun[];
  total: number;
}

export interface SkillDefinition {
  id: string;
  namespace: string;
  name: string;
  qualified_name: string;
  description: string;
  source_type: 'UPLOAD' | 'REPO';
  allowed_tools: string[];
  metadata: Record<string, string>;
  version: string;
  user_id: string;
  created_at: string;
  updated_at: string;
}

export interface ListSkillsResponse {
  skills: SkillDefinition[];
  total: number;
}

export interface SkillDownloadResponse {
  name: string;
  namespace: string;
  content: string;
  resources: { path: string; content: string }[];
}

export interface UpdateSkillRequest {
  description?: string;
  content?: string;
  allowed_tools?: string[];
  resources?: { path: string; content: string }[];
}

export interface ToolRegistryView {
  id: string;
  name: string;
  description: string;
  type: string;
  category: string;
  config: Record<string, string>;
  raw_config?: string;
  enabled: boolean;
}

export interface ListToolsResponse {
  tools: ToolRegistryView[];
  total: number;
}

export interface ListToolCategoriesResponse {
  categories: string[];
}

export interface McpToolInfo {
  name: string;
  description: string;
  input_schema?: string;
}

export interface McpServerToolsResponse {
  server_id: string;
  server_name: string;
  tools: McpToolInfo[];
}

export type McpConnectionState =
  | 'NOT_CONNECTED'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'DISCONNECTED'
  | 'RECONNECTING'
  | 'FAILED';

export interface McpServerStatusResponse {
  server_id: string;
  state: McpConnectionState;
  message?: string;
}

export interface TestMcpToolResponse {
  success: boolean;
  result: string;
  duration_ms: number;
}

export interface TestApiToolResponse {
  success: boolean;
  result: string;
  duration_ms: number;
}

export interface ApiAppView {
  name: string;
  base_url: string;
  version: string;
  description: string;
}

export interface ApiPayloadService {
  name: string;
  description: string;
  operations: ApiPayloadOperation[];
}

export interface ApiPayload {
  app: string;
  base_url: string;
  version: string;
  services: ApiPayloadService[];
  types: ApiPayloadType[];
}

export interface ListApiAppsResponse {
  apps: ApiAppView[];
}

export interface ApiServiceView {
  name: string;
  description: string;
  operation_count: number;
  operations: ApiOperationView[];
}

export interface ListApiAppServicesResponse {
  services: ApiServiceView[];
}

// Service API types
export interface OperationAdditionalView {
  name: string;
  description?: string;
  example?: string;
  enabled?: boolean;
  need_auth?: boolean;
  path_param_additional?: PathParamAdditionalView[];
}

export interface PathParamAdditionalView {
  name: string;
  description?: string;
  example?: string;
}

export interface ServiceAdditionalView {
  name: string;
  description?: string;
  enabled?: boolean;
  operation_additional?: OperationAdditionalView[];
}

export interface FieldAdditionalView {
  name: string;
  description?: string;
  example?: string;
}

export interface TypeAdditionalView {
  name: string;
  description?: string;
  field_additional?: FieldAdditionalView[];
}

export interface ServiceApiView {
  id: string;
  name: string;
  enabled: boolean;
  description: string;
  base_url: string;
  url: string;
  version: string;
  payload: string;
  service_additional: ServiceAdditionalView[];
  type_additional: TypeAdditionalView[];
  created_by: string;
  created_at: string;
  updated_by: string;
  updated_at: string;
}

// Parsed payload types (from ServiceApi.payload JSON)
export interface ApiPayloadPathParam {
  name: string;
  description: string;
  example: string;
  type: string;
}

export interface ApiPayloadOperation {
  name: string;
  description: string;
  need_auth: boolean;
  example: string;
  method: string;
  path: string;
  pathParams: ApiPayloadPathParam[];
  requestType: string;
  responseType: string;
  optional: boolean;
  deprecated: boolean;
}

export interface ApiServiceView {
  name: string;
  description: string;
  operation_count: number;
  operations: ApiOperationView[];
}

export interface ApiOperationView {
  name: string;
  tool_name?: string;
  description: string;
  method: string;
  path: string;
  request_type?: string;
  response_type?: string;
  input_schema?: string;
  output_schema?: string;
}

export interface ApiPayloadTypeField {
  name: string;
  description: string;
  example: string;
  type: string;
  typeParams: string[];
  constraints: Record<string, unknown>;
}

export interface ApiPayloadType {
  name: string;
  type: string;
  fields: ApiPayloadTypeField[];
  enumConstants: { name: string; value: string }[];
}

export interface ApiPayload {
  app: string;
  base_url: string;
  version: string;
  services: ApiPayloadService[];
  types: ApiPayloadType[];
}

export interface ListServiceApiResponse {
  service_apis: ServiceApiView[];
}

export interface AgentScheduleView {
  id: string;
  agent_id: string;
  name?: string;
  remark?: string;
  cron_expression: string;
  timezone: string;
  enabled: boolean;
  input: string;
  variables?: Record<string, string>;
  concurrency_policy: string;
  next_run_at: string;
  created_at: string;
  updated_at: string;
}

export interface ListSchedulesResponse {
  schedules: AgentScheduleView[];
}

export interface CreateScheduleRequest {
  agent_id: string;
  name?: string;
  remark?: string;
  cron_expression: string;
  timezone?: string;
  input?: string;
  variables?: Record<string, string>;
  concurrency_policy?: string;
}

export interface UpdateScheduleRequest {
  name?: string;
  remark?: string;
  cron_expression?: string;
  timezone?: string;
  enabled?: boolean;
  input?: string;
  variables?: Record<string, string>;
  concurrency_policy?: string;
}

export interface CloneWorkflowResponse {
  workflow: WorkflowView;
  warnings?: string[];   // publish-blocking issues the clone already has (e.g. agent nodes the caller doesn't own)
}

export interface ExploreWorkflowsResponse {
  workflows: WorkflowView[];
  total: number;
}

export interface WorkflowView {
  id: string;
  user_id?: string;
  user_name?: string;
  editable?: boolean;   // false = read-only (another user's published workflow); undefined/true = editable
  name: string;
  mode?: string;
  status?: string;
  visibility?: string;
  published_version?: number;
  published_version_id?: string;
  draft_graph?: string;
}

export interface WorkflowVersionView {
  id: string;
  workflow_id?: string;
  version?: number;
  preview?: boolean;
  status?: string;
  sha256?: string;
  published_by?: string;
  published_at?: string;
  current_public?: boolean;
}

export interface ListWorkflowVersionsResponse {
  versions: WorkflowVersionView[];
}

export interface ListWorkflowsResponse {
  workflows: WorkflowView[];
  total?: number;
  offset?: number;
  limit?: number;
}

export interface ExportWorkflowResponse {
  format: string;
  exported_at?: string;
  name: string;
  mode?: string;
  description?: string;
  graph: string;
}

export interface UnresolvedReferenceView {
  node_id: string;
  node_type: string;
  ref_type: string;
  ref_id?: string;
  message: string;
}

export interface ImportWorkflowResponse {
  workflow: WorkflowView;
  unresolved_references?: UnresolvedReferenceView[];
}

export interface WorkflowArtifactView {
  file_id?: string;
  file_name?: string;
  content_type?: string;
  size?: number;
  url?: string;
  title?: string;
  description?: string;
}

export interface PendingInputFieldView {
  name?: string;
  type?: string;
  label?: string;
  required?: boolean;
}

// present on single-run reads when status is PAUSED: the human waits blocking the run and how to resume each
export interface PendingInputView {
  node_id?: string;
  mode?: string;
  prompt?: string;
  fields?: PendingInputFieldView[];
}

export interface WorkflowRunView {
  id: string;
  workflow_id?: string;
  status?: string;
  visibility?: string;
  input?: string;
  output?: string;
  artifacts?: WorkflowArtifactView[];
  error?: string;
  started_at?: string;
  completed_at?: string;
  resumed_from_run_id?: string;   // set when this run was resumed from an intermediate node of another run
  resume_from_node_id?: string;
  parent_run_id?: string;
  parent_node_id?: string;
  pending_inputs?: PendingInputView[];
}

export interface ListWorkflowRunsResponse {
  runs: WorkflowRunView[];
}

export interface WorkflowNodeRunView {
  node_id: string;
  node_type?: string;
  status?: string;
  input?: string;
  output?: string;
  artifacts?: WorkflowArtifactView[];
  error?: string;
  child_run_id?: string;
  child_run_type?: string;
  child_workflow_id?: string;
  trace_id?: string;
  span_id?: string;
  started_at?: string;
  completed_at?: string;
}

export interface ListNodeRunsResponse {
  node_runs: WorkflowNodeRunView[];
}

export interface WorkflowRunGraphResponse {
  graph: string;
}

export interface CreateRunResponse {
  run_id: string;
  status: string;
}

export interface ValidateWorkflowResponse {
  valid: boolean;
  errors: string[];
}

export const api = {
  traces: {
    list: (offset = 0, limit = 20, filters?: TraceFilter) => {
      const params = new URLSearchParams({ offset: String(offset), limit: String(limit) });
      if (filters) {
        Object.entries(filters).forEach(([k, v]) => { if (v) params.set(k, v); });
      }
      return request<TraceListResponse>(`/api/traces?${params}`);
    },
    get: (id: string) => request<Trace>(`/api/traces/${id}`),
    spans: (id: string) => request<Span[]>(`/api/traces/${id}/spans`),
    generations: (offset = 0, limit = 20, model?: string) => {
      const params = new URLSearchParams({ offset: String(offset), limit: String(limit) });
      if (model) params.set('model', model);
      return request<Span[]>(`/api/traces/generations?${params}`);
    },
    sessionSummary: (sessionId: string) =>
      request<SessionSummary>(`/api/traces/sessions/${encodeURIComponent(sessionId)}/summary`),
    facets: (field: 'model' | 'agentName' | 'source', filters?: TraceFilter) => {
      const params = new URLSearchParams({ field });
      if (filters) {
        Object.entries(filters).forEach(([k, v]) => { if (v) params.set(k, v); });
      }
      return request<TraceFacet[]>(`/api/traces/facets?${params}`);
    },
  },
  prompts: {
    list: (offset = 0, limit = 20) =>
      request<PromptTemplate[]>(`/api/prompts?offset=${offset}&limit=${limit}`),
    get: (id: string) => request<PromptTemplate>(`/api/prompts/${id}`),
    create: (data: Partial<PromptTemplate>) =>
      request<PromptTemplate>('/api/prompts', { method: 'POST', body: JSON.stringify(data) }),
    update: (id: string, data: Partial<PromptTemplate>) =>
      request<PromptTemplate>(`/api/prompts/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id: string) =>
      request<void>(`/api/prompts/${id}`, { method: 'DELETE' }),
    publish: (id: string) =>
      request<PromptTemplate>(`/api/prompts/${id}/publish`, { method: 'POST' }),
  },
  agents: {
    list: (my?: boolean, query?: string, limit?: number, page?: number, sort?: string, includeSystemDefault?: boolean) => {
      const params = new URLSearchParams();
      if (my !== undefined) params.set('my', String(my));
      if (query) params.set('query', query);
      if (limit) params.set('limit', String(limit));
      if (page) params.set('page', String(page));
      if (sort) params.set('sort', sort);
      if (includeSystemDefault !== undefined) params.set('include_system_default', String(includeSystemDefault));
      const qs = params.toString();
      return request<ListAgentsResponse>(`/api/agents${qs ? `?${qs}` : ''}`);
    },
    get: (id: string) =>
      request<AgentDefinition>(`/api/agents/${id}`),
    create: (data: Partial<AgentDefinition>) =>
      request<AgentDefinition>('/api/agents', { method: 'POST', body: JSON.stringify(data) }),
    update: (id: string, data: Partial<AgentDefinition>) =>
      request<AgentDefinition>(`/api/agents/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id: string) =>
      request<void>(`/api/agents/${id}`, { method: 'DELETE' }),
    publish: (id: string) =>
      request<AgentDefinition>(`/api/agents/${id}/publish`, { method: 'POST' }),
    trigger: (agentId: string, input: string) =>
      request<TriggerRunResponse>(`/api/runs/agent/${agentId}/trigger`, { method: 'POST', body: JSON.stringify({ input }) }),
    runs: (agentId: string) =>
      request<ListRunsResponse>(`/api/runs/agent/${agentId}/list`),
    getRun: (runId: string) =>
      request<AgentRunDetail>(`/api/runs/${runId}`),
    llmCall: (id: string, input: string, attachments?: { url?: string; type: string; data?: string; media_type?: string }[]) =>
      request<LLMCallResponse>(`/api/llm/${id}/call`, { method: 'POST', body: JSON.stringify({ input, attachments }) }),
    javaToSchema: (javaCode: string) =>
      request<ConvertJavaToSchemaResponse>('/api/utils/java-to-schema', { method: 'POST', body: JSON.stringify({ java_code: javaCode }) }),
    memories: (agentId: string) =>
      request<ListAgentMemoriesResponse>(`/api/agents/${agentId}/memories`),
  },
  workflows: {
    list: (my?: boolean, keyword?: string, offset?: number, limit?: number) => {
      const params = new URLSearchParams();
      if (my !== undefined) params.set('my', String(my));
      if (keyword) params.set('keyword', keyword);
      if (offset !== undefined) params.set('offset', String(offset));
      if (limit !== undefined) params.set('limit', String(limit));
      return request<ListWorkflowsResponse>(`/api/workflows${params.toString() ? `?${params}` : ''}`);
    },
    clone: (id: string) => request<CloneWorkflowResponse>(`/api/workflows/${id}/clone`, { method: 'POST' }),
    explore: (keyword: string, offset: number, limit: number) =>
      request<ExploreWorkflowsResponse>(
        `/api/explore/workflows?keyword=${encodeURIComponent(keyword)}&offset=${offset}&limit=${limit}`,
      ),
    create: (data: { name: string; mode?: string; graph: string }) =>
      request<WorkflowView>('/api/workflows', { method: 'POST', body: JSON.stringify(data) }),
    get: (id: string) => request<WorkflowView>(`/api/workflows/${id}`),
    update: (id: string, data: { name?: string; graph?: string }) =>
      request<WorkflowView>(`/api/workflows/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id: string) => request<void>(`/api/workflows/${id}`, { method: 'DELETE' }),
    validate: (id: string) =>
      request<ValidateWorkflowResponse>(`/api/workflows/${id}/validate`, { method: 'POST' }),
    publish: (id: string) =>
      request<WorkflowView>(`/api/workflows/${id}/publish`, { method: 'POST' }),
    versions: (id: string) => request<ListWorkflowVersionsResponse>(`/api/workflows/${id}/versions`),
    saveVersion: (id: string) =>
      request<WorkflowVersionView>(`/api/workflows/${id}/versions`, { method: 'POST' }),
    publishVersion: (id: string, versionId: string) =>
      request<WorkflowView>(`/api/workflows/${id}/versions/${versionId}/publish`, { method: 'POST' }),
    restoreVersion: (id: string, versionId: string) =>
      request<WorkflowView>(`/api/workflows/${id}/versions/${versionId}/restore`, { method: 'POST' }),
    unpublish: (id: string) =>
      request<WorkflowView>(`/api/workflows/${id}/unpublish`, { method: 'POST' }),
    createRun: (id: string, input: string, visibility?: 'PRIVATE' | 'PUBLIC') =>
      request<CreateRunResponse>(`/api/workflows/${id}/runs`, { method: 'POST', body: JSON.stringify({ input, visibility }) }),
    runSync: (id: string, input: string, visibility?: 'PRIVATE' | 'PUBLIC') =>
      request<WorkflowRunView>(`/api/workflows/${id}/run-sync`, { method: 'POST', body: JSON.stringify({ input, visibility }) }),
    previewRun: (id: string, input: string) =>
      request<CreateRunResponse>(`/api/workflows/${id}/preview-runs`, { method: 'POST', body: JSON.stringify({ input }) }),
    runs: (id: string) => request<ListWorkflowRunsResponse>(`/api/workflows/${id}/runs`),
    getRun: (runId: string) => request<WorkflowRunView>(`/api/workflow-runs/${runId}`),
    nodeRuns: (runId: string) => request<ListNodeRunsResponse>(`/api/workflow-runs/${runId}/nodes`),
    runGraph: (runId: string) => request<WorkflowRunGraphResponse>(`/api/workflow-runs/${runId}/graph`),
    versionGraph: (versionId: string) => request<WorkflowRunGraphResponse>(`/api/workflow-versions/${versionId}/graph`),
    resume: (runId: string, body: { node_id: string; approve?: boolean; input?: string }) =>
      request<CreateRunResponse>(`/api/workflow-runs/${runId}/resume`, { method: 'POST', body: JSON.stringify(body) }),
    // resume a finished/failed run from an intermediate node: upstream is reused, the node + its forward cone re-run.
    // returns a NEW run id — the caller must re-point polling to it.
    resumeFromNode: (runId: string, nodeId: string) =>
      request<CreateRunResponse>(`/api/workflow-runs/${runId}/resume-from-node`, { method: 'POST', body: JSON.stringify({ node_id: nodeId }) }),
    export: (id: string) => request<ExportWorkflowResponse>(`/api/workflows/${id}/export`),
    import: (content: string, name?: string) =>
      request<ImportWorkflowResponse>('/api/workflows/import', { method: 'POST', body: JSON.stringify({ content, name }) }),
  },
  utils: {
    generate: (data: { system_prompt: string; user_prompt: string }) =>
      request<{ output: string }>('/api/utils/generate', { method: 'POST', body: JSON.stringify(data) }),
  },
  systemPrompts: {
    list: (offset = 0, limit = 20) =>
      request<SystemPrompt[]>(`/api/system-prompts?offset=${offset}&limit=${limit}`),
    get: (promptId: string) =>
      request<SystemPrompt>(`/api/system-prompts/${promptId}`),
    create: (data: { name: string; description?: string; content: string; tags?: string[] }) =>
      request<SystemPrompt>('/api/system-prompts', { method: 'POST', body: JSON.stringify(data) }),
    update: (promptId: string, data: { name?: string; description?: string; content?: string; tags?: string[]; changelog?: string }) =>
      request<SystemPrompt>(`/api/system-prompts/${promptId}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (promptId: string) =>
      request<void>(`/api/system-prompts/${promptId}`, { method: 'DELETE' }),
    versions: (promptId: string) =>
      request<SystemPromptVersion[]>(`/api/system-prompts/${promptId}/versions`),
    getVersion: (promptId: string, version: number) =>
      request<SystemPrompt>(`/api/system-prompts/${promptId}/versions/${version}`),
    test: (promptId: string, data: { model: string; userMessage: string; variables?: Record<string, string> }) =>
      request<SystemPromptTestResult>(`/api/system-prompts/${promptId}/test`, { method: 'POST', body: JSON.stringify(data) }),
  },
  skills: {
    list: (namespace?: string, sourceType?: string, q?: string, userId?: string, offset?: number, limit?: number, searchIn?: string) => {
      const params = new URLSearchParams();
      if (namespace) params.set('namespace', namespace);
      if (sourceType) params.set('source_type', sourceType);
      if (q) params.set('q', q);
      if (searchIn) params.set('search_in', searchIn);
      if (userId) params.set('user_id', userId);
      if (offset !== undefined) params.set('offset', String(offset));
      if (limit !== undefined) params.set('limit', String(limit));
      return request<ListSkillsResponse>(`/api/skills${params.toString() ? `?${params}` : ''}`);
    },
    get: (id: string) =>
      request<SkillDefinition>(`/api/skills/${id}`),
    update: (id: string, data: UpdateSkillRequest) =>
      request<SkillDefinition>(`/api/skills/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id: string) =>
      request<void>(`/api/skills/${id}`, { method: 'DELETE' }),
    sync: (id: string) =>
      request<SkillDefinition>(`/api/skills/${id}/sync`, { method: 'POST' }),
    download: (id: string) =>
      request<SkillDownloadResponse>(`/api/skills/${id}/download`),
    upload: async (skillFile: File, resourceFiles?: File[]): Promise<SkillDefinition> => {
      const formData = new FormData();
      formData.append('skill_file', skillFile);
      if (resourceFiles) {
        for (const f of resourceFiles) {
          formData.append(f.name, f);
        }
      }
      const headers: Record<string, string> = {};
      const apiKey = localStorage.getItem('apiKey');
      if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`;
      const res = await fetch('/api/skills/upload', { method: 'POST', headers, body: formData });
      if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
      return res.json();
    },
  },
  tools: {
    list: (category?: string) => {
      const params = category ? `?category=${category}` : '';
      return request<ListToolsResponse>(`/api/tools${params}`);
    },
    categories: () =>
      request<ListToolCategoriesResponse>('/api/tools/categories'),
    get: (id: string) =>
      request<ToolRegistryView>(`/api/tools/${id}`),
    createMcpServer: (data: { name: string; description?: string; category?: string; config: Record<string, string>; enabled?: boolean }) =>
      request<ToolRegistryView>('/api/tools/mcp-servers', { method: 'POST', body: JSON.stringify(data) }),
    updateMcpServer: (id: string, data: { name?: string; description?: string; category?: string; config?: Record<string, string>; raw_config?: string; enabled?: boolean }) =>
      request<ToolRegistryView>(`/api/tools/mcp-servers/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteMcpServer: (id: string) =>
      request<void>(`/api/tools/mcp-servers/${id}`, { method: 'DELETE' }),
    importMcpServers: (data: { config: string; category?: string; enabled?: boolean }) =>
      request<{ servers: ToolRegistryView[]; total: number }>('/api/tools/mcp-servers/import', { method: 'POST', body: JSON.stringify(data) }),
    enableMcpServer: (id: string) =>
      request<ToolRegistryView>(`/api/tools/mcp-servers/${id}/enable`, { method: 'PUT' }),
    disableMcpServer: (id: string) =>
      request<ToolRegistryView>(`/api/tools/mcp-servers/${id}/disable`, { method: 'PUT' }),
    listApiApps: () =>
      request<ListApiAppsResponse>('/api/tools/service-api/apps'),
    listApiAppServices: (appName: string) =>
      request<ListApiAppServicesResponse>(`/api/tools/service-api/apps/${appName}/services`),
    listMcpServerTools: (serverId: string) =>
      request<McpServerToolsResponse>(`/api/tools/mcp-servers/${serverId}/tools`),
    getMcpServerStatus: (serverId: string) =>
      request<McpServerStatusResponse>(`/api/tools/mcp-servers/${serverId}/status`),
    connectMcpServer: (serverId: string) =>
      request<McpServerStatusResponse>(`/api/tools/mcp-servers/${serverId}/connect`, { method: 'POST' }),
    testMcpServerTool: (serverId: string, toolName: string, args: string) =>
      request<TestMcpToolResponse>(`/api/tools/mcp-servers/${serverId}/test-tool`, {
        method: 'POST',
        body: JSON.stringify({ tool_name: toolName, arguments: args }),
      }),
    testServiceApiTool: (toolId: string, args: string) =>
      request<TestApiToolResponse>('/api/tools/service-api/test-tool', {
        method: 'POST',
        body: JSON.stringify({ tool_id: toolId, arguments: args }),
      }),
  },
  serviceApis: {
    list: () =>
      request<ListServiceApiResponse>('/api/service-api'),
    get: (id: string) =>
      request<ServiceApiView>(`/api/service-api/${id}`),
    create: (data: { name: string; description?: string; operator: string }) =>
      request<void>('/api/service-api', { method: 'POST', body: JSON.stringify(data) }),
    update: (id: string, data: { enabled?: boolean; description?: string; url?: string; base_url?: string; payload?: string; service_additional?: ServiceAdditionalView[]; type_additional?: TypeAdditionalView[]; operator: string }) =>
      request<void>(`/api/service-api/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id: string) =>
      request<void>(`/api/service-api/${id}`, { method: 'DELETE' }),
    updateFromSysApi: (id: string, url: string, operator: string) =>
      request<void>(`/api/service-api/${id}/update-from-sys-api`, { method: 'PUT', body: JSON.stringify({ url, operator }) }),
    updateAllFromSysApi: (operator: string) =>
      request<void>('/api/service-api/update-all-from-sys-api', { method: 'PUT', body: JSON.stringify({ operator }) }),
    enable: (id: string, operator: string) =>
      request<void>(`/api/service-api/${id}`, { method: 'PUT', body: JSON.stringify({ enabled: true, operator }) }),
    disable: (id: string, operator: string) =>
      request<void>(`/api/service-api/${id}`, { method: 'PUT', body: JSON.stringify({ enabled: false, operator }) }),
  },
  schedules: {
    list: () =>
      request<ListSchedulesResponse>('/api/schedules'),
    listByAgent: (agentId: string) =>
      request<ListSchedulesResponse>(`/api/schedules/agent/${agentId}/list`),
    create: (data: CreateScheduleRequest) =>
      request<AgentScheduleView>('/api/schedules', { method: 'POST', body: JSON.stringify(data) }),
    update: (id: string, data: UpdateScheduleRequest) =>
      request<AgentScheduleView>(`/api/schedules/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id: string) =>
      request<void>(`/api/schedules/${id}`, { method: 'DELETE' }),
  },
  triggers: {
    list: (type?: string) => {
      const params = type ? `?type=${type}` : '';
      return request<ListTriggersResponse>(`/api/triggers${params}`);
    },
    get: (id: string) =>
      request<TriggerView>(`/api/triggers/${id}`),
    create: (data: CreateTriggerRequest) =>
      request<TriggerView>('/api/triggers', { method: 'POST', body: JSON.stringify(data) }),
    update: (id: string, data: UpdateTriggerRequest) =>
      request<TriggerView>(`/api/triggers/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id: string) =>
      request<void>(`/api/triggers/${id}`, { method: 'DELETE' }),
    enable: (id: string) =>
      request<TriggerView>(`/api/triggers/${id}/enable`, { method: 'POST' }),
    disable: (id: string) =>
      request<TriggerView>(`/api/triggers/${id}/disable`, { method: 'POST' }),
    rotateSecret: (id: string) =>
      request<TriggerView>(`/api/triggers/${id}/rotate-secret`, { method: 'POST' }),
  },
  channels: {
    list: () =>
      request<ListChannelsResponse>('/api/admin/channels'),
    get: (id: string) =>
      request<ChannelResponse>(`/api/admin/channels/${id}`),
    create: (data: Record<string, unknown>) =>
      request<ChannelResponse>('/api/admin/channels', { method: 'POST', body: JSON.stringify(data) }),
    update: (id: string, data: Record<string, unknown>) =>
      request<ChannelResponse>(`/api/admin/channels/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id: string) =>
      request<void>(`/api/admin/channels/${id}`, { method: 'DELETE' }),
    types: () =>
      request<ChannelTypesResponse>('/api/admin/channel-types'),
  },
  datasets: {
    list: (q?: string, offset?: number, limit?: number) => {
      const params = new URLSearchParams();
      if (q) params.set('q', q);
      if (offset !== undefined) params.set('offset', String(offset));
      if (limit !== undefined) params.set('limit', String(limit));
      return request<ListDatasetsResponse>(`/api/datasets${params.toString() ? `?${params}` : ''}`);
    },
    get: (id: string) =>
      request<DatasetView>(`/api/datasets/${id}`),
    create: (data: CreateDatasetRequest) =>
      request<DatasetView>('/api/datasets', { method: 'POST', body: JSON.stringify(data) }),
    update: (id: string, data: UpdateDatasetRequest) =>
      request<DatasetView>(`/api/datasets/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id: string) =>
      request<void>(`/api/datasets/${id}`, { method: 'DELETE' }),
    records: (id: string, filters?: DatasetRecordFilter) => {
      const params = new URLSearchParams();
      if (filters) {
        Object.entries(filters).forEach(([k, v]) => { if (v !== undefined && v !== null) params.set(k, String(v)); });
      }
      const qs = params.toString();
      return request<ListDatasetRecordsResponse>(`/api/datasets/${id}/records${qs ? `?${qs}` : ''}`);
    },
  },
  forYou: {
    dashboard: () =>
      request<ForYouDashboard>('/api/for-you'),
    listReports: () =>
      request<{ reports: ForYouReport[] }>('/api/for-you/reports'),
    createReport: (data: { title: string; content?: string; type?: string; tags?: string[] }) =>
      request<{ report: ForYouReport }>('/api/for-you/reports', { method: 'POST', body: JSON.stringify(data) }),
    updateReport: (id: string, data: { title?: string; content?: string; type?: string; tags?: string[] }) =>
      request<{ report: ForYouReport }>(`/api/for-you/reports/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteReport: (id: string) =>
      request<{ deleted: boolean }>(`/api/for-you/reports/${id}`, { method: 'DELETE' }),
    listTodos: () =>
      request<{ todos: ForYouTodo[] }>('/api/for-you/todos'),
    createTodo: (data: { title: string; description?: string; priority?: string; due_date?: string }) =>
      request<{ todo: ForYouTodo }>('/api/for-you/todos', { method: 'POST', body: JSON.stringify(data) }),
    updateTodo: (id: string, data: { title?: string; description?: string; completed?: boolean; priority?: string; due_date?: string }) =>
      request<{ todo: ForYouTodo }>(`/api/for-you/todos/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteTodo: (id: string) =>
      request<{ deleted: boolean }>(`/api/for-you/todos/${id}`, { method: 'DELETE' }),
    listFiles: () =>
      request<{ files: ForYouFile[] }>('/api/for-you/files'),
    tokenUsage: (range?: string, from?: string, to?: string) => {
        const params = new URLSearchParams();
        if (from && to) {
          params.set('from', from);
          params.set('to', to);
        } else if (range) {
          params.set('range', range);
        } else {
          params.set('range', '7d');
        }
        return request<ForYouTokenUsage>(`/api/for-you/token-usage?${params.toString()}`);
      },
  },
  artifacts: {
    listMy: (offset = 0, limit = 20) => {
      const params = new URLSearchParams({ offset: String(offset), limit: String(limit) });
      return request<ListMyArtifactsResponse>(`/api/artifacts/my?${params}`);
    },
    listShared: (offset = 0, limit = 20, name?: string, userId?: string) => {
      const params = new URLSearchParams({ offset: String(offset), limit: String(limit) });
      if (name) params.set('name', name);
      if (userId) params.set('user_id', userId);
      return request<ListSharedArtifactsResponse>(`/api/artifacts/shared?${params}`);
    },
  },
};

export interface TriggerView {
  id: string;
  name: string;
  description: string;
  type: string;
  enabled: boolean;
  config: Record<string, string>;
  webhook_url?: string;
  action_type: string;
  action_config: Record<string, string>;
  last_triggered_at: string;
  created_at: string;
  updated_at: string;
}

export interface ListTriggersResponse {
  triggers: TriggerView[];
  total: number;
}

export interface CreateTriggerRequest {
  name: string;
  description?: string;
  type: string;
  config?: Record<string, string>;
  action_type: string;
  action_config?: Record<string, string>;
}

export interface UpdateTriggerRequest {
  name?: string;
  description?: string;
  enabled?: boolean;
  config?: Record<string, string>;
  action_type?: string;
  action_config?: Record<string, string>;
}

export interface SchemaFieldView {
  name: string;
  type: string;
  label: string;
}

export interface DatasetView {
  id: string;
  name: string;
  description: string;
  schema: SchemaFieldView[];
  created_at: string;
  created_by: string;
  updated_at: string;
}

export interface ListDatasetsResponse {
  datasets: DatasetView[];
  total: number;
}

export interface CreateDatasetRequest {
  name: string;
  description?: string;
  schema: SchemaFieldView[];
}

export interface UpdateDatasetRequest {
  name?: string;
  description?: string;
  schema?: SchemaFieldView[];
}

export interface DatasetRecordView {
  id: string;
  run_id: string;
  agent_id: string;
  run_started_at: string;
  data: string;
  user_id: string;
  created_by: string;
  created_at: string;
  updated_at: string;
  updated_by: string;
}

export interface ListDatasetRecordsResponse {
  records: DatasetRecordView[];
  total: number;
}

export interface DatasetRecordFilter {
  from?: string;
  to?: string;
  fields?: string;
  limit?: number;
  offset?: number;
  agent_id?: string;
}

// --- For You types ---

export interface ForYouDashboard {
  report_count: number;
  todo_count: number;
  active_todo_count: number;
  file_count: number;
  recent_sessions: ForYouSession[];
  recent_reports: ForYouReport[];
  active_todos: ForYouTodo[];
  recent_files: ForYouFile[];
}

export interface ForYouSession {
  id: string;
  user_id: string;
  agent_id: string;
  source: string;
  title: string;
  message_count: number;
  created_at: string;
  last_message_at: string | null;
}

export interface ForYouReport {
  id: string;
  user_id: string;
  title: string;
  content: string | null;
  type: string;
  tags: string[];
  created_at: string;
  updated_at: string;
}

export interface ForYouTodo {
  id: string;
  user_id: string;
  title: string;
  description: string;
  completed: boolean;
  priority: string;
  due_date: string | null;
  created_at: string;
  updated_at: string;
}

export interface ForYouFile {
  id: string;
  user_id: string;
  file_name: string;
  content_type: string;
  size: number;
  created_at: string;
}

export interface ForYouTokenUsage {
  total_input_tokens: number;
  total_output_tokens: number;
  total_tokens: number;
  total_cached_tokens: number;
  total_cost_usd: number;
  daily: DailyTokenUsage[];
}

export interface DailyTokenUsage {
  date: string;
  input_tokens: number;
  output_tokens: number;
  total_tokens: number;
  cached_tokens: number;
  cost_usd: number;
}

// --- Artifacts ---

export interface MyArtifactView {
  id: string;
  file_name: string;
  content_type: string;
  size: number;
  created_at: string;
  session_id?: string;
  session_title?: string;
}

export interface ListMyArtifactsResponse {
  total: number;
  artifacts: MyArtifactView[];
}

export interface SharedArtifactView {
  id: string;
  file_name: string;
  content_type: string;
  size: number;
  user_id: string;
  created_at: string;
  shared_at: string;
}

export interface ListSharedArtifactsResponse {
  total: number;
  artifacts: SharedArtifactView[];
}

// Channel types

export interface ChannelView {
  channelId: string;
  channelType: string;
  enabled: boolean;
  requireAuth: boolean;
  agentId: string;
  userId: string | null;
  sessionTtlMinutes: number;
  webhookUrl: string;
  config: Record<string, string>;
  filterConfig: Record<string, string> | null;
}

export interface ChannelResponse {
  channel: ChannelView;
}

export interface ListChannelsResponse {
  channels: ChannelView[];
}

export interface ChannelTypeInfo {
  type: string;
  label: string;
}

export interface ChannelTypesResponse {
  types: ChannelTypeInfo[];
}
