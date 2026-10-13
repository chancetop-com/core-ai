import type { ToolRef } from './session';

function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const apiKey = localStorage.getItem('apiKey');
  if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`;
  return headers;
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(url, { headers: getAuthHeaders(), ...options });
  const text = await res.text();
  if (!res.ok) {
    const detail = text ? `: ${text}` : '';
    throw new Error(`${res.status} ${res.statusText}${detail}`);
  }
  return text ? JSON.parse(text) : (undefined as T);
}

/** Editable execution config of one comparison variant (mirrors the server view; null field = keep on PATCH). */
export interface ComparisonVariantConfig {
  system_prompt?: string;
  system_prompt_id?: string;
  model?: string;
  multi_modal_model?: string;
  prefer_caption_path?: boolean;
  temperature?: number;
  thinking_effort?: string;
  max_turns?: number;
  timeout_seconds?: number;
  tools?: ToolRef[];
  skill_ids?: string[];
  subagent_ids?: string[];
  input_template?: string;
  variables?: Record<string, string>;
  response_schema?: string;
  enable_memory?: boolean;
  sandbox_config?: Record<string, unknown>;
  dataset_config?: Record<string, unknown>[];
}

export interface ComparisonVariant {
  variant: string;
  label: string;
  model?: string;
  summary?: string;
  effective_tools?: ToolRef[];
  config?: ComparisonVariantConfig;
}

export interface ComparisonVote {
  id: string;
  comparison_id: string;
  pair_id?: string;
  session_a: string;
  session_b?: string;
  turn: number;
  chosen: 'A' | 'B' | 'TIE' | 'NEITHER' | string;
  comment?: string;
  message_a?: string;
  message_b?: string;
  user_id: string;
  created_at?: string;
}

export interface ComparisonSessionPair {
  pair_id: string;
  session_a?: string;
  session_b?: string;
  user_id?: string;
  turn_count?: number;
  vote_count?: number;
  created_at?: string;
}

export interface ComparisonCounts {
  votes_a?: number;
  votes_b?: number;
  ties?: number;
  neithers?: number;
  votes?: number;
  turns?: number;
}

export interface AgentComparison {
  id: string;
  agent_id: string;
  agent_name?: string;
  status: 'DRAFT' | 'RUNNING' | 'ENDED' | string;
  created_by?: string;
  participant_user_ids?: string[];
  blind?: boolean;
  variant_a?: ComparisonVariant;
  variant_b?: ComparisonVariant;
  /** source saved versions of each side; null = current published (A) resp. a copy of A (B) */
  version_a_id?: string;
  version_b_id?: string;
  pairs?: ComparisonSessionPair[];
  votes?: ComparisonVote[];
  counts?: ComparisonCounts;
  budget_usd?: number;
  agent_changed?: boolean;
  started_at?: string;
  ended_at?: string;
  adopted_variant?: string;
  created_at?: string;
  updated_at?: string;
}

/** A saved agent configuration ("version") — the selectable side of an experiment. */
export interface AgentVersion {
  id: string;
  agent_id: string;
  agent_name?: string;
  label?: string;
  model?: string;
  config?: ComparisonVariantConfig;
  created_by?: string;
  created_at?: string;
  updated_at?: string;
}

/** Comparison metadata for one session of a pair; comparison_id is null for a regular session. */
export interface ComparisonSessionContext {
  comparison_id?: string;
  pair_id?: string;
  agent_id?: string;
  agent_name?: string;
  status?: string;
  variant?: string;
  primary_session_id?: string;
  secondary_session_id?: string;
  blind?: boolean;
  budget_usd?: number;
  votes?: ComparisonVote[];
}

export interface CreateComparisonSessionsResponse {
  comparison_id: string;
  pair_id: string;
  primary_session_id: string;
  secondary_session_id: string;
}

export interface UpdateAgentComparisonRequest {
  variant_b?: ComparisonVariantConfig;
  /** switches the source version; '' clears it (A: current published, B: a copy of A) */
  version_a_id?: string;
  version_b_id?: string;
  participant_user_ids?: string[];
  budget_usd?: number;
  effective_tools_a?: ToolRef[];
  effective_tools_b?: ToolRef[];
}

export interface ComparisonStats {
  counts?: ComparisonCounts;
  votes?: ComparisonVote[];
  pairs?: ComparisonSessionPair[];
}

export const comparisonApi = {
  create: (agentId: string, options?: { versionAId?: string | null; versionBId?: string | null }) =>
    request<AgentComparison>('/api/agent-comparisons', {
      method: 'POST',
      body: JSON.stringify({
        agent_id: agentId,
        version_a_id: options?.versionAId ?? null,
        version_b_id: options?.versionBId ?? null,
      }),
    }),

  /** Without agentId: every comparison the caller created or takes part in (admins see all). */
  list: (agentId?: string) =>
    request<{ comparisons: AgentComparison[] }>(
      agentId ? `/api/agent-comparisons?agent_id=${encodeURIComponent(agentId)}` : '/api/agent-comparisons',
    ),

  running: (agentId: string) =>
    request<AgentComparison>(`/api/agent-comparisons/agent/${encodeURIComponent(agentId)}/running`),

  get: (id: string) => request<AgentComparison>(`/api/agent-comparisons/${encodeURIComponent(id)}`),

  update: (id: string, body: UpdateAgentComparisonRequest) =>
    request<AgentComparison>(`/api/agent-comparisons/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),

  start: (id: string, body?: { effective_tools_a?: ToolRef[]; effective_tools_b?: ToolRef[] }) =>
    request<AgentComparison>(`/api/agent-comparisons/${encodeURIComponent(id)}/start`, {
      method: 'POST',
      body: JSON.stringify(body || {}),
    }),

  createSessions: (id: string) =>
    request<CreateComparisonSessionsResponse>(`/api/agent-comparisons/${encodeURIComponent(id)}/sessions`, {
      method: 'POST',
    }),

  bySession: (sessionId: string) =>
    request<ComparisonSessionContext>(`/api/agent-comparisons/by-session/${encodeURIComponent(sessionId)}`),

  vote: (
    id: string,
    body: { session_a: string; turn: number; chosen: string; comment?: string; message_a?: string; message_b?: string },
  ) =>
    request<ComparisonVote>(`/api/agent-comparisons/${encodeURIComponent(id)}/votes`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  end: (id: string, body: { adopt?: 'a' | 'b' | null; force?: boolean }) =>
    request<AgentComparison>(`/api/agent-comparisons/${encodeURIComponent(id)}/end`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  remove: (id: string) =>
    request<void>(`/api/agent-comparisons/${encodeURIComponent(id)}`, { method: 'DELETE' }),
};

export const agentVersionApi = {
  list: (agentId: string) =>
    request<{ versions: AgentVersion[] }>(`/api/agent-versions?agent_id=${encodeURIComponent(agentId)}`),

  get: (id: string) => request<AgentVersion>(`/api/agent-versions/${encodeURIComponent(id)}`),

  create: (body: { agent_id: string; label?: string; copy_from_version_id?: string }) =>
    request<AgentVersion>('/api/agent-versions', { method: 'POST', body: JSON.stringify(body) }),

  update: (id: string, body: { label?: string; config?: ComparisonVariantConfig }) =>
    request<AgentVersion>(`/api/agent-versions/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),

  remove: (id: string) =>
    request<void>(`/api/agent-versions/${encodeURIComponent(id)}`, { method: 'DELETE' }),
};
