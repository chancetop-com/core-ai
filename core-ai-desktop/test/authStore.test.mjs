// Auth-store tests: auth.json is shared with the CLI engine, so the per-server TLS opt-in
// (`insecure`) must survive re-login, environment switches and old records that predate it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import authStore from '../dist/test/authStore.js';

const {
  activeAuthEntry,
  authServersOf,
  authStatusOf,
  loadAuthEntries,
  withActive,
  withInsecure,
  withSavedEntry,
  without,
  writeAuthEntries,
} = authStore;

function tempFile() {
  return path.join(mkdtempSync(path.join(os.tmpdir(), 'coreai-auth-')), 'auth.json');
}

const local = { server_url: 'https://localhost:8443', api_key: 'k-local', user_id: null, name: null, role: null, login_at: '2026-10-15T00:00:00Z', active: true, insecure: true };

test('a saved login records the TLS opt-in and keeps the previous role', () => {
  const before = [{ server_url: 'https://localhost:8443', api_key: 'old', role: 'admin', active: true, insecure: false }];

  const after = withSavedEntry(before, 'https://localhost:8443', 'new-key', 'u-1', 'Ada', true);

  assert.equal(after.length, 1);
  assert.equal(after[0].api_key, 'new-key');
  assert.equal(after[0].role, 'admin');
  assert.equal(after[0].insecure, true);
  assert.equal(after[0].active, true);
});

test('a verified login clears a stale opt-in', () => {
  const after = withSavedEntry([local], 'https://localhost:8443', 'k-local', null, null, false);
  assert.equal(after[0].insecure, false);
});

test('flipping the opt-in touches one server only', () => {
  const entries = [local, { server_url: 'https://uat', api_key: 'k-uat', active: false, insecure: false }];

  const flipped = withInsecure(entries, 'https://uat', true);

  assert.equal(flipped[0].insecure, true);
  assert.equal(flipped[1].insecure, true);
  const cleared = withInsecure(flipped, 'https://localhost:8443', false);
  assert.equal(cleared[0].insecure, false);
  assert.equal(cleared[1].insecure, true);
});

test('switching environments keeps each server opt-in', () => {
  const entries = [local, { server_url: 'https://uat', api_key: 'k-uat', active: false, insecure: false }];

  const switched = withActive(entries, 'https://uat');

  assert.equal(switched[0].active, false);
  assert.equal(switched[1].active, true);
  assert.equal(switched[0].insecure, true);
  assert.equal(switched[1].insecure, false);
  assert.equal(authStatusOf(switched).serverUrl, 'https://uat');
  assert.equal(authStatusOf(switched).insecure, false);
});

test('status and environment list expose the opt-in', () => {
  const status = authStatusOf([local]);
  assert.equal(status.loggedIn, true);
  assert.equal(status.serverUrl, 'https://localhost:8443');
  assert.equal(status.insecure, true);

  const servers = authServersOf([local, { server_url: 'https://uat', api_key: 'k-uat' }]);
  assert.equal(servers.length, 2);
  assert.equal(servers[0].insecure, true);
  assert.equal(servers[1].insecure, false);
});

test('a signed-out store reports nothing and older records default to verification', () => {
  assert.equal(authStatusOf([]).loggedIn, false);
  assert.equal(authStatusOf([]).insecure, false);

  const entries = [{ server_url: 'https://uat', api_key: 'k', active: true }];
  assert.equal(authStatusOf(entries).insecure, false);
  assert.equal(authServersOf(entries)[0].insecure, false);
});

test('entries round-trip through auth.json and single-object files still load', () => {
  const file = tempFile();
  writeAuthEntries([local], file);
  const loaded = loadAuthEntries(file);
  assert.equal(loaded[0].insecure, true);
  assert.equal(activeAuthEntry(loaded).api_key, 'k-local');

  const legacy = tempFile();
  writeFileSync(legacy, JSON.stringify({ server_url: 'https://uat', api_key: 'k', active: true }));
  assert.equal(loadAuthEntries(legacy).length, 1);
  assert.equal(loadAuthEntries(legacy)[0].insecure, undefined);
  assert.equal(loadAuthEntries(path.join(path.dirname(legacy), 'missing.json')).length, 0);

  writeAuthEntries(without(loaded, 'https://localhost:8443'), file);
  assert.equal(loadAuthEntries(file).length, 0);
});
