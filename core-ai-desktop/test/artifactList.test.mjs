// Artifact list merge rules: the engine scan is authoritative, live events only add entries the
// workspace still has. A file deleted after submit_artifacts must leave the list, not stay as a
// broken thumbnail. Run via `npm test` (builds dist/test/artifactList.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import artifactList from '../dist/test/artifactList.js';

const { unscannedArtifactPaths, mergeArtifactEntries } = artifactList;

const live = (path) => ({ path, name: path, size: 0 });
const scanned = (path, size) => ({ path, name: path, size });

test('unscannedArtifactPaths returns the entries the scan did not report', () => {
  const existing = [live('a.png'), live('b.pdf'), live('c.md')];
  const baseline = [scanned('b.pdf', 10)];
  assert.deepEqual(unscannedArtifactPaths(existing, baseline), ['a.png', 'c.md']);
  assert.deepEqual(unscannedArtifactPaths([], baseline), []);
});

test('mergeArtifactEntries keeps a live entry the workspace still has', () => {
  const existing = [live('a.png')];
  const baseline = [scanned('b.pdf', 10)];
  assert.deepEqual(mergeArtifactEntries(existing, baseline, new Set()).map((entry) => entry.path), ['a.png', 'b.pdf']);
});

test('mergeArtifactEntries drops a live entry the workspace no longer has', () => {
  const existing = [live('deleted.png'), live('kept.png')];
  const baseline = [scanned('report.pdf', 10)];
  const merged = mergeArtifactEntries(existing, baseline, new Set(['deleted.png']));
  assert.deepEqual(merged.map((entry) => entry.path), ['kept.png', 'report.pdf']);
});

test('mergeArtifactEntries lets the scan win for a path both sides report', () => {
  const existing = [live('a.png')];
  const baseline = [scanned('a.png', 42)];
  assert.deepEqual(mergeArtifactEntries(existing, baseline, new Set()), [scanned('a.png', 42)]);
});
