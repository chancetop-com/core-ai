// Electron smoke for the Home plugin page: the real package is loaded through PluginPageHost with a
// fake home client, so the page's rendering, todo writes and bridge isolation are all exercised.
// Run with: node_modules/.bin/electron test/homePage.smoke.cjs
const { app, BrowserWindow, protocol } = require('electron');
const assert = require('node:assert/strict');
const { mkdtempSync, mkdirSync } = require('node:fs');
const os = require('node:os');
const path = require('node:path');

protocol.registerSchemesAsPrivileged([
  { scheme: 'coreai-plugin-page', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } },
  { scheme: 'coreai-plugin-asset', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } },
]);

const packageDir = path.join(__dirname, '..', 'examples', 'plugins', 'home');
const workspace = mkdtempSync(path.join(os.tmpdir(), 'coreai-home-smoke-'));
mkdirSync(workspace, { recursive: true });

const todos = [
  { id: 't-1', title: 'Manual item', completed: false, source: 'MANUAL', originSessionIds: [] },
  {
    id: 't-2', title: 'Auto item', completed: false, source: 'AUTO', originDate: '2026-11-18',
    originSessionIds: ['s-1'], dedupeKey: 'auto:2026-11-18:s-1:abc', generatedBy: 'test-model',
  },
  { id: 't-3', title: 'Done item', completed: true, source: 'MANUAL', originSessionIds: [] },
];
let created = 0;
const storage = new Map();

const home = () => ({
  status: () => ({ signedIn: true, serverHost: 'dev.example.com', userName: 'Stephen' }),
  reports: async () => ({
    reports: [{
      id: 'r-1', title: 'Weekly revenue', displayType: 'METRIC', datasetName: 'Orders',
      updatedAt: '2026-11-18T00:00:00Z', revision: 3,
    }],
    total: 1,
    fetchedAt: '2026-11-19T00:00:00Z',
  }),
  report: async (id) => ({ id, title: 'Weekly revenue', displayType: 'METRIC', datasetName: 'Orders', revision: 3 }),
  reportData: async () => ({
    reportId: 'r-1', status: 'READY', columns: [{ name: 'Revenue', type: 'NUMBER' }],
    rows: [['1,200']], matchedRecordCount: 1, hasMore: false, evaluatedAt: '2026-11-19T00:00:00Z',
  }),
  todos: async () => ({ todos: todos.map((todo) => ({ ...todo })), fetchedAt: '2026-11-19T00:00:00Z' }),
  createTodo: async (input) => {
    created += 1;
    const todo = { id: `new-${created}`, title: input.title, completed: false, source: 'MANUAL', originSessionIds: [] };
    todos.push(todo);
    return todo;
  },
  updateTodo: async (id, patch) => {
    const todo = todos.find((item) => item.id === id);
    todo.completed = patch.completed === true;
    return { ...todo };
  },
  deleteTodo: async (id) => {
    const index = todos.findIndex((item) => item.id === id);
    if (index >= 0) todos.splice(index, 1);
    return { deleted: true };
  },
  todoDigest: async () => ({
    date: '2026-11-18', status: 'COMPLETED', todoCount: 2, skippedCount: 1,
    sessionCount: 3, model: 'test-model', completedAt: '2026-11-19T00:05:00Z',
  }),
});

function page() {
  return {
    pluginId: 'core-ai.home', pluginName: 'My Home', pageId: 'home', title: 'My Home',
    view: 'pages/home.html', scope: 'workspace', dir: packageDir, digest: 'digest',
    granted: ['ui.pages', 'reports.read', 'todos.read', 'todos.write', 'plugin.storage'],
  };
}

