import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { probeModels } from '../dist/test/providerProbe.js';

async function withServer(handler, run) {
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  try {
    return await run(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

test('probe reads model ids from /models', async () => {
  await withServer((req, res) => {
    assert.equal(req.url, '/models');
    assert.equal(req.headers.authorization, 'Bearer sk-test');
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ data: [{ id: 'deepseek-chat' }, { id: 'deepseek-reasoner' }] }));
  }, async (base) => {
    const result = await probeModels(base, 'sk-test');
    assert.equal(result.ok, true);
    assert.equal(result.status, 200);
    assert.deepEqual(result.models, ['deepseek-chat', 'deepseek-reasoner']);
  });
});

test('probe omits Authorization without a key and trims trailing slashes', async () => {
  await withServer((req, res) => {
    assert.equal(req.url, '/models');
    assert.equal(req.headers.authorization, undefined);
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ data: [{ id: 'local-model' }] }));
  }, async (base) => {
    const result = await probeModels(`${base}/`, null);
    assert.equal(result.ok, true);
    assert.deepEqual(result.models, ['local-model']);
  });
});

test('probe surfaces an HTTP error body', async () => {
  await withServer((req, res) => {
    res.writeHead(401, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: { message: 'invalid key' } }));
  }, async (base) => {
    const result = await probeModels(base, 'bad');
    assert.equal(result.ok, false);
    assert.equal(result.status, 401);
    assert.match(result.error, /invalid key/);
  });
});

test('probe rejects a completions URL instead of guessing the root', async () => {
  const result = await probeModels('https://api.example.com/v1/chat/completions', 'k');
  assert.equal(result.ok, false);
  assert.match(result.error, /API root/);
});

test('probe reports a non-JSON body without failing the connection', async () => {
  await withServer((req, res) => {
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end('<html>ok</html>');
  }, async (base) => {
    const result = await probeModels(base, null);
    assert.equal(result.ok, true);
    assert.deepEqual(result.models, []);
    assert.match(result.error, /not JSON/);
  });
});
