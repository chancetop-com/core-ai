// Pure transcript reducer: records in, view model out. The engine owns the ordering; this layer only
// groups records into turns and keeps the last write for each call/task/plan.

import { toolUseKind, type ToolUseKind, type TaskNotification, parseTaskNotifications, systemReminderText } from './classify';
import {
  recordBool,
  recordNumber,
  recordRef,
  recordText,
  type PartialTurn,
  type RecordAttachment,
  type TranscriptRecord,
  type TurnInput,
} from './records';

export interface MemoryRun {
  runId: string;
  phase: string;
  trigger?: string;
  added: string[];
  updated: string[];
  note?: string | null;
  durationMs?: number;
  cursor?: number | null;
  running: boolean;
}

export interface TurnSummary {
  cancelled?: boolean;
  maxTurnsReached?: boolean;
  durationMs?: number;
  inputTokens?: number;
  outputTokens?: number;
  cachedTokens?: number;
  costUsd?: number;
}

export interface InputItem {
  seq: number;
  key: string;
  kind: 'input';
  text?: string;
  textRef?: string;
  textPreview?: string;
  attachments: RecordAttachment[];
  clientTurnId?: string | null;
  steering: boolean;
  ts?: number;
}

export interface TextItem {
  seq: number;
  key: string;
  kind: 'text';
  text?: string;
  textRef?: string;
  textPreview?: string;
}

export interface ReasoningItem {
  seq: number;
  key: string;
  kind: 'reasoning';
  text?: string;
  textRef?: string;
  textPreview?: string;
  durationMs?: number;
}

export interface ToolItem {
  seq: number;
  key: string;
  kind: 'tool';
  callId: string;
  name: string;
  useKind: ToolUseKind | null;
  status: string;
  approval: string | null;
  arguments?: string;
  argumentsRef?: string;
  diff?: string;
  diffRef?: string;
  result?: string;
  resultRef?: string;
  resultPreview?: string;
  resultSeq?: number;
  durationMs?: number;
  taskId?: string | null;
  runInBackground?: boolean;
  toolType?: string | null;
}

export interface BatchItem {
  seq: number;
  key: string;
  kind: 'batch';
  group?: string;
  tools: unknown[];
  taskId?: string | null;
}

export interface CustomItem {
  seq: number;
  key: string;
  kind: 'custom';
  name: string;
  data?: string;
  dataRef?: string;
  card?: string;
  cardRef?: string;
  callId?: string | null;
}

export interface NoticeItem {
  seq: number;
  key: string;
  kind: 'notice';
  level: string;
  text?: string;
  textRef?: string;
  textPreview?: string;
}

export interface MemoryItem {
  seq: number;
  key: string;
  kind: 'memory';
  run: MemoryRun;
}

/** A completed context compression; the started record only says a summarization is running. */
export interface CompressionItem {
  seq: number;
  key: string;
  kind: 'compression';
  beforeCount: number;
  afterCount: number;
  contextTokens?: number;
  maxContextTokens?: number;
  triggerThreshold?: number;
}

export interface TaskItem {
  seq: number;
  key: string;
  kind: 'task';
  taskId: string;
  toolName?: string | null;
  status: string;
}

export type Item =
  | InputItem
  | TextItem
  | ReasoningItem
  | ToolItem
  | BatchItem
  | CustomItem
  | NoticeItem
  | MemoryItem
  | CompressionItem
  | TaskItem;

export interface Turn {
  turnId: string;
  sortSeq: number;
  origin: 'transcript' | 'projection';
  kind: 'user' | 'task_note';
  items: Item[];
  plan: unknown[] | null;
  summary: TurnSummary | null;
  open: boolean;
  partial: 'none' | 'head' | 'tail';
  memoryRuns: MemoryRun[];
  /** Start of the turn, i.e. when the user message that opened it was sent. */
  startedAt?: number;
}

export interface TranscriptModel {
  turns: Turn[];
  taskIndex: Record<string, string>;
  pendingTaskStatus: Record<string, string>;
  orphans: Item[];
}

export function emptyModel(): TranscriptModel {
  return { turns: [], taskIndex: {}, pendingTaskStatus: {}, orphans: [] };
}

export function reduceAll(records: TranscriptRecord[], partials?: PartialTurn[]): TranscriptModel {
  const model = emptyModel();
  for (const record of records) reduce(model, record);
  applyPartials(model, partials ?? []);
  return model;
}

