import { test } from 'node:test';
import assert from 'node:assert/strict';
import pluginView from '../dist/test/pluginView.js';

const { dockTabs, composerActions, slotActions, artifactRenderers, settingsSections, normalizePlugins, pluginNeedsConsent, isPluginDockKey, pluginSummary } = pluginView;

function plugin(overrides = {}) {
  return {
    id: 'example.demo',
    name: 'Demo',
    version: '1.0.0',
    digest: 'a'.repeat(64),
    scope: 'user',
    enabled: true,
    granted: ['engine.skills', 'ui.dock', 'ui.settings', 'ui.composer', 'ui.slots', 'ui.renderers'],
    requiredCapabilities: ['engine.skills'],
    optionalCapabilities: [],
    status: 'active',
    reason: null,
    source: '/tmp/demo',
    components: [{ kind: 'skills', path: 'skills', supported: true }],
    unsupported: [],
    contributions: {
      dock: [{ id: 'panel', title: 'Panel', blocks: [{ type: 'text', text: 'hello' }] }],
      settings: [{ id: 'general', title: 'General', fields: [{ key: 'name', label: 'Name', type: 'text' }] }],
      composer: [{ id: 'insert', title: 'Insert', insertText: 'hello' }],
      slots: [
        { id: 'open', slot: 'sidebar.footer.action', title: 'Open', action: { kind: 'openPage', pageId: 'main' } },
        { id: 'tail', slot: 'conversation.turn.tail', title: 'Tail', action: { kind: 'openPage', pageId: 'main' } },
      ],
      renderers: [{ id: 'viewer', kind: 'artifact', title: 'Viewer', pageId: 'main', extensions: ['.md'] }],
    },
    settings: {},
    ...overrides,
  };
}

test('dock tabs and composer actions require an active plugin with the matching grant', () => {
  assert.equal(dockTabs([plugin()]).length, 1);
  assert.equal(dockTabs([plugin({ status: 'disabled' })]).length, 0);
  assert.equal(dockTabs([plugin({ granted: ['engine.skills'] })]).length, 0);

  assert.equal(composerActions([plugin()]).length, 1);
  assert.equal(composerActions([plugin({ granted: ['ui.dock'] })]).length, 0);
  assert.equal(slotActions([plugin()], 'sidebar.footer.action').length, 1);
  assert.equal(slotActions([plugin()], 'conversation.turn.tail').length, 1);
  assert.equal(slotActions([plugin()], 'composer.dock').length, 0);
  assert.equal(artifactRenderers([plugin()], 'notes.md').length, 1);
  assert.equal(artifactRenderers([plugin()], 'notes.bin').length, 0);
  assert.equal(slotActions([plugin({ granted: ['ui.dock'] })], 'sidebar.footer.action').length, 0);
  assert.equal(isPluginDockKey('plugin:example.demo:panel'), true);
  assert.equal(isPluginDockKey('browser'), false);
});

test('settings sections stay editable once granted even while disabled', () => {
  assert.equal(settingsSections([plugin({ status: 'disabled' })]).length, 1);
  assert.equal(settingsSections([plugin({ status: 'incompatible' })]).length, 0);
  assert.equal(settingsSections([plugin({ granted: [] })]).length, 0);
});

test('consent is required while a declared capability is missing', () => {
  assert.equal(pluginNeedsConsent(plugin({ granted: ['engine.skills'], requiredCapabilities: ['engine.skills', 'ui.dock'] })), true);
  assert.equal(pluginNeedsConsent(plugin({ granted: ['engine.skills'], requiredCapabilities: ['engine.skills'] })), false);
});

test('normalizePlugins drops malformed entries and unknown statuses', () => {
  const normalized = normalizePlugins([
    plugin(),
    { id: 'broken' },
    { id: 'bad-status', name: 'Bad', status: 'weird' },
    null,
    'nope',
  ]);
  assert.equal(normalized.length, 1);
  assert.equal(normalized[0].id, 'example.demo');
  assert.deepEqual(normalizePlugins('not an array'), []);
});

test('pluginSummary includes the status and reason', () => {
  assert.match(pluginSummary(plugin()), /Demo v1\.0\.0 · Active/);
  assert.match(pluginSummary(plugin({ status: 'awaiting-consent', reason: 'missing permission: engine.skills' })), /Needs permission/);
});
