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

test('revoke writes a schemaVersion 2 tombstone instead of deleting and load preserves it', async () => {
  const root = tempRoot();
  const store = new WorkspaceTrustStore(root);
  await store.trust('one', '1'.repeat(64));
  await store.trust('two', '2'.repeat(64));

  await store.revoke('one');

  assert.equal(await store.read('one'), null);
  assert.equal(await store.isRevoked('one'), true);
  const entry = await store.readEntry('one');
  assert.equal(entry.state, 'revoked');
  assert.ok(entry.revokedAt);
  assert.equal((await store.read('two')).fingerprint, '2'.repeat(64));

  // Subsequent writes to another key must preserve the tombstone on reload.
  const reopened = new WorkspaceTrustStore(root);
  await reopened.trust('three', '3'.repeat(64));
  const raw = JSON.parse(await fs.readFile(path.join(root, 'workspace-trust.json'), 'utf8'));
  assert.equal(raw.schemaVersion, 2);
  assert.equal(raw.workspaces.one.state, 'revoked');
  assert.equal(raw.workspaces.one.fingerprint, undefined);
  assert.equal(raw.workspaces.two.fingerprint, '2'.repeat(64));
  assert.equal(raw.workspaces.three.fingerprint, '3'.repeat(64));
});

test('applyMigration writes desktop fingerprint, preserves legacy v1 entry, and never overwrites tombstone', async () => {
  const root = tempRoot();
  await fs.mkdir(root, { recursive: true });
  await fs.writeFile(
    path.join(root, 'workspace-trust.json'),
    JSON.stringify({
      schemaVersion: 1,
      workspaces: {
        'C:\\ws': { fingerprint: 'legacy_fp', trustedAt: '2026-11-20T00:00:00Z' },
      },
    }),
  );
  const store = new WorkspaceTrustStore(root);
  const migration = {
    legacyKey: 'C:\\ws',
    legacyFingerprint: 'legacy_fp',
    desktopKey: 'C:\\ws#desktop-mcp',
    desktopFingerprint: 'desktop_fp',
  };

  assert.equal(await store.applyMigration(migration), true);
  assert.equal(await store.applyMigration(migration), false);

  const rawAfterMigrate = JSON.parse(await fs.readFile(path.join(root, 'workspace-trust.json'), 'utf8'));
  assert.equal(rawAfterMigrate.schemaVersion, 2);
  assert.equal(rawAfterMigrate.workspaces['C:\\ws'].fingerprint, 'legacy_fp');
  assert.equal(rawAfterMigrate.workspaces['C:\\ws#desktop-mcp'].fingerprint, 'desktop_fp');

  // Revoke writes tombstone and leaves legacy entry intact.
  await store.revoke('C:\\ws#desktop-mcp');
  assert.equal(await store.isRevoked('C:\\ws#desktop-mcp'), true);
  assert.equal(await store.applyMigration(migration), false);
  const rawAfterRevoke = JSON.parse(await fs.readFile(path.join(root, 'workspace-trust.json'), 'utf8'));
  assert.equal(rawAfterRevoke.workspaces['C:\\ws#desktop-mcp'].state, 'revoked');
  assert.equal(rawAfterRevoke.workspaces['C:\\ws'].fingerprint, 'legacy_fp');

  // Re-trusting overwrites the tombstone cleanly.
  await store.trust('C:\\ws#desktop-mcp', 'desktop_fp');
  assert.equal(await store.isRevoked('C:\\ws#desktop-mcp'), false);
  assert.equal((await store.read('C:\\ws#desktop-mcp')).fingerprint, 'desktop_fp');
});

test('a corrupt trust file grants nothing and is replaced on the next write', async () => {
  const root = tempRoot();
  await fs.mkdir(root, { recursive: true });
  await fs.writeFile(path.join(root, 'workspace-trust.json'), '{not json');
  const store = new WorkspaceTrustStore(root);

  assert.equal(await store.read('ws'), null);
  await store.trust('ws', 'b'.repeat(64));
  const raw = JSON.parse(await fs.readFile(path.join(root, 'workspace-trust.json'), 'utf8'));
  assert.equal(raw.schemaVersion, 2);
  assert.equal(raw.workspaces.ws.fingerprint, 'b'.repeat(64));
});

test('trust rejects an empty key or fingerprint', async () => {
  const store = new WorkspaceTrustStore(tempRoot());
  await assert.rejects(() => store.trust('', 'f'.repeat(64)), /key and fingerprint/);
  await assert.rejects(() => store.trust('ws', ''), /key and fingerprint/);
});
