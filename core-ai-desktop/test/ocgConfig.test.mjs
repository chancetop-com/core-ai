// OCG's proactive-send gate: the desktop provisions the secret itself, once.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ensureOcgSendSecret } from '../dist/test/ocgConfig.js';

function tempConfig(contents) {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'core-ai-ocg-config-'));
  const file = path.join(dir, 'ocg.json');
  if (contents !== undefined) writeFileSync(file, contents, 'utf8');
  return { dir, file };
}

test('ensureOcgSendSecret adds a local secret and keeps the rest of the config', () => {
  const { dir, file } = tempConfig(JSON.stringify({
    agentUrl: 'http://localhost:8080',
    channels: { qqbot: { enabled: true, agentType: 'mailbox' } },
  }));
  try {
    const first = ensureOcgSendSecret(file);
    assert.deepEqual(first, { ok: true, changed: true });
    const saved = JSON.parse(readFileSync(file, 'utf8'));
    assert.match(saved.sendSecret, /^[0-9a-f]{64}$/);
    assert.equal(saved.agentUrl, 'http://localhost:8080');
    assert.equal(saved.channels.qqbot.agentType, 'mailbox');
    assert.deepEqual(ensureOcgSendSecret(file), { ok: true, changed: false });
    assert.equal(JSON.parse(readFileSync(file, 'utf8')).sendSecret, saved.sendSecret);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('ensureOcgSendSecret leaves an existing send or callback secret alone', () => {
  const withSend = tempConfig(JSON.stringify({ sendSecret: 'send' }));
  const withCallback = tempConfig(JSON.stringify({ callbackSecret: 'callback' }));
  try {
    assert.deepEqual(ensureOcgSendSecret(withSend.file), { ok: true, changed: false });
    assert.deepEqual(ensureOcgSendSecret(withCallback.file), { ok: true, changed: false });
    assert.equal(JSON.parse(readFileSync(withSend.file, 'utf8')).sendSecret, 'send');
    assert.equal(JSON.parse(readFileSync(withCallback.file, 'utf8')).callbackSecret, 'callback');
  } finally {
    for (const { dir } of [withSend, withCallback]) rmSync(dir, { recursive: true, force: true });
  }
});

test('ensureOcgSendSecret does nothing without a config and refuses invalid JSON', () => {
  const missing = tempConfig();
  const invalid = tempConfig('{ not json');
  try {
    assert.deepEqual(ensureOcgSendSecret(missing.file), { ok: true, changed: false });
    const result = ensureOcgSendSecret(invalid.file);
    assert.equal(result.ok, false);
    assert.equal(result.changed, false);
    assert.match(result.error ?? '', /not valid JSON/);
    assert.equal(readFileSync(invalid.file, 'utf8'), '{ not json');
  } finally {
    for (const { dir } of [missing, invalid]) rmSync(dir, { recursive: true, force: true });
  }
});
