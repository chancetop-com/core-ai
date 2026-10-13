// Local tests for the artifact protocol helpers: token/path decoding, the .core-ai whitelist and HTTP
// range parsing. Run via `npm test` (builds dist/test/artifactPath.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import artifactPath from '../dist/test/artifactPath.js';

const { tokenOf, decodeRelativePath, contentTypeFor, isInside, resolveInsideArtifacts, parseRange } = artifactPath;

function workspace() {
  const root = mkdtempSync(path.join(os.tmpdir(), 'coreai-artifact-'));
  const images = path.join(root, '.core-ai', 'media', 'images');
  mkdirSync(images, { recursive: true });
  writeFileSync(path.join(images, 'image-x.png'), 'png');
  return root;
}

test('tokenOf reads the first path segment', () => {
  assert.equal(tokenOf('/abc123/.core-ai/x.png'), 'abc123');
  assert.equal(tokenOf('/abc123'), 'abc123');
  assert.equal(tokenOf('//abc/'), 'abc');
});

test('decodeRelativePath decodes each segment and rejects bad escapes', () => {
  assert.equal(decodeRelativePath('/tok/.core-ai/media/images/a.png'), '.core-ai/media/images/a.png');
  assert.equal(decodeRelativePath('/tok'), '');
  assert.equal(decodeRelativePath('/tok/a%20b/c.png'), 'a b/c.png');
  assert.equal(decodeRelativePath('/tok/%E0%A4%A'), null);
});

test('contentTypeFor maps extensions and falls back', () => {
  assert.equal(contentTypeFor('image.PNG'), 'image/png');
  assert.equal(contentTypeFor('clip.mp4'), 'video/mp4');
  assert.equal(contentTypeFor('file.unknown'), 'application/octet-stream');
});

test('resolveInsideArtifacts only serves files under .core-ai', () => {
  const root = workspace();
  assert.ok(resolveInsideArtifacts(root, '.core-ai/media/images/image-x.png'));

  writeFileSync(path.join(root, 'outside.txt'), 'x');
  assert.equal(resolveInsideArtifacts(root, 'outside.txt'), null);
  assert.equal(resolveInsideArtifacts(root, '.core-ai/../secret.txt'), null);
  assert.equal(resolveInsideArtifacts(root, '../outside.txt'), null);
  assert.equal(resolveInsideArtifacts(root, '.core-ai/media/images/missing.png'), null);
});

test('isInside is boundary aware', () => {
  const parent = path.resolve('/tmp', 'core-ai');
  assert.equal(isInside(parent, path.join(parent, 'a', 'b')), true);
  assert.equal(isInside(parent, parent), false);
  assert.equal(isInside(parent, `${parent}-evil`), false);
  assert.equal(isInside(parent, path.resolve(parent, '..', 'other')), false);
});

test('parseRange handles closed, open, suffix and invalid ranges', () => {
  assert.equal(parseRange(null, 1000), null);
  assert.deepEqual(parseRange('bytes=0-99', 1000), { start: 0, end: 99 });
  assert.deepEqual(parseRange('bytes=100-', 1000), { start: 100, end: 999 });
  assert.deepEqual(parseRange('bytes=-100', 1000), { start: 900, end: 999 });
  assert.deepEqual(parseRange('bytes=999-2000', 1000), { start: 999, end: 999 });
  assert.deepEqual(parseRange('bytes=0-0', 1), { start: 0, end: 0 });
  assert.equal(parseRange('bytes=1000-', 1000), 'invalid');
  assert.equal(parseRange('bytes=500-100', 1000), 'invalid');
  assert.equal(parseRange('bytes=-0', 1000), 'invalid');
  assert.equal(parseRange('bytes=abc', 1000), 'invalid');
  assert.equal(parseRange('items=0-1', 1000), 'invalid');
});
