import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import supervisorModule from '../dist/test/engineSupervisor.js';

const { EngineSupervisor } = supervisorModule;
const here = path.dirname(fileURLToPath(import.meta.url));
const fakeEngine = path.join(here, 'fixtures', 'fake-engine.mjs');

function createSupervisor(options = {}) {
  const logs = [];
  const supervisor = new EngineSupervisor({
    enginePath: process.execPath,
    engineArgs: [fakeEngine],
    clientVersion: 'test',
    onEvent: () => undefined,
    onLog: (_workspace, line) => logs.push(line),
    ...options,
  });
  return { supervisor, logs };
}

test('caches capabilities from initialize and reports process liveness', async () => {
  const { supervisor } = createSupervisor();
  const workspace = path.dirname(fakeEngine);
  await supervisor.call(workspace, 'echo', {});
  assert.deepEqual(supervisor.capabilities(workspace), ['scheduled_tasks']);
  assert.equal(supervisor.trustState(workspace).state, 'trusted');
  assert.equal(supervisor.trustState(workspace).fingerprint, 'abc123');
  assert.equal(await supervisor.quiesce(workspace), 'ok');
  await supervisor.resumeIntake(workspace);
  assert.equal(supervisor.isRunning(workspace), true);
  assert.deepEqual(supervisor.runningWorkspaces(), [workspace]);
  await supervisor.shutdownAll();
  assert.equal(supervisor.isRunning(workspace), false);
});

test('ensureTrustState starts the engine and returns the handshake trust state', async () => {
  const { supervisor } = createSupervisor();
  const workspace = path.dirname(fakeEngine);
  const trust = await supervisor.ensureTrustState(workspace);
  assert.equal(trust.state, 'trusted');
  assert.equal(trust.engineRestricted, false);
  assert.equal(supervisor.trustState(workspace).key, 'C:\\ws');
  await supervisor.shutdownAll();
});

test('discards a dead engine entry and respawns it on the next call', async () => {
  const { supervisor, logs } = createSupervisor();
  const workspace = path.dirname(fakeEngine);
  await supervisor.call(workspace, 'echo', {});
  await assert.rejects(() => supervisor.call(workspace, 'exit', {}), /engine exited/);
  await new Promise((resolve) => setTimeout(resolve, 40));
  assert.equal(supervisor.isRunning(workspace), false);
  await supervisor.call(workspace, 'echo', {});
  assert.equal(supervisor.isRunning(workspace), true);
  assert.ok(logs.some((line) => line.includes('discarded exited engine')));
  await supervisor.shutdownAll();
});

test('concurrent first calls share a single engine spawn', async () => {
  const { supervisor, logs } = createSupervisor({
    extraEnv: async () => {
      await new Promise((resolve) => setTimeout(resolve, 30));
      return {};
    },
  });
  const workspace = path.dirname(fakeEngine);
  const results = await Promise.all([
    supervisor.call(workspace, 'echo', { id: 1 }),
    supervisor.call(workspace, 'echo', { id: 2 }),
  ]);
  assert.deepEqual(results, [{ id: 1 }, { id: 2 }]);
  assert.equal(logs.filter((line) => line.startsWith('engine spawned:')).length, 1);
  await supervisor.shutdownAll();
});

test('reports the engine stderr reason when the process exits', async () => {
  const { supervisor } = createSupervisor({ extraEnv: async () => ({ FAKE_ENGINE_BUSY: '1' }) });
  const workspace = path.dirname(fakeEngine);
  await assert.rejects(
    () => supervisor.call(workspace, 'echo', {}),
    /engine exited \(code=2, signal=null\): another core-ai engine is already running/,
  );
  assert.equal(supervisor.isRunning(workspace), false);
});
