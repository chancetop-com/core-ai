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
    granted: ['ui.pages', 'media.assets.read', 'media.models.read', 'media.generate'],
  };
  let granted = [...page.granted];
  const calls = [];
  const supervisor = {
    ensureTrustState: async () => ({ state: 'trusted' }),
    call: async (_workspace, method) => {
      calls.push(method);
      if (method === 'artifacts/list') {
        return { files: [{ name: 'one.png', path: '.core-ai/media/images/one.png', kind: 'image', size: 3 }] };
      }
      return { kind: 'image', files: [] };
    },
  };
  const host = new PluginPageHost({
    pluginHost: () => ({ resolvePage: async () => ({ ...page, granted }) }),
    supervisor: () => supervisor,
    preloadPath: path.join(__dirname, '..', 'dist', 'pluginPagePreload.js'),
  });
  const { token } = await host.open({
    window, workspace, pluginId: 'test.page', pageId: 'main',
    bounds: { x: 0, y: 0, width: 800, height: 600 },
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
  await view.webContents.executeJavaScript('window.coreaiPlugin.media.generate({ kind: "image", prompt: "x" })');
  assert.deepEqual(calls, ['artifacts/list', 'media/generate']);

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
