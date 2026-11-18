// Shared transcript primitives used by both the local engine transcript and the remote
// server conversation. Keeping the chip/panel/tool/plan markup here is what makes the two
// backends look and behave the same; the state machines above them stay backend-specific.
import { toolUseKind, toolUseLabel, type ToolUseKind } from '../transcript/classify';
import type { ToolItem } from '../transcript/reducer';

export interface AssistToggle {
  chip: HTMLButtonElement;
  panel: HTMLElement;
  body: HTMLElement;
  setLabel(label: string): void;
  setOpen(open: boolean): void;
  onOpen(listener: () => void): void;
  userToggled(): boolean;
}

export interface ToolUseChipMeta {
  label: string;
  chipClass: string;
}

export const TOOL_USE_CHIPS: Record<ToolUseKind, ToolUseChipMeta> = {
  skill: { label: 'Skill use', chipClass: 'skills-chip' },
  subagent: { label: 'Subagent', chipClass: 'subagent-chip' },
  mcp: { label: 'MCP', chipClass: 'mcp-chip' },
  api: { label: 'API tool', chipClass: 'api-chip' },
  agent: { label: 'Agent', chipClass: 'agent-chip' },
  web: { label: 'Web', chipClass: 'web-chip' },
};

export function createEl<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string,
                                                               text?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

export function createAssistToggle(label: string): AssistToggle {
  const chip = createEl('button', 'assist-chip') as HTMLButtonElement;
  const group = createEl('div', 'assist-panel');
  const body = createEl('div', 'assist-panel-body');
  const collapse = createEl('button', 'assist-collapse', 'Collapse') as HTMLButtonElement;
  let base = label;
  let open = false;
  let touched = false;
  let openListener: (() => void) | null = null;
  const render = () => {
    group.hidden = !open;
    chip.textContent = `${open ? '▾' : '▸'} ${base}`;
    chip.classList.toggle('active', open);
  };
  const setOpen = (next: boolean) => {
    const wasOpen = open;
    open = next;
    render();
    if (!wasOpen && open) openListener?.();
  };
  chip.onclick = () => {
    touched = true;
    setOpen(!open);
  };
  collapse.onclick = () => {
    touched = true;
    setOpen(false);
  };
  group.append(body, collapse);
  const setLabel = (next: string) => {
    base = next;
    render();
  };
  setLabel(label);
  return {
    chip,
    panel: group,
    body,
    setLabel,
    setOpen,
    onOpen: (listener) => { openListener = listener; },
    userToggled: () => touched,
  };
}

export function transcriptPre(text: string, className: string): HTMLElement {
  const pre = createEl('pre', className);
  pre.textContent = text;
  return pre;
}

export function addTranscriptToggle(chips: HTMLElement, panels: HTMLElement, label: string,
                                    body: HTMLElement): AssistToggle {
  const toggle = createAssistToggle(label);
  toggle.body.append(body);
  chips.append(toggle.chip);
  panels.append(toggle.panel);
  return toggle;
}

export interface ToolRecordView {
  node: HTMLElement;
  statusNode: HTMLElement;
  resultNode: HTMLElement | null;
}

export function toolRecordView(tool: ToolItem,
                               onLoadFull?: (row: HTMLElement, seq: number, field: string, className: string) => void): ToolRecordView {
  const row = createEl('div', 'record-tool');
  const head = createEl('div', 'record-tool-head');
  head.append(createEl('span', 'record-tool-name', tool.name));
  const status = tool.status === 'ok' ? 'success' : tool.status;
  const statusNode = createEl('span', `record-tool-status ${status}`, tool.status);
  head.append(statusNode);
  if (tool.approval) head.append(createEl('span', 'record-tool-approval', tool.approval));
  row.append(head);
  if (tool.arguments) row.append(transcriptPre(tool.arguments, 'record-tool-args'));
  let resultNode: HTMLElement | null = null;
  const result = tool.result ?? tool.resultPreview;
  if (result) {
    resultNode = transcriptPre(result, 'record-tool-result');
    row.append(resultNode);
  }
  if (tool.resultRef && tool.resultSeq !== undefined && onLoadFull) {
    onLoadFull(row, tool.resultSeq, 'result', 'record-tool-result');
  }
  return { node: row, statusNode, resultNode };
}

export function toolRecordEl(tool: ToolItem,
                             onLoadFull?: (row: HTMLElement, seq: number, field: string, className: string) => void): HTMLElement {
  return toolRecordView(tool, onLoadFull).node;
}

