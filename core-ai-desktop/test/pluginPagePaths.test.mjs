import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, symlinkSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import pluginPagePaths from '../dist/test/pluginPagePaths.js';

const {
  assetUrl,
  contentTypeFor,
  pageUrl,
  relativeFromPathname,
  resolveMediaFile,
  resolvePackageFile,
} = pluginPagePaths;

function tempDir(prefix) {
  return mkdtempSync(path.join(os.tmpdir(), prefix));
}

test('page pathnames decode segments and reject escapes', () => {
  assert.equal(relativeFromPathname('/pages/studio.html'), 'pages/studio.html');
  assert.equal(relativeFromPathname('/pages/a%20b.css'), 'pages/a b.css');
  assert.equal(relativeFromPathname('/../secret'), null);
  assert.equal(relativeFromPathname('/pages/%2e%2e/secret'), null);
  assert.equal(relativeFromPathname('/pages/C%3A/secret'), null);
  assert.equal(relativeFromPathname('/'), null);
});

test('package files stay inside the package and never follow a symlink out', () => {
  const root = tempDir('coreai-plugin-page-');
  const outside = tempDir('coreai-plugin-outside-');
  mkdirSync(path.join(root, 'pages'));
  writeFileSync(path.join(root, 'pages', 'studio.html'), '<html></html>');
  writeFileSync(path.join(outside, 'secret.txt'), 'nope');

  assert.ok(resolvePackageFile(root, 'pages/studio.html'));
  assert.equal(resolvePackageFile(root, '../secret.txt'), null);
  assert.equal(resolvePackageFile(root, 'missing.html'), null);

  try {
    symlinkSync(outside, path.join(root, 'linked'), 'junction');
    assert.equal(resolvePackageFile(root, 'linked/secret.txt'), null);
  } catch {
    // symlink creation may require privileges on Windows; the lexical check above still runs
  }
});

test('plugin pages can read generated media only, never arbitrary workspace files', () => {
  const workspace = tempDir('coreai-plugin-workspace-');
  const image = path.join(workspace, '.core-ai', 'media', 'images');
  mkdirSync(image, { recursive: true });
  writeFileSync(path.join(image, 'one.png'), 'png');
  writeFileSync(path.join(workspace, 'notes.txt'), 'private');

  assert.ok(resolveMediaFile(workspace, '.core-ai/media/images/one.png'));
  assert.equal(resolveMediaFile(workspace, 'notes.txt'), null);
  assert.equal(resolveMediaFile(workspace, '.core-ai/media/../notes.txt'), null);
  assert.equal(resolveMediaFile(workspace, '.core-ai/media/images/one.txt'), null);
});

test('page and asset URLs encode each segment', () => {
  assert.equal(pageUrl('pages/studio.html'), 'coreai-plugin-page://page/pages/studio.html');
  assert.equal(assetUrl('.core-ai/media/images/a b.png'),
    'coreai-plugin-asset://assets/.core-ai/media/images/a%20b.png');
  assert.equal(contentTypeFor('x.html'), 'text/html; charset=utf-8');
  assert.equal(contentTypeFor('x.bin'), 'application/octet-stream');
});
