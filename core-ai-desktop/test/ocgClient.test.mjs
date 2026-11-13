// OCG HTTP client: /ocg/v1 discovery, inbox wait, reply idempotency fields,
// and the CLI fallback when the daemon does not advertise apiVersion 1.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import clientModule from '../dist/test/ocgClient.js';

const { OcgHttpPort, createOcgPort, channelsFromStatus } = clientModule;
const TOKEN = 'local-token';

function startServer(handler) {
  return new Promise((resolve) => {
    const server = http.createServer(handler);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      resolve({ server, baseUrl: `http://127.0.0.1:${address.port}` });
    });
  });
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (chunk) => { data += chunk; });
    req.on('end', () => resolve(data ? JSON.parse(data) : {}));
  });
}

test('health, wait and reply use the versioned routes and local token', async (t) => {
  const bodies = [];
  const { server, baseUrl } = await startServer(async (req, res) => {
    if (req.headers['x-ocg-local-token'] !== TOKEN) {
      res.writeHead(401, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false, code: 'UNAUTHORIZED', message: 'bad token' }));
      return;
    }
    const url = new URL(req.url, 'http://localhost');
    res.setHeader('Content-Type', 'application/json');
    if (url.pathname === '/ocg/v1/health') {
      res.end(JSON.stringify({
        ok: true, apiVersion: 1, version: '1.3.3', features: ['inbox'],
        agentType: 'mailbox',
        mailbox: { enabled: true, pending: 1, leased: 2, done: 3, oldestPendingAt: 123 },
      }));
      return;
    }
    if (url.pathname === '/ocg/v1/inbox/wait') {
      assert.equal(url.searchParams.get('consumer'), 'core-ai-desktop');
      assert.equal(url.searchParams.get('timeoutMs'), '1000');
      res.end(JSON.stringify({
        ok: true,
        messages: [{
          id: 'm_1', channel: 'openclaw-weixin', accountId: 'acct', target: 'peer',
          sessionKey: 'agent:main:openclaw-weixin:acct:direct:peer', chatType: 'direct',
          sender: { id: 'peer', name: null }, groupSubject: null, text: 'hi', media: [],
          platformMessageId: 'p1', replyToId: null, wasMentioned: null,
          receivedAt: Date.now(), replyable: true,
        }],
        leaseExpiresAt: Date.now() + 300_000,
      }));
      return;
    }
    if (url.pathname === '/ocg/v1/inbox/m_1/reply' && req.method === 'POST') {
      const body = await readBody(req);
      bodies.push(body);
      res.end(JSON.stringify({ ok: true, id: 'm_1', via: 'reply' }));
      return;
    }
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: false, code: 'NOT_FOUND', message: 'no route' }));
  });
  t.after(() => server.close());

  const client = new OcgHttpPort({ baseUrl, token: TOKEN });
  const health = await client.health();
  assert.equal(health.ok, true);
  assert.equal(health.apiVersion, 1);
  assert.equal(health.agentType, 'mailbox');
  assert.deepEqual(health.mailbox, { enabled: true, pending: 1, leased: 2, done: 3, oldestPendingAt: 123 });

  const wait = await client.wait('core-ai-desktop', 1);
  assert.equal(wait.ok, true);
  assert.equal(wait.data.messages[0].text, 'hi');

  const reply = await client.reply('m_1', '收到', false, 'core-ai-desktop', 'm_1:1');
  assert.equal(reply.ok, true);
  assert.deepEqual(bodies[0], {
    text: '收到', isError: false, consumer: 'core-ai-desktop', clientReplyId: 'm_1:1',
  });
});

test('channelsFromStatus groups live accounts and keeps their transport', () => {
  const channels = channelsFromStatus([
    { id: 'qqbot', accountId: 'default', status: 'running', agentType: 'acp' },
    { id: 'qqbot', accountId: '1905153209', status: 'running', agentType: 'acp' },
    { id: 'qqbot', accountId: 'default', status: 'running', agentType: 'acp' },
    { id: 'openclaw-weixin', accountId: 'acct', status: 'running', agentType: 'http' },
  ]);
  assert.deepEqual(channels, [
    {
      channelId: 'qqbot',
      enabled: true,
      agentType: 'acp',
      accounts: [
        { accountId: 'default', running: true },
        { accountId: '1905153209', running: true },
      ],
    },
    {
      channelId: 'openclaw-weixin',
      enabled: true,
      agentType: 'http',
      accounts: [{ accountId: 'acct', running: true }],
    },
  ]);
});

test('a daemon without apiVersion falls back to the CLI port', async (t) => {
  const { server, baseUrl } = await startServer((_req, res) => {
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: false, code: 'NOT_FOUND', message: 'old daemon' }));
  });
  t.after(() => server.close());

  let fallbackWaits = 0;
  const fallback = {
    wait: async () => { fallbackWaits += 1; return { ok: true, data: { messages: [] } }; },
    reply: async () => ({ ok: true, data: {} }),
    ack: async () => ({ ok: true, data: {} }),
    send: async () => ({ ok: true, data: {} }),
  };
  const port = createOcgPort(fallback, { baseUrl, token: TOKEN });
  const result = await port.wait('core-ai-desktop', 1);
  assert.equal(result.ok, true);
  assert.equal(fallbackWaits, 1);
});

test('send omits an empty account id and keeps a present one', async (t) => {
  const bodies = [];
  const { server, baseUrl } = await startServer(async (req, res) => {
    const body = await readBody(req);
    bodies.push(body);
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ ok: true }));
  });
  t.after(() => server.close());

  const client = new OcgHttpPort({ baseUrl, token: TOKEN });
  assert.equal((await client.send('openclaw-weixin', 'peer', 'hi', '')).ok, true);
  assert.equal((await client.send('openclaw-weixin', 'peer', 'hi', 'acct')).ok, true);

  assert.deepEqual(bodies[0], { channel: 'openclaw-weixin', to: 'peer', text: 'hi' });
  assert.deepEqual(bodies[1], { channel: 'openclaw-weixin', to: 'peer', text: 'hi', accountId: 'acct' });
});
