// The remote semantic boundary: server SSE/history field names are normalized to the desktop's
// canonical local event/message shape before the renderer sees them.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import protocol from '../dist/test/remoteProtocol.js';

const { normalizeRemoteEvent, normalizeRemoteMessages } = protocol;

test('server text and reasoning chunks become canonical chunk events', () => {
  assert.equal(normalizeRemoteEvent({ type: 'text_chunk', content: 'hello' }).chunk, 'hello');
  assert.equal(normalizeRemoteEvent({ type: 'reasoning_chunk', content: 'think' }).chunk, 'think');
});

test('tool events normalize snake_case fields and argument objects', () => {
  const start = normalizeRemoteEvent({
    type: 'tool_start', call_id: 'c1', tool_name: 'web_search', tool_args: { query: 'x' }, tool_notes: 'diff',
  });
  assert.equal(start.callId, 'c1');
  assert.equal(start.toolName, 'web_search');
  assert.equal(start.arguments, '{"query":"x"}');
  assert.equal(start.diff, 'diff');

  const result = normalizeRemoteEvent({
    type: 'tool_result', call_id: 'c1', tool_name: 'web_search', result: 'ok', status: 'success', tool_type: 'web',
  });
  assert.equal(result.callId, 'c1');
  assert.equal(result.toolType, 'web');
  assert.equal(result.result, 'ok');
});

test('history roles and tool fields normalize before rendering', () => {
  const [user, agent] = normalizeRemoteMessages([
    { role: 'user', content: 'hi', seq: 1 },
    {
      role: 'agent', content: 'hello', thinking: 'why', seq: 2,
      tools: [{ call_id: 'c1', name: 'read', arguments: '{}', result: 'ok', status: 'success', tool_type: 'mcp' }],
      events: [{ name: 'plan_update', data: '{"todos":[]}', card: '{"blocks":[]}' }],
    },
  ]);
  assert.equal(user.role, 'user');
  assert.equal(agent.role, 'assistant');
  assert.equal(agent.thinking, 'why');
  assert.equal(agent.tools[0].callId, 'c1');
  assert.equal(agent.tools[0].toolType, 'mcp');
  assert.equal(agent.events[0].callId, undefined);
});

test('unknown and malformed events are ignored', () => {
  assert.equal(normalizeRemoteEvent(null), null);
  assert.equal(normalizeRemoteEvent({ content: 'no type' }), null);
  assert.deepEqual(normalizeRemoteMessages(null), []);
});
