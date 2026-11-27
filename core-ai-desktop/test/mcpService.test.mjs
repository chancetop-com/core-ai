import test from 'node:test';
import assert from 'node:assert/strict';
import mcpServiceModule from '../dist/test/mcpService.js';

const { McpService } = mcpServiceModule;

function createInMemoryStore() {
  const byWorkspace = new Map();
  let seq = 0;
  return {
    async list(key) {
      const servers = byWorkspace.get(key) ?? [];
      return { servers: servers.map((s) => ({ ...s })), secretNames: {} };
    },
    hashOf(servers) {
      return servers.map((s) => `${s.id}:${s.updatedAt}`).join('|');
    },
    async save(key, draft) {
      const list = byWorkspace.get(key) ?? [];
      const existing = draft.id ? list.find((s) => s.id === draft.id) : null;
      const now = new Date().toISOString();
      const record = {
        ...draft,
        id: existing?.id ?? `srv-${++seq}`,
        createdAt: existing?.createdAt ?? now,
        updatedAt: now,
      };
      byWorkspace.set(
        key,
        existing ? list.map((s) => (s.id === record.id ? record : s)) : [...list, record],
      );
      return record;
    },
    async setEnabled(key, id, enabled) {
      const list = byWorkspace.get(key) ?? [];
      byWorkspace.set(key, list.map((s) => (s.id === id ? { ...s, enabled } : s)));
    },
    async remove(key, id) {
      const list = byWorkspace.get(key) ?? [];
      byWorkspace.set(key, list.filter((s) => s.id !== id));
    },
    async importServers(key, drafts) {
      for (const draft of drafts) await this.save(key, draft);
      return { imported: drafts.map((d) => d.name), skipped: [], renamed: [] };
    },
  };
}

function createFakeSupervisor(quiescePlan, store, appliedConfigs) {
  let quiesceIndex = 0;
  let running = true;
  let probeCounter = 0;
  let onProbeStart = null;
  return {
    isShuttingDown: false,
    setOnProbeStart(fn) {
      onProbeStart = fn;
    },
    normalizeWorkspace(ws) {
      return ws.toLowerCase();
    },
    async withLifecycleLock(_ws, action) {
      return action();
    },
    isRunning() {
      return running;
    },
    capabilities() {
      return ['mcp_management_v1'];
    },
    async quiesce() {
      const next = quiescePlan[Math.min(quiesceIndex, quiescePlan.length - 1)];
      quiesceIndex += 1;
      return next;
    },
    async restart() {
      running = false;
    },
    async ensureRunning(ws) {
      running = true;
      const listed = await store.list(ws);
      appliedConfigs.push(listed.servers.map((s) => ({ name: s.name, config: s.config })));
    },
    async call(_ws, method, params) {
      if (method === 'mcp/probe/start') {
        const probeId = `probe-${++probeCounter}`;
        onProbeStart?.(probeId, params.name);
        return { probeId };
      }
      return {};
    },
  };
}

test('D2: save rejects ${env:} in url, endpoint, command, and args with MCP_SECRET_UNSUPPORTED_LOCATION', async () => {
  const store = createInMemoryStore();
  const supervisor = createFakeSupervisor([{ status: 'idle', hasActiveTurn: false }], store, []);
  const service = new McpService(store, supervisor, (ws) => ws, () => 'ask', () => undefined);

  const badConfigs = [
    { url: 'https://example.com/mcp?t=${env:SECRET_TOKEN}' },
    { url: 'https://example.com', endpoint: '/mcp?t=${env:SECRET_TOKEN}' },
    { command: 'run-${env:SECRET_TOKEN}' },
    { command: 'node', args: ['--token=${env:SECRET_TOKEN}'] },
  ];
  for (const config of badConfigs) {
    await assert.rejects(
      () => service.save('C:\\ws', { server: { name: 'bad', enabled: true, config } }),
      (err) => {
        assert.equal(err.code, 'MCP_SECRET_UNSUPPORTED_LOCATION');
        assert.equal(err.message.includes('example.com'), false);
        assert.equal(err.message.includes('SECRET_TOKEN'), false);
        return true;
      },
    );
  }
});

