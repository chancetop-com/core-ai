// Local tests for the attachment stager: classification, size/type gates, unique naming, reuse,
// clipboard-buffer staging, removal boundary and the age-based sweep. Run via `npm test`
// (builds dist/test/attachmentStager.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, statSync, truncateSync, utimesSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import stagerModule from '../dist/test/attachmentStager.js';

const { classifyAttachment, stagingDirOf, stageAttachments, stageBuffers, removeStaged, sweepStaging, MAX_IMAGE_BYTES, MAX_FILE_BYTES } = stagerModule;

function tempDir() {
  return mkdtempSync(path.join(os.tmpdir(), 'attachment-stager-'));
}

test('classify maps extensions to engine part types', () => {
  assert.equal(classifyAttachment('photo.PNG'), 'image');
  assert.equal(classifyAttachment('photo.jpeg'), 'image');
  assert.equal(classifyAttachment('animation.gif'), 'image');
  assert.equal(classifyAttachment('doc.pdf'), 'pdf');
  assert.equal(classifyAttachment('notes.md'), 'file');
  assert.equal(classifyAttachment('script.PY'), 'file');
  assert.equal(classifyAttachment('data.csv'), 'file');
  assert.equal(classifyAttachment('report.xlsx'), 'file');
  assert.equal(classifyAttachment('archive.zip'), 'file');
  assert.equal(classifyAttachment('image.bmp'), 'file');
  assert.equal(classifyAttachment('README'), 'file');
});

test('an xlsx is staged as a file the agent opens itself, not as something the model reads', async () => {
  const root = tempDir();
  const source = path.join(root, '社媒数据.xlsx');
  writeFileSync(source, Buffer.from([0x50, 0x4b, 0x03, 0x04, 0, 1, 2]));

  const result = await stageAttachments(path.join(root, 'staging'), 'session-1', [source]);
  assert.equal(result.skipped.length, 0);
  assert.equal(result.attachments.length, 1);
  assert.equal(result.attachments[0].kind, 'file');
  assert.equal(result.attachments[0].name, '社媒数据.xlsx');
  assert.ok(existsSync(result.attachments[0].path));
});

test('stage copies the file into the session staging directory', async () => {
  const root = tempDir();
  const source = path.join(root, 'photo.png');
  writeFileSync(source, 'fake-png');

  const result = await stageAttachments(path.join(root, 'staging'), 'session-1', [source]);
  assert.equal(result.skipped.length, 0);
  assert.equal(result.attachments.length, 1);
  const attachment = result.attachments[0];
  assert.equal(attachment.kind, 'image');
  assert.equal(attachment.name, 'photo.png');
  assert.equal(attachment.size, 8);
  assert.equal(attachment.path, path.join(root, 'staging', 'session-1', 'photo.png'));
  assert.ok(existsSync(attachment.path));
});

test('missing and oversize files are skipped with reasons', async () => {
  const root = tempDir();
  const bigFile = path.join(root, 'big.bin');
  writeFileSync(bigFile, 'x');
  truncateSync(bigFile, MAX_FILE_BYTES + 1);
  const bigImage = path.join(root, 'huge.png');
  writeFileSync(bigImage, 'x');
  truncateSync(bigImage, MAX_IMAGE_BYTES + 1);

  const result = await stageAttachments(path.join(root, 'staging'), 'session-1', [
    bigFile, bigImage, path.join(root, 'missing.pdf'),
  ]);
  assert.equal(result.attachments.length, 0);
  assert.equal(result.skipped.length, 3);
  assert.match(result.skipped[0].reason, /100 MB/);
  assert.match(result.skipped[1].reason, /50 MB/);
  assert.match(result.skipped[2].reason, /could not be read/);
});

test('same file re-staged reuses the copy, changed size gets a suffixed name', async () => {
  const root = tempDir();
  const staging = path.join(root, 'staging');
  const source = path.join(root, 'notes.md');
  writeFileSync(source, 'v1');

  const first = await stageAttachments(staging, 's', [source]);
  const second = await stageAttachments(staging, 's', [source]);
  assert.equal(first.attachments[0].path, second.attachments[0].path);

  writeFileSync(source, 'v2-longer');
  const third = await stageAttachments(staging, 's', [source]);
  assert.equal(third.attachments[0].name, 'notes.md');
  assert.notEqual(third.attachments[0].path, first.attachments[0].path);
  assert.match(third.attachments[0].path, /notes-2\.md$/);
  assert.equal(statSync(third.attachments[0].path).size, 9);
});

