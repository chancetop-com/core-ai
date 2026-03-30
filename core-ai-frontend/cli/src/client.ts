import type { A2AEvent, AgentCard, SessionInfo } from './types.js';

export class A2AClient {
  constructor(public readonly baseUrl: string) {}

  async getAgentCard(): Promise<AgentCard> {
    const res = await fetch(`${this.baseUrl}/.well-known/agent-card.json`);
    if (!res.ok) throw new Error(`agent-card fetch failed: ${res.status}`);
    return res.json();
  }

  async sendMessageStream(
    text: string,
    onEvent: (event: A2AEvent) => void | Promise<void>,
  ): Promise<string | null> {
    const res = await fetch(`${this.baseUrl}/message/send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ message: { role: 'user', parts: [{ type: 'text', text }] } }),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
    return this.readSseStream(res.body!, onEvent);
  }

  private async readSseStream(
    body: ReadableStream<Uint8Array>,
    onEvent: (event: A2AEvent) => void | Promise<void>,
  ): Promise<string | null> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let taskId: string | null = null;

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const nlIdx = buffer.lastIndexOf('\n');
        if (nlIdx === -1) continue;
        const chunk = buffer.slice(0, nlIdx + 1);
        buffer = buffer.slice(nlIdx + 1);
        for (const line of chunk.split('\n')) {
          if (!line.startsWith('data: ')) continue;
          const raw = line.slice(6).trim();
          if (!raw) continue;
          let event: A2AEvent;
          try { event = JSON.parse(raw); } catch { continue; }
          if (!taskId && event.taskId) taskId = event.taskId;
          await onEvent(event);
        }
      }
    } finally {
      reader.cancel().catch(() => {});
    }
    return taskId;
  }

  async approve(taskId: string, decision: string, callId?: string): Promise<void> {
    const parts = callId
      ? [{ type: 'data', data: { decision, call_id: callId } }]
      : [{ type: 'text', text: decision }];
    await fetch(`${this.baseUrl}/tasks/${encodeURIComponent(taskId)}/message/send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: { role: 'user', parts } }),
    });
  }

  async cancel(taskId: string): Promise<void> {
    await fetch(`${this.baseUrl}/tasks/${encodeURIComponent(taskId)}/cancel`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });
  }

  async getSessions(): Promise<SessionInfo[]> {
    const res = await fetch(`${this.baseUrl}/api/sessions`);
    if (!res.ok) return [];
    return res.json();
  }
}
