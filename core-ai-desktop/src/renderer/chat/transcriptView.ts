// Shared transcript primitives used by both the local engine transcript and the remote
// server conversation. Keeping the chip/panel/tool/plan markup here is what makes the two
// backends look and behave the same; the state machines above them stay backend-specific.
import { subagentModelFrom, subagentTypeFrom, toolUseKind, toolUseLabel, type ToolUseKind } from '../transcript/classify';
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

/** Chip order in the row: the origins the local engine produces, most specific first. */
export const TOOL_USE_ORDER: ToolUseKind[] = ['skill', 'subagent', 'mcp', 'api', 'agent', 'web'];

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
  const close = () => {
    if (!open) return;
    open = false;
    render();
  };
  const setOpen = (next: boolean) => {
    const wasOpen = open;
    open = next;
    render();
    if (!wasOpen && open) {
      // Accordion inside one message: a long Thinking panel must not push the reply (or the
      // question above it) out of view while Tool use is being inspected, and vice versa.
      const siblings = group.parentElement?.children;
      if (siblings) {
        for (const sibling of siblings) {
          if (sibling !== group) sibling.dispatchEvent(new Event('assist:close'));
        }
      }
      openListener?.();
    }
  };
  group.addEventListener('assist:close', close);
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

/** Compact, human-readable span so a 30-minute turn does not read as "1800s". */
export function formatSpan(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${seconds % 60}s`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m`;
}

