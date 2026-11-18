import { test } from 'node:test';
import assert from 'node:assert/strict';
import { definePlugin, validateManifest, PLUGIN_SCHEMA_VERSION, CAPABILITIES } from '../index.js';

const base = {
  name: 'Hello Plugin',
  version: '1.0.0',
  coreai: {
    schemaVersion: 1,
    id: 'example.hello-plugin',
    desktopApi: '^1.0',
    components: [{ kind: 'skills', path: 'skills' }],
    capabilities: { required: ['engine.skills', 'ui.composer'], optional: [] },
    contributes: {
      composer: [{ id: 'greet', title: 'Insert greeting', insertText: 'Greet the user.' }],
    },
  },
};

test('a complete manifest validates', () => {
  const result = validateManifest(definePlugin(base));
  assert.equal(result.ok, true, result.errors.join('; '));
  assert.deepEqual(result.errors, []);
  assert.equal(PLUGIN_SCHEMA_VERSION, 1);
  assert.ok(CAPABILITIES.includes('engine.skills'));
});

test('JSON text input is accepted', () => {
  assert.equal(validateManifest(JSON.stringify(base)).ok, true);
});

test('identity, paths and capabilities are checked', () => {
  assert.equal(validateManifest({ ...base, name: '' }).ok, false);
  assert.equal(validateManifest({ ...base, coreai: { ...base.coreai, id: 'Bad Id' } }).ok, false);
  assert.equal(validateManifest({
    ...base,
    coreai: { ...base.coreai, components: [{ kind: 'skills', path: '../skills' }] },
  }).ok, false);
  assert.equal(validateManifest({
    ...base,
    coreai: { ...base.coreai, capabilities: { required: ['browser.control'] } },
  }).ok, false);
});

test('skills need engine.skills and a contribution needs its ui capability', () => {
  assert.equal(validateManifest({
    ...base,
    coreai: { ...base.coreai, capabilities: { required: ['ui.composer'] } },
  }).ok, false);
  assert.equal(validateManifest({
    ...base,
    coreai: {
      ...base.coreai,
      capabilities: { required: ['engine.skills'] },
      contributes: { composer: [{ id: 'x', title: 'X', insertText: 'x' }] },
    },
  }).ok, false);
});

test('scripted dock views are a warning, not a hard failure', () => {
  const result = validateManifest({
    ...base,
    coreai: {
      ...base.coreai,
      capabilities: { required: ['engine.skills', 'ui.dock'] },
      contributes: { dock: [{ id: 'panel', title: 'Panel', view: 'ui/index.html' }] },
    },
  });
  assert.equal(result.ok, true, result.errors.join('; '));
  assert.ok(result.warnings.some((warning) => warning.includes('scripted')));
});

test('pages need ui.pages, identity and a package-relative view', () => {
  const page = {
    ...base,
    coreai: {
      ...base.coreai,
      capabilities: { required: ['engine.skills', 'ui.pages'] },
      contributes: { pages: [{ id: 'studio', title: 'Studio', view: 'pages/studio.html' }] },
    },
  };
  assert.equal(validateManifest(page).ok, true);
  assert.equal(validateManifest({
    ...page,
    coreai: { ...page.coreai, capabilities: { required: ['engine.skills'] } },
  }).ok, false);
  assert.equal(validateManifest({
    ...page,
    coreai: { ...page.coreai, contributes: { pages: [{ id: 'studio', title: 'Studio', view: '../studio.html' }] } },
  }).ok, false);
  assert.equal(validateManifest({
    ...page,
    coreai: { ...page.coreai, contributes: { pages: [{ id: 'studio', view: 'studio.html' }] } },
  }).ok, false);
});

test('garbage input fails without throwing', () => {
  assert.equal(validateManifest('{not json').ok, false);
  assert.equal(validateManifest(null).ok, false);
  assert.equal(validateManifest([]).ok, false);
});
