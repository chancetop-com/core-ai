// The custom schemes have to reach Electron in a single privileged registration: a later call
// replaces the earlier list, and the dropped coreai-artifact:// lost fetch support that way — every
// markdown/json preview (and every image thumbnail) failed with "Failed to fetch" while the file was
// listed and readable. Both invariants are asserted here so the regression cannot come back quietly.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import schemes from '../dist/test/protocolSchemes.js';

const { PRIVILEGED_SCHEMES, ARTIFACT_SCHEME, PLUGIN_PAGE_SCHEME, PLUGIN_ASSET_SCHEME } = schemes;

test('every custom scheme carries the privileges its protocol needs', () => {
  const byName = new Map(PRIVILEGED_SCHEMES.map((item) => [item.scheme, item.privileges]));
  assert.deepEqual([...byName.keys()].sort(), [ARTIFACT_SCHEME, PLUGIN_ASSET_SCHEME, PLUGIN_PAGE_SCHEME].sort());
  for (const privileges of byName.values()) {
    assert.equal(privileges.standard, true);
    assert.equal(privileges.secure, true);
    assert.equal(privileges.supportFetchAPI, true);
    assert.equal(privileges.stream, true);
  }
});

test('the artifact scheme is fetchable from the file:// renderer', () => {
  const artifact = PRIVILEGED_SCHEMES.find((item) => item.scheme === ARTIFACT_SCHEME);
  assert.equal(artifact?.privileges.corsEnabled, true);
});

test('registerSchemesAsPrivileged is called once, from main.ts only', () => {
  const src = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'src');
  const offenders = [];
  const callsRegistration = (file) => readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .filter((line) => !line.trim().startsWith('//'))
    .some((line) => line.includes('protocol.registerSchemesAsPrivileged('));
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.ts') && callsRegistration(full)) {
        offenders.push(path.relative(src, full).split(path.sep).join('/'));
      }
    }
  };
  walk(src);
  assert.deepEqual(offenders, ['main/main.ts']);
});