export function prettyJson(raw: unknown): string {
  if (typeof raw !== 'string') return JSON.stringify(raw, null, 2);
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

/** Collapsed-line summary so the user can tell what a tool call did without expanding it. */
export function toolSummary(argumentsText: unknown): string {
  try {
    const parsed = typeof argumentsText === 'string' ? JSON.parse(argumentsText) : argumentsText;
    if (parsed && typeof parsed === 'object') {
      const args = parsed as Record<string, unknown>;
      if (typeof args.description === 'string' && args.description.trim()) return args.description.trim().slice(0, 120);
      for (const [key, value] of Object.entries(args)) {
        if (typeof value === 'string' && value.trim()) return `${key}: ${value.trim().slice(0, 100)}`;
        if (typeof value === 'number' || typeof value === 'boolean') return `${key}: ${String(value)}`;
      }
    }
  } catch {
    return '';
  }
  return '';
}

export interface LiveToolEntryView {
  node: HTMLElement;
  statusNode: HTMLElement;
  resultNode: HTMLElement;
  durationNode: HTMLElement;
  /** Nested calls of a subagent land here; hidden until the first one arrives. */
  childrenNode: HTMLElement;
  setChildCount(count: number): void;
}

/**
 * The collapsed one-line tool row a running turn shows inside its "Tool use" panel. Static history
 * uses the denser record-tool markup instead; this is the live shape both backends share.
 */
export function liveToolEntryView(name: string, argumentsText: string | undefined,
                                  toolType?: unknown): LiveToolEntryView {
  const entry = createEl('div', 'tool-entry');
  const head = createEl('button', 'tool-entry-head') as HTMLButtonElement;
  const chevron = createEl('span', 'tool-chevron', '▸');
  head.append(chevron, createEl('span', 'tool-name', name));
  const tag = toolOriginTag(name, argumentsText, toolType);
  if (tag) head.append(createEl('span', 'tool-tag', tag));
  head.append(createEl('span', 'tool-desc', toolSummary(argumentsText)));
  const childCountNode = createEl('span', 'tool-child-count');
  childCountNode.hidden = true;
  const durationNode = createEl('span', 'tool-duration');
  const statusNode = createEl('span', 'tool-status running', 'running');
  head.append(childCountNode, durationNode, statusNode);
  const body = createEl('div', 'tool-entry-body');
  body.hidden = true;
  const childrenNode = createEl('div', 'tool-children');
  childrenNode.hidden = true;
  body.append(createEl('pre', undefined, prettyJson(argumentsText)));
  const resultNode = createEl('pre');
  resultNode.style.display = 'none';
  body.append(resultNode);
  head.onclick = () => {
    body.hidden = !body.hidden;
    chevron.textContent = body.hidden ? '▸' : '▾';
  };
  // A subagent's own calls stay outside the collapsible body: they are the activity, not detail.
  entry.append(head, childrenNode, body);
  return {
    node: entry,
    statusNode,
    resultNode,
    durationNode,
    childrenNode,
    setChildCount: (count: number) => {
      childCountNode.hidden = count <= 0;
      childCountNode.textContent = `${count} tool${count === 1 ? '' : 's'}`;
    },
  };
}

/** A subagent call is tagged with its agent type and model so a `task` row reads as the origin of a nested list. */
export function toolOriginTag(name: string, argumentsText: unknown, toolType?: unknown): string | null {
  if (toolUseKind(name, toolType ?? null) !== 'subagent') return null;
  const type = subagentTypeFrom(argumentsText);
  const model = subagentModelFrom(argumentsText);
  return type ? `subagent · ${type} · ${model}` : `subagent · ${model}`;
}

export interface ReasoningBlockView {
  section: HTMLElement;
  head: HTMLElement;
  body: HTMLElement;
}

/** One numbered section inside the turn's Thinking panel; both backends use the same markup. */
export function reasoningBlockView(index: number): ReasoningBlockView {
  const section = createEl('div', 'reasoning-block');
  const head = createEl('div', 'reasoning-block-head', `#${index}`);
  const body = createEl('div', 'reasoning-text');
  section.append(head, body);
  return { section, head, body };
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

export interface ToolTree {
  roots: ToolItem[];
  childrenOf: Map<string, ToolItem[]>;
  callIds: Set<string>;
  nestedCount: number;
}

/**
 * One level of the hierarchy: a subagent call owns the calls whose parentCallId names it. A child
 * whose parent was not loaded (a background subagent's later turn, an old transcript) stays a root
 * so nothing disappears; it is marked instead.
 */
export function toolTree(tools: ToolItem[]): ToolTree {
  const callIds = new Set(tools.map((tool) => tool.callId));
  const childrenOf = new Map<string, ToolItem[]>();
  const roots: ToolItem[] = [];
  let nestedCount = 0;
  for (const tool of tools) {
    const parentId = tool.parentCallId;
    if (parentId && parentId !== tool.callId && callIds.has(parentId)) {
      const children = childrenOf.get(parentId) ?? [];
      children.push(tool);
      childrenOf.set(parentId, children);
      nestedCount += 1;
    } else {
      roots.push(tool);
    }
  }
  return { roots, childrenOf, callIds, nestedCount };
}

function buildToolRecord(tool: ToolItem, childrenOf: Map<string, ToolItem[]>,
                         onLoadFull: ((row: HTMLElement, seq: number, field: string, className: string) => void) | undefined,
                         seen: Set<string>, orphan: boolean): ToolRecordView {
  const row = createEl('div', 'record-tool');
  if (orphan) row.classList.add('record-tool-orphan');
  const head = createEl('div', 'record-tool-head');
  head.append(createEl('span', 'record-tool-name', tool.name));
  const tag = toolOriginTag(tool.name, tool.arguments, tool.toolType);
  if (tag) head.append(createEl('span', 'record-tool-tag', tag));
  if (tool.durationMs) head.append(createEl('span', 'record-tool-duration', formatSpan(tool.durationMs)));
  const status = tool.status === 'ok' ? 'success' : tool.status;
  const statusNode = createEl('span', `record-tool-status ${status}`, tool.status);
  head.append(statusNode);
  if (tool.approval) head.append(createEl('span', 'record-tool-approval', tool.approval));
  row.append(head);
  const children = seen.has(tool.callId) ? [] : (childrenOf.get(tool.callId) ?? []);
  if (children.length) {
    seen.add(tool.callId);
    const block = createEl('div', 'record-tool-children');
    for (const child of children) {
      block.append(buildToolRecord(child, childrenOf, onLoadFull, seen, false).node);
    }
    row.append(block);
  }
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

export function toolRecordView(tool: ToolItem,
                               onLoadFull?: (row: HTMLElement, seq: number, field: string, className: string) => void): ToolRecordView {
  return buildToolRecord(tool, new Map(), onLoadFull, new Set(), false);
}

export function toolRecordEl(tool: ToolItem,
                             onLoadFull?: (row: HTMLElement, seq: number, field: string, className: string) => void): HTMLElement {
  return toolRecordView(tool, onLoadFull).node;
}

export function addTranscriptTools(chips: HTMLElement, panels: HTMLElement, tools: ToolItem[],
                                   onLoadFull?: (row: HTMLElement, seq: number, field: string, className: string) => void): void {
  const tree = toolTree(tools);
  const body = createEl('div');
  for (const tool of tree.roots) {
    const orphan = Boolean(tool.parentCallId) && !tree.callIds.has(tool.parentCallId!);
    body.append(buildToolRecord(tool, tree.childrenOf, onLoadFull, new Set(), orphan).node);
  }
  const total = tools.reduce((sum, tool) => sum + (tool.durationMs ?? 0), 0);
  const nested = tree.nestedCount ? ` · ${tree.nestedCount} nested` : '';
  addTranscriptToggle(chips, panels, `Tool use (${tree.roots.length}${nested})${total > 0 ? ` · ${formatSpan(total)}` : ''}`, body);
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

export interface ToolUseChip {
  toggle: AssistToggle;
  panel: HTMLElement;
  rows: Map<string, { node: HTMLElement; count: number }>;
}

export type ToolUseChips = Map<ToolUseKind, ToolUseChip>;

/**
 * One chip per tool origin next to thinking / tool use; expanding lists everything called in that
 * turn. Shared by the local live turn and the remote live turn so the two chip rows cannot drift.
 */
export function recordToolUse(chips: HTMLElement, panels: HTMLElement, state: ToolUseChips,
                              kind: ToolUseKind, text: string): void {
  let chip = state.get(kind);
  if (!chip) {
    const meta = TOOL_USE_CHIPS[kind];
    const toggle = createAssistToggle(meta.label);
    toggle.chip.classList.add(meta.chipClass);
    chip = { toggle, panel: toggle.body, rows: new Map() };
    state.set(kind, chip);
    chips.append(toggle.chip);
    panels.append(toggle.panel);
  }
  const existing = chip.rows.get(text);
  if (existing) {
    existing.count += 1;
    existing.node.textContent = `${text} · ×${existing.count}`;
  } else {
    const node = createEl('div', 'tool-use-row', text);
    chip.panel.append(node);
    chip.rows.set(text, { node, count: 1 });
  }
  chip.toggle.setLabel(`${TOOL_USE_CHIPS[kind].label} (${chip.rows.size})`);
}

export interface AssistantChipContent {
  reasoning?: string[];
  /** Sum of the reasoning sections, so the chip can show time spent thinking. */
  reasoningDurationMs?: number;
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
    const total = content.reasoningDurationMs ?? 0;
    addTranscriptToggle(chips, panels, `Thinking${total > 0 ? ` · ${formatSpan(total)}` : ''}`, body);
  }
  if (content.tools?.length) {
    addTranscriptTools(chips, panels, content.tools, content.onLoadFull);
    // Every origin the local live turn would have chipped gets a chip here too, so reopening a
    // session does not silently drop Skill use / MCP / API rows that were visible while running.
    for (const kind of TOOL_USE_ORDER) {
      const tools = content.tools.filter((tool) => tool.useKind === kind);
      if (tools.length) addTranscriptToolUse(chips, panels, kind, tools);
    }
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
  parentCallId?: string | null;
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
    parentCallId: tool.parentCallId ?? null,
  };
}
