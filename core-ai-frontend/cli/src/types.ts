// History item types
export type HistoryItemType =
  | 'user'
  | 'assistant'
  | 'thinking'
  | 'tool_start'
  | 'tool_result'
  | 'info'
  | 'warning'
  | 'error'
  | 'system'
  | 'help'
  | 'stats'
  | 'tools_list'
  | 'mcp_status';

export interface HistoryItem {
  id: string;
  type: HistoryItemType;
  content: string;
  // tool fields
  toolName?: string;
  toolArgs?: string;
  callId?: string;
  resultStatus?: string;
  // thinking
  isStreaming?: boolean;
}

export type StreamingState = 'idle' | 'responding' | 'waiting_approval';

export interface ApprovalRequest {
  taskId: string;
  callId: string;
  tool: string;
  args: string;
}

export interface SessionInfo {
  id: string;
  time: string;
  firstMessage: string;
  isCurrent: boolean;
}

export interface AgentCard {
  name: string;
  description: string;
  version: string;
  capabilities: { streaming: boolean };
}

// SSE event from A2A backend
export interface A2AEvent {
  type: 'status' | 'artifact';
  taskId: string;
  status?: {
    state: string;
    message?: { role: string; parts: Array<{ type: string; text?: string }> };
  };
  artifact?: { parts: Array<{ type: string; text?: string }> };
  metadata?: Record<string, string>;
}

export interface UIState {
  history: HistoryItem[];
  pendingItems: HistoryItem[];
  streamingState: StreamingState;
  currentTaskId: string | null;
  isInputActive: boolean;
  pendingApproval: ApprovalRequest | null;
  modelName: string;
  agentName: string;
  turnCount: number;
  totalTokens: number;
  inputTokens: number;
  outputTokens: number;
  activeDialog: string | null;
  terminalWidth: number;
  lastAssistantText: string;
  thought: string;
  elapsedMs: number;
}

export interface UIActions {
  sendMessage: (text: string) => void;
  cancelTurn: () => void;
  approveToolCall: (decision: 'approve' | 'deny') => void;
  addHistoryItem: (item: HistoryItem) => void;
  clearHistory: () => void;
  setActiveDialog: (dialog: string | null) => void;
  quit: () => void;
}

export interface CommandContext {
  state: UIState;
  actions: UIActions;
  client: import('./client.js').A2AClient;
  baseUrl: string;
}

export interface SlashCommand {
  name: string;
  aliases?: string[];
  description: string;
  hidden?: boolean;
  action: (ctx: CommandContext, args: string) => void | Promise<void>;
}
