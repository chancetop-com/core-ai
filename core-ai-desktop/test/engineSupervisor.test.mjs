import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import supervisorModule from '../dist/test/engineSupervisor.js';

const { EngineSupervisor } = supervisorModule;
const here = path.dirname(fileURLToPath(import.meta.url));
const fakeEngine = path.join(here, 'fixtures', 'fake-engine.mjs');

function createSupervisor() {
  const logs = [];
  const supervisor = new EngineSupervisor({
    enginePath: process.execPath,
    engineArgs: [fakeEngine],
    clientVersion: 'test',
    onEvent: () => undefined,
    onLog: (_workspace, line) => logs.push(line),
  });
  return { supervisor, logs };
}

test('caches capabilities from initialize and reports process liveness', async () => {
  const { supervisor } = createSupervisor();
  const workspace = path.dirname(fakeEngine);
  await supervisor.call(workspace, 'echo', {});
  assert.deepEqual(supervisor.capabilities(workspace), ['scheduled_tasks']);
  assert.equal(supervisor.isRunning(workspace), true);
  assert.deepEqual(supervisor.runningWorkspaces(), [workspace]);
  await supervisor.shutdownAll();
  assert.equal(supervisor.isRunning(workspace), false);
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
