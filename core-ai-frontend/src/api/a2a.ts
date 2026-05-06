const BASE = '';

export interface AgentCard {
  name: string;
  description: string;
  version: string;
  supportedInterfaces: { url?: string; protocolBinding: string; protocolVersion: string; tenant?: string }[];
  capabilities: { streaming?: boolean; pushNotifications?: boolean; extendedAgentCard?: boolean };
  skills: { name: string; description: string }[];
  defaultInputModes: string[];
  defaultOutputModes: string[];
}

export interface Part {
  text?: string;
  raw?: string;
  url?: string;
  data?: Record<string, unknown>;
  filename?: string;
  mediaType?: string;
  metadata?: Record<string, unknown>;
}

export interface Message {
  role: 'ROLE_USER' | 'ROLE_AGENT';
  parts: Part[];
  messageId?: string;
  taskId?: string;
  contextId?: string;
  referenceTaskIds?: string[];
  metadata?: Record<string, unknown>;
  extensions?: string[];
}

export interface TaskStatus {
  state:
    | 'TASK_STATE_UNSPECIFIED'
    | 'TASK_STATE_SUBMITTED'
    | 'TASK_STATE_WORKING'
    | 'TASK_STATE_COMPLETED'
    | 'TASK_STATE_FAILED'
    | 'TASK_STATE_CANCELED'
    | 'TASK_STATE_INPUT_REQUIRED'
    | 'TASK_STATE_REJECTED'
    | 'TASK_STATE_AUTH_REQUIRED';
  message?: Message;
  timestamp?: string;
}

export interface Artifact {
  artifactId: string;
  name?: string;
  description?: string;
  parts: Part[];
  metadata?: Record<string, unknown>;
  extensions?: string[];
}

export interface Task {
  id: string;
  contextId?: string;
  status: TaskStatus;
  artifacts?: Artifact[];
  history?: Message[];
}

export interface SessionItem {
  id: string;
  time: string;
  firstMessage: string;
  isCurrent: boolean;
}

export interface SessionMessage {
  role: 'user' | 'agent';
  content: string;
}

export interface SendMessageResponse {
  task?: Task;
  message?: Message;
}

export interface TaskStatusUpdateEvent {
  taskId: string;
  contextId: string;
  status: TaskStatus;
  metadata?: Record<string, unknown>;
}

export interface TaskArtifactUpdateEvent {
  taskId: string;
  contextId: string;
  artifact: Artifact;
  append?: boolean;
  lastChunk?: boolean;
  metadata?: Record<string, unknown>;
}

export interface StreamEvent {
  task?: Task;
  message?: Message;
  statusUpdate?: TaskStatusUpdateEvent;
  artifactUpdate?: TaskArtifactUpdateEvent;
}

export const a2aApi = {
  agentCard: async (): Promise<AgentCard> => {
    const res = await fetch(`${BASE}/.well-known/agent-card.json`, { headers: { 'A2A-Version': '1.0' } });
    return res.json();
  },

  sendMessage: async (text: string): Promise<Task> => {
    const res = await fetch(`${BASE}/message:send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/a2a+json', 'A2A-Version': '1.0' },
      body: JSON.stringify({
        message: { role: 'ROLE_USER', messageId: crypto.randomUUID(), parts: [{ text, mediaType: 'text/plain' }] },
      }),
    });
    const body = (await res.json()) as SendMessageResponse;
    if (!body.task) throw new Error('SendMessage response did not contain a task');
    return body.task;
  },

  sendMessageStream: async (
    text: string,
    onEvent: (event: StreamEvent) => void,
    onDone: () => void,
  ): Promise<void> => {
    const res = await fetch(`${BASE}/message:stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/a2a+json', Accept: 'text/event-stream', 'A2A-Version': '1.0' },
      body: JSON.stringify({
        message: { role: 'ROLE_USER', messageId: crypto.randomUUID(), parts: [{ text, mediaType: 'text/plain' }] },
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
    const res = await fetch(`${BASE}/tasks/${taskId}`, { headers: { 'A2A-Version': '1.0' } });
    return res.json();
  },

  resumeTask: async (taskId: string, decision: 'approve' | 'deny', callId: string): Promise<void> => {
    await fetch(`${BASE}/message:send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/a2a+json', 'A2A-Version': '1.0' },
      body: JSON.stringify({
        message: {
          role: 'ROLE_USER',
          messageId: crypto.randomUUID(),
          taskId,
          parts: [{ data: { decision, call_id: callId }, mediaType: 'application/json' }],
        },
      }),
    });
  },

  cancelTask: async (taskId: string): Promise<void> => {
    await fetch(`${BASE}/tasks/${taskId}:cancel`, { method: 'POST', headers: { 'A2A-Version': '1.0' } });
  },

  listSessions: async (): Promise<SessionItem[]> => {
    const res = await fetch(`${BASE}/api/sessions`);
    return res.json();
  },

  getSessionMessages: async (sessionId: string): Promise<SessionMessage[]> => {
    const res = await fetch(`${BASE}/api/sessions/${sessionId}/messages`);
    return res.json();
  },
};
