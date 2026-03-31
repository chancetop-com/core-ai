const BASE = '';

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  const text = await res.text();
  return text ? JSON.parse(text) : (undefined as T);
}

export interface Trace {
  id: string;
  traceId: string;
  name: string;
  sessionId: string;
  userId: string;
  status: 'RUNNING' | 'COMPLETED' | 'ERROR';
  input: string;
  output: string;
  metadata: Record<string, string>;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  durationMs: number;
  startedAt: string;
  completedAt: string;
  createdAt: string;
}

export interface Span {
  id: string;
  traceId: string;
  spanId: string;
  parentSpanId: string | null;
  name: string;
  type: 'LLM' | 'AGENT' | 'TOOL' | 'FLOW' | 'GROUP';
  model: string;
  input: string;
  output: string;
  inputTokens: number;
  outputTokens: number;
  durationMs: number;
  status: 'OK' | 'ERROR';
  attributes: Record<string, string>;
  startedAt: string;
  completedAt: string;
}

export interface TraceFilter {
  name?: string;
  status?: string;
  sessionId?: string;
  userId?: string;
  startFrom?: string;
  startTo?: string;
}

export interface SessionSummary {
  session_id: string;
  trace_count: number;
  total_tokens: number;
  total_duration_ms: number;
  error_count: number;
  user_id: string;
  last_trace_at: string;
  first_trace_at: string;
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

export interface AgentDefinition {
  id: string;
  name: string;
  description: string;
  system_prompt: string;
  system_prompt_id: string;
  model: string;
  temperature: number;
  max_turns: number;
  timeout_seconds: number;
  tool_ids: string[];
  input_template: string;
  variables: Record<string, string>;
  webhook_secret: string;
  system_default: boolean;
  type: string;
  created_by: string;
  status: string;
  published_at: string;
  created_at: string;
  updated_at: string;
}

export interface ListAgentsResponse {
  agents: AgentDefinition[];
  total: number;
}

export interface TriggerRunResponse {
  run_id: string;
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

export const api = {
  traces: {
    list: (offset = 0, limit = 20, filters?: TraceFilter) => {
      const params = new URLSearchParams({ offset: String(offset), limit: String(limit) });
      if (filters) {
        Object.entries(filters).forEach(([k, v]) => { if (v) params.set(k, v); });
      }
      return request<Trace[]>(`/api/traces?${params}`);
    },
    get: (id: string) => request<Trace>(`/api/traces/${id}`),
    spans: (id: string) => request<Span[]>(`/api/traces/${id}/spans`),
    generations: (offset = 0, limit = 20, model?: string) => {
      const params = new URLSearchParams({ offset: String(offset), limit: String(limit) });
      if (model) params.set('model', model);
      return request<Span[]>(`/api/traces/generations?${params}`);
    },
    sessions: (offset = 0, limit = 20) =>
      request<SessionSummary[]>(`/api/traces/sessions?offset=${offset}&limit=${limit}`),
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
    list: () =>
      request<ListAgentsResponse>('/api/agents'),
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
};
