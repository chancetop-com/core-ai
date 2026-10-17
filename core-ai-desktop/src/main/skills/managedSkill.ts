// Managed install of a bundled skill into ~/.core-ai/skills: the desktop owns a marker file so it can
// refresh its own copy on app updates while never touching a copy the user (or the skill hub) created.
// Same ownership rule as the CLI's hub installer, its own marker name.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';

export const MARKER_FILE = '.core-ai-desktop.json';

export interface ManagedSkillState {
  installed: boolean;
  managed: boolean;
  upToDate: boolean;
  detail: string;
  files: number;
}

export interface ManagedSkillMeta {
  skill: string;
  source: string;
  desktopVersion: string;
  version?: string;
}

export interface ManagedSkillInstallOptions {
  /** Explicit user action: overwrite an unmanaged or locally modified directory. */
  force?: boolean;
}

export interface ManagedSkillInstallResult {
  ok: boolean;
  changed: boolean;
  files: number;
  detail: string;
}

interface Marker {
  skill?: string;
  source?: string;
  desktopVersion?: string;
  version?: string;
  installedAt?: string;
  files?: Record<string, string>;
}

function sha256(file: string): string {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

/** Relative paths (forward slashes) and content hashes of every regular file under `root`, marker excluded. */
export function hashTree(root: string): Record<string, string> {
  const files: Record<string, string> = {};
  if (!existsSync(root)) return files;
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.name.startsWith('.')) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (entry.isFile()) {
        files[path.relative(root, full).split(path.sep).join('/')] = sha256(full);
      }
    }
  };
  walk(root);
  return files;
}

export function readManagedSkillMarker(targetDir: string): Marker | null {
  try {
    return JSON.parse(readFileSync(path.join(targetDir, MARKER_FILE), 'utf8')) as Marker;
  } catch {
    return null;
  }
}

function sameFiles(a: Record<string, string>, b: Record<string, string>): boolean {
  const aKeys = Object.keys(a).sort();
  const bKeys = Object.keys(b).sort();
  if (aKeys.length !== bKeys.length) return false;
  return aKeys.every((key, index) => key === bKeys[index] && a[key] === b[key]);
}

export function describeManagedSkill(sourceDir: string, targetDir: string): ManagedSkillState {
  if (!existsSync(path.join(targetDir, 'SKILL.md'))) {
    return { installed: false, managed: false, upToDate: false, detail: 'not installed', files: 0 };
  }
  const marker = readManagedSkillMarker(targetDir);
  if (!marker?.files) {
    return { installed: true, managed: false, upToDate: false, detail: 'installed outside desktop management', files: 0 };
  }
  const local = hashTree(targetDir);
  const files = Object.keys(local).length;
  if (!sameFiles(local, marker.files)) {
    return { installed: true, managed: true, upToDate: false, detail: 'modified locally - update skipped', files };
  }
  if (!existsSync(sourceDir)) {
    return { installed: true, managed: true, upToDate: false, detail: 'bundled copy missing', files };
  }
  const source = hashTree(sourceDir);
  if (!sameFiles(source, marker.files)) {
    return { installed: true, managed: true, upToDate: false, detail: 'update available', files };
  }
  return { installed: true, managed: true, upToDate: true, detail: 'up to date', files };
}

/**
 * Copies the bundled skill into place when absent or outdated. Returns ok:false with a reason when
 * an unmanaged or locally modified directory would have to be overwritten. `force` is the explicit
 * Restore action from the Skills/Settings UI.
 */
export function installManagedSkill(
  sourceDir: string,
  targetDir: string,
  meta: ManagedSkillMeta,
  options: ManagedSkillInstallOptions = {},
): ManagedSkillInstallResult {
  if (!existsSync(path.join(sourceDir, 'SKILL.md'))) {
    return { ok: false, changed: false, files: 0, detail: `bundled skill missing: ${sourceDir}` };
  }
  const source = hashTree(sourceDir);
  if (existsSync(path.join(targetDir, 'SKILL.md'))) {
    const marker = readManagedSkillMarker(targetDir);
    if (!options.force && !marker?.files) {
      return { ok: false, changed: false, files: 0, detail: 'a copy exists outside desktop management (install path: ' + targetDir + ')' };
    }
    if (!options.force && !sameFiles(hashTree(targetDir), marker!.files!)) {
      return { ok: false, changed: false, files: 0, detail: 'the installed copy was modified locally - left as is' };
    }
    if (!options.force && sameFiles(source, marker!.files!)) {
      return { ok: true, changed: false, files: Object.keys(source).length, detail: 'up to date' };
    }
    rmSync(targetDir, { recursive: true, force: true });
  }
  for (const relative of Object.keys(source)) {
    const destination = path.join(targetDir, ...relative.split('/'));
    mkdirSync(path.dirname(destination), { recursive: true });
    writeFileSync(destination, readFileSync(path.join(sourceDir, ...relative.split('/'))));
  }
  const marker: Marker = {
    skill: meta.skill,
    source: meta.source,
    desktopVersion: meta.desktopVersion,
    ...(meta.version ? { version: meta.version } : {}),
    installedAt: new Date().toISOString(),
    files: source,
  };
  mkdirSync(targetDir, { recursive: true });
  writeFileSync(path.join(targetDir, MARKER_FILE), `${JSON.stringify(marker, null, 2)}\n`);
  return { ok: true, changed: true, files: Object.keys(source).length, detail: 'installed' };
}

/** Removes a desktop-managed copy only; locally modified or unmanaged directories stay on disk. */
export function removeManagedSkill(targetDir: string, options: ManagedSkillInstallOptions = {}): ManagedSkillInstallResult {
  if (!existsSync(path.join(targetDir, 'SKILL.md'))) {
    return { ok: true, changed: false, files: 0, detail: 'not installed' };
  }
  const marker = readManagedSkillMarker(targetDir);
  if (!marker?.files) {
    return { ok: false, changed: false, files: 0, detail: 'the copy is outside desktop management' };
  }
  const local = hashTree(targetDir);
  if (!options.force && !sameFiles(local, marker.files)) {
    return { ok: false, changed: false, files: 0, detail: 'the installed copy was modified locally - left as is' };
  }
  rmSync(targetDir, { recursive: true, force: true });
  return { ok: true, changed: true, files: Object.keys(local).length, detail: 'removed' };
}
