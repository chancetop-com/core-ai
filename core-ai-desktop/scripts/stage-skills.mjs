// Stages the public skill packages into the desktop app for electron-builder. The canonical copies
// live in docs/skills (the same bytes the docs site publishes); this directory is generated and
// ignored by git. Dev runs resolve docs/skills directly, so only packaging needs this step.
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..');
const sourceRoot = path.join(repoRoot, 'docs', 'skills');
const targetRoot = path.resolve(here, '..', 'skills');
const catalogFile = path.join(sourceRoot, 'catalog.json');

if (!existsSync(catalogFile)) {
  console.error(`missing skill catalog: ${catalogFile}`);
  process.exit(1);
}
const catalog = JSON.parse(readFileSync(catalogFile, 'utf8'));
const entries = Array.isArray(catalog.skills) ? catalog.skills : [];
rmSync(targetRoot, { recursive: true, force: true });
mkdirSync(targetRoot, { recursive: true });
writeFileSync(path.join(targetRoot, 'catalog.json'), `${JSON.stringify(catalog, null, 2)}\n`);

/** Ownership markers belong to an installed copy, never to a published or bundled skill. */
function stripMarkers(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) stripMarkers(full);
    else if (entry.name === '.skill-hub.json' || entry.name === '.core-ai-desktop.json') rmSync(full, { force: true });
  }
}

let failed = false;
for (const entry of entries) {
  const name = typeof entry?.name === 'string' ? entry.name : null;
  if (!name) continue;
  const source = path.join(sourceRoot, name);
  if (!existsSync(path.join(source, 'SKILL.md'))) {
    console.error(`skill '${name}' has no SKILL.md under ${source}`);
    failed = true;
    continue;
  }
  const destination = path.join(targetRoot, name);
  cpSync(source, destination, { recursive: true });
  stripMarkers(destination);
  console.log(`staged skill ${name} -> skills/${name}`);
}
if (failed) process.exit(1);
