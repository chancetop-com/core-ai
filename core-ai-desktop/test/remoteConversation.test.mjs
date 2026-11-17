// Direct remote-conversation transport tests: SSE framing, credential-scoped server selection and
// the input validation that keeps renderer-supplied ids from becoming arbitrary paths.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import remote from '../dist/test/remoteConversation.js';

const { RemoteConversationClient, SseFrameParser, normalizeServerUrl, validSessionId } = remote;

function client(entries) {
  return new RemoteConversationClient({ loadEntries: () => entries });
}

test('SSE frames survive chunk boundaries, CRLF and multiple events', () => {
  const parser = new SseFrameParser();
  assert.deepEqual(parser.push('data: {"type":"status_change","status":"RUN'), []);
  assert.deepEqual(parser.push('NING"}\r\n'), [{ type: 'status_change', status: 'RUNNING' }]);
  const events = parser.push('data: {"type":"text_chunk","content":"a"}\ndata: {"type":"text_chunk","content":"b"}\n');
  assert.deepEqual(events.map((event) => event.content), ['a', 'b']);
});

test('SSE parser ignores comments, blank data and malformed frames', () => {
  const parser = new SseFrameParser();
  assert.deepEqual(parser.push(': keep-alive\ndata:\ndata: {not-json}\n'), []);
});

test('servers are normalized and credentials never leave the client', () => {
  const instance = client([
    { server_url: 'https://dev.example.com/', api_key: 'secret', user_id: 'u-1', name: 'Dev', active: false },
    { server_url: 'https://prod.example.com', api_key: '', user_id: 'u-2', name: 'Prod', active: true },
  ]);
  const servers = instance.servers();
  assert.equal(servers.length, 1);
  assert.equal(servers[0].serverUrl, 'https://dev.example.com');
  assert.equal(Object.prototype.hasOwnProperty.call(servers[0], 'apiKey'), false);
  assert.equal(JSON.stringify(servers).includes('secret'), false);
});

test('normalizeServerUrl strips only trailing slashes', () => {
  assert.equal(normalizeServerUrl(' https://x.test// '), 'https://x.test');
});

test('session ids reject path-shaped input', () => {
  assert.equal(validSessionId('4d2b30b0-91b2-4f1d-9f55-0d72f4a1b9aa'), true);
  assert.equal(validSessionId('../etc/passwd'), false);
  assert.equal(validSessionId('a/b'), false);
  assert.equal(validSessionId(''), false);
  const instance = client([{ server_url: 'https://dev.example.com', api_key: 'key' }]);
  assert.throws(() => instance.subscribe('https://dev.example.com', '../escape'), /invalid session id/);
});

test('calls reject a server the user is not signed in to', async () => {
  const instance = client([{ server_url: 'https://dev.example.com', api_key: 'key' }]);
  await assert.rejects(() => instance.capabilities('https://other.example.com'), /not signed in/);
});
