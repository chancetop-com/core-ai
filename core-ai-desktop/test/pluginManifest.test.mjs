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

test('slot actions need ui.slots and a supported action', () => {
  const parsed = parsePluginManifest(JSON.stringify({
    name: 'Slots',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.slots',
      capabilities: { required: ['ui.slots'] },
      contributes: {
        slots: [
          { id: 'open', slot: 'sidebar.footer.action', title: 'Open', action: { kind: 'openPage', pageId: 'main' } },
          { id: 'insert', slot: 'composer.dock', title: 'Insert', action: { kind: 'insertText', text: 'hello' } },
        ],
      },
    },
  }));
  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.equal(parsed.descriptor.contributions.slots.length, 2);
  assert.equal(parsed.descriptor.contributions.slots[0].action.kind, 'openPage');

  const missingCapability = parsePluginManifest(JSON.stringify({
    name: 'Slots',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.slots',
      contributes: { slots: [{ id: 'open', slot: 'sidebar.footer.action', title: 'Open', action: { kind: 'openPage', pageId: 'main' } }] },
    },
  }));
  assert.equal(missingCapability.ok, false);

  const unsupported = parsePluginManifest(JSON.stringify({
    name: 'Slots',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.slots',
      capabilities: { required: ['ui.slots'] },
      contributes: { slots: [{ id: 'open', slot: 'conversation.session.header.actions', title: 'Open', action: { kind: 'openPage', pageId: 'main' } }] },
    },
  }));
  assert.equal(unsupported.ok, true);
  assert.equal(unsupported.descriptor.contributions.slots.length, 0);
  assert.ok(unsupported.descriptor.unsupported.some((item) => item.includes('ui.slots.slot')));
});

test('artifact renderers need ui.renderers and extensions', () => {
  const parsed = parsePluginManifest(JSON.stringify({
    name: 'Renderer',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.renderer',
      capabilities: { required: ['ui.renderers'] },
      contributes: { renderers: [{ id: 'viewer', kind: 'artifact', title: 'Viewer', pageId: 'main', extensions: ['.md', '.txt'] }] },
    },
  }));
  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.equal(parsed.descriptor.contributions.renderers[0].extensions.length, 2);

  const missingCapability = parsePluginManifest(JSON.stringify({
    name: 'Renderer',
    version: '1.0.0',
    coreai: { schemaVersion: 1, id: 'example.renderer', contributes: { renderers: [{ id: 'viewer', kind: 'artifact', title: 'Viewer', pageId: 'main', extensions: ['.md'] }] } },
  }));
  assert.equal(missingCapability.ok, false);
});

test('prompt sections need prompt.sections and content', () => {
  const parsed = parsePluginManifest(JSON.stringify({
    name: 'Prompt',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.prompt',
      capabilities: { required: ['prompt.sections'] },
      contributes: { promptSections: [{ id: 'style', title: 'Style', content: 'Answer concisely.' }] },
    },
  }));
  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.equal(parsed.descriptor.contributions.promptSections[0].content, 'Answer concisely.');

  const missingCapability = parsePluginManifest(JSON.stringify({
    name: 'Prompt',
    version: '1.0.0',
    coreai: { schemaVersion: 1, id: 'example.prompt', contributes: { promptSections: [{ id: 'style', title: 'Style', content: 'x' }] } },
  }));
  assert.equal(missingCapability.ok, false);
});

test('terminal commands need terminal.exec and an allowlisted operation', () => {
  const parsed = parsePluginManifest(JSON.stringify({
    name: 'Terminal',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.terminal',
      capabilities: { required: ['terminal.exec'] },
      contributes: { terminal: [{ id: 'status', name: 'git-status', description: 'Git status', operation: 'git.status' }] },
    },
  }));
  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.equal(parsed.descriptor.contributions.terminal[0].operation, 'git.status');

  const shell = parsePluginManifest(JSON.stringify({
    name: 'Terminal',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.terminal',
      capabilities: { required: ['terminal.exec'] },
      contributes: { terminal: [{ id: 'shell', name: 'shell', description: 'Shell', operation: 'shell.exec' }] },
    },
  }));
  assert.equal(shell.descriptor.contributions.terminal.length, 0);

  const missingCapability = parsePluginManifest(JSON.stringify({
    name: 'Terminal',
    version: '1.0.0',
    coreai: { schemaVersion: 1, id: 'example.terminal', contributes: { terminal: [{ id: 'status', name: 'git-status', description: 'x', operation: 'git.status' }] } },
  }));
  assert.equal(missingCapability.ok, false);
});