export function reduce(model: TranscriptModel, record: TranscriptRecord): TranscriptModel {
  switch (record.kind) {
    case 'session_start':
      return model;
    case 'turn_start':
      addTurn(model, record);
      return model;
    case 'steer':
      applySteer(model, record);
      return model;
    case 'assistant_text':
      appendText(model, record);
      return model;
    case 'reasoning':
      addReasoning(model, record);
      return model;
    case 'tool_start':
      addTool(model, record);
      return model;
    case 'tool_result':
      applyToolResult(model, record);
      return model;
    case 'tool_approval':
      applyApproval(model, record);
      return model;
    case 'batch_tool_start':
      addBatch(model, record);
      return model;
    case 'plan_update':
      applyPlan(model, record);
      return model;
    case 'compression':
      addCompression(model, record);
      return model;
    case 'sandbox':
      addNotice(model, record, 'sandbox', recordText(record, 'message'));
      return model;
    case 'custom':
      applyCustom(model, record);
      return model;
    case 'task_status':
      applyTaskStatus(model, record);
      return model;
    case 'error':
      applyError(model, record);
      return model;
    case 'turn_complete':
      applyTurnComplete(model, record);
      return model;
    case 'notice':
      addNotice(model, record, String(record.level ?? 'system'), recordText(record, 'text'));
      return model;
    case 'rewind':
      applyRewind(model, record);
      return model;
    default:
      return model;
  }
}

export function applyPartials(model: TranscriptModel, partials: PartialTurn[]): void {
  for (const partial of partials) {
    const turn = model.turns.find((candidate) => candidate.turnId === partial.turnId);
    if (turn) turn.partial = partial.side;
  }
}

export function totalTurns(model: TranscriptModel): number {
  return model.turns.length;
}

function addTurn(model: TranscriptModel, record: TranscriptRecord): void {
  const turnId = typeof record.turn_id === 'string' && record.turn_id ? record.turn_id : `t${record.seq}`;
  const existing = model.turns.find((turn) => turn.turnId === turnId);
  if (existing) {
    existing.open = true;
    return;
  }
  const source = typeof record.source === 'string' ? record.source : 'user';
  const kind: Turn['kind'] = source === 'task_note' ? 'task_note' : 'user';
  const ts = recordTs(record);
  const turn: Turn = {
    turnId,
    sortSeq: record.seq,
    origin: record.synthetic || record.seq < 0 ? 'projection' : 'transcript',
    kind,
    items: [],
    plan: null,
    summary: null,
    open: true,
    partial: 'none',
    memoryRuns: [],
    startedAt: ts,
  };
  const inputs = Array.isArray(record.inputs) ? record.inputs as TurnInput[] : [];
  if (inputs.length) {
    inputs.forEach((input, index) => {
      const text = input.text ?? input.text_preview ?? undefined;
      turn.items.push({
        seq: record.seq,
        key: `${record.seq}:${index}`,
        kind: 'input',
        text,
        textRef: input.text_ref,
        textPreview: input.text_preview,
        attachments: Array.isArray(input.attachments) ? input.attachments : [],
        clientTurnId: input.client_turn_id ?? null,
        steering: false,
        ts,
      });
    });
  } else {
    turn.items.push({
      seq: record.seq,
      key: `${record.seq}:0`,
      kind: 'input',
      attachments: [],
      steering: false,
      ts,
    });
  }
  model.turns.push(turn);
}

function applySteer(model: TranscriptModel, record: TranscriptRecord): void {
  const state = String(record.state ?? '');
  const ref = recordNumber(record, 'ref_seq');
  if (state === 'cancelled') {
    if (ref === undefined) return;
    for (const turn of model.turns) {
      turn.items = turn.items.filter((item) => !(item.kind === 'input' && item.steering && item.seq === ref));
    }
    return;
  }
  if (state !== 'pending') return;
  const turn = openTurn(model) ?? turnFor(model, record);
  if (!turn) return;
  const turnId = typeof record.turn_id === 'string' ? record.turn_id : null;
  if (turnId && turn.turnId !== turnId) return;
  const input: InputItem = {
    seq: record.seq,
    key: `${record.seq}:0`,
    kind: 'input',
    text: recordText(record, 'text'),
    textRef: recordRef(record, 'text'),
    textPreview: typeof record.text_preview === 'string' ? record.text_preview : undefined,
    attachments: Array.isArray(record.attachments) ? record.attachments as RecordAttachment[] : [],
    clientTurnId: typeof record.client_turn_id === 'string' ? record.client_turn_id : null,
    steering: true,
    ts: recordTs(record),
  };
  turn.items.push(input);
}