export function addTranscriptTools(chips: HTMLElement, panels: HTMLElement, tools: ToolItem[],
                                   onLoadFull?: (row: HTMLElement, seq: number, field: string, className: string) => void): void {
  const body = createEl('div');
  for (const tool of tools) body.append(toolRecordEl(tool, onLoadFull));
  addTranscriptToggle(chips, panels, `Tool use (${tools.length})`, body);
}

export function addTranscriptToolUse(chips: HTMLElement, panels: HTMLElement, kind: ToolUseKind,
                                     tools: ToolItem[]): void {
  const counts = new Map<string, number>();
  for (const tool of tools) {
    const label = toolUseLabel(kind, tool.name, tool.arguments);
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  const body = createEl('div');
  for (const [label, count] of counts) {
    body.append(createEl('div', 'tool-use-row', count > 1 ? `${label} · ×${count}` : label));
  }
  const meta = TOOL_USE_CHIPS[kind];
  const toggle = createAssistToggle(`${meta.label} (${counts.size})`);
  toggle.chip.classList.add(meta.chipClass);
  toggle.body.append(body);
  chips.append(toggle.chip);
  panels.append(toggle.panel);
}

export interface AssistantChipContent {
  reasoning?: string[];
  tools?: ToolItem[];
  extra?: (chips: HTMLElement, panels: HTMLElement) => void;
  onLoadFull?: (row: HTMLElement, seq: number, field: string, className: string) => void;
}

export function addAssistantChips(bubble: HTMLElement, content: AssistantChipContent): void {
  const chips = createEl('div', 'assist-chips');
  const panels = createEl('div', 'assist-panels');
  bubble.prepend(panels);
  bubble.prepend(chips);
  if (content.reasoning?.length) {
    const body = createEl('div');
    for (const entry of content.reasoning) body.append(transcriptPre(entry, 'record-reasoning'));
    addTranscriptToggle(chips, panels, 'Thinking', body);
  }
  if (content.tools?.length) {
    addTranscriptTools(chips, panels, content.tools, content.onLoadFull);
    const webTools = content.tools.filter((tool) => tool.useKind === 'web');
    if (webTools.length) addTranscriptToolUse(chips, panels, 'web', webTools);
  }
  content.extra?.(chips, panels);
}

type PlanState = 'done' | 'active' | 'pending';

const PLAN_MARKS: Record<PlanState, string> = { done: '✓', active: '▶', pending: '○' };

export function planState(status: unknown): PlanState {
  const value = String(status ?? '').toLowerCase();
  if (value === 'completed' || value === 'done') return 'done';
  if (value === 'in_progress' || value === 'in-progress' || value === 'active') return 'active';
  return 'pending';
}

export function fillPlanBlock(block: HTMLElement, todos: unknown[]): void {
  const head = createEl('div', 'plan-head');
  head.append(createEl('span', 'plan-title', 'plan'));
  const items = todos.filter((todo): todo is Record<string, unknown> => todo !== null && typeof todo === 'object');
  const done = items.filter((todo) => planState(todo.status) === 'done').length;
  head.append(createEl('span', 'plan-count', `${done}/${items.length}`));
  block.replaceChildren(head);
  for (const todo of items) {
    const state = planState(todo.status);
    const item = createEl('div', `plan-item ${state}`);
    item.append(createEl('span', 'plan-mark', PLAN_MARKS[state]),
      createEl('span', 'plan-text', String(todo.content ?? todo.text ?? '')));
    block.append(item);
  }
}

export function planBlockEl(todos: unknown[]): HTMLElement {
  const block = createEl('div', 'plan-block');
  fillPlanBlock(block, todos);
  return block;
}

export function toolItemOf(tool: {
  callId?: string | null;
  name?: string | null;
  arguments?: string;
  result?: string;
  status?: string;
  toolType?: string | null;
}, seq: number): ToolItem {
  const name = tool.name ?? 'tool';
  return {
    seq,
    key: `${seq}:${tool.callId ?? name}`,
    kind: 'tool',
    callId: tool.callId ?? '',
    name,
    useKind: toolUseKind(name, tool.toolType ?? null),
    status: tool.status ?? 'done',
    approval: null,
    arguments: tool.arguments,
    result: tool.result,
    toolType: tool.toolType ?? null,
  };
}
