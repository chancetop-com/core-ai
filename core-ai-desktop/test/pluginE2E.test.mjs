import { test } from 'node:test';
import assert from 'node:assert/strict';
import { promises as fs, mkdtempSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import pluginHostModule from '../dist/test/pluginHost.js';

const { PluginHost, PLUGIN_SKILL_ROOTS_ENV, PLUGIN_STORE_ROOT_ENV } = pluginHostModule;
const here = path.dirname(fileURLToPath(import.meta.url));
const sample = path.resolve(here, '..', 'examples', 'plugins', 'hello-plugin');

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
  const advertised = JSON.parse(env[PLUGIN_SKILL_ROOTS_ENV]);
  assert.equal(advertised.length, 1);
  assert.equal(advertised[0].dir, roots[0].dir);

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
