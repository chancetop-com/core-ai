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

export interface SseEvent {
  type: string;
  sessionId: string;
  data: string;
  timestamp: string;
}

export interface HistoryMessage {
  role: string;
  content: string;
  timestamp: string;
  metadata: Record<string, string>;
}

export const sessionApi = {
  create: (agentId: string) =>
    request<{ sessionId: string }>('/api/sessions', { method: 'POST', body: JSON.stringify({ agent_id: agentId }) }),

  sendMessage: (sessionId: string, message: string) =>
    request<void>(`/api/sessions/${sessionId}/messages`, { method: 'POST', body: JSON.stringify({ message }) }),

  approve: (sessionId: string, callId: string, decision: 'APPROVE' | 'DENY') =>
    request<void>(`/api/sessions/${sessionId}/approve`, { method: 'POST', body: JSON.stringify({ call_id: callId, decision }) }),

  cancel: (sessionId: string) =>
    request<void>(`/api/sessions/${sessionId}/cancel`, { method: 'POST' }),

  close: (sessionId: string) =>
    request<void>(`/api/sessions/${sessionId}`, { method: 'DELETE' }),

  history: (sessionId: string) =>
    request<{ messages: HistoryMessage[] }>(`/api/sessions/${sessionId}/history`),

  connectSSE: (
    sessionId: string,
    onEvent: (event: SseEvent) => void,
    onError?: (err: unknown) => void,
    onClose?: () => void,
  ): AbortController => {
    const controller = new AbortController();

    fetch(`${BASE}/api/sessions/events?sessionId=${sessionId}`, {
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
