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

export interface ChatMessage {
  role: 'user' | 'agent';
  content: string;
  thinking?: string;
  tools?: ToolEvent[];
  approval?: AwaitInfo;
}