function openTurn(model: TranscriptModel): Turn | undefined {
  for (let index = model.turns.length - 1; index >= 0; index--) {
    if (model.turns[index].open) return model.turns[index];
  }
  return undefined;
}

function appendText(model: TranscriptModel, record: TranscriptRecord): void {
  const turn = turnFor(model, record);
  const text = recordText(record, 'text');
  if (!turn || !text) return;
  const last = turn.items[turn.items.length - 1];
  if (last?.kind === 'text' && last.text !== undefined && !recordRef(record, 'text')) {
    last.text = `${last.text}\n\n${text}`;
    return;
  }
  turn.items.push({
    seq: record.seq,
    key: `${record.seq}:0`,
    kind: 'text',
    text,
    textRef: recordRef(record, 'text'),
    textPreview: typeof record.text_preview === 'string' ? record.text_preview : undefined,
  });
}

function addReasoning(model: TranscriptModel, record: TranscriptRecord): void {
  const turn = turnFor(model, record);
  const text = recordText(record, 'reasoning');
  if (!turn || !text) return;
  turn.items.push({
    seq: record.seq,
    key: `${record.seq}:0`,
    kind: 'reasoning',
    text,
    textRef: recordRef(record, 'reasoning'),
    textPreview: typeof record.reasoning_preview === 'string' ? record.reasoning_preview : undefined,
    durationMs: recordNumber(record, 'durationMs'),
  });
}

function addTool(model: TranscriptModel, record: TranscriptRecord): void {
  const turn = turnFor(model, record);
  if (!turn) return;
  const name = String(record.toolName ?? 'tool');
  const taskId = typeof record.taskId === 'string' ? record.taskId : null;
  const item: ToolItem = {
    seq: record.seq,
    key: `${record.seq}:0`,
    kind: 'tool',
    callId: String(record.callId ?? ''),
    name,
    useKind: toolUseKind(name, record.toolType),
    status: 'running',
    approval: null,
    arguments: recordText(record, 'arguments'),
    argumentsRef: recordRef(record, 'arguments'),
    diff: recordText(record, 'diff'),
    diffRef: recordRef(record, 'diff'),
    taskId,
    runInBackground: recordBool(record, 'run_in_background'),
    toolType: typeof record.toolType === 'string' ? record.toolType : null,
  };
  turn.items.push(item);
  if (taskId) {
    model.taskIndex[taskId] = turn.turnId;
    const pending = model.pendingTaskStatus[taskId];
    if (pending) {
      updateTask(turn, taskId, name, pending, record.seq);
      delete model.pendingTaskStatus[taskId];
    }
  }
}

function applyToolResult(model: TranscriptModel, record: TranscriptRecord): void {
  const turn = turnFor(model, record);
  if (!turn) return;
  const callId = String(record.callId ?? '');
  const existing = turn.items.find((item): item is ToolItem => item.kind === 'tool' && item.callId === callId);
  const result = recordText(record, 'result');
  const status = String(record.status ?? 'unknown');
  if (existing) {
    existing.status = status;
    existing.result = result;
    existing.resultRef = recordRef(record, 'result');
    existing.resultPreview = typeof record.result_preview === 'string' ? record.result_preview : undefined;
    existing.resultSeq = record.seq;
    existing.durationMs = recordNumber(record, 'durationMs');
    return;
  }
  turn.items.push({
    seq: record.seq,
    key: `${record.seq}:0`,
    kind: 'tool',
    callId,
    name: String(record.toolName ?? 'tool'),
    useKind: toolUseKind(String(record.toolName ?? 'tool'), record.toolType),
    status,
    approval: null,
    result,
    resultRef: recordRef(record, 'result'),
    resultPreview: typeof record.result_preview === 'string' ? record.result_preview : undefined,
    resultSeq: record.seq,
    durationMs: recordNumber(record, 'durationMs'),
    toolType: typeof record.toolType === 'string' ? record.toolType : null,
  });
}

function applyApproval(model: TranscriptModel, record: TranscriptRecord): void {
  const turn = turnFor(model, record);
  if (!turn) return;
  const callId = String(record.callId ?? '');
  const existing = turn.items.find((item): item is ToolItem => item.kind === 'tool' && item.callId === callId);
  if (existing) {
    existing.approval = typeof record.decision === 'string' ? record.decision : 'pending';
    return;
  }
  turn.items.push({
    seq: record.seq,
    key: `${record.seq}:0`,
    kind: 'tool',
    callId,
    name: String(record.toolName ?? 'tool'),
    useKind: toolUseKind(String(record.toolName ?? 'tool'), record.toolType),
    status: 'running',
    approval: typeof record.decision === 'string' ? record.decision : 'pending',
  });
}

