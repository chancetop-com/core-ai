// Manual Electron smoke for the plugin page host. Run with:
//   node_modules/.bin/electron test/pluginPageHost.smoke.cjs
// It uses a fake plugin host/supervisor and never touches the user's plugin store.
const { app, BrowserWindow, protocol } = require('electron');
const assert = require('node:assert/strict');
const { mkdtempSync, mkdirSync, writeFileSync } = require('node:fs');
const os = require('node:os');
const path = require('node:path');

protocol.registerSchemesAsPrivileged([
  { scheme: 'coreai-plugin-page', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } },
  { scheme: 'coreai-plugin-asset', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } },
]);

const root = mkdtempSync(path.join(os.tmpdir(), 'coreai-page-smoke-'));
const packageDir = path.join(root, 'package');
const workspace = path.join(root, 'workspace');
mkdirSync(packageDir, { recursive: true });
mkdirSync(path.join(workspace, '.core-ai', 'media', 'images'), { recursive: true });
writeFileSync(path.join(workspace, '.core-ai', 'report.md'), '# smoke artifact\n');
writeFileSync(path.join(packageDir, 'index.html'), '<!doctype html><meta charset="utf-8"><body><script>window.pageReady=true</script><p>ok</p></body>');
writeFileSync(path.join(workspace, '.core-ai', 'media', 'images', 'one.png'),
  Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64'));

async function main() {
  console.log('waiting for app ready');
  await app.whenReady();
  console.log('app ready');
  setTimeout(() => {
    console.error('smoke timed out');
    app.exit(2);
  }, 20000).unref();
  const { PluginPageHost } = require('../dist/test/pluginPageHost.js');
  const window = new BrowserWindow({ show: false, width: 900, height: 700, webPreferences: { contextIsolation: true, nodeIntegration: false } });
  const page = {
    pluginId: 'test.page', pluginName: 'Test Page', pageId: 'main', title: 'Test Page',
    view: 'index.html', scope: 'workspace', dir: packageDir, digest: 'digest',
    granted: ['ui.pages', 'media.assets.read', 'media.assets.write', 'media.models.read', 'media.generate',
      'sessions.read', 'sessions.write', 'memory.read', 'memory.write', 'workspace.read', 'usage.read', 'plugin.storage', 'events.read', 'notification.send',
      'schedules.read', 'schedules.write', 'artifacts.read'],
  };
  let granted = [...page.granted];
  const calls = [];
  const storage = new Map();
  let notifications = 0;
  const supervisor = {
    ensureTrustState: async () => ({ state: 'trusted' }),
    call: async (_workspace, method) => {
      calls.push(method);
      if (method === 'artifacts/list') {
        return { files: [{ name: 'one.png', path: '.core-ai/media/images/one.png', kind: 'image', size: 3 }] };
      }
      if (method === 'session/list') {
        return { sessions: [{ sessionId: 's-1', title: 'One', state: 'ok', status: 'idle', updatedAt: '2026-11-18T00:00:00Z' }] };
      }
      if (method === 'session/rename') {
        return { sessionId: 's-1' };
      }
      if (method === 'schedules/list') {
        return { tasks: [{ id: 'task-1', name: 'Daily', enabled: true }] };
      }
      if (method === 'schedules/get') {
        return { task: { id: 'task-1', name: 'Daily', enabled: true }, runs: [] };
      }
      if (method === 'schedules/update') {
        return { task: { id: 'task-1', name: 'Daily', enabled: false } };
      }
      if (method === 'schedules/trigger') {
        return { run: { id: 'run-1' } };
      }
      if (method === 'schedules/delete') {
        return {};
      }
      if (method === 'memory/list') {
        return { files: [{ path: 'knowledge/a.md', size: 10, modifiedAt: '2026-11-18T00:00:00Z' }] };
      }
      if (method === 'memory/read') {
        return { path: 'knowledge/a.md', content: '# A' };
      }
      if (method === 'memory/write') {
        return { path: 'knowledge/plugin-notes/a.md', mode: 'append', size: 5 };
      }
      if (method === 'usage/summary') {
        return { range: 'all', totals: { sessions: 1, inputTokens: 10, outputTokens: 5, cachedTokens: 0, costUsd: 0.01, turns: 1, completedTurns: 1 }, sessions: [] };
      }
      return { kind: 'image', files: [] };
    },
  };
  const host = new PluginPageHost({
    pluginHost: () => ({
      resolvePage: async () => ({ ...page, granted }),
      list: async () => [{
        id: 'test.page',
        status: 'active',
        provides: [{ id: 'demo.provider', version: '1' }],
        requires: ['memory.read'],
      }],
      storageGet: async (_pluginId, key) => storage.get(key) ?? null,
      storageSet: async (_pluginId, key, value) => { storage.set(key, value); },
      storageDelete: async (_pluginId, key) => storage.delete(key),
      storageKeys: async () => [...storage.keys()],
    }),
    supervisor: () => supervisor,
    preloadPath: path.join(__dirname, '..', 'dist', 'pluginPagePreload.js'),
    onLog: (line) => console.log('[page]', line),
    notify: async () => {
      notifications += 1;
      return { ok: true };
    },
  });
  const { token } = await host.open({
    window, workspace, pluginId: 'test.page', pageId: 'main',
    bounds: { x: 0, y: 0, width: 800, height: 600 },
    artifactPath: '.core-ai/report.md',
  });
  console.log('page opened', token);
  const view = host.instances.get(token).view;
  view.webContents.on('console-message', (_event, level, message) => console.log('page console', level, message));
  view.webContents.on('did-fail-load', (_event, code, description, url) => console.log('page fail', code, description, url));
  await view.webContents.executeJavaScript('new Promise((resolve) => document.readyState === "loading" ? addEventListener("DOMContentLoaded", resolve, { once: true }) : resolve())');
  console.log('dom ready');
  const context = await view.webContents.executeJavaScript('window.coreaiPlugin.context()');
  assert.equal(context.pluginId, 'test.page');
  assert.deepEqual(context.capabilities, page.granted);
  assert.equal(context.artifactPath, '.core-ai/report.md');
  const artifact = await view.webContents.executeJavaScript('window.coreaiPlugin.artifacts.read()');
  assert.equal(artifact.content, '# smoke artifact\n');
  const assets = await view.webContents.executeJavaScript('window.coreaiPlugin.assets.list()');
  const assetResponse = await view.webContents.session.fetch(assets.files[0].url).catch((error) => ({ error }));
  console.log('asset response', assetResponse.status, assetResponse.error ? assetResponse.error.message : '');
  assert.equal(assets.files.length, 1);
  assert.match(assets.files[0].url, /^coreai-plugin-asset:\/\/assets\//);
  const loaded = await view.webContents.executeJavaScript(`new Promise((resolve) => {
    const image = new Image();
    image.onload = () => resolve('ok');
    image.onerror = () => resolve('error');
    image.src = ${JSON.stringify(assets.files[0].url)};
  })`);
  assert.equal(loaded, 'ok');
  assert.equal(await view.webContents.executeJavaScript('typeof require'), 'undefined');
  const imported = await view.webContents.executeJavaScript(
    `window.coreaiPlugin.assets.import({ name: "ref.png", data: "${Buffer.from('tiny').toString('base64')}" })`,
  );
  assert.match(imported.file.path, /^\.core-ai\/media\/imports\/import-[0-9a-f-]+\.png$/);
  assert.equal(imported.file.kind, 'image');
  await view.webContents.executeJavaScript('window.coreaiPlugin.media.generate({ kind: "image", prompt: "x" })');
  assert.deepEqual(calls, ['artifacts/list', 'media/generate']);

  const sessions = await view.webContents.executeJavaScript('window.coreaiPlugin.sessions.list({ limit: 10 })');
  assert.equal(sessions.sessions[0].sessionId, 's-1');
  const renamed = await view.webContents.executeJavaScript('window.coreaiPlugin.sessions.rename("s-1", "Renamed")');
  assert.equal(renamed.sessionId, 's-1');
  const schedules = await view.webContents.executeJavaScript('window.coreaiPlugin.schedules.list()');
  assert.equal(schedules.tasks[0].id, 'task-1');
  const schedule = await view.webContents.executeJavaScript('window.coreaiPlugin.schedules.get("task-1")');
  assert.equal(schedule.task.enabled, true);
  const updated = await view.webContents.executeJavaScript('window.coreaiPlugin.schedules.update({ id: "task-1", enabled: false })');
  assert.equal(updated.task.enabled, false);
  await view.webContents.executeJavaScript('window.coreaiPlugin.schedules.trigger("task-1")');
  await view.webContents.executeJavaScript('window.coreaiPlugin.schedules.delete("task-1")');
  const memory = await view.webContents.executeJavaScript('window.coreaiPlugin.memory.list()');
  assert.equal(memory.files[0].path, 'knowledge/a.md');
  const written = await view.webContents.executeJavaScript(
    'window.coreaiPlugin.memory.write("knowledge/plugin-notes/a.md", "note")',
  );
  assert.equal(written.mode, 'append');
  assert.equal(written.size, 5);
  const services = await view.webContents.executeJavaScript('window.coreaiPlugin.services.list()');
  assert.equal(services.plugins[0].provides[0].id, 'demo.provider');
  assert.equal(services.own.requires[0], 'memory.read');
  assert.ok(services.host.includes('memory.write'));
  const summary = await view.webContents.executeJavaScript('window.coreaiPlugin.workspace.summary()');
  assert.equal(summary.sessionCount, 1);
  assert.equal(summary.artifactCount, 1);
  assert.equal(summary.memoryFileCount, 1);
  const usage = await view.webContents.executeJavaScript('window.coreaiPlugin.usage.summary("all")');
  assert.equal(usage.totals.inputTokens, 10);
  const sent = await view.webContents.executeJavaScript('window.coreaiPlugin.notify.send("hello")');
  assert.equal(sent.ok, true);
  assert.equal(notifications, 1);
  for (let i = 0; i < 4; i++) {
    await view.webContents.executeJavaScript('window.coreaiPlugin.notify.send("hello")');
  }
  await assert.rejects(
    view.webContents.executeJavaScript('window.coreaiPlugin.notify.send("hello")'),
    /rate limit/,
  );
  await view.webContents.executeJavaScript('window.coreaiPlugin.storage.set("counter", "1")');
  const stored = await view.webContents.executeJavaScript('window.coreaiPlugin.storage.get("counter")');
  assert.equal(stored.value, '1');
  assert.deepEqual(await view.webContents.executeJavaScript('window.coreaiPlugin.storage.keys()'), { keys: ['counter'] });
  assert.deepEqual(await view.webContents.executeJavaScript('window.coreaiPlugin.storage.delete("counter")'), { deleted: true });

  await view.webContents.executeJavaScript(`window.__events = [];
    window.coreaiPlugin.events.on((event) => window.__events.push(event));
    window.coreaiPlugin.events.subscribe(['turn.completed', 'tool.finished'])`);
  host.onEngineEvent(workspace, 'session/event', {
    sessionId: 's-1',
    event: { type: 'turn_complete', turn_id: 't-1', cancelled: false, input_tokens: 10, output_tokens: 5, output: 'secret' },
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
  const received = await view.webContents.executeJavaScript('window.__events');
  assert.equal(received.length, 1);
  assert.equal(received[0].type, 'turn.completed');
  assert.equal(received[0].data.inputTokens, 10);
  assert.equal(received[0].data.output, undefined);

  granted = granted.filter((capability) => capability !== 'media.assets.write');
  await assert.rejects(
    view.webContents.executeJavaScript('window.coreaiPlugin.assets.import({ name: "ref.png", data: "dGlueQ==" })'),
    /media\.assets\.write is not granted/,
  );

  // A grant change is visible to the next bridge call without reopening the page.
  granted = granted.filter((capability) => capability !== 'media.generate');
  await assert.rejects(
    view.webContents.executeJavaScript('window.coreaiPlugin.media.generate({ kind: "image", prompt: "x" })'),
    /media\.generate is not granted/,
  );
  host.close(token);
  window.destroy();
  console.log('plugin page smoke passed');
  app.exit(0);
}

main().catch((error) => {
  console.error(error);
  app.exit(1);
});
