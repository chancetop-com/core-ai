const BASE = '';

export interface AgentCard {
  name: string;
  description: string;
  version: string;
  capabilities: { streaming: boolean; pushNotifications: boolean };
  skills: { name: string; description: string }[];
  defaultInputModes: string[];
  defaultOutputModes: string[];
}

export interface Part {
  type: 'text' | 'file' | 'data';
  text?: string;
  file?: { name: string; mimeType: string; uri: string };
  data?: Record<string, unknown>;
  metadata?: Record<string, string>;
}

export interface Message {
  role: 'user' | 'agent';
  parts: Part[];
  messageId?: string;
  taskId?: string;
}

export interface TaskStatus {
  state: 'submitted' | 'working' | 'input-required' | 'completed' | 'canceled' | 'failed';
  message?: Message;
}

export interface Artifact {
  name?: string;
  parts: Part[];
}

export interface Task {
  id: string;
  contextId?: string;
  status: TaskStatus;
  artifacts?: Artifact[];
  history?: Message[];
}

export interface StreamEvent {
  type: 'status' | 'artifact';
  taskId: string;
  status?: TaskStatus;
  artifact?: Artifact;
  metadata?: Record<string, string>;
}

export const a2aApi = {
  agentCard: async (): Promise<AgentCard> => {
    const res = await fetch(`${BASE}/.well-known/agent-card.json`);
    return res.json();
  },

  sendMessage: async (text: string): Promise<Task> => {
    const res = await fetch(`${BASE}/message/send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: { role: 'user', parts: [{ type: 'text', text }] },
      }),
    });
    return res.json();
  },

  sendMessageStream: async (
    text: string,
    onEvent: (event: StreamEvent) => void,
    onDone: () => void,
  ): Promise<void> => {
    const res = await fetch(`${BASE}/message/send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({
        message: { role: 'user', parts: [{ type: 'text', text }] },
      }),
    });

    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
    if (!res.body) throw new Error('No response body');

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
        if (line.startsWith('data: ')) {
          try {
            const event = JSON.parse(line.slice(6)) as StreamEvent;
            onEvent(event);
          } catch {
            // skip malformed events
          }
        }
      }
    }
    onDone();
  },

  getTask: async (taskId: string): Promise<Task> => {
    const res = await fetch(`${BASE}/tasks/${taskId}`);
    return res.json();
  },

  resumeTask: async (taskId: string, decision: 'approve' | 'deny', callId: string): Promise<void> => {
    await fetch(`${BASE}/tasks/${taskId}/message/send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: {
          role: 'user',
          parts: [{ type: 'data', data: { decision, call_id: callId } }],
        },
      }),
    });
  },

  cancelTask: async (taskId: string): Promise<void> => {
    await fetch(`${BASE}/tasks/${taskId}/cancel`, { method: 'POST' });
  },
};
