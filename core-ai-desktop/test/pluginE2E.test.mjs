import { test } from 'node:test';
import assert from 'node:assert/strict';
import { promises as fs, mkdtempSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import pluginHostModule from '../dist/test/pluginHost.js';

const { PluginHost, PLUGIN_SKILL_ROOTS_ENV, PLUGIN_STORE_ROOT_ENV, PLUGIN_STATE_FILE_ENV, PLUGIN_HOME_ENV } = pluginHostModule;
const here = path.dirname(fileURLToPath(import.meta.url));
const sample = path.resolve(here, '..', 'examples', 'plugins', 'hello-plugin');
const mediaSample = path.resolve(here, '..', 'examples', 'plugins', 'media-studio');
const insightsSample = path.resolve(here, '..', 'examples', 'plugins', 'workspace-insights');

function tempRoot() {
  return mkdtempSync(path.join(os.tmpdir(), 'coreai-plugin-host-'));
}

test('the example plugin installs, activates and reaches the engine environment', async () => {
  const host = new PluginHost({ root: tempRoot() });

  const installed = await host.installFolder(sample);
  assert.equal(installed.id, 'example.hello-plugin');
  assert.equal(installed.status, 'disabled');
  assert.deepEqual(installed.granted, []);

  const enabled = await host.setEnabled('example.hello-plugin', true);
  assert.equal(enabled.status, 'awaiting-consent');

  const active = await host.grant('example.hello-plugin', ['engine.skills', 'ui.dock', 'ui.settings', 'ui.composer']);
  assert.equal(active.status, 'active');
  assert.equal(active.contributions.dock.length, 1);
  assert.equal(active.contributions.settings.length, 1);
  assert.equal(active.contributions.composer.length, 1);

  const contributions = await host.contributions();
  assert.equal(contributions.length, 1);
  assert.equal(contributions[0].pluginId, 'example.hello-plugin');
  assert.equal(contributions[0].contributions.dock[0].title, 'Hello');

  const roots = await host.skillRoots();
  assert.equal(roots.length, 1);
  const skill = await fs.readFile(path.join(roots[0].dir, 'hello-plugin', 'SKILL.md'), 'utf8');
  assert.match(skill, /name: hello-plugin/);

  const env = await host.engineEnv();
  assert.equal(env[PLUGIN_STORE_ROOT_ENV], path.join(host.rootDir, 'packages'));
  assert.equal(env[PLUGIN_STATE_FILE_ENV], path.join(host.rootDir, 'state.json'));
  assert.equal(env[PLUGIN_HOME_ENV], host.rootDir);
  const advertised = JSON.parse(env[PLUGIN_SKILL_ROOTS_ENV]);
  assert.equal(advertised.length, 1);
  assert.equal(advertised[0].dir, roots[0].dir);
  assert.equal(advertised[0].name, 'example.hello-plugin');
  assert.equal(advertised[0].digest, roots[0].digest);
  assert.equal(typeof advertised[0].grantEpoch, 'number');

  const state = JSON.parse(await fs.readFile(env[PLUGIN_STATE_FILE_ENV], 'utf8'));
  assert.equal(state.packages[roots[0].digest].requiredCapabilities.includes('engine.skills'), true);

  const revoked = await host.revoke('example.hello-plugin');
  assert.equal(revoked.status, 'awaiting-consent');
  const revokedEnv = await host.engineEnv();
  assert.equal(revokedEnv[PLUGIN_SKILL_ROOTS_ENV], undefined);
  assert.equal((await host.contributions()).length, 0);
});

test('installing the sample twice keeps one install and the same digest', async () => {
  const host = new PluginHost({ root: tempRoot() });
  const first = await host.installFolder(sample);
  const second = await host.installFolder(sample);
  assert.equal(first.digest, second.digest);
  assert.equal((await host.list()).length, 1);
});

test('a page is resolvable only while its install is active and ui.pages is granted', async () => {
  const host = new PluginHost({ root: tempRoot() });
  await host.installFolder(mediaSample);
  await host.setEnabled('core-ai.media-studio', true);
  await host.grant('core-ai.media-studio', ['ui.pages', 'media.assets.read', 'media.assets.write', 'media.models.read', 'media.generate']);

  const page = await host.resolvePage('core-ai.media-studio', 'studio');
  assert.ok(page);
  assert.equal(page.title, 'Media Studio');
  assert.equal(page.view, 'pages/studio.html');
  assert.equal(page.scope, 'workspace');
  assert.equal(await fs.stat(path.join(page.dir, page.view)).then((stat) => stat.isFile()), true);

  const contributions = await host.contributions();
  assert.equal(contributions[0].contributions.pages.length, 1);

  await host.revoke('core-ai.media-studio');
  assert.equal(await host.resolvePage('core-ai.media-studio', 'studio'), null);

  await host.grant('core-ai.media-studio', ['ui.pages', 'media.assets.read', 'media.assets.write', 'media.models.read', 'media.generate']);
  assert.ok(await host.resolvePage('core-ai.media-studio', 'studio'));
});

test('a read-only insights page resolves with the general host capabilities', async () => {
  const host = new PluginHost({ root: tempRoot() });
  await host.installFolder(insightsSample);
  await host.setEnabled('core-ai.workspace-insights', true);
  const granted = ['ui.pages', 'sessions.read', 'memory.read', 'workspace.read', 'usage.read', 'plugin.storage', 'events.read', 'notification.send'];
  await host.grant('core-ai.workspace-insights', granted);
  const page = await host.resolvePage('core-ai.workspace-insights', 'insights');
  assert.ok(page);
  assert.equal(page.view, 'pages/insights.html');
  assert.deepEqual(page.granted.sort(), [...granted].sort());
});
