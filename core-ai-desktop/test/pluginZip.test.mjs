import { test } from 'node:test';
import assert from 'node:assert/strict';
import pluginZip from '../dist/test/pluginZip.js';
import { makeZip } from './pluginFixtures.mjs';

const { readZipEntries, isSafeZipName, ZipError } = pluginZip;

test('reads stored and deflated entries', () => {
  const stored = readZipEntries(makeZip({ 'a.txt': 'hello', 'b/c.txt': 'world' }));
  assert.deepEqual(stored.map((entry) => entry.name).sort(), ['a.txt', 'b/c.txt']);
  assert.equal(stored.find((entry) => entry.name === 'a.txt').data.toString(), 'hello');

  const deflated = readZipEntries(makeZip({ 'a.txt': 'hello hello hello hello' }, { compress: true }));
  assert.equal(deflated[0].data.toString(), 'hello hello hello hello');
});

test('rejects traversal, backslashes, absolute paths and device names', () => {
  assert.equal(isSafeZipName('skills/a/SKILL.md'), true);
  assert.equal(isSafeZipName('../evil'), false);
  assert.equal(isSafeZipName('a/../../evil'), false);
  assert.equal(isSafeZipName('/etc/passwd'), false);
  assert.equal(isSafeZipName('C:/evil'), false);
  assert.equal(isSafeZipName('a\\evil'), false);
  assert.equal(isSafeZipName('skills/CON.txt'), false);
  assert.equal(isSafeZipName('skills/trailing.'), false);

  assert.throws(() => readZipEntries(makeZip({ '../evil': 'x' })), ZipError);
});

test('rejects empty archives and size limits', () => {
  assert.throws(() => readZipEntries(makeZip({})), /no files/);
  assert.throws(() => readZipEntries(makeZip({ 'a.txt': '12345' }), { maxFiles: 10, maxFileBytes: 3, maxTotalBytes: 100 }), /too large/);
  assert.throws(() => readZipEntries(makeZip({ 'a.txt': 'x', 'b.txt': 'y' }), { maxFiles: 1, maxFileBytes: 100, maxTotalBytes: 100 }), /too many entries/);
});