test('tool guards need hooks.guard and exact tool names', () => {
  const parsed = parsePluginManifest(JSON.stringify({
    name: 'Guards',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.guards',
      capabilities: { required: ['hooks.guard'] },
      contributes: { guards: [{ id: 'no-shell', name: 'no-shell', description: 'Deny shell', toolNames: ['shell', 'bash'], action: 'deny' }] },
    },
  }));
  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.deepEqual(parsed.descriptor.contributions.guards[0].toolNames, ['shell', 'bash']);

  const unsupportedAction = parsePluginManifest(JSON.stringify({
    name: 'Guards',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.guards',
      capabilities: { required: ['hooks.guard'] },
      contributes: { guards: [{ id: 'ask', name: 'ask', description: 'Ask', toolNames: ['shell'], action: 'ask' }] },
    },
  }));
  assert.equal(unsupportedAction.descriptor.contributions.guards.length, 0);

  const missingCapability = parsePluginManifest(JSON.stringify({
    name: 'Guards',
    version: '1.0.0',
    coreai: { schemaVersion: 1, id: 'example.guards', contributes: { guards: [{ id: 'no-shell', name: 'no-shell', description: 'x', toolNames: ['shell'], action: 'deny' }] } },
  }));
  assert.equal(missingCapability.ok, false);
});

test('sandbox providers are descriptor-only and need sandbox.provider', () => {
  const parsed = parsePluginManifest(JSON.stringify({
    name: 'Sandbox',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.sandbox',
      capabilities: { required: ['sandbox.provider'] },
      contributes: { sandboxProviders: [{ id: 'reserved', name: 'reserved', description: 'Reserved', isolation: 'process' }] },
    },
  }));
  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.equal(parsed.descriptor.contributions.sandboxProviders[0].isolation, 'process');

  const unsupportedIsolation = parsePluginManifest(JSON.stringify({
    name: 'Sandbox',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.sandbox',
      capabilities: { required: ['sandbox.provider'] },
      contributes: { sandboxProviders: [{ id: 'bad', name: 'bad', description: 'Bad', isolation: 'none' }] },
    },
  }));
  assert.equal(unsupportedIsolation.descriptor.contributions.sandboxProviders.length, 0);

  const missingCapability = parsePluginManifest(JSON.stringify({
    name: 'Sandbox',
    version: '1.0.0',
    coreai: { schemaVersion: 1, id: 'example.sandbox', contributes: { sandboxProviders: [{ id: 'reserved', name: 'reserved', description: 'x', isolation: 'process' }] } },
  }));
  assert.equal(missingCapability.ok, false);
});

test('provides and requires parse service metadata', () => {
  const parsed = parsePluginManifest(JSON.stringify({
    name: 'Services',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.services',
      provides: [{ id: 'memory.provider', version: '1.0' }],
      requires: ['events.session'],
    },
  }));
  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.deepEqual(parsed.descriptor.provides, [{ id: 'memory.provider', version: '1.0' }]);
  assert.deepEqual(parsed.descriptor.requires, ['events.session']);

  const invalid = parsePluginManifest(JSON.stringify({
    name: 'Services',
    version: '1.0.0',
    coreai: { schemaVersion: 1, id: 'example.services', provides: [{ id: 'Bad Service' }] },
  }));
  assert.equal(invalid.ok, false);
});

test('a plugin page needs ui.pages and a package-relative view', () => {  const parsed = parsePluginManifest(JSON.stringify({
    name: 'Media Studio',
    version: '0.1.0',
    coreai: {
      schemaVersion: 1,
      id: 'example.media-studio',
      capabilities: { required: ['ui.pages'] },
      contributes: { pages: [{ id: 'studio', title: 'Studio', view: 'pages/studio.html', scope: 'workspace' }] },
    },
  }));
  assert.equal(parsed.ok, true, parsed.errors.join('; '));
  assert.equal(parsed.descriptor.contributions.pages.length, 1);
  assert.equal(parsed.descriptor.contributions.pages[0].view, 'pages/studio.html');
  assert.equal(parsed.descriptor.contributions.pages[0].scope, 'workspace');

  const missingCapability = parsePluginManifest(JSON.stringify({
    name: 'x',
    version: '1.0.0',
    coreai: { schemaVersion: 1, id: 'a.b', contributes: { pages: [{ id: 'p', title: 'P', view: 'p.html' }] } },
  }));
  assert.equal(missingCapability.ok, false);
  assert.ok(missingCapability.errors.some((error) => error.includes('requires capability ui.pages')));

  const escapingView = parsePluginManifest(JSON.stringify({
    name: 'x',
    version: '1.0.0',
    coreai: {
      schemaVersion: 1,
      id: 'a.b',
      capabilities: { required: ['ui.pages'] },
      contributes: { pages: [{ id: 'p', title: 'P', view: '../p.html' }] },
    },
  }));
  assert.equal(escapingView.ok, true);
  assert.equal(escapingView.descriptor.contributions.pages.length, 0);
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
    coreai: { schemaVersion: 1, id: 'a.b', capabilities: { required: ['providers.register'] } },
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