async function waitFor(view, expression, timeoutMs = 5000) {
  const started = Date.now();
  for (;;) {
    const value = await view.webContents.executeJavaScript(expression);
    if (value) return value;
    if (Date.now() - started > timeoutMs) throw new Error(`timed out waiting for: ${expression}`);
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}

async function main() {
  await app.whenReady();
  setTimeout(() => {
    console.error('home page smoke timed out');
    app.exit(2);
  }, 30000).unref();

  const { PluginPageHost } = require('../dist/test/pluginPageHost.js');
  const window = new BrowserWindow({ show: false, width: 1200, height: 800, webPreferences: { contextIsolation: true, nodeIntegration: false } });
  const host = new PluginPageHost({
    pluginHost: () => ({
      resolvePage: async () => page(),
      list: async () => [],
      storageGet: async (_pluginId, key) => storage.get(key) ?? null,
      storageSet: async (_pluginId, key, value) => { storage.set(key, value); },
      storageDelete: async (_pluginId, key) => storage.delete(key),
      storageKeys: async () => [...storage.keys()],
    }),
    supervisor: () => ({ ensureTrustState: async () => ({ state: 'trusted' }), call: async () => ({}) }),
    preloadPath: path.join(__dirname, '..', 'dist', 'pluginPagePreload.js'),
    home,
    onLog: (line) => console.log('[page]', line),
  });

  const { token } = await host.open({
    window, workspace, pluginId: 'core-ai.home', pageId: 'home',
    bounds: { x: 0, y: 0, width: 1100, height: 760 },
  });
  const view = host.instances.get(token).view;
  view.webContents.on('console-message', (_event, level, message) => console.log('page console', level, message));

  await waitFor(view, 'document.querySelectorAll("#report-list .report").length === 1');
  assert.equal(await view.webContents.executeJavaScript('document.querySelector("#report-list .report .title").firstChild.textContent'), 'Weekly revenue');
  await waitFor(view, 'document.querySelectorAll("#todo-active .todo").length === 2');
  assert.equal(await view.webContents.executeJavaScript('document.querySelectorAll("#todo-done .todo").length'), 1);
  assert.equal(await view.webContents.executeJavaScript('document.querySelector("#todo-active .badge.auto") !== null'), true);
  assert.equal(await view.webContents.executeJavaScript('document.querySelector("#detail-body") !== null'), true);
  await waitFor(view, 'document.querySelector("#detail-body .metric .value") !== null');

  await view.webContents.executeJavaScript(`(() => {
    const input = document.getElementById('todo-title');
    input.value = 'New item from the smoke';
    document.getElementById('todo-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  })()`);
  await waitFor(view, 'document.querySelectorAll("#todo-active .todo").length === 3');
  await waitFor(view, 'document.getElementById("todo-title").value === ""');
  assert.equal(todos.length, 4);

  await view.webContents.executeJavaScript(`(() => {
    const row = [...document.querySelectorAll('#todo-active .todo')]
      .find((item) => item.textContent.includes('New item from the smoke'));
    row.querySelector('input[type="checkbox"]').click();
  })()`);
  await waitFor(view, 'document.querySelectorAll("#todo-active .todo").length === 2');
  assert.equal(await view.webContents.executeJavaScript('document.querySelectorAll("#todo-done .todo").length'), 2);

  await view.webContents.executeJavaScript(`(() => {
    const row = [...document.querySelectorAll('#todo-active .todo')]
      .find((item) => item.textContent.includes('Manual item'));
    row.querySelector('button.icon-button').click();
  })()`);
  await waitFor(view, 'document.querySelectorAll("#todo-active .todo").length === 1');
  assert.equal(todos.some((todo) => todo.title === 'Manual item'), false);

  assert.equal(await view.webContents.executeJavaScript('typeof require'), 'undefined');
  assert.equal(await view.webContents.executeJavaScript('typeof window.coreaiPlugin.home.todos'), 'function');
  assert.equal(await view.webContents.executeJavaScript('document.getElementById("digest").textContent.includes("Daily summary")'), true);

  host.close(token);
  window.destroy();
  console.log('home page smoke passed');
  app.exit(0);
}

main().catch((error) => {
  console.error(error);
  app.exit(1);
});