test('stageBuffers writes pasted bytes to disk and re-stages the copy', async () => {
  const root = tempDir();
  const staging = path.join(root, 'staging');
  const bytes = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3]);

  const result = await stageBuffers(staging, 's', [{ name: 'screenshot-20261016-101010.png', data: bytes.buffer }]);
  assert.equal(result.skipped.length, 0);
  assert.equal(result.attachments.length, 1);
  const attachment = result.attachments[0];
  assert.equal(attachment.kind, 'image');
  assert.equal(attachment.name, 'screenshot-20261016-101010.png');
  assert.equal(attachment.size, bytes.byteLength);
  assert.equal(attachment.path, path.join(staging, 's', 'screenshot-20261016-101010.png'));
  assert.deepEqual([...readFileSync(attachment.path)], [...bytes]);

  const again = await stageBuffers(staging, 's', [{ name: 'screenshot-20261016-101010.png', data: bytes.buffer }]);
  assert.equal(again.attachments[0].path, attachment.path);
});

test('stageBuffers strips directories from names and applies the pick gates', async () => {
  const root = tempDir();
  const staging = path.join(root, 'staging');
  const png = new Uint8Array([1, 2, 3]);
  const big = new Uint8Array(MAX_IMAGE_BYTES + 1);

  const result = await stageBuffers(staging, 's', [
    { name: 'C:\\shots\\..\\deep\\screenshot.png', data: png.buffer },
    { name: '', data: png.buffer },
    { name: 'archive.zip', data: png.buffer },
    { name: 'huge.png', data: big.buffer },
    { name: 'no-data.png' },
  ]);
  assert.equal(result.attachments.length, 2);
  assert.equal(result.attachments[0].name, 'screenshot.png');
  assert.equal(result.attachments[0].kind, 'image');
  assert.equal(result.attachments[0].path, path.join(staging, 's', 'screenshot.png'));
  assert.equal(result.attachments[1].name, 'archive.zip');
  assert.equal(result.attachments[1].kind, 'file');
  assert.equal(result.skipped.length, 3);
  assert.equal(result.skipped[0].reason, 'not a file');
  assert.match(result.skipped[1].reason, /50 MB/);
  assert.match(result.skipped[2].reason, /missing data/);
});

test('staging directories are per session', () => {
  assert.equal(stagingDirOf('C:\\root', 'abc'), path.join('C:\\root', 'abc'));
});

test('removeStaged deletes inside the root and refuses paths outside it', async () => {
  const root = tempDir();
  const staging = path.join(root, 'staging');
  const source = path.join(root, 'photo.jpg');
  writeFileSync(source, 'img');
  const staged = await stageAttachments(staging, 's', [source]);
  const stagedPath = staged.attachments[0].path;

  assert.equal(await removeStaged(staging, stagedPath), true);
  assert.ok(!existsSync(stagedPath));

  const outside = path.join(root, 'keep.txt');
  writeFileSync(outside, 'keep');
  assert.equal(await removeStaged(staging, outside), false);
  assert.ok(existsSync(outside));
  assert.equal(await removeStaged(staging, path.join(staging, '..', 'keep.txt')), false);
});

test('sweepStaging drops old session directories only', async () => {
  const staging = tempDir();
  const fresh = path.join(staging, 'fresh-session');
  const stale = path.join(staging, 'stale-session');
  mkdirSync(fresh, { recursive: true });
  mkdirSync(stale, { recursive: true });
  writeFileSync(path.join(stale, 'old.txt'), 'old');
  const past = new Date(Date.now() - 8 * 24 * 60 * 60 * 1000);
  utimesSync(stale, past, past);

  const removed = await sweepStaging(staging, 7 * 24 * 60 * 60 * 1000);
  assert.equal(removed, 1);
  assert.ok(!existsSync(stale));
  assert.ok(existsSync(fresh));
});

test('sweepStaging tolerates a missing root', async () => {
  assert.equal(await sweepStaging(path.join(tempDir(), 'never-created'), 1000), 0);
});