function addBatch(model: TranscriptModel, record: TranscriptRecord): void {
  const turn = turnFor(model, record);
  if (!turn) return;
  turn.items.push({
    seq: record.seq,
    key: `${record.seq}:0`,
    kind: 'batch',
    group: typeof record.group === 'string' ? record.group : undefined,
    tools: Array.isArray(record.tools) ? record.tools : [],
    taskId: typeof record.task_id === 'string' ? record.task_id : null,
  });
}

function applyPlan(model: TranscriptModel, record: TranscriptRecord): void {
  const turn = turnFor(model, record);
  if (!turn) return;
  turn.plan = Array.isArray(record.todos) ? record.todos : null;
}

function addNotice(model: TranscriptModel, record: TranscriptRecord, level: string, text?: string): void {
  const turn = turnFor(model, record);
  const item: NoticeItem = {
    seq: record.seq,
    key: `${record.seq}:0`,
    kind: 'notice',
    level,
    text,
    textRef: recordRef(record, 'text'),
    textPreview: typeof record.text_preview === 'string' ? record.text_preview : undefined,
  };
  if (turn) turn.items.push(item);
  else model.orphans.push(item);
}

/**
 * A started record carries the number of messages queued for summarization, not a result, so only the
 * completed record is kept; the chip renders it beside Thinking / Tool use.
 */
function addCompression(model: TranscriptModel, record: TranscriptRecord): void {
  if (recordBool(record, 'completed') !== true) return;
  const before = recordNumber(record, 'before_count');
  const after = recordNumber(record, 'after_count');
  if (before === undefined || after === undefined) return;
  const turn = turnFor(model, record);
  if (!turn) return;
  turn.items.push({
    seq: record.seq,
    key: `${record.seq}:0`,
    kind: 'compression',
    beforeCount: before,
    afterCount: after,
    contextTokens: recordNumber(record, 'context_tokens'),
    maxContextTokens: recordNumber(record, 'max_context_tokens'),
    triggerThreshold: recordNumber(record, 'trigger_threshold'),
  });
}

function applyCustom(model: TranscriptModel, record: TranscriptRecord): void {
  if (record.name === 'memory') {
    applyMemory(model, record);
    return;
  }
  const turn = turnFor(model, record);
  const item: CustomItem = {
    seq: record.seq,
    key: `${record.seq}:0`,
    kind: 'custom',
    name: String(record.name ?? 'custom'),
    data: recordText(record, 'data'),
    dataRef: recordRef(record, 'data'),
    card: recordText(record, 'card'),
    cardRef: recordRef(record, 'card'),
    callId: typeof record.callId === 'string' ? record.callId : null,
  };
  if (turn) turn.items.push(item);
  else model.orphans.push(item);
}

function applyMemory(model: TranscriptModel, record: TranscriptRecord): void {
  const data = parseData(recordText(record, 'data'));
  const runId = typeof data.runId === 'string' ? data.runId : `run-${record.seq}`;
  const turn = turnFor(model, record);
  if (!turn) return;
  let run = turn.memoryRuns.find((candidate) => candidate.runId === runId);
  if (!run) {
    run = { runId, phase: 'STARTED', added: [], updated: [], running: true };
    turn.memoryRuns.push(run);
    turn.items.push({ seq: record.seq, key: `${record.seq}:0`, kind: 'memory', run });
  }
  const phase = String(data.phase ?? '').toUpperCase();
  run.phase = phase || run.phase;
  run.trigger = typeof data.trigger === 'string' ? data.trigger : run.trigger;
  run.added = stringList(data.added);
  run.updated = stringList(data.updated);
  run.note = typeof data.note === 'string' ? data.note : run.note;
  run.durationMs = typeof data.durationMs === 'number' ? data.durationMs : run.durationMs;
  run.cursor = typeof data.cursor === 'number' ? data.cursor : run.cursor;
  run.running = phase !== 'COMPLETED';
}

function applyTaskStatus(model: TranscriptModel, record: TranscriptRecord): void {
  const taskId = String(record.taskId ?? '');
  if (!taskId) return;
  const status = String(record.status ?? '');
  const toolName = typeof record.toolName === 'string' ? record.toolName : null;
  const turnId = model.taskIndex[taskId];
  if (!turnId) {
    model.pendingTaskStatus[taskId] = status;
    return;
  }
  const turn = model.turns.find((candidate) => candidate.turnId === turnId);
  if (!turn) {
    model.pendingTaskStatus[taskId] = status;
    return;
  }
  updateTask(turn, taskId, toolName, status, record.seq);
}

