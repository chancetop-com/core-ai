export interface AwaitInfo {
  callId: string;
  tool: string;
  arguments: string;
}

export interface ToolEvent {
  type: 'start' | 'result';
  tool: string;
  callId: string;
  arguments?: string;
  result?: string;
  resultStatus?: string;
  taskId?: string;
  runInBackground?: boolean;
  toolType?: string;
  children?: ToolEvent[];
}

export interface PlanTodo {
  content: string;
  status: string;
}

export interface TextSegment {
  type: 'text';
  content: string;
}

export interface ThinkingSegment {
  type: 'thinking';
  content: string;
}

export interface ToolsSegment {
  type: 'tools';
  tools: ToolEvent[];
}

export interface SandboxSegment {
  type: 'sandbox';
  sandboxType: string;  // creating | ready | error | replacing | terminated
  sandboxId: string;
  message: string;
  hostname?: string;
  ip?: string;
  image?: string;
  durationMs?: number;
}

export type MessageSegment = TextSegment | ThinkingSegment | ToolsSegment | SandboxSegment;

export interface ChatAttachment {
  url: string;
  type: 'IMAGE' | 'PDF';
}

export interface ChatMessage {
  role: 'user' | 'agent';
  segments: MessageSegment[];
  attachments?: ChatAttachment[];
  approval?: AwaitInfo;
  timestamp?: string;
}
