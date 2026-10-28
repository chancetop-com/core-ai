// P0 IM bridge: one OCG message becomes one engine turn and one OCG reply.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import bridgeModule from '../dist/test/channelBridge.js';

const { ChannelBridge } = bridgeModule;

function message(overrides = {}) {
  return {
    id: 'm_1',
    channel: 'openclaw-weixin',
    accountId: 'acct',
    target: 'peer@im.wechat',
    sessionKey: 'agent:main:openclaw-weixin:acct:direct:peer@im.wechat',
    chatType: 'direct',
    sender: { id: 'peer@im.wechat', name: '张三' },
    groupSubject: null,
    text: '你好',
    media: [],
    platformMessageId: 'p_1',
    replyToId: null,
    wasMentioned: null,
    receivedAt: Date.now(),
    replyable: true,
    ...overrides,
  };
}

function makeBridge(t, { messages, engine, progressDelayMs }) {
  const dir = mkdtempSync(path.join(tmpdir(), 'coreai-bridge-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const queue = [...messages];
  const replies = [];
  const acks = [];
  const sends = [];
  const engineSends = [];
  const created = [];
  const ocg = {
    wait: async () => {
      if (queue.length > 0) return { ok: true, data: { messages: [queue.shift()] } };
      await new Promise((resolve) => setTimeout(resolve, 5));
      return { ok: true, data: { messages: [] } };
    },
    reply: async (id, text, isError) => {
      replies.push({ id, text, isError: isError === true });
      return { ok: true, data: {} };
    },
    ack: async (id) => {
      acks.push(id);
      return { ok: true, data: {} };
    },
    send: async (channel, to, text, accountId) => {
      sends.push({ channel, to, text, accountId });
      return { ok: true, data: {} };
    },
  };
  const callEngine = async (workspace, method, params) => {
    if (method === 'session/create') {
      created.push(params);
      return { sessionId: `s_${created.length}` };
    }
    if (method === 'session/resume') return { sessionId: params.sessionId };
    if (method === 'session/send') {
      engineSends.push(params);
      setTimeout(() => {
        bridge.onEngineEvent(workspace, 'session/event', {
          sessionId: params.sessionId,
          event: {
            type: 'turn_complete',
            output: engine.output ?? '回复内容',
            cancelled: false,
            ...(params.turnId ? { turn_id: params.turnId } : {}),
          },
        });
      }, engine.delayMs ?? 5);
      return { accepted: true, queued: false };
    }
    if (method === 'session/history') return { messages: [] };
    throw new Error(`unexpected engine method ${method}`);
  };
  const states = [];
  const bridge = new ChannelBridge({
    workspace: () => 'D:/work',
    callEngine,
    ocg,
    storeFile: path.join(dir, 'bridge.json'),
    consumer: 'core-ai-desktop',
    ...(progressDelayMs === undefined ? {} : { progressDelayMs }),
    onState: (state) => states.push(state),
    onSession: () => undefined,
    log: () => undefined,
  });
  return { bridge, replies, acks, sends, engineSends, created, states };
}

async function waitFor(predicate, timeoutMs = 2_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error('condition was not met before timeout');
}

test('direct message creates a session and replies with the turn output', async (t) => {
  const { bridge, replies, engineSends, created } = makeBridge(t, { messages: [message()], engine: { output: '你好，我是助手' } });
  bridge.start();
  await waitFor(() => replies.length === 1);
  bridge.stop();
  assert.equal(created.length, 1);
  assert.equal(created[0].title, '微信 · 张三');
  assert.equal(engineSends[0].turnId, 'm_1');
  assert.deepEqual(replies[0], { id: 'm_1', text: '你好，我是助手', isError: false });
});

test('group message without a mention is acknowledged without running a turn', async (t) => {
  const { bridge, replies, acks, created } = makeBridge(t, {
    messages: [message({ chatType: 'group', groupSubject: '项目群', wasMentioned: false })],
    engine: {},
  });
  bridge.start();
  await waitFor(() => acks.length === 1);
  bridge.stop();
  assert.equal(replies.length, 0);
  assert.equal(created.length, 0);
});

test('media-only message is answered without invoking the engine', async (t) => {
  const { bridge, replies, created } = makeBridge(t, {
    messages: [message({ text: '', media: [{ path: 'x.png', url: null, mime: 'image/png' }] })],
    engine: {},
  });
  bridge.start();
  await waitFor(() => replies.length === 1);
  bridge.stop();
  assert.equal(created.length, 0);
  assert.equal(replies[0].text, '当前版本只支持文本消息。');
});

test('a redelivered message id is acknowledged without a second turn', async (t) => {
  const { bridge, replies, acks } = makeBridge(t, {
    messages: [message(), message({ id: 'm_1', text: '重复投递' })],
    engine: {},
  });
  bridge.start();
  await waitFor(() => replies.length === 1);
  await waitFor(() => acks.length === 1);
  bridge.stop();
  assert.equal(replies.length, 1);
});

test('a slow turn sends one processing hint before the reply', async (t) => {
  const { bridge, replies, sends } = makeBridge(t, {
    messages: [message()],
    engine: { output: '完成', delayMs: 80 },
    progressDelayMs: 10,
  });
  bridge.start();
  await waitFor(() => replies.length === 1);
  bridge.stop();
  assert.equal(sends.length, 1);
  assert.equal(sends[0].text, '收到，正在处理…');
  assert.equal(sends[0].to, 'peer@im.wechat');
});

test('a fast turn sends no processing hint', async (t) => {
  const { bridge, replies, sends } = makeBridge(t, {
    messages: [message()],
    engine: { output: '快', delayMs: 5 },
    progressDelayMs: 50,
  });
  bridge.start();
  await waitFor(() => replies.length === 1);
  await new Promise((resolve) => setTimeout(resolve, 80));
  bridge.stop();
  assert.equal(sends.length, 0);
});
