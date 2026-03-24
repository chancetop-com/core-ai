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
  trace_id: string;
  name: string;
  session_id: string;
  user_id: string;
  status: 'RUNNING' | 'COMPLETED' | 'ERROR';
  input: string;
  output: string;
  metadata: Record<string, string>;
  input_tokens: number;
  output_tokens: number;
  total_tokens: number;
  duration_ms: number;
  started_at: string;
  completed_at: string;
  created_at: string;
}

export interface Span {
  id: string;
  trace_id: string;
  span_id: string;
  parent_span_id: string | null;
  name: string;
  type: 'LLM' | 'AGENT' | 'TOOL' | 'FLOW' | 'GROUP';
  model: string;
  input: string;
  output: string;
  input_tokens: number;
  output_tokens: number;
  duration_ms: number;
  status: 'OK' | 'ERROR';
  attributes: Record<string, string>;
  started_at: string;
  completed_at: string;
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

export const api = {
  traces: {
    list: (offset = 0, limit = 20) =>
      request<Trace[]>(`/api/traces?offset=${offset}&limit=${limit}`),
    get: (id: string) => request<Trace>(`/api/traces/${id}`),
    spans: (id: string) => request<Span[]>(`/api/traces/${id}/spans`),
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
};
