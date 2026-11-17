import { test } from 'node:test';
import assert from 'node:assert/strict';
import pluginManifest from '../dist/test/pluginManifest.js';

const { parsePluginManifest, satisfiesVersionRange, safeRelativePath, isDescriptorCompatible } = pluginManifest;

test('a coreai manifest resolves to a descriptor with contributions', () => {
  const parsed = parsePluginManifest(JSON.stringify({
    name: 'Usage Panel',
    version: '1.2.3',
    coreai: {
      schemaVersion: 1,
      id: 'example.usage-panel',
      desktopApi: '^1.0',
      components: [{ kind: 'skills', path: 'skills' }],
      capabilities: { required: ['engine.skills', 'ui.dock'], optional: [] },
      contributes: {
        dock: [{ id: 'usage', title: 'Usage', blocks: [{ type: 'text', text: 'ok' }] }],
      },
    },
  }));

  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.equal(parsed.descriptor.id, 'example.usage-panel');
  assert.equal(parsed.descriptor.version, '1.2.3');
  assert.equal(parsed.descriptor.legacy, false);
  assert.equal(parsed.descriptor.components[0].supported, true);
  assert.equal(parsed.descriptor.contributions.dock.length, 1);
  assert.equal(parsed.descriptor.contributions.dock[0].blocks[0].text, 'ok');
  assert.equal(isDescriptorCompatible(parsed.descriptor), true);
});

test('missing name, schema or id is fatal', () => {
  assert.equal(parsePluginManifest('{}').ok, false);
  assert.equal(parsePluginManifest(JSON.stringify({ name: 'x', coreai: { schemaVersion: 2, id: 'a.b' } })).ok, false);
  assert.equal(parsePluginManifest(JSON.stringify({ name: 'x', coreai: { schemaVersion: 1 } })).ok, false);
  assert.equal(parsePluginManifest('not json').ok, false);
});

test('unsupported required capabilities and component paths are rejected', () => {
  const unknownCapability = parsePluginManifest(JSON.stringify({
    name: 'x',
    coreai: { schemaVersion: 1, id: 'a.b', capabilities: { required: ['browser.control'] } },
  }));
  assert.equal(unknownCapability.ok, false);
  assert.ok(unknownCapability.errors.some((error) => error.includes('unsupported required capability')));

  const absolutePath = parsePluginManifest(JSON.stringify({
    name: 'x',
    coreai: { schemaVersion: 1, id: 'a.b', components: [{ kind: 'skills', path: 'C:/plugins/skills' }] },
  }));
  assert.equal(absolutePath.ok, false);

  const traversal = parsePluginManifest(JSON.stringify({
    name: 'x',
    coreai: { schemaVersion: 1, id: 'a.b', components: [{ kind: 'skills', path: '../skills' }] },
  }));
  assert.equal(traversal.ok, false);
});

test('a contribution needs its capability declared', () => {
  const dockWithoutCapability = parsePluginManifest(JSON.stringify({
    name: 'x',
    coreai: {
      schemaVersion: 1,
      id: 'a.b',
      contributes: { dock: [{ id: 'd', title: 'D', blocks: [{ type: 'text', text: 'x' }] }] },
    },
  }));
  assert.equal(dockWithoutCapability.ok, false);
  assert.ok(dockWithoutCapability.errors.some((error) => error.includes('ui.dock')));
});

test('scripted dock views are reported as unsupported instead of being run', () => {
  const parsed = parsePluginManifest(JSON.stringify({
    name: 'x',
    coreai: {
      schemaVersion: 1,
      id: 'a.b',
      capabilities: { required: ['ui.dock'] },
      contributes: { dock: [{ id: 'd', title: 'D', view: 'ui/index.html' }] },
    },
  }));
  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.deepEqual(parsed.descriptor.unsupported, ['ui.dock.view']);
  assert.equal(parsed.descriptor.contributions.dock.length, 0);
});

test('unsupported components stay visible but are not marked supported', () => {
  const parsed = parsePluginManifest(JSON.stringify({
    name: 'x',
    coreai: {
      schemaVersion: 1,
      id: 'a.b',
      components: [{ kind: 'skills', path: 'skills' }, { kind: 'hooks', path: 'hooks' }],
      capabilities: { required: ['engine.skills'] },
    },
  }));
  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.deepEqual(parsed.descriptor.unsupported, ['component:hooks']);
  assert.equal(parsed.descriptor.components.find((component) => component.kind === 'hooks').supported, false);
});

test('legacy manifests derive a local id and never promise engine skills', () => {
  const parsed = parsePluginManifest(JSON.stringify({ name: 'Legacy Thing', version: '0.1' }), {
    detectedComponents: [{ kind: 'skills', path: 'skills' }, { kind: 'hooks', path: 'hooks' }],
  });

  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.equal(parsed.descriptor.legacy, true);
  assert.equal(parsed.descriptor.id, 'local.legacy-thing');
  assert.ok(parsed.warnings.some((warning) => warning.includes('not injected')));
  assert.deepEqual(parsed.descriptor.unsupported, ['component:hooks']);
});

test('version ranges cover the supported forms', () => {
  assert.equal(satisfiesVersionRange('1.2.3', '^1.0'), true);
  assert.equal(satisfiesVersionRange('2.0.0', '^1.0'), false);
  assert.equal(satisfiesVersionRange('0.2.9', '^0.2.3'), true);
  assert.equal(satisfiesVersionRange('0.3.0', '^0.2.3'), false);
  assert.equal(satisfiesVersionRange('1.2.9', '~1.2.3'), true);
  assert.equal(satisfiesVersionRange('1.3.0', '~1.2.3'), false);
  assert.equal(satisfiesVersionRange('1.2.3', '>=1.2.3'), true);
  assert.equal(satisfiesVersionRange('1.2.2', '>1.2.2'), false);
  assert.equal(satisfiesVersionRange('1.0.0', '*'), true);
  assert.equal(satisfiesVersionRange('nope', '*'), false);
});

test('relative paths reject traversal, drives and reserved segments', () => {
  assert.equal(safeRelativePath('skills/hello'), 'skills/hello');
  assert.equal(safeRelativePath('./skills/hello'), 'skills/hello');
  assert.equal(safeRelativePath('../skills'), null);
  assert.equal(safeRelativePath('C:/skills'), null);
  assert.equal(safeRelativePath('/skills'), null);
  assert.equal(safeRelativePath('skills/con.txt'), null);
  assert.equal(safeRelativePath('skills/bad:name'), null);
});
