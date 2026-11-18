// Direct remote-conversation transport tests: SSE framing, credential-scoped server selection and
// the input validation that keeps renderer-supplied ids from becoming arbitrary paths.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import remote from '../dist/test/remoteConversation.js';

const { RemoteConversationClient, SseFrameParser, normalizeServerUrl, validSessionId, attachmentContentType } = remote;

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

test('attachment content types decide multimodal vs sandbox and keep the extension known', () => {
  assert.equal(attachmentContentType('shot.PNG'), 'image/png');
  assert.equal(attachmentContentType('clip.mov'), 'video/quicktime');
  assert.equal(attachmentContentType('report.pdf'), 'application/pdf');
  assert.equal(attachmentContentType('notes.docx'), 'application/vnd.openxmlformats-officedocument.wordprocessingml.document');
  assert.equal(attachmentContentType('data.unknown'), 'application/octet-stream');
});

test('attachments upload through the session credential and ride the send payload', async () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'coreai-attach-'));
  const file = path.join(dir, 'note.png');
  writeFileSync(file, Buffer.from([0x89, 0x50, 0x4e, 0x47]));
  const seen = { credential: null, upload: null, send: null };
  const server = http.createServer((request, response) => {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1');
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => {
      const body = Buffer.concat(chunks);
      if (request.method === 'GET' && url.pathname.endsWith('/attachments/credential')) {
        seen.credential = { session: url.pathname.split('/')[5], query: Object.fromEntries(url.searchParams) };
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({
          upload_url: `http://127.0.0.1:${server.address().port}/storage/blob?sig=1`,
          blob_url: 'https://cdn.example/ai/conv/s-1/one.png',
          container: 'static',
          blob_name: 'ai/conv/s-1/one.png',
        }));
        return;
      }
      if (request.method === 'PUT' && url.pathname === '/storage/blob') {
        seen.upload = { headers: request.headers, bytes: body.length, signature: url.searchParams.get('sig') };
        response.writeHead(201);
        response.end();
        return;
      }
      if (request.method === 'POST' && url.pathname.endsWith('/messages')) {
        seen.send = JSON.parse(body.toString('utf8'));
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ duplicate: false }));
        return;
      }
      response.writeHead(404);
      response.end();
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const instance = client([{ server_url: base, api_key: 'key' }]);
    const upload = await instance.uploadAttachment(base, 's-1', file);
    assert.equal(upload.type, 'IMAGE');
    assert.equal(upload.category, 'multimodal');
    assert.equal(upload.size, 4);
    assert.deepEqual(seen.credential, { session: 's-1', query: { content_type: 'image/png', category: 'multimodal' } });
    assert.equal(seen.upload.headers['x-ms-blob-type'], 'BlockBlob');
    assert.equal(seen.upload.headers['x-ms-blob-content-type'], 'image/png');
    assert.equal(seen.upload.bytes, 4);
    assert.equal(seen.upload.signature, '1');

    await instance.send(base, 's-1', 'look at this', [upload]);
    assert.equal(seen.send.message, 'look at this');
    assert.deepEqual(seen.send.attachments, [{
      url: 'https://cdn.example/ai/conv/s-1/one.png',
      type: 'IMAGE',
      file_name: 'note.png',
      content_type: 'image/png',
      category: 'multimodal',
      container: 'static',
      blob_name: 'ai/conv/s-1/one.png',
    }]);

    await assert.rejects(() => instance.send(base, 's-1', '', []), /message or attachments/);
    await assert.rejects(() => instance.uploadAttachment(base, 's-1', path.join(dir, 'missing.png')), /file not found/);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
