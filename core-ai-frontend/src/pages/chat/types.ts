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

export type MessageSegment = TextSegment | ThinkingSegment | ToolsSegment;

export interface ChatMessage {
  role: 'user' | 'agent';
  segments: MessageSegment[];
  approval?: AwaitInfo;
}
