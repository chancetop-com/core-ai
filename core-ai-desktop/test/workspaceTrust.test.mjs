import { test } from 'node:test';
import assert from 'node:assert/strict';
import { promises as fs, mkdtempSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import workspaceTrustModule from '../dist/test/workspaceTrust.js';

const { WorkspaceTrustStore } = workspaceTrustModule;

function tempRoot() {
  return mkdtempSync(path.join(os.tmpdir(), 'coreai-workspace-trust-'));
}

test('trust records are keyed and survive a second store instance', async () => {
  const root = tempRoot();
  const store = new WorkspaceTrustStore(root);
  assert.equal(await store.read('C:\\ws'), null);

  await store.trust('C:\\ws', 'f'.repeat(64));
  const reopened = new WorkspaceTrustStore(root);
  const record = await reopened.read('C:\\ws');
  assert.equal(record.fingerprint, 'f'.repeat(64));
  assert.ok(record.trustedAt);

  await reopened.trust('D:\\other', 'a'.repeat(64));
  assert.equal((await reopened.read('C:\\ws')).fingerprint, 'f'.repeat(64));
  assert.equal((await reopened.read('D:\\other')).fingerprint, 'a'.repeat(64));
});

test('revoke removes only the requested key', async () => {
  const root = tempRoot();
  const store = new WorkspaceTrustStore(root);
  await store.trust('one', '1'.repeat(64));
  await store.trust('two', '2'.repeat(64));

  await store.revoke('one');

  assert.equal(await store.read('one'), null);
  assert.equal((await store.read('two')).fingerprint, '2'.repeat(64));
});

test('a corrupt trust file grants nothing and is replaced on the next write', async () => {
  const root = tempRoot();
  await fs.mkdir(root, { recursive: true });
  await fs.writeFile(path.join(root, 'workspace-trust.json'), '{not json');
  const store = new WorkspaceTrustStore(root);

  assert.equal(await store.read('ws'), null);
  await store.trust('ws', 'b'.repeat(64));
  const raw = JSON.parse(await fs.readFile(path.join(root, 'workspace-trust.json'), 'utf8'));
  assert.equal(raw.schemaVersion, 1);
  assert.equal(raw.workspaces.ws.fingerprint, 'b'.repeat(64));
});

test('trust rejects an empty key or fingerprint', async () => {
  const store = new WorkspaceTrustStore(tempRoot());
  await assert.rejects(() => store.trust('', 'f'.repeat(64)), /key and fingerprint/);
  await assert.rejects(() => store.trust('ws', ''), /key and fingerprint/);
});
