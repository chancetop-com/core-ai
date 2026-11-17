import { test } from 'node:test';
import assert from 'node:assert/strict';
import { promises as fs } from 'node:fs';
import { mkdtempSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import pluginStoreModule from '../dist/test/pluginStore.js';
import { PLUGIN_MANIFEST, SKILL_FILE, makeZip } from './pluginFixtures.mjs';

const { PluginStore, PluginError } = pluginStoreModule;

function tempRoot() {
  return mkdtempSync(path.join(os.tmpdir(), 'coreai-plugin-store-'));
}

async function writeFolder(root, manifest = PLUGIN_MANIFEST, skill = SKILL_FILE) {
  const dir = path.join(root, 'source');
  await fs.mkdir(path.join(dir, '.claude-plugin'), { recursive: true });
  await fs.writeFile(path.join(dir, '.claude-plugin', 'plugin.json'), JSON.stringify(manifest, null, 2));
  for (const [relative, content] of Object.entries(skill)) {
    const file = path.join(dir, ...relative.split('/'));
    await fs.mkdir(path.dirname(file), { recursive: true });
    await fs.writeFile(file, content);
  }
  return dir;
}

test('installs a folder as a disabled package without grants', async () => {
  const root = tempRoot();
  const store = new PluginStore({ root });
  const source = await writeFolder(root);

  const installed = await store.installFolder(source);
  assert.equal(installed.id, 'example.hello-plugin');
  assert.equal(installed.status, 'disabled');
  assert.deepEqual(installed.granted, []);
  assert.equal(installed.digest.length, 64);

  const listed = await store.list();
  assert.equal(listed.length, 1);
  assert.equal(listed[0].components.some((component) => component.kind === 'skills' && component.supported), true);
  assert.equal((await store.engineSkillRoots()).length, 0);
});

test('enabling without grants asks for consent; granting activates and injects skills', async () => {
  const root = tempRoot();
  const store = new PluginStore({ root });
  const source = await writeFolder(root);
  await store.installFolder(source);

  await store.setEnabled('example.hello-plugin', true);
  let view = (await store.list())[0];
  assert.equal(view.status, 'awaiting-consent');
  assert.equal((await store.engineSkillRoots()).length, 0);

  view = await store.grant('example.hello-plugin', ['engine.skills', 'ui.dock']);
  assert.equal(view.status, 'active');
  const roots = await store.engineSkillRoots();
  assert.equal(roots.length, 1);
  assert.equal(roots[0].name, 'example.hello-plugin');
  assert.equal(await fs.readFile(path.join(roots[0].dir, 'hello-plugin', 'SKILL.md'), 'utf8').then((text) => text.includes('Say hello')), true);

  const contributions = await store.activeContributions();
  assert.equal(contributions.length, 1);
  assert.equal(contributions[0].contributions.dock.length, 1);
});

test('grant rejects undeclared capabilities and revoke removes access', async () => {
  const root = tempRoot();
  const store = new PluginStore({ root });
  await store.installFolder(await writeFolder(root));
  await store.setEnabled('example.hello-plugin', true);

  await assert.rejects(() => store.grant('example.hello-plugin', ['session.history.read']), (error) =>
    error instanceof PluginError && error.code === 'capability_not_declared');

  await store.grant('example.hello-plugin', ['engine.skills', 'ui.dock']);
  await store.revoke('example.hello-plugin');
  const view = (await store.list())[0];
  assert.equal(view.status, 'awaiting-consent');
  assert.equal((await store.engineSkillRoots()).length, 0);
  assert.equal((await store.activeContributions()).length, 0);
});

test('a digest change requires consent again and keeps the old package for rollback', async () => {
  const root = tempRoot();
  const store = new PluginStore({ root });
  const first = await writeFolder(root);
  await store.installFolder(first);
  await store.setEnabled('example.hello-plugin', true);
  await store.grant('example.hello-plugin', ['engine.skills', 'ui.dock']);
  const digestV1 = (await store.list())[0].digest;

  const updated = {
    ...PLUGIN_MANIFEST,
    version: '1.1.0',
    coreai: { ...PLUGIN_MANIFEST.coreai, contributes: { dock: [{ id: 'overview', title: 'Hello v2', blocks: [{ type: 'text', text: 'v2' }] }] } },
  };
  const second = await writeFolder(path.join(root, 'v2'), updated, SKILL_FILE);
  await store.installFolder(second);

  const view = (await store.list())[0];
  assert.notEqual(view.digest, digestV1);
  assert.equal(view.status, 'awaiting-consent');
  assert.deepEqual(view.granted, []);
  assert.equal((await store.engineSkillRoots()).length, 0);
  const packages = await fs.readdir(path.join(root, 'packages'));
  assert.equal(packages.includes(digestV1), true);
  assert.equal(packages.length, 2);
});

test('same content reuses the package and keeps a single install', async () => {
  const root = tempRoot();
  const store = new PluginStore({ root });
  const source = await writeFolder(root);
  const first = await store.installFolder(source);
  const second = await store.installFolder(source);
  assert.equal(first.digest, second.digest);
  assert.equal((await store.list()).length, 1);
  assert.equal((await fs.readdir(path.join(root, 'packages'))).length, 1);
});

test('unsupported components are reported but not exposed', async () => {
  const root = tempRoot();
  const store = new PluginStore({ root });
  const withHooks = {
    ...PLUGIN_MANIFEST,
    coreai: {
      ...PLUGIN_MANIFEST.coreai,
      components: [{ kind: 'skills', path: 'skills' }, { kind: 'hooks', path: 'hooks' }],
    },
  };
  const source = await writeFolder(root, withHooks);
  await fs.mkdir(path.join(source, 'hooks'), { recursive: true });
  await fs.writeFile(path.join(source, 'hooks', 'hooks.json'), '{}');
  const view = await store.installFolder(source);
  assert.deepEqual(view.unsupported, ['component:hooks']);
  assert.equal(view.components.find((component) => component.kind === 'hooks').supported, false);
});

test('settings persist and are type checked', async () => {
  const root = tempRoot();
  const store = new PluginStore({ root });
  const manifest = {
    ...PLUGIN_MANIFEST,
    coreai: {
      ...PLUGIN_MANIFEST.coreai,
      capabilities: { required: ['engine.skills', 'ui.settings'], optional: [] },
      contributes: {
        settings: [{ id: 'general', title: 'General', fields: [
          { key: 'name', label: 'Name', type: 'text', default: 'Ada' },
          { key: 'copies', label: 'Copies', type: 'number', default: 1 },
          { key: 'enabled', label: 'Enabled', type: 'boolean', default: true },
        ] }],
      },
    },
  };
  await store.installFolder(await writeFolder(root, manifest));
  await store.grant('example.hello-plugin', ['engine.skills', 'ui.settings']);

  const view = await store.setSetting('example.hello-plugin', 'name', 'Grace');
  assert.equal(view.settings.name, 'Grace');
  await assert.rejects(() => store.setSetting('example.hello-plugin', 'copies', 'many'), (error) => error.code === 'invalid_setting');
  await assert.rejects(() => store.setSetting('example.hello-plugin', 'missing', 'x'), (error) => error.code === 'unknown_setting');
});

test('zip installs use the same transaction and reject unsafe archives', async () => {
  const root = tempRoot();
  const store = new PluginStore({ root });
  const archive = path.join(root, 'plugin.zip');
  const manifest = JSON.stringify(PLUGIN_MANIFEST, null, 2);
  await fs.writeFile(archive, makeZip({
    '.claude-plugin/plugin.json': manifest,
    'skills/hello-plugin/SKILL.md': SKILL_FILE['skills/hello-plugin/SKILL.md'],
  }));
  const view = await store.installZip(archive);
  assert.equal(view.id, 'example.hello-plugin');

  const bad = path.join(root, 'bad.zip');
  await fs.writeFile(bad, makeZip({ '../evil.txt': 'x' }));
  await assert.rejects(() => store.installZip(bad), /unsafe entry path/);
});

test('symlinked source files are rejected instead of followed', async (t) => {
  const root = tempRoot();
  const store = new PluginStore({ root });
  const source = await writeFolder(root);
  await fs.writeFile(path.join(source, 'README.txt'), 'x');
  const link = path.join(source, 'linked.txt');
  try {
    await fs.symlink(path.join(source, 'README.txt'), link);
  } catch {
    t.skip('symlink creation is not permitted in this environment');
    return;
  }
  await assert.rejects(() => store.installFolder(source), (error) => error.code === 'package_invalid');
});

test('a legacy package installs but never injects skills', async () => {
  const root = tempRoot();
  const store = new PluginStore({ root });
  const source = await writeFolder(root, { name: 'Legacy Thing', version: '0.1.0' });
  const view = await store.installFolder(source);
  assert.equal(view.id, 'local.legacy-thing');
  await store.setEnabled('local.legacy-thing', true);
  const listed = (await store.list())[0];
  assert.notEqual(listed.status, 'active');
  assert.equal((await store.engineSkillRoots()).length, 0);
});
