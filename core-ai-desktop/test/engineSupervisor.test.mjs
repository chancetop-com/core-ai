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
  assert.deepEqual(await supervisor.quiesce(workspace), { status: 'idle', hasActiveTurn: false });
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

test('T3: EngineAlreadyRunning does not retry without --desktop-mcp and leaves engines clean', async () => {
  const { supervisor, logs } = createSupervisor({
    desktopMcp: () => true,
    extraEnv: async () => ({ FAKE_ENGINE_BUSY: '1' }),
  });
  const workspace = path.dirname(fakeEngine);
  await assert.rejects(
    () => supervisor.call(workspace, 'echo', {}),
    /engine exited \(code=2, signal=null\): another core-ai engine is already running/,
  );
  assert.equal(supervisor.isRunning(workspace), false);
  assert.equal(logs.filter((line) => line.startsWith('engine spawned:')).length, 1);
});

test('T3: unknown --desktop-mcp option retries once without flag and caches unsupported enginePath', async () => {
  const { supervisor, logs } = createSupervisor({
    desktopMcp: () => true,
    extraEnv: async () => ({ FAKE_ENGINE_UNKNOWN_OPTION: '1' }),
  });
  const wsA = path.dirname(fakeEngine);
  const wsB = here;
  await supervisor.call(wsA, 'echo', { ok: 1 });
  assert.equal(supervisor.isRunning(wsA), true);
  assert.equal(logs.filter((line) => line.startsWith('engine spawned:')).length, 2);
  assert.ok(logs.some((line) => line.includes('retrying once without it')));

  // Second workspace on the same enginePath must not pass --desktop-mcp again.
  await supervisor.call(wsB, 'echo', { ok: 2 });
  const spawns = logs.filter((line) => line.startsWith('engine spawned:'));
  assert.equal(spawns.length, 3);
  assert.equal(spawns[2].includes('--desktop-mcp'), false);
  await supervisor.shutdownAll();
});

test('D5/T3: engines.set happens after ready barrier (onTrustState -> mcp/config -> mcp/secrets -> mcp/finalize)', async () => {
  const order = [];
  let runningDuringBarrier = null;
  let supervisorRef;
  const { supervisor } = createSupervisor({
    desktopMcp: () => true,
    extraEnv: async () => ({ FAKE_ENGINE_DESKTOP_MCP: '1', FAKE_ENGINE_LEGACY_MIGRATION: '1' }),
    onTrustState: async (_ws, trust) => {
      order.push(`trust:${trust.migratedFromLegacy}:${trust.migration?.desktopFingerprint}`);
      runningDuringBarrier = supervisorRef.isRunning(ws);
    },
    mcpConfigProvider: async () => {
      order.push('provider');
      assert.equal(supervisorRef.isRunning(ws), false, 'engines.set must not happen before ready completes');
      return {
        servers: [{ name: 'demo', config: { command: 'node' } }],
        secrets: { demo: { TOKEN: 'val' } },
      };
    },
    onReady: () => {
      order.push(`ready:${supervisorRef.isRunning(ws)}`);
    },
  });
  supervisorRef = supervisor;
  const ws = path.dirname(fakeEngine);

  await supervisor.ensureRunning(ws);
  assert.equal(runningDuringBarrier, false);
  assert.equal(supervisor.isRunning(ws), true);
  assert.equal(supervisor.mcpDegraded(ws), false);
  assert.deepEqual(order, [
    'trust:true:desktop_fp_222',
    'provider',
    'ready:true',
  ]);
  const status = await supervisor.call(ws, 'mcp/status', {});
  assert.equal(status.finalized, true);
  assert.equal(status.secretsLoaded, true);
  assert.equal(status.servers.length, 1);
  await supervisor.shutdownAll();
});

test('D5/T3: process exit during mcp/config rejects ready, cleans client, and respawns next time', async () => {
  let shouldCrash = true;
  const { supervisor } = createSupervisor({
    desktopMcp: () => true,
    extraEnv: async () => ({
      FAKE_ENGINE_DESKTOP_MCP: '1',
      ...(shouldCrash ? { FAKE_ENGINE_EXIT_ON_MCP_CONFIG: '1' } : {}),
    }),
    mcpConfigProvider: async () => ({ servers: [{ name: 'demo', config: { command: 'node' } }], secrets: {} }),
  });
  const ws = path.dirname(fakeEngine);
  await assert.rejects(() => supervisor.ensureRunning(ws), /engine exited/);
  assert.equal(supervisor.isRunning(ws), false);

  shouldCrash = false;
  await supervisor.ensureRunning(ws);
  assert.equal(supervisor.isRunning(ws), true);
  assert.equal(supervisor.mcpDegraded(ws), false);
  await supervisor.shutdownAll();
});

test('D5/T3: business failure during mcp/config while process stays alive degrades MCP without killing engine', async () => {
  const { supervisor } = createSupervisor({
    desktopMcp: () => true,
    extraEnv: async () => ({
      FAKE_ENGINE_DESKTOP_MCP: '1',
      FAKE_ENGINE_FAIL_MCP_CONFIG: '1',
    }),
    mcpConfigProvider: async () => ({ servers: [{ name: 'bad', config: {} }], secrets: {} }),
  });
  const ws = path.dirname(fakeEngine);
  await supervisor.ensureRunning(ws);
  assert.equal(supervisor.isRunning(ws), true);
  assert.equal(supervisor.mcpDegraded(ws), true);
  const status = await supervisor.call(ws, 'mcp/status', {});
  assert.equal(status.finalized, true);
  await supervisor.shutdownAll();
});

test('D5/T3: shutdownAll during ready barrier kills starting engine and never populates engines map', async () => {
  const { supervisor } = createSupervisor({
    desktopMcp: () => true,
    extraEnv: async () => ({
      FAKE_ENGINE_DESKTOP_MCP: '1',
      FAKE_ENGINE_INIT_DELAY_MS: '200',
    }),
  });
  const ws = path.dirname(fakeEngine);
  const pending = supervisor.ensureRunning(ws);
  await new Promise((resolve) => setTimeout(resolve, 40));
  await supervisor.shutdownAll();
  await assert.rejects(() => pending, /shutting down|engine exited|stopped/);
  assert.equal(supervisor.isRunning(ws), false);
});
