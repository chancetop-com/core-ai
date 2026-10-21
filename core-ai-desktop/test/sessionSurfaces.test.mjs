import { test } from 'node:test';
import assert from 'node:assert/strict';
import sessionSurfaces from '../dist/test/sessionSurfaces.js';

const { createSurface, SessionSurfaces } = sessionSurfaces;

test('a surface starts blank, empty-docked and unflagged', () => {
  assert.deepEqual(createSurface(), {
    draft: '',
    dock: 'none',
    dockFullscreen: false,
    activeArtifactPath: null,
    artifactSource: false,
    artifactUnread: false,
    artifactDismissedForTurn: false,
  });
});

test('a session keeps one surface across lookups; peek does not create one', () => {
  const surfaces = new SessionSurfaces();

  assert.equal(surfaces.peek('a'), undefined);
  const first = surfaces.get('a');
  first.draft = 'half-written';
  first.dock = 'artifacts';

  assert.equal(surfaces.get('a'), first);
  assert.equal(surfaces.peek('a')?.draft, 'half-written');
  assert.notEqual(surfaces.get('b'), first);
});

test('the +New window has its own surface, and adopt moves it onto the created session', () => {
  const surfaces = new SessionSurfaces();
  const real = surfaces.get('a');
  real.draft = 'from a';

  const draft = surfaces.get(null);
  draft.draft = 'first message';
  draft.dock = 'browser';

  const adopted = surfaces.adopt('new-session');
  assert.equal(adopted, draft);
  assert.equal(surfaces.get('new-session'), draft);
  assert.equal(surfaces.get('new-session')?.dock, 'browser');
  assert.equal(surfaces.get('a')?.draft, 'from a');

  const nextDraft = surfaces.get(null);
  assert.notEqual(nextDraft, draft);
  assert.equal(nextDraft.draft, '');
  assert.equal(nextDraft.dock, 'none');
});

test('resetDraft discards the blank window without touching real sessions', () => {
  const surfaces = new SessionSurfaces();
  const real = surfaces.get('a');
  real.draft = 'kept';
  const draft = surfaces.get(null);
  draft.draft = 'abandoned';

  const fresh = surfaces.resetDraft();
  assert.notEqual(fresh, draft);
  assert.equal(fresh.draft, '');
  assert.equal(surfaces.get(null), fresh);
  assert.equal(surfaces.get('a')?.draft, 'kept');
});

test('delete drops only that session', () => {
  const surfaces = new SessionSurfaces();
  surfaces.get('a').draft = 'a';
  surfaces.get('b').draft = 'b';

  surfaces.delete('a');

  assert.equal(surfaces.peek('a'), undefined);
  assert.equal(surfaces.get('b').draft, 'b');
  assert.equal(surfaces.get('a').draft, '');
});
