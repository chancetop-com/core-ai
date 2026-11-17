import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import * as storeModule from '../dist/test/providerStore.js';

const {
  blankStore,
  deleteProfile,
  fallbackConnection,
  loadProviderStore,
  maskKey,
  preset,
  saveProviderStore,
  setDefaultConnection,
  storeView,
  toProfileView,
  upsertProfile,
} = storeModule;

function tempFile() {
  return path.join(mkdtempSync(path.join(os.tmpdir(), 'coreai-providers-')), 'llm-providers.json');
}

test('a missing store loads empty', () => {
  const loaded = loadProviderStore(tempFile());
  assert.equal(loaded.error, undefined);
  assert.deepEqual(loaded.store, blankStore());
});

test('save/load roundtrip keeps profiles, default and server model', () => {
  const file = tempFile();
  const store = blankStore();
  upsertProfile(store, { type: 'deepseek', baseUrl: 'https://api.deepseek.com/v1' }, 'sk-123456789');
  store.default = 'deepseek';
  store.serverModel = 'deepseek-v4-flash';
  saveProviderStore(store, file);

  const loaded = loadProviderStore(file);
  assert.equal(loaded.error, undefined);
  assert.equal(loaded.store.default, 'deepseek');
  assert.equal(loaded.store.serverModel, 'deepseek-v4-flash');
  assert.equal(loaded.store.profiles.length, 1);
  assert.equal(loaded.store.profiles[0].apiKey, 'sk-123456789');
  assert.equal(loaded.store.profiles[0].defaultModel, 'deepseek-chat');
  assert.equal(loaded.store.profiles[0].reasoningEffortMap.max, 'xhigh');
});

test('the serialized file never exposes the key through the profile view', () => {
  const profile = preset('deepseek');
  profile.apiKey = 'sk-abcdef123456';
  const view = toProfileView(profile);
  assert.equal(view.hasApiKey, true);
  assert.equal(view.apiKeyHint, '••••3456');
  assert.equal('apiKey' in view, false);
  assert.equal(maskKey('short'), '••••');
  assert.equal(maskKey(null), null);
});

test('upsert keeps the saved key when the caller leaves apiKey empty, and clearApiKey removes it', () => {
  const store = blankStore();
  upsertProfile(store, { type: 'deepseek', baseUrl: 'https://api.deepseek.com/v1' }, 'sk-first');
  const kept = upsertProfile(store, { type: 'deepseek', baseUrl: 'https://proxy.example/v1' });
  assert.equal(kept.apiKey, 'sk-first');
  assert.equal(kept.baseUrl, 'https://proxy.example/v1');
  const cleared = upsertProfile(store, { type: 'deepseek', baseUrl: 'https://proxy.example/v1' }, null, true);
  assert.equal(cleared.apiKey, undefined);
});

test('upsert validates type and base URL', () => {
  const store = blankStore();
  assert.throws(() => upsertProfile(store, { type: 'azure', baseUrl: 'https://x/v1' }), /unsupported provider type/);
  assert.throws(() => upsertProfile(store, { type: 'openai', baseUrl: 'ftp://x' }), /http/);
  assert.throws(() => upsertProfile(store, { type: 'openai', baseUrl: 'https://x/v1/chat/completions' }), /API root/);
});

test('delete clears a dangling default and fallback prefers a profile', () => {
  const store = blankStore();
  upsertProfile(store, { type: 'deepseek', baseUrl: 'https://api.deepseek.com/v1' }, 'k');
  store.default = 'deepseek';
  assert.equal(deleteProfile(store, 'deepseek'), true);
  assert.equal(store.default, null);
  assert.equal(fallbackConnection(store, false), null);
  upsertProfile(store, { type: 'openai', baseUrl: 'https://api.openai.com/v1' }, 'k');
  assert.equal(fallbackConnection(store, true), 'openai');
  assert.throws(() => setDefaultConnection(store, 'deepseek'), /not configured/);
});

test('a corrupt file reports an error and loads empty', () => {
  const file = tempFile();
  writeFileSync(file, '{not-json');
  const loaded = loadProviderStore(file);
  assert.ok(loaded.error);
  assert.equal(loaded.store.profiles.length, 0);
  assert.ok(readFileSync(file, 'utf8').includes('not-json'));
});

test('storeView carries profiles but never the raw key', () => {
  const store = blankStore();
  upsertProfile(store, { type: 'deepseek', baseUrl: 'https://api.deepseek.com/v1' }, 'sk-secret-value');
  const view = storeView(store, '/tmp/llm-providers.json');
  assert.equal(view.profiles[0].hasApiKey, true);
  assert.equal(view.profiles[0].apiKey, undefined);
  assert.equal(JSON.stringify(view).includes('sk-secret-value'), false);
});
