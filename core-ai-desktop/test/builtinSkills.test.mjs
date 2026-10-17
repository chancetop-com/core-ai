// Built-in skill lifecycle: bundled installs, ownership marker, covered-by namespaced copies,
// local modifications, restore and remove. Run via `npm test`.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import builtinModule from '../dist/test/builtinSkills.js';

const { BuiltinSkills, skillFrontmatterName } = builtinModule;
const temps = [];

function tempDir(prefix) {
  const dir = mkdtempSync(path.join(tmpdir(), prefix));
  temps.push(dir);
  return dir;
}

function makeSkill(root, name, defaultMode = 'always', runtime = 'none') {
  const dir = path.join(root, name);
  mkdirSync(dir, { recursive: true });
  writeFileSync(path.join(dir, 'SKILL.md'), `---\nname: ${name}\ndescription: test skill\n---\n# ${name}\n`);
  writeFileSync(path.join(dir, 'note.txt'), `note for ${name}\n`);
  return {
    name,
    title: name,
    description: 'test skill',
    capability: name === 'browser-use' ? 'browser' : 'hub',
    default: defaultMode,
    runtime,
    version: '1',
    source: 'test',
    license: 'MIT',
    docsUrl: '',
  };
}

function makeRoot() {
  const root = tempDir('coreai-builtin-src-');
  const skills = [makeSkill(root, 'core-ai-cli-manual'), makeSkill(root, 'browser-use', 'always', 'browser-use'), makeSkill(root, 'gui-automation', 'when-enabled', 'cua')];
  writeFileSync(path.join(root, 'catalog.json'), JSON.stringify({ schema: 1, skills }));
  return root;
}

function manager(root, home) {
  return new BuiltinSkills({ skillsRoot: root, homeDir: home, desktopVersion: '0.0.0' });
}

test.after(() => {
  for (const dir of temps) rmSync(dir, { recursive: true, force: true });
});

test('skillFrontmatterName reads the YAML name', () => {
  assert.equal(skillFrontmatterName('---\nname: browser-use\ndescription: x\n---\n'), 'browser-use');
  assert.equal(skillFrontmatterName('---\nname: "ocg"\n---\n'), 'ocg');
  assert.equal(skillFrontmatterName('# no frontmatter\n'), null);
});

test('installDefaults installs the always skills into ~/.core-ai/skills', () => {
  const root = makeRoot();
  const home = tempDir('coreai-builtin-home-');
  const skills = manager(root, home);
  const results = skills.installDefaults();

  assert.equal(results.length, 2);
  assert.ok(results.every((result) => result.ok && result.changed));
  assert.ok(existsSync(path.join(home, '.core-ai', 'skills', 'core-ai-cli-manual', 'SKILL.md')));
  assert.ok(existsSync(path.join(home, '.core-ai', 'skills', 'browser-use', 'note.txt')));
  assert.ok(existsSync(path.join(home, '.core-ai', 'skills', 'browser-use', '.core-ai-desktop.json')));

  const statuses = skills.list();
  const browser = statuses.find((entry) => entry.name === 'browser-use');
  assert.deepEqual([browser.installed, browser.managed, browser.upToDate], [true, true, true]);
  const computer = statuses.find((entry) => entry.name === 'gui-automation');
  assert.equal(computer.installed, false);
});

test('a namespaced same-name skill covers the built-in copy', () => {
  const root = makeRoot();
  const home = tempDir('coreai-builtin-home-');
  const namespaced = path.join(home, '.core-ai', 'skills', 'Stephen', 'browser-use');
  mkdirSync(namespaced, { recursive: true });
  writeFileSync(path.join(namespaced, 'SKILL.md'), '---\nname: browser-use\ndescription: hub copy\n---\n');

  const skills = manager(root, home);
  const browser = skills.list().find((entry) => entry.name === 'browser-use');
  assert.equal(browser.installed, false);
  assert.equal(browser.coveredBy, 'Stephen/browser-use');

  const result = skills.install('browser-use');
  assert.equal(result.ok, true);
  assert.equal(result.changed, false);
  assert.equal(result.detail, 'provided by Stephen/browser-use');
  assert.equal(existsSync(path.join(home, '.core-ai', 'skills', 'browser-use')), false);
});

test('a flat same-name skill covers the built-in copy', () => {
  const root = makeRoot();
  const home = tempDir('coreai-builtin-home-');
  const skillsRoot = path.join(home, '.core-ai', 'skills');
  mkdirSync(skillsRoot, { recursive: true });
  writeFileSync(path.join(skillsRoot, 'browser-use.md'), '---\nname: browser-use\ndescription: flat copy\n---\n');

  const skills = manager(root, home);
  const browser = skills.list().find((entry) => entry.name === 'browser-use');
  assert.equal(browser.installed, false);
  assert.equal(browser.coveredBy, 'browser-use');
  assert.equal(skills.install('browser-use').detail, 'provided by browser-use');
  assert.equal(existsSync(path.join(skillsRoot, 'browser-use')), false);
});

test('an unmanaged copy is never overwritten without restore', () => {
  const root = makeRoot();
  const home = tempDir('coreai-builtin-home-');
  const target = path.join(home, '.core-ai', 'skills', 'browser-use');
  mkdirSync(target, { recursive: true });
  writeFileSync(path.join(target, 'SKILL.md'), '---\nname: browser-use\ndescription: user copy\n---\n');

  const skills = manager(root, home);
  const blocked = skills.install('browser-use');
  assert.equal(blocked.ok, false);
  assert.match(blocked.detail, /outside desktop management/);
  assert.match(readFileSync(path.join(target, 'SKILL.md'), 'utf8'), /user copy/);

  const restored = skills.restore('browser-use');
  assert.equal(restored.ok, true);
  assert.equal(restored.changed, true);
  assert.match(readFileSync(path.join(target, 'SKILL.md'), 'utf8'), /# browser-use/);
});

test('a locally modified managed copy is kept until restore', () => {
  const root = makeRoot();
  const home = tempDir('coreai-builtin-home-');
  const skills = manager(root, home);
  skills.install('browser-use');
  const target = path.join(home, '.core-ai', 'skills', 'browser-use');
  writeFileSync(path.join(target, 'SKILL.md'), '---\nname: browser-use\ndescription: edited\n---\n');

  const blocked = skills.install('browser-use');
  assert.equal(blocked.ok, false);
  assert.match(blocked.detail, /modified locally/);
  assert.match(readFileSync(path.join(target, 'SKILL.md'), 'utf8'), /edited/);

  const restored = skills.restore('browser-use');
  assert.equal(restored.ok, true);
  assert.equal(restored.changed, true);
  assert.match(readFileSync(path.join(target, 'SKILL.md'), 'utf8'), /# browser-use/);
});

test('remove only deletes an untouched managed copy', () => {
  const root = makeRoot();
  const home = tempDir('coreai-builtin-home-');
  const skills = manager(root, home);
  skills.install('browser-use');
  const target = path.join(home, '.core-ai', 'skills', 'browser-use');

  const removed = skills.remove('browser-use');
  assert.equal(removed.ok, true);
  assert.equal(removed.changed, true);
  assert.equal(existsSync(target), false);

  skills.install('browser-use');
  writeFileSync(path.join(target, 'SKILL.md'), '---\nname: browser-use\ndescription: edited\n---\n');
  const blocked = skills.remove('browser-use');
  assert.equal(blocked.ok, false);
  assert.match(blocked.detail, /modified locally/);
  assert.ok(existsSync(target));
});
