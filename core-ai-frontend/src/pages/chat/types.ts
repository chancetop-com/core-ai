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
  output?: string;
  resultStatus?: string;
  taskId?: string;
  runInBackground?: boolean;
  toolType?: string;
  model?: string;
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
  historical?: boolean;
  hostname?: string;
  ip?: string;
  image?: string;
  durationMs?: number;
}

/**
 * A context compression that happened at the start of this turn. Recorded with the message so the
 * conversation still explains the shorter history after a reload, exactly like tool and sandbox blocks.
 */
export interface CompressionSegment {
  type: 'compression';
  before: number;
  after: number;
  contextTokens?: number;
  maxContextTokens?: number;
  triggerThreshold?: number;
}

export interface BackgroundTask {
  taskId: string;
  toolName?: string;
  status: string;
}

/**
 * Background work that finished while the session was idle. It is not part of any turn and is not
 * persisted with the message, so it disappears on reload — it exists to explain why the session
 * started talking again on its own.
 */
export interface TasksSegment {
  type: 'tasks';
  tasks: BackgroundTask[];
}

export interface SandboxTerminalSpec {
  sandboxId: string;
  sessionId: string;
  hostname?: string;
  ip?: string;
  image?: string;
}

export interface QuickReplyOption {
  label: string;
  value: string;
  description?: string;
}

/** A platform-renderable card block; fields beyond `type` are per block kind (see design-session-rich-cards.md). */
export interface CardBlock {
  type: string;
  [key: string]: unknown;
}

export interface RichCard {
  schema_version?: number;
  title?: string;
  blocks: CardBlock[];
}

/**
 * The platform-standard quick_replies event (present_choices tool): rendered as quick-reply buttons,
 * clicking one sends its value as the next user message.
 */
export interface QuickRepliesSegment {
  type: 'quick_replies';
  question?: string;
  options: QuickReplyOption[];
}

/** A custom event that carried the optional platform renderable card. */
export interface CardSegment {
  type: 'card';
  name?: string;
  card: RichCard;
}

export type MessageSegment = TextSegment | ThinkingSegment | ToolsSegment | SandboxSegment | TasksSegment | CompressionSegment | QuickRepliesSegment | CardSegment;

export interface ChatAttachment {
  url: string;
  type: 'IMAGE' | 'PDF' | 'FILE' | 'VIDEO';
  file_name?: string;
}

export interface ChatMessage {
  role: 'user' | 'agent';
  segments: MessageSegment[];
  attachments?: ChatAttachment[];
  approval?: AwaitInfo;
  timestamp?: string;
}
