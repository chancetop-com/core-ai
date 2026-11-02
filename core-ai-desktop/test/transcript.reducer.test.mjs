// Transcript reducer: turns, steering, tools, tasks, memory and rewind. Run via `npm test`
// (builds dist/test/transcript.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import transcript from '../dist/test/transcript.js';

const { reduceAll, reduce, emptyModel, applyPartials } = transcript;

const start = (seq, text, source = 'user') => ({
  v: 1,
  seq,
  ts: 1,
  turn_id: `t${seq}`,
  kind: 'turn_start',
  source,
  inputs: [{ text, attachments: [], client_turn_id: `c-${seq}` }],
  message_count_before: 0,
});

test('groups a turn with text, tool, approval and summary', () => {
  const model = reduceAll([
    { seq: 1, kind: 'session_start', turn_id: null },
    start(2, 'hello'),
    { seq: 3, turn_id: 't2', kind: 'assistant_text', text: 'first' },
    { seq: 4, turn_id: 't2', kind: 'tool_start', callId: 'c1', toolName: 'grep', arguments: '{}', toolType: 'builtin' },
    { seq: 5, turn_id: 't2', kind: 'tool_approval', callId: 'c1', toolName: 'grep' },
    { seq: 6, turn_id: 't2', kind: 'tool_approval', callId: 'c1', decision: 'APPROVE' },
    { seq: 7, turn_id: 't2', kind: 'tool_result', callId: 'c1', toolName: 'grep', status: 'success', result: 'found' },
    { seq: 8, turn_id: 't2', kind: 'turn_complete', output: 'done', input_tokens: 10, output_tokens: 2, cost_usd: 0.01, message_count: 4 },
  ]);
  assert.equal(model.turns.length, 1);
  const turn = model.turns[0];
  assert.equal(turn.turnId, 't2');
  assert.equal(turn.kind, 'user');
  assert.equal(turn.origin, 'transcript');
  assert.equal(turn.open, false);
  assert.equal(turn.items[0].kind, 'input');
  assert.equal(turn.items[0].text, 'hello');
  assert.equal(turn.items[0].clientTurnId, 'c-2');
  assert.equal(turn.items[1].text, 'first');
  const tool = turn.items.find((item) => item.kind === 'tool');
  assert.equal(tool.status, 'success');
  assert.equal(tool.result, 'found');
  assert.equal(tool.approval, 'APPROVE');
  assert.equal(turn.summary.inputTokens, 10);
  assert.equal(turn.summary.costUsd, 0.01);
});

test('seals unresolved tools when the turn ends', () => {
  const model = reduceAll([
    start(2, 'go'),
    { seq: 3, turn_id: 't2', kind: 'tool_start', callId: 'c1', toolName: 'grep' },
    { seq: 4, turn_id: 't2', kind: 'turn_complete', cancelled: true, message_count: 1 },
  ]);
  assert.equal(model.turns[0].items.find((item) => item.kind === 'tool').status, 'cancelled');
});

test('steer pending renders and cancel removes it', () => {
  let model = reduceAll([start(2, 'hello')]);
  model = reduce(model, { seq: 3, turn_id: 't2', kind: 'steer', state: 'pending', text: 'more', client_turn_id: 'c-9' });
  assert.equal(model.turns[0].items.filter((item) => item.kind === 'input').length, 2);
  model = reduce(model, { seq: 4, kind: 'steer', state: 'cancelled', ref_seq: 3 });
  assert.equal(model.turns[0].items.filter((item) => item.kind === 'input').length, 1);
});

test('task note source and task index are tracked', () => {
  const model = reduceAll([
    start(2, '<task-notification><task-id>t-1</task-id><status>completed</status></task-notification>', 'task_note'),
    { seq: 3, turn_id: 't2', kind: 'assistant_text', text: 'done' },
    { seq: 4, turn_id: 't2', kind: 'turn_complete', message_count: 1 },
    { seq: 5, turn_id: null, kind: 'task_status', taskId: 't-x', status: 'completed' },
  ]);
  assert.equal(model.turns[0].kind, 'task_note');
  assert.equal(model.pendingTaskStatus['t-x'], 'completed');
});

test('memory runs merge by runId', () => {
  const model = reduceAll([
    start(2, 'hello'),
    { seq: 3, turn_id: 't2', kind: 'turn_complete', message_count: 1 },
    { seq: 4, turn_id: null, kind: 'custom', name: 'memory', data: JSON.stringify({ runId: 'r1', phase: 'STARTED', trigger: 'TURN' }) },
    { seq: 5, turn_id: null, kind: 'custom', name: 'memory', data: JSON.stringify({ runId: 'r1', phase: 'COMPLETED', added: ['a.md'], updated: [] }) },
  ]);
  assert.equal(model.turns[0].memoryRuns.length, 1);
  assert.deepEqual(model.turns[0].memoryRuns[0].added, ['a.md']);
  assert.equal(model.turns[0].memoryRuns[0].running, false);
  assert.equal(model.turns[0].items.filter((item) => item.kind === 'memory').length, 1);
});

test('rewind drops the target turn and later ones', () => {
  const model = reduceAll([
    start(2, 'one'),
    { seq: 3, turn_id: 't2', kind: 'turn_complete', message_count: 1 },
    start(4, 'two'),
    { seq: 5, turn_id: 't4', kind: 'turn_complete', message_count: 2 },
    { seq: 6, kind: 'rewind', to_seq: 4, message_count: 1 },
  ]);
  assert.equal(model.turns.length, 1);
  assert.equal(model.turns[0].turnId, 't2');
});

test('projection turns keep their origin and partials are applied', () => {
  const model = reduceAll([
    { seq: -2, turn_id: 'pabc', kind: 'turn_start', inputs: [{ text: 'old' }] },
    { seq: -1, turn_id: 'pabc', kind: 'turn_complete', message_count: 1 },
  ], [{ turnId: 't2', side: 'head' }]);
  assert.equal(model.turns[0].origin, 'projection');
  applyPartials(model, [{ turnId: 'pabc', side: 'tail' }]);
  assert.equal(model.turns[0].partial, 'tail');
});

test('null turn records attach to the nearest turn', () => {
  const model = reduceAll([
    start(2, 'hello'),
    { seq: 3, turn_id: 't2', kind: 'turn_complete', message_count: 1 },
    { seq: 4, turn_id: null, kind: 'custom', name: 'artifact', data: '{}' },
  ]);
  assert.equal(model.turns[0].items.filter((item) => item.kind === 'custom').length, 1);
  assert.equal(model.orphans.length, 0);
});

test('emptyModel has no turns', () => {
  assert.deepEqual(emptyModel(), { turns: [], taskIndex: {}, pendingTaskStatus: {}, orphans: [] });
});
