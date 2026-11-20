import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import originModule from '../dist/test/originStore.js';
import storeModule from '../dist/test/watchStore.js';
import serviceModule from '../dist/test/subscriptionService.js';

const { OriginStore } = originModule;
const { WatchStore } = storeModule;
const { SubscriptionService } = serviceModule;

function inbound() {
  return {
    id: 'm_1',
    channel: 'openclaw-weixin',
    accountId: 'acct',
    target: 'peer@im.wechat',
    sessionKey: 'session-key',
    chatType: 'direct',
    sender: { id: 'peer@im.wechat', name: '张三' },
    groupSubject: null,
    text: 'hi',
    media: [],
    platformMessageId: 'p_1',
    replyToId: null,
    wasMentioned: null,
    receivedAt: Date.now(),
    replyable: true,
  };
}

function harness(t, callEngine) {
  const dir = mkdtempSync(path.join(tmpdir(), 'coreai-subscription-'));
  const store = WatchStore.open(path.join(dir, 'observations.db'));
  const origins = new OriginStore();
  const sent = [];
  const service = new SubscriptionService({
    store,
    origins,
    callEngine,
    send: async (target, text) => {
      sent.push({ target, text });
      return { ok: true };
    },
    sessionTitle: () => '报表任务',
    log: () => undefined,
  });
  service.start();
  t.after(() => {
    service.stop();
    store.close();
    rmSync(dir, { recursive: true, force: true });
  });
  return { store, origins, service, sent };
}

function origin(origins) {
  return origins.begin({ message: inbound(), workspace: 'ws-1', sessionId: 'channel-session' });
}

async function waitFor(predicate, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error('timed out waiting for condition');
}

test('a watch binds one run and a matching ended event reaches the original channel', async (t) => {
  const calls = [];
  const { store, origins, service, sent } = harness(t, async (workspace, method, params) => {
    calls.push({ workspace, method, params });
    return { status: 'bound' };
  });
  origin(origins);

  const result = await service.handleHostRequest('ws-1', {
    operation: 'watch_session',
    sessionId: 'channel-session',
    payload: { sourceSessionId: 'source', sourceTaskRunId: 'run-1', runGeneration: 2, scope: 'current_task_run' },
  });
  assert.equal(result.status, 'active');
  assert.equal(calls[0].method, 'lifecycle/bind');
  assert.equal(calls[0].params.taskRunId, 'run-1');

  service.onEngineEvent('ws-1', 'session/lifecycle', {
    sessionId: 'source',
    event: { type: 'task_run.ended', event_id: 'e-1', task_run_id: 'run-1', run_generation: 2, outcome: 'returned', duration_ms: 900000 },
  });
  await waitFor(() => sent.length === 1);

  assert.equal(sent[0].target.channel, 'openclaw-weixin');
  assert.match(sent[0].text, /报表任务/);
  assert.match(sent[0].text, new RegExp(String(result.watchId)));
  assert.equal(store.getWatch(String(result.watchId)).deliveryState, 'accepted');

  const list = await service.handleHostRequest('ws-1', {
    operation: 'list_session_watches',
    sessionId: 'channel-session',
    payload: {},
  });
  assert.equal(list.watches.length, 1);
  assert.equal(list.watches[0].deliveryState, 'accepted');
});

test('an already-ended run is delivered immediately and reports its real outcome', async (t) => {
  const { service, origins, sent } = harness(t, async () => ({
    status: 'already_ended',
    lastEndedTaskRun: {
      run: { taskRunId: 'run-2', runGeneration: 3 },
      ended: { taskRunId: 'run-2', runGeneration: 3, outcome: 'failed', reason: 'boom', durationMs: 1000 },
    },
  }));
  origin(origins);

  const result = await service.handleHostRequest('ws-1', {
    operation: 'watch_session',
    sessionId: 'channel-session',
    payload: { sourceSessionId: 'source', sourceTaskRunId: 'run-2', runGeneration: 3, scope: 'explicit_task_run' },
  });
  assert.equal(result.status, 'already_ended');
  await waitFor(() => sent.length === 1);
  assert.match(sent[0].text, /已失败/);
  assert.doesNotMatch(sent[0].text, /boom/);
});

test('startup reconciliation turns an ended run into a delivery after a host restart', async (t) => {
  const { store, service, sent } = harness(t, async () => ({
    status: 'already_ended',
    lastEndedTaskRun: {
      run: { taskRunId: 'run-3', runGeneration: 4 },
      ended: { taskRunId: 'run-3', runGeneration: 4, outcome: 'returned', durationMs: 1000 },
    },
  }));
  store.createActiveWatch({
    watchId: 'watch-recovered',
    originId: 'origin-1',
    ownerSessionId: 'channel-session',
    sourceWorkspace: 'ws-1',
    sourceSessionId: 'source',
    taskRunId: 'run-3',
    runGeneration: 4,
    scope: 'current_task_run',
    action: 'notify_origin',
    sourceAlias: '报表任务',
    target: { channel: 'openclaw-weixin', accountId: 'acct', target: 'peer', chatType: 'direct' },
    expiresAt: Date.now() + 60_000,
  });

  service.start();
  await waitFor(() => sent.length === 1);
  assert.match(sent[0].text, /已正常收尾/);
  assert.equal(store.getWatch('watch-recovered').deliveryState, 'accepted');
});

test('the settings view lists and cancels watches without a channel origin', async (t) => {
  const { store, service } = harness(t, async () => ({ status: 'bound' }));
  store.createActiveWatch({
    watchId: 'watch-settings',
    originId: 'origin-1',
    ownerSessionId: 'channel-session',
    sourceWorkspace: 'ws-1',
    sourceSessionId: 'source',
    taskRunId: 'run-9',
    runGeneration: 1,
    scope: 'current_task_run',
    action: 'notify_origin',
    sourceAlias: '本地任务',
    target: { channel: 'openclaw-weixin', accountId: 'acct', target: 'peer-openid-123456', chatType: 'direct' },
    expiresAt: Date.now() + 60_000,
  });

  const listed = service.listForUi();
  assert.equal(listed.ok, true);
  assert.equal(listed.watches.length, 1);
  assert.equal(listed.watches[0].targetMasked.includes('peer'), true);

  const cancelled = service.cancelForUi('watch-settings');
  assert.equal(cancelled.ok, true);
  assert.equal(store.getWatch('watch-settings').state, 'cancelled');
});

test('a host request without an inbound origin is rejected', async (t) => {
  const { service } = harness(t, async () => ({ status: 'bound' }));
  await assert.rejects(
    () => service.handleHostRequest('ws-1', {
      operation: 'watch_session',
      sessionId: 'channel-session',
      payload: { sourceSessionId: 'source', sourceTaskRunId: 'run-1', runGeneration: 1 },
    }),
    (error) => error.code === 'NO_ORIGIN',
  );
});
