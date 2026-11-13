// Session-completion notifications: terminal-event filtering, formatting and settings shapes.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import notifierModule from '../dist/test/completionNotifier.js';
import rendererModule from '../dist/test/notifications.js';
import ocgCliModule from '../dist/test/ocgCli.js';

const {
  CompletionNotifier,
  formatCompletionNotice,
  formatDuration,
  normalizeNotifySessions,
  normalizeNotifyTarget,
  notifySessionKey,
} = notifierModule;

const {
  maskNotifyTarget,
  normalizeNotifySessionsMap,
  normalizeNotifyTargetView,
  notifyChannelLabel,
  notifyTargetDisplayLabel,
  notifyTargetFromOcg,
  notifyTargetKey,
  notifyTargetOptionLabel,
} = rendererModule;

const { normalizeOcgTargets } = ocgCliModule;

function tick() {
  return new Promise((resolve) => setImmediate(resolve));
}

function makeNotifier(overrides = {}) {
  const sent = [];
  const deliveries = [];
  const logs = [];
  const options = {
    isEnabled: () => true,
    target: () => ({ channel: 'openclaw-weixin', accountId: 'acct', target: 'peer@im.wechat', label: '微信 · Stephen' }),
    send: async (target, text) => {
      sent.push({ target, text });
      return { ok: true };
    },
    sessionTitle: () => 'Fix build',
    log: (line) => logs.push(line),
    onDelivery: (result) => deliveries.push(result),
    ...overrides,
  };
  return { notifier: new CompletionNotifier(options), sent, deliveries, logs };
}

test('formatCompletionNotice renders completed turns with duration, usage and output', () => {
  const text = formatCompletionNotice({
    title: 'Refactor auth',
    failed: false,
    status: 'completed',
    durationMs: 192_000,
    inputTokens: 12_000,
    outputTokens: 3_000,
    costUsd: 0.12,
    output: 'All tests pass.\nNext step: review.',
  });
  assert.equal(text, [
    'Session finished — Refactor auth',
    'Duration: 3m 12s · completed',
    'Cost: $0.12 · tokens 12K in / 3K out',
    'All tests pass. Next step: review.',
  ].join('\n'));
});

test('formatCompletionNotice carries max-turns and budget statuses', () => {
  const text = formatCompletionNotice({
    title: 'Long task',
    failed: false,
    status: 'stopped at max turns · budget exhausted',
    durationMs: 65_000,
  });
  assert.match(text, /Duration: 1m 05s · stopped at max turns · budget exhausted/);
  assert.doesNotMatch(text, /tokens/);
});

test('formatCompletionNotice renders failures without output or usage', () => {
  const text = formatCompletionNotice({
    title: 'Broken run',
    failed: true,
    status: 'failed',
    durationMs: 5_400,
    output: 'partial output',
    error: 'provider timeout',
    inputTokens: 10,
  });
  assert.equal(text, [
    'Session failed — Broken run',
    'Duration: 5s · failed — provider timeout',
  ].join('\n'));
});

test('formatCompletionNotice truncates details and normalizes whitespace', () => {
  const text = formatCompletionNotice({
    title: 'Title',
    failed: false,
    status: 'completed',
    output: `${'x'.repeat(250)}\nnext`,
  });
  const detail = text.split('\n').at(-1);
  assert.equal(detail.length, 203);
  assert.ok(detail.endsWith('...'));
});

test('formatDuration handles milliseconds, seconds, minutes and hours', () => {
  assert.equal(formatDuration(250), '250ms');
  assert.equal(formatDuration(1_500), '2s');
  assert.equal(formatDuration(65_000), '1m 05s');
  assert.equal(formatDuration(3_900_000), '1h 05m');
});

test('settings shapes keep only valid targets and enabled sessions', () => {
  assert.deepEqual(
    normalizeNotifyTarget({ channel: ' openclaw-weixin ', target: ' peer ', accountId: 'acct', label: '微信', chatType: 'direct' }),
    { channel: 'openclaw-weixin', target: 'peer', accountId: 'acct', label: '微信', chatType: 'direct' },
  );
  assert.equal(normalizeNotifyTarget({ channel: '', target: 'peer' }), null);
  assert.equal(normalizeNotifyTarget({ channel: 'x', target: '' }), null);
  assert.equal(normalizeNotifyTarget(null), null);
  assert.deepEqual(normalizeNotifySessions({ a: true, b: false, c: 'true' }), { a: true });
  assert.deepEqual(normalizeNotifySessions(null), {});
  assert.equal(notifySessionKey('D:\\ws', 's1'), 'D:\\ws::s1');
});

test('notifier sends one message for a completed turn and dedupes the same turn id', async () => {
  const { notifier, sent, deliveries } = makeNotifier();
  const event = { type: 'turn_complete', cancelled: false, turn_id: 't1', duration_ms: 1_200, output: 'done' };
  notifier.onEngineEvent('D:\\ws', 'session/event', { sessionId: 's1', event });
  notifier.onEngineEvent('D:\\ws', 'session/event', { sessionId: 's1', event });
  await tick();
  assert.equal(sent.length, 1);
  assert.equal(deliveries.length, 1);
  assert.equal(deliveries[0].ok, true);
  assert.match(sent[0].text, /^Session finished — Fix build/);
  assert.match(sent[0].text, /Duration: 1s · completed/);
});

