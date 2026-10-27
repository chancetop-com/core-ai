import { test } from 'node:test';
import assert from 'node:assert/strict';
import localPaths from '../dist/test/localPaths.js';

const { decodePathRef, workspaceRelativePath } = localPaths;

const workspace = 'D:\\core-ai';
const relative = (value) => workspaceRelativePath(workspace, value);

test('an absolute path under the workspace becomes workspace-relative', () => {
  assert.equal(relative('D:\\core-ai\\out\\see-joy-posts\\kids_arc_crop.png'), 'out/see-joy-posts/kids_arc_crop.png');
  assert.equal(relative('d:/core-ai/out/x.png'), 'out/x.png');
  assert.equal(relative('D:\\core-ai\\out\\x.png'), relative('D:\\core-ai/out/x.png'));
});

test('the relative form submit_artifacts reports back resolves against the workspace root', () => {
  assert.equal(relative('out/see-joy-posts/kids_arc_crop.png'), 'out/see-joy-posts/kids_arc_crop.png');
  assert.equal(relative('./out/x.png'), 'out/x.png');
  assert.equal(relative('out//x.png'), 'out/x.png');
  assert.equal(relative('  out/x.png  '), 'out/x.png');
  assert.equal(relative('file:///D:/core-ai/out/x.png'), 'out/x.png');
});

test('percent-encoded refs are decoded by the caller before matching', () => {
  assert.equal(decodePathRef('D:%5Ccore-ai%5Cout%5Cx.png'), 'D:\\core-ai\\out\\x.png');
  assert.equal(relative(decodePathRef('out%2Fmy%20image.png')), 'out/my image.png');
  assert.equal(decodePathRef('%E0%A4%A'), '%E0%A4%A');
});

test('paths outside the workspace or not local files are rejected', () => {
  assert.equal(relative('E:\\core-ai\\out\\x.png'), null);
  assert.equal(relative('D:\\core-ai-other\\x.png'), null);
  assert.equal(relative('/etc/passwd'), null);
  assert.equal(relative('\\\\server\\share\\x.png'), null);
  assert.equal(relative('https://example.com/x.png'), null);
  assert.equal(relative('data:image/png;base64,AAAA'), null);
  assert.equal(relative('coreai-artifact://artifacts/token/x.png'), null);
  assert.equal(relative('#anchor'), null);
});

test('traversal and empty references are rejected', () => {
  assert.equal(relative('../../Windows/x.png'), null);
  assert.equal(relative('out/../../x.png'), null);
  assert.equal(relative('.'), null);
  assert.equal(relative(''), null);
  assert.equal(relative(null), null);
  assert.equal(workspaceRelativePath(null, 'out/x.png'), null);
});

test('the root keeps its boundary: a sibling with the same prefix is not inside it', () => {
  assert.equal(workspaceRelativePath('D:\\core-ai', 'D:\\core-ai-evil\\x.png'), null);
  assert.equal(workspaceRelativePath('D:\\core-ai\\', 'D:\\core-ai\\x.png'), 'x.png');
  assert.equal(workspaceRelativePath('D:/core-ai', 'D:\\core-ai\\x.png'), 'x.png');
});