test('D4/T4: back-to-back saves with quiesce no-active-turn timeouts coalesce to R2, apply backoff, and mcpTest waits for reloaded', async () => {
  const store = createInMemoryStore();
  const appliedConfigs = [];
  const quiescePlan = [
    { status: 'busy', hasActiveTurn: false },
    { status: 'busy', hasActiveTurn: false },
    { status: 'idle', hasActiveTurn: false },
  ];
  const supervisor = createFakeSupervisor(quiescePlan, store, appliedConfigs);
  const delays = [];
  const reloadedEvents = [];
  const observedStates = [];
  let serviceRef;

  const service = new McpService(store, supervisor, (ws) => ws, () => 'ask', () => undefined, {
    onReloaded: (ws, revision) => reloadedEvents.push({ ws, revision }),
    delay: async (ms) => {
      delays.push(ms);
      observedStates.push(serviceRef.reloadStatus('C:\\ws').reloadState);
      await new Promise((resolve) => setTimeout(resolve, 1));
    },
  });
  serviceRef = service;
  supervisor.setOnProbeStart((probeId, name) => {
    assert.equal(service.reloadStatus('C:\\ws').appliedRevision, 2, 'mcpTest must wait until appliedRevision >= 2');
    setTimeout(() => {
      service.onEngineEvent('C:\\ws', 'mcp/probe/result', { probeId, ok: true, server: name, tools: [] });
    }, 1);
  });

  const r1 = await service.save('C:\\ws', {
    server: { name: 'srv', enabled: true, config: { command: 'node', args: ['v1.js'] } },
  });
  const r2 = await service.save('C:\\ws', {
    server: { id: r1.server.id, name: 'srv', enabled: true, config: { command: 'node', args: ['v2.js'] } },
  });
  assert.equal(r1.revision, 1);
  assert.equal(r2.revision, 2);

  const probe = await service.test('C:\\ws', 'srv', r2.revision);
  assert.equal(probe.ok, true);

  const status = service.reloadStatus('C:\\ws');
  assert.equal(status.desiredRevision, 2);
  assert.equal(status.appliedRevision, 2);
  assert.equal(status.reloadState, 'idle');
  assert.deepEqual(delays, [250, 1000]);
  assert.ok(observedStates.includes('reloading'));
  assert.ok(reloadedEvents.length >= 1);
  assert.equal(reloadedEvents[reloadedEvents.length - 1].revision, 2);
  assert.deepEqual(appliedConfigs[appliedConfigs.length - 1], [
    { name: 'srv', config: { command: 'node', args: ['v2.js'] } },
  ]);
});

test('D4/T4: quiesce timeout exceeding 3 backoffs transitions to MCP_RELOAD_STUCK and recovers on retryReload', async () => {
  const store = createInMemoryStore();
  const appliedConfigs = [];
  const quiescePlan = [
    { status: 'busy', hasActiveTurn: false },
    { status: 'busy', hasActiveTurn: false },
    { status: 'busy', hasActiveTurn: false },
    { status: 'busy', hasActiveTurn: false },
    { status: 'idle', hasActiveTurn: false },
  ];
  const supervisor = createFakeSupervisor(quiescePlan, store, appliedConfigs);
  const delays = [];

  const service = new McpService(store, supervisor, (ws) => ws, () => 'ask', () => undefined, {
    testWaitTimeoutMs: 200,
    delay: async (ms) => {
      delays.push(ms);
      await new Promise((resolve) => setTimeout(resolve, 1));
    },
  });

  const saved = await service.save('C:\\ws', {
    server: { name: 'srv', enabled: true, config: { command: 'node', args: ['a.js'] } },
  });
  assert.equal(saved.revision, 1);

  // Wait for test() to surface MCP_RELOAD_PENDING with stuck state instead of hanging forever.
  await assert.rejects(
    () => service.test('C:\\ws', 'srv', saved.revision),
    (err) => {
      assert.equal(err.code, 'MCP_RELOAD_PENDING');
      assert.equal(err.reloadState, 'stuck');
      return true;
    },
  );
  assert.deepEqual(delays, [250, 1000, 3000]);
  const stuckStatus = service.reloadStatus('C:\\ws');
  assert.equal(stuckStatus.reloadState, 'stuck');
  assert.equal(stuckStatus.lastReloadError.code, 'MCP_RELOAD_STUCK');

  // Retry succeeds on the 5th quiesce call.
  service.retryReload('C:\\ws');
  await service.waitForRevision('C:\\ws', 1, 1000);
  const recovered = service.reloadStatus('C:\\ws');
  assert.equal(recovered.reloadState, 'idle');
  assert.equal(recovered.appliedRevision, 1);
  assert.equal(recovered.lastReloadError, null);
});