test('notifier ignores cancelled, disabled, untargeted and non-terminal events', async () => {
  const cancelled = makeNotifier();
  cancelled.notifier.onEngineEvent('D:\\ws', 'session/event', {
    sessionId: 's1',
    event: { type: 'turn_complete', cancelled: true, turn_id: 't1' },
  });
  await tick();
  assert.equal(cancelled.sent.length, 0);

  const disabled = makeNotifier({ isEnabled: () => false });
  disabled.notifier.onEngineEvent('D:\\ws', 'session/event', {
    sessionId: 's1',
    event: { type: 'turn_complete', cancelled: false, turn_id: 't1' },
  });
  await tick();
  assert.equal(disabled.sent.length, 0);

  const untargeted = makeNotifier({ target: () => null });
  untargeted.notifier.onEngineEvent('D:\\ws', 'session/event', {
    sessionId: 's1',
    event: { type: 'turn_complete', cancelled: false, turn_id: 't1' },
  });
  await tick();
  assert.equal(untargeted.sent.length, 0);
  assert.ok(untargeted.logs.some((line) => line.includes('target is not configured')));

  const nonTerminal = makeNotifier();
  nonTerminal.notifier.onEngineEvent('D:\\ws', 'session/event', {
    sessionId: 's1',
    event: { type: 'text_chunk', chunk: 'hi' },
  });
  await tick();
  assert.equal(nonTerminal.sent.length, 0);
});

test('notifier sends a failed notice for error events and keeps the turn start for duration', async () => {
  const { notifier, sent } = makeNotifier();
  notifier.onEngineEvent('D:\\ws', 'session/event', {
    sessionId: 's1',
    event: { type: 'status_change', status: 'running' },
  });
  notifier.onEngineEvent('D:\\ws', 'session/event', {
    sessionId: 's1',
    event: { type: 'error', turn_id: 'e1', message: 'provider timeout' },
  });
  await tick();
  assert.equal(sent.length, 1);
  assert.match(sent[0].text, /^Session failed — Fix build/);
  assert.match(sent[0].text, /failed — provider timeout/);
});

test('notifier reports send failures and does not throw', async () => {
  const { notifier, deliveries } = makeNotifier({ send: async () => ({ ok: false, error: 'offline' }) });
  notifier.onEngineEvent('D:\\ws', 'session/event', {
    sessionId: 's1',
    event: { type: 'turn_complete', cancelled: false, turn_id: 't1' },
  });
  await tick();
  assert.equal(deliveries.length, 1);
  assert.equal(deliveries[0].ok, false);
  assert.equal(deliveries[0].error, 'offline');
});

test('notifier scopes dedupe keys by workspace and session', async () => {
  const { notifier, sent } = makeNotifier();
  const event = { type: 'turn_complete', cancelled: false, turn_id: 'same', duration_ms: 10 };
  notifier.onEngineEvent('D:\\ws-a', 'session/event', { sessionId: 's1', event });
  notifier.onEngineEvent('D:\\ws-b', 'session/event', { sessionId: 's1', event });
  notifier.onEngineEvent('D:\\ws-a', 'session/event', { sessionId: 's2', event });
  await tick();
  assert.equal(sent.length, 3);
});

test('renderer notification helpers label, mask and map targets', () => {
  assert.equal(notifyChannelLabel('openclaw-weixin'), '微信');
  assert.equal(notifyChannelLabel('qqbot'), 'QQ');
  assert.equal(notifyChannelLabel('custom'), 'custom');
  assert.equal(maskNotifyTarget('o9cq809xMvk5ISWQRsqSPSHR5Wxo@im.wechat'), 'o9cq…chat');
  assert.equal(maskNotifyTarget('short'), 'short');
  assert.equal(notifyTargetDisplayLabel(null), 'Not configured');
  assert.equal(
    notifyTargetDisplayLabel({ channel: 'openclaw-weixin', target: 'peer', label: 'Stephen' }),
    '微信 · Stephen',
  );
  assert.equal(
    notifyTargetDisplayLabel({ channel: 'openclaw-weixin', target: 'peer', label: 'Team', chatType: 'group' }),
    '微信 · Team (shared)',
  );
  const mapped = notifyTargetFromOcg({
    channel: 'openclaw-weixin',
    accountId: 'acct',
    target: 'peer@im.wechat',
    chatType: 'direct',
    senderName: 'Stephen',
  });
  assert.deepEqual(mapped, {
    channel: 'openclaw-weixin',
    accountId: 'acct',
    target: 'peer@im.wechat',
    label: 'Stephen',
    chatType: 'direct',
  });
  assert.equal(
    notifyTargetOptionLabel({ channel: 'qqbot', target: '1905153209', chatType: 'group', groupSubject: 'Team' }),
    'QQ · Team (shared)',
  );
  assert.equal(notifyTargetKey(mapped), 'openclaw-weixin\u0000acct\u0000peer@im.wechat');
  assert.deepEqual(normalizeNotifySessionsMap({ a: true, b: false }), { a: true });
  assert.equal(normalizeNotifyTargetView({ channel: 'qqbot', target: 'peer' }).channel, 'qqbot');
  assert.equal(normalizeNotifyTargetView({ channel: 'qqbot' }), null);
});

test('normalizeOcgTargets keeps usable rows and drops malformed ones', () => {
  const targets = normalizeOcgTargets([
    { channel: 'openclaw-weixin', accountId: 'acct', target: 'peer', chatType: 'direct', senderName: 'Stephen', lastSeenAt: 1, count: 2 },
    { channel: 'qqbot', target: 'group-1', chatType: 'group', groupSubject: 'Team' },
    { channel: '', target: 'skip' },
    { channel: 'skip', target: '' },
    null,
  ]);
  assert.deepEqual(targets, [
    { channel: 'openclaw-weixin', accountId: 'acct', target: 'peer', chatType: 'direct', senderName: 'Stephen', lastSeenAt: 1, count: 2 },
    { channel: 'qqbot', target: 'group-1', chatType: 'group', groupSubject: 'Team' },
  ]);
});
