// Local tests for the engine client: framing, dispatch, errors, notifications, timeout, shutdown.
// Run with `npm test` (builds dist/test/rpcClient.js first, then node --test).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import clientModule from '../dist/test/rpcClient.js';

const { RpcClient, RpcCallError } = clientModule;
const here = path.dirname(fileURLToPath(import.meta.url));
const fakeEngine = path.join(here, 'fixtures', 'fake-engine.mjs');

function spawnClient() {
  return new RpcClient(process.execPath, [fakeEngine], { defaultTimeoutMs: 5_000 });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

test('initialize returns the result and forwards engine/ready', async () => {
  const client = spawnClient();
  const notifications = [];
  client.onNotification((method, params) => notifications.push({ method, params }));
  const result = await client.call('initialize', { protocolVersion: '1.0' });
  assert.equal(result.protocolVersion, '1.0');
  await sleep(80);
  assert.ok(notifications.some((entry) => entry.method === 'engine/ready'), 'engine/ready notification expected');
  await client.shutdown();
});

test('calls round-trip params and preserve call ordering', async () => {
  const client = spawnClient();
  const first = client.call('echo', { value: 'one' });
  const second = client.call('slow', {});
  const third = client.call('echo', { value: 'three' });
  assert.deepEqual(await first, { value: 'one' });
  assert.deepEqual(await second, { slept: true });
  assert.deepEqual(await third, { value: 'three' });
  await client.shutdown();
});

test('error frames reject with code and data', async () => {
  const client = spawnClient();
  await assert.rejects(
    () => client.call('fail', {}),
    (error) => {
      assert.ok(error instanceof RpcCallError);
      assert.equal(error.code, -32602);
      assert.equal(error.data.code, 'INVALID_PARAMS');
      return true;
    },
  );
  await client.shutdown();
});

test('engine notifications reach the handler', async () => {
  const client = spawnClient();
  const received = [];
  client.onNotification((method, params) => received.push({ method, params }));
  await client.call('emit', {});
  await sleep(80);
  const event = received.find((entry) => entry.method === 'session/event');
  assert.ok(event, 'session/event expected');
  assert.equal(event.params.event.type, 'text_chunk');
  assert.equal(event.params.sessionId, 's1');
  await client.shutdown();
});

test('a missing response surfaces as a timeout', async () => {
  const client = spawnClient();
  await assert.rejects(() => client.call('never', {}, 150), /rpc timeout: never/);
  await client.shutdown();
});

test('shutdown stops the engine process', async () => {
  const client = spawnClient();
  await client.call('echo', {});
  await client.shutdown();
  await sleep(100);
  assert.equal(client.isRunning, false);
});

test('calls after exit are rejected instead of hanging', async () => {
  const client = spawnClient();
  await client.shutdown();
  await sleep(100);
  await assert.rejects(() => client.call('echo', {}), /engine is not running|engine exited/);
});
