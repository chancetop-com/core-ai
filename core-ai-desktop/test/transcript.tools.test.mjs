// Tool hierarchy: a subagent's calls nest under the task call that spawned them, and nothing
// disappears when the parent row is not part of the same turn.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import view from '../dist/test/transcriptView.js';

const tool = (callId, parentCallId = null, name = 'read_file') => ({
  seq: 1,
  key: `1:${callId}`,
  kind: 'tool',
  callId,
  name,
  useKind: 'subagent',
  status: 'success',
  approval: null,
  parentCallId,
});

test('subagent calls group under their parent and keep nesting levels', () => {
  const tree = view.toolTree([
    tool('c1', null, 'task'),
    tool('c2', 'c1'),
    tool('c3', 'c1', 'task'),
    tool('c4', 'c3'),
    tool('c5'),
  ]);

  assert.deepEqual(tree.roots.map((item) => item.callId), ['c1', 'c5']);
  assert.deepEqual(tree.childrenOf.get('c1').map((item) => item.callId), ['c2', 'c3']);
  assert.deepEqual(tree.childrenOf.get('c3').map((item) => item.callId), ['c4']);
  assert.equal(tree.nestedCount, 3);
});

test('a call whose parent is not in this turn stays a root', () => {
  const tree = view.toolTree([tool('c2', 'missing'), tool('c3', 'c2')]);

  assert.deepEqual(tree.roots.map((item) => item.callId), ['c2']);
  assert.deepEqual(tree.childrenOf.get('c2').map((item) => item.callId), ['c3']);
  assert.equal(tree.nestedCount, 1);
});

test('a self reference never becomes a child of itself', () => {
  const tree = view.toolTree([tool('c1', 'c1')]);

  assert.deepEqual(tree.roots.map((item) => item.callId), ['c1']);
  assert.equal(tree.nestedCount, 0);
});

test('only subagent calls carry an origin tag', () => {
  assert.equal(view.toolOriginTag('task', '{"subagent_type":"explore"}', 'subagent'), 'subagent · explore · default');
  assert.equal(view.toolOriginTag('task', '{"subagent_type":"explore","model":"deepseek-flash"}', 'subagent'),
    'subagent · explore · deepseek-flash');
  assert.equal(view.toolOriginTag('task', '{}'), 'subagent · default');
  assert.equal(view.toolOriginTag('read_file', '{}', 'builtin'), null);
});
