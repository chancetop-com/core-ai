const BASE = '';

function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const apiKey = localStorage.getItem('apiKey');
  if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`;
  return headers;
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, { headers: getAuthHeaders(), ...options });
  const text = await res.text();
  if (!res.ok) {
    const detail = text ? `: ${text}` : '';
    throw new Error(`${res.status} ${res.statusText}${detail}`);
  }
  return text ? JSON.parse(text) : (undefined as T);
}

export interface SseBaseEvent {
  type: string;
  sessionId: string;
  timestamp: string;
}

export interface SseTextChunkEvent extends SseBaseEvent {
  content: string;
  is_final_chunk: boolean;
}

export interface SseReasoningChunkEvent extends SseBaseEvent {
  content: string;
  is_final_chunk: boolean;
}

export interface SseToolStartEvent extends SseBaseEvent {
  call_id: string;
  tool_name: string;
  tool_args?: Record<string, unknown>;
  tool_notes?: string;
  task_id?: string;
  run_in_background?: boolean;
  model?: string;
}

export interface SseToolResultEvent extends SseBaseEvent {
  call_id: string;
  tool_name: string;
  status: string;
  result?: string;
  tool_type?: string;
}

export interface SseToolApprovalRequestEvent extends SseBaseEvent {
  call_id: string;
  tool_name: string;
  arguments?: string;
  suggested_pattern?: string;
}

export interface SseTurnCompleteEvent extends SseBaseEvent {
  output?: string;
  cancelled?: boolean;
  max_turns_reached?: boolean;
  input_tokens?: number;
  output_tokens?: number;
}

export interface SseErrorEvent extends SseBaseEvent {
  message: string;
  detail?: string;
}

export interface SseStatusChangeEvent extends SseBaseEvent {
  status: string;
}

export interface SsePlanUpdateEvent extends SseBaseEvent {
  todos: Array<{ content: string; status: string }>;
}

export interface SseBatchToolStartEvent extends SseBaseEvent {
  group: string;
  tools: Array<{ call_id: string; tool_name: string; arguments: string }>;
  task_id?: string;
}

export interface SseEnvironmentOutputChunkEvent extends SseBaseEvent {
  source: string;
  call_id: string;
  chunk: string;
}

export interface SseCompressionEvent extends SseBaseEvent {
  before_count: number;
  after_count: number;
  completed: boolean;
}

export interface SseSandboxEvent extends SseBaseEvent {
  sandbox_id?: string;
  sandbox_type?: string;
  message?: string;
  duration_ms?: number;
  hostname?: string;
  ip?: string;
  image?: string;
}

export type SseEvent =
  | SseTextChunkEvent
  | SseReasoningChunkEvent
  | SseToolStartEvent
  | SseToolResultEvent
  | SseToolApprovalRequestEvent
  | SseTurnCompleteEvent
  | SseErrorEvent
  | SseStatusChangeEvent
  | SsePlanUpdateEvent
  | SseBatchToolStartEvent
  | SseEnvironmentOutputChunkEvent
  | SseCompressionEvent
  | SseSandboxEvent;

export interface HistoryToolCall {
  call_id: string;
  name: string;
  arguments?: string;
  result?: string;
  status?: string;
}

export interface ChatSessionSummary {
  id: string;
  user_id?: string;
  agent_id?: string;
  source?: string;          // chat | test | api | a2a | scheduled
  schedule_id?: string;
  api_key_id?: string;
  title?: string;
  message_count?: number;
  created_at?: string;
  last_message_at?: string;
}

export interface SessionArtifact {
  file_id: string;
  file_name: string;
  content_type?: string;
  size?: number;
  title?: string;
  description?: string;
}

export interface HistoryMessage {
  role: string;
  content: string;
  thinking?: string;
  tools?: HistoryToolCall[];
  seq?: number;
  trace_id?: string;
  timestamp?: string;
  metadata?: Record<string, string>;
}

export interface IdName {
  id: string;
  name: string;
}

export interface CreateSessionResponse {
  sessionId: string;
  loaded_tools?: IdName[];
  loaded_skills?: IdName[];
  loaded_sub_agents?: IdName[];
}

export interface LoadToolsResponse {
  loaded_tools: IdName[];
}

export interface LoadSkillsResponse {
  loaded_skills: IdName[];
}

export interface LoadSubAgentsResponse {
  loaded_sub_agents: IdName[];
}

export interface SessionInfo {
  id: string;
  agent_id: string;
  loaded_tools?: { id: string; type?: string; source?: string }[];
  loaded_skill_ids?: string[];
  loaded_sub_agent_ids?: string[];
}

export interface SessionStatusResponse {
  sessionId: string;
  status: 'idle' | 'running' | 'error';
  createdAt?: string;
  lastActiveAt?: string;
  messageCount?: number;
  estimatedTokens?: number;
}

export interface ToolRef {
  id: string;
  type?: string;
  source?: string;
}

export interface CreateSessionOptions {
  config?: Record<string, unknown>;
  tools?: ToolRef[];
  skill_ids?: string[];
  sub_agent_ids?: string[];
  dataset_configs?: { dataset_id: string; permission: string; is_output?: boolean }[];
}

export interface SessionFeedback {
  // Layer 1: Outcome
  outcome: 'COMPLETED' | 'PARTIAL' | 'FAILED';
  // Layer 2: Failure Reasons
  failure_reasons?: string[];
  failure_detail?: string;
  // Layer 3: Ratings (1-5)
  understanding_rating?: number;
  problem_solving_rating?: number;
  tool_usage_rating?: number;
  communication_rating?: number;
  outcome_rating?: number;
  // Layer 4: Work-style Fit
  proactivity_fit?: 'TOO_ACTIVE' | 'JUST_RIGHT' | 'TOO_CONSERVATIVE';
  decision_fit?: 'SHOULD_DECIDE' | 'SHOULD_ASK' | 'JUST_RIGHT';
  // Layer 5: Trust
  trust_level?: 'FULLY_TRUST' | 'MOSTLY_TRUST' | 'NEED_CONFIRM' | 'WOULD_NOT_USE';
  // Free text
  comment?: string;
  // Auto-collected
  model_id?: string;
  token_count?: number;
  session_duration_ms?: number;
  tool_call_count?: number;
  tool_error_count?: number;
  message_count?: number;
  source?: string;
}

export const sessionApi = {
  create: (agentId: string, options?: CreateSessionOptions | Record<string, unknown>) => {
    // Support legacy call signature: create(agentId, config)
    let body: Record<string, unknown>;
    if (options && ('config' in options || 'tools' in options || 'skill_ids' in options || 'sub_agent_ids' in options || 'dataset_configs' in options)) {
      const opts = options as CreateSessionOptions;
      body = agentId ? { agent_id: agentId, ...opts } : { ...opts };
    } else {
      body = agentId ? { agent_id: agentId, config: options } : { config: options };
    }
    return request<CreateSessionResponse>('/api/sessions', {
      method: 'POST',
      body: JSON.stringify(body),
    });
  },

  sendMessage: (sessionId: string, message: string, variables?: Record<string, string>, attachments?: { url: string; type: string; file_name?: string; category?: string; container?: string; blob_name?: string }[]) =>
    request<void>(`/api/sessions/${sessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ message, variables, attachments }),
    }),

  approve: (sessionId: string, callId: string, decision: 'APPROVE' | 'DENY') =>
    request<void>(`/api/sessions/${sessionId}/approve`, { method: 'POST', body: JSON.stringify({ call_id: callId, decision }) }),

  cancel: (sessionId: string) =>
    request<void>(`/api/sessions/${sessionId}/cancel`, { method: 'POST' }),

  close: (sessionId: string) =>
    request<void>(`/api/sessions/${sessionId}`, { method: 'DELETE' }),

  history: (sessionId: string) =>
    request<{ messages: HistoryMessage[]; artifacts?: SessionArtifact[] }>(`/api/sessions/${sessionId}/history`),

  listChatSessions: (offset = 0, limit = 50, sources?: string[]) => {
    const qs = [`offset=${offset}`, `limit=${limit}`];
    if (sources && sources.length > 0) qs.push(`sources=${sources.join(',')}`);
    return request<{ sessions: ChatSessionSummary[]; total: number }>(`/api/chat/sessions?${qs.join('&')}`);
  },

  getSession: (sessionId: string) =>
    request<ChatSessionSummary>(`/api/chat/sessions/${sessionId}`),

  deleteChatSession: (sessionId: string) =>
    request<{ deleted: boolean }>(`/api/chat/sessions/${sessionId}`, { method: 'DELETE' }),

  batchDeleteChatSessions: (sessionIds: string[]) =>
    request<{ deleted: number }>('/api/chat/sessions/batch-delete', {
      method: 'POST',
      body: JSON.stringify({ session_ids: sessionIds }),
    }),

  renameChatSession: (sessionId: string, title: string) =>
    request<{ updated: boolean }>(`/api/chat/sessions/${sessionId}`, {
      method: 'PUT',
      body: JSON.stringify({ title }),
    }),

  submitFeedback: (sessionId: string, feedback: SessionFeedback) =>
    request<{ id: string; created: boolean }>(`/api/chat/sessions/${sessionId}/feedback`, {
      method: 'POST',
      body: JSON.stringify(feedback),
    }),

  loadTools: (sessionId: string, tools: ToolRef[]) =>
    request<LoadToolsResponse>(`/api/sessions/${sessionId}/tools`, {
      method: 'POST',
      body: JSON.stringify({ tools }),
    }),

  loadSkills: (sessionId: string, skillIds: string[]) =>
    request<LoadSkillsResponse>(`/api/sessions/${sessionId}/skills`, {
      method: 'POST',
      body: JSON.stringify({ skill_ids: skillIds }),
    }),

  loadSubAgents: (sessionId: string, agentIds: string[]) =>
    request<LoadSubAgentsResponse>(`/api/sessions/${sessionId}/subagents`, {
      method: 'POST',
      body: JSON.stringify({ agent_ids: agentIds }),
    }),

  getInfo: (sessionId: string) =>
    request<SessionInfo>(`/api/sessions/${sessionId}`),

  status: (sessionId: string) =>
    request<SessionStatusResponse>(`/api/sessions/${sessionId}/status`),

  connectSSE: (
    sessionId: string,
    onEvent: (event: SseEvent) => void,
    onError?: (err: unknown) => void,
    onClose?: () => void,
  ): AbortController => {
    const controller = new AbortController();
    const url = `${BASE}/api/sessions/events?agent-session-id=${sessionId}`;

    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url, true);
    const authHeaders = getAuthHeaders();
    for (const [key, value] of Object.entries(authHeaders)) {
      xhr.setRequestHeader(key, value);
    }
    xhr.setRequestHeader('Accept', 'text/event-stream');

    let lastIndex = 0;
    let buffer = '';

    xhr.onreadystatechange = () => {
      if (xhr.readyState === xhr.HEADERS_RECEIVED || xhr.readyState === xhr.LOADING) {
        const newText = xhr.responseText.substring(lastIndex);
        lastIndex = xhr.responseText.length;
        if (!newText) return;

        buffer += newText;
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim();
            if (data) {
              try {
                const event = JSON.parse(data) as SseEvent;
                onEvent(event);
              } catch {
                // ignore
              }
            }
          }
        }
      }
      if (xhr.readyState === xhr.DONE) {
        if (xhr.status >= 400) {
          onError?.(new Error(`SSE connection failed: ${xhr.status}`));
        }
        onClose?.();
      }
    };

    xhr.onerror = () => {
      onError?.(new Error('SSE connection error'));
    };

    controller.signal.addEventListener('abort', () => xhr.abort());

    xhr.send();

    return controller;
  },
};
