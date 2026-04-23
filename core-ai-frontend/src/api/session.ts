const BASE = '';

function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const apiKey = localStorage.getItem('apiKey');
  if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`;
  return headers;
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, { headers: getAuthHeaders(), ...options });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  const text = await res.text();
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
}

export interface SseToolResultEvent extends SseBaseEvent {
  call_id: string;
  tool_name: string;
  status: string;
  result?: string;
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

export interface CreateSessionResponse {
  sessionId: string;
  loaded_tools?: string[];
  loaded_skills?: string[];
  loaded_sub_agents?: string[];
}

export interface LoadToolsResponse {
  loaded_tools: string[];
}

export interface LoadSkillsResponse {
  loaded_skills: string[];
}

export interface LoadSubAgentsResponse {
  loaded_sub_agents: string[];
}

export interface SessionInfo {
  id: string;
  agent_id: string;
  loaded_tools?: { id: string; type?: string; source?: string }[];
  loaded_skill_ids?: string[];
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
}

export const sessionApi = {
  create: (agentId: string, options?: CreateSessionOptions | Record<string, unknown>) => {
    // Support legacy call signature: create(agentId, config)
    let body: Record<string, unknown>;
    if (options && ('config' in options || 'tools' in options || 'skill_ids' in options)) {
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

  sendMessage: (sessionId: string, message: string, variables?: Record<string, string>) =>
    request<void>(`/api/sessions/${sessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ message, variables }),
    }),

  approve: (sessionId: string, callId: string, decision: 'APPROVE' | 'DENY') =>
    request<void>(`/api/sessions/${sessionId}/approve`, { method: 'POST', body: JSON.stringify({ call_id: callId, decision }) }),

  cancel: (sessionId: string) =>
    request<void>(`/api/sessions/${sessionId}/cancel`, { method: 'POST' }),

  close: (sessionId: string) =>
    request<void>(`/api/sessions/${sessionId}`, { method: 'DELETE' }),

  history: (sessionId: string) =>
    request<{ messages: HistoryMessage[] }>(`/api/sessions/${sessionId}/history`),

  listChatSessions: (offset = 0, limit = 50, sources?: string[]) => {
    const qs = [`offset=${offset}`, `limit=${limit}`];
    if (sources && sources.length > 0) qs.push(`sources=${sources.join(',')}`);
    return request<{ sessions: ChatSessionSummary[] }>(`/api/chat/sessions?${qs.join('&')}`);
  },

  getSession: (sessionId: string) =>
    request<ChatSessionSummary>(`/api/chat/sessions/${sessionId}`),

  deleteChatSession: (sessionId: string) =>
    request<{ deleted: boolean }>(`/api/chat/sessions/${sessionId}`, { method: 'DELETE' }),

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

  connectSSE: (
    sessionId: string,
    onEvent: (event: SseEvent) => void,
    onError?: (err: unknown) => void,
    onClose?: () => void,
  ): AbortController => {
    const controller = new AbortController();

    fetch(`${BASE}/api/sessions/events?agent-session-id=${sessionId}`, {
      method: 'PUT',
      headers: {
        ...getAuthHeaders(),
        'Accept': 'text/event-stream',
      },
      signal: controller.signal,
    }).then(async (res) => {
      if (!res.ok || !res.body) {
        onError?.(new Error(`SSE connection failed: ${res.status}`));
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
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
      onClose?.();
    }).catch((err) => {
      if (err.name !== 'AbortError') onError?.(err);
    });

    return controller;
  },
};
