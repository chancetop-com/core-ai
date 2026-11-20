import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import storeModule from '../dist/test/watchStore.js';

const { WatchStore } = storeModule;

function input(overrides = {}) {
  return {
    watchId: 'watch-1',
    originId: 'origin-1',
    ownerSessionId: 'channel-session',
    sourceWorkspace: 'ws-1',
    sourceSessionId: 'source-session',
    taskRunId: 'run-1',
    runGeneration: 2,
    scope: 'current_task_run',
    action: 'notify_origin',
    sourceAlias: '报表任务',
    target: { channel: 'openclaw-weixin', accountId: 'acct', target: 'peer', chatType: 'direct' },
    expiresAt: Date.now() + 60_000,
    ...overrides,
  };
}

function harness(t) {
  const dir = mkdtempSync(path.join(tmpdir(), 'coreai-watch-store-'));
  const store = WatchStore.open(path.join(dir, 'observations.db'));
  t.after(() => {
    store.close();
    rmSync(dir, { recursive: true, force: true });
  });
  return store;
}

test('matching a watch and creating its delivery is atomic and idempotent', (t) => {
  const store = harness(t);
  store.createActiveWatch(input());
  const delivery = store.matchWatch('watch-1', 'event-1', 'body');
  assert.equal(delivery.state, 'queued');
  assert.equal(store.matchWatch('watch-1', 'event-1', 'body'), null, 'a second match must not create another delivery');
  assert.equal(store.listQueuedDeliveries(10).length, 1);
  assert.equal(store.getWatch('watch-1').deliveryState, 'queued');
});

test('claim is exclusive and only queued deliveries can be sent', (t) => {
  const store = harness(t);
  store.createActiveWatch(input());
  const delivery = store.matchWatch('watch-1', 'event-1', 'body');
  assert.equal(store.claimDelivery(delivery.deliveryId), true);
  assert.equal(store.claimDelivery(delivery.deliveryId), false);
  store.finishDelivery(delivery.deliveryId, 'accepted', null);
  assert.equal(store.listQueuedDeliveries(10).length, 0);
});

test('cancelling a watch cancels only its queued delivery', (t) => {
  const store = harness(t);
  store.createActiveWatch(input());
  const delivery = store.matchWatch('watch-1', 'event-1', 'body');
  store.cancelWatch('watch-1');
  assert.equal(store.getWatch('watch-1').state, 'cancelled');
  assert.equal(store.getWatch('watch-1').deliveryState, 'cancelled');
  assert.equal(store.claimDelivery(delivery.deliveryId), false);
});