function updateTask(turn: Turn, taskId: string, toolName: string | null, status: string, seq: number): void {
  const existing = turn.items.find((item): item is TaskItem => item.kind === 'task' && item.taskId === taskId);
  if (existing) {
    existing.status = status;
    if (toolName) existing.toolName = toolName;
    return;
  }
  turn.items.push({ seq, key: `${seq}:0`, kind: 'task', taskId, toolName, status });
}

function applyError(model: TranscriptModel, record: TranscriptRecord): void {
  const terminal = recordBool(record, 'terminal') === true;
  const turn = turnFor(model, record);
  const item: NoticeItem = {
    seq: record.seq,
    key: `${record.seq}:0`,
    kind: 'notice',
    level: 'error',
    text: recordText(record, 'message'),
  };
  if (turn) turn.items.push(item);
  else model.orphans.push(item);
  if (terminal && turn) sealTurn(turn);
}

function applyTurnComplete(model: TranscriptModel, record: TranscriptRecord): void {
  const turn = turnFor(model, record);
  if (!turn) return;
  turn.summary = {
    cancelled: recordBool(record, 'cancelled'),
    maxTurnsReached: recordBool(record, 'max_turns_reached'),
    durationMs: recordNumber(record, 'duration_ms'),
    inputTokens: recordNumber(record, 'input_tokens'),
    outputTokens: recordNumber(record, 'output_tokens'),
    cachedTokens: recordNumber(record, 'cached_tokens'),
    costUsd: recordNumber(record, 'cost_usd'),
  };
  const output = recordText(record, 'output');
  const hasText = turn.items.some((item) => item.kind === 'text' && item.text);
  if (!hasText && output) {
    turn.items.push({
      seq: record.seq,
      key: `${record.seq}:0`,
      kind: 'text',
      text: output,
      textRef: recordRef(record, 'output'),
      textPreview: typeof record.output_preview === 'string' ? record.output_preview : undefined,
    });
  }
  sealTurn(turn);
}

function sealTurn(turn: Turn): void {
  turn.open = false;
  for (const item of turn.items) {
    if (item.kind === 'tool' && item.status === 'running') item.status = 'cancelled';
    if (item.kind === 'tool' && item.approval === 'pending') item.approval = null;
  }
}

function applyRewind(model: TranscriptModel, record: TranscriptRecord): void {
  const toSeq = recordNumber(record, 'to_seq');
  if (toSeq === undefined) return;
  const dropped = new Set<string>();
  model.turns = model.turns.filter((turn) => {
    if (turn.sortSeq >= toSeq) {
      dropped.add(turn.turnId);
      return false;
    }
    return true;
  });
  for (const taskId of Object.keys(model.taskIndex)) {
    if (dropped.has(model.taskIndex[taskId])) delete model.taskIndex[taskId];
  }
}

function turnFor(model: TranscriptModel, record: TranscriptRecord): Turn | undefined {
  const turnId = typeof record.turn_id === 'string' && record.turn_id ? record.turn_id : null;
  if (turnId) {
    const exact = model.turns.find((turn) => turn.turnId === turnId);
    if (exact) return exact;
  }
  let nearest: Turn | undefined;
  for (const turn of model.turns) {
    if (turn.sortSeq <= record.seq) nearest = turn;
  }
  return nearest;
}

function parseData(text: string | undefined): Record<string, unknown> {
  if (!text) return {};
  try {
    const parsed: unknown = JSON.parse(text);
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : {};
  } catch {
    return {};
  }
}

/**
 * Timestamp of a record the engine wrote. Projected legacy history carries materialisation time,
 * not the time the message was sent, so those records deliberately have no timestamp.
 */
function recordTs(record: TranscriptRecord): number | undefined {
  if (record.synthetic === true || record.seq < 0) return undefined;
  return typeof record.ts === 'number' && Number.isFinite(record.ts) && record.ts > 0 ? record.ts : undefined;
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

export function noticeText(item: NoticeItem): string | undefined {
  const reminder = systemReminderText(item.text);
  return reminder ?? item.text;
}

export function taskNoteText(item: InputItem): TaskNotification[] | null {
  return parseTaskNotifications(item.text);
}
