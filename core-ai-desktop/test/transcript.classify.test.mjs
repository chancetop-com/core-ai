// Tool-use classification: web calls get their own chip row text. Run via `npm test`
// (builds dist/test/transcriptClassify.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import classify from '../dist/test/transcriptClassify.js';

const { toolUseKind, toolUseLabel } = classify;

test('web search and web fetch map to the web chip', () => {
  assert.equal(toolUseKind('web_search', 'builtin'), 'web');
  assert.equal(toolUseKind('web_fetch', 'builtin'), 'web');
  assert.equal(toolUseKind('grep', 'builtin'), null);
});

test('web rows show the query or the URL', () => {
  assert.equal(toolUseLabel('web', 'web_search', JSON.stringify({ query: 'core-ng' })), 'search · core-ng');
  assert.equal(toolUseLabel('web', 'web_fetch', JSON.stringify({ url: 'https://example.com/a' })), 'fetch · https://example.com/a');
});

test('a web call without parseable arguments falls back to its tool name', () => {
  assert.equal(toolUseLabel('web', 'web_search', 'not json'), 'web_search');
});
