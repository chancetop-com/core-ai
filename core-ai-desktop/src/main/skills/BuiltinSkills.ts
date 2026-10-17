// Built-in skills shipped with core-ai-desktop. The installer is deliberately local-only: a packaged
// copy is authoritative, no network or server login is involved, and a copy the user or the hub
// created is never overwritten. Runtime binaries (browser-use, cua, ocg) stay outside this manager;
// callers decide when a skill is enabled based on their own probes.
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import path from 'node:path';
import {
  describeManagedSkill,
  installManagedSkill,
  readManagedSkillMarker,
  removeManagedSkill,
  type ManagedSkillInstallOptions,
  type ManagedSkillInstallResult,
} from './managedSkill';

export type BuiltinCapability = 'hub' | 'browser' | 'computer' | 'channels';
export type BuiltinDefaultMode = 'always' | 'when-enabled' | 'auto-detect';
export type BuiltinRuntimeKind = 'none' | 'browser-use' | 'cua' | 'ocg';

export interface BuiltinCatalogEntry {
  name: string;
  title: string;
  description: string;
  capability: BuiltinCapability;
  default: BuiltinDefaultMode;
  runtime: BuiltinRuntimeKind;
  version: string;
  source: string;
  license: string;
  docsUrl: string;
}

export interface BuiltinSkillStatus extends BuiltinCatalogEntry {
  installed: boolean;
  managed: boolean;
  upToDate: boolean;
  detail: string;
  coveredBy: string | null;
  targetDir: string;
  sourceDir: string;
}

export interface BuiltinSkillsOptions {
  /** Directory containing catalog.json and one subdirectory per skill (resources/skills when packaged). */
  skillsRoot: string;
  /** Overridable for tests; defaults to the real user home. */
  homeDir?: string;
  desktopVersion: string;
  onLog?: (message: string) => void;
}

interface CatalogFile {
  schema?: number;
  skills?: unknown;
}

interface RawCatalogEntry {
  name?: unknown;
  title?: unknown;
  description?: unknown;
  capability?: unknown;
  default?: unknown;
  runtime?: unknown;
  version?: unknown;
  source?: unknown;
  license?: unknown;
  docsUrl?: unknown;
}

const CATALOG_FILE = 'catalog.json';
const SKILL_FILE = 'SKILL.md';

function isCatalogEntry(value: unknown): value is RawCatalogEntry {
  if (!value || typeof value !== 'object') return false;
  const name = (value as RawCatalogEntry).name;
  return typeof name === 'string' && /^[a-z0-9][a-z0-9-]*$/.test(name);
}

function text(value: unknown, fallback: string): string {
  return typeof value === 'string' && value.trim().length > 0 ? value : fallback;
}

/** Frontmatter name of a skill directory/file; directory names are the fallback. */
export function skillFrontmatterName(markdown: string): string | null {
  const match = /^---\s*\r?\n([\s\S]*?)\r?\n---/.exec(markdown);
  if (!match) return null;
  const name = /^\s*name:\s*["']?([^"'\r\n]+?)["']?\s*$/m.exec(match[1]);
  return name ? name[1].trim() : null;
}

export class BuiltinSkills {
  private readonly catalog: BuiltinCatalogEntry[];
  private readonly catalogError: string | null;

  constructor(private readonly options: BuiltinSkillsOptions) {
    const result = this.readCatalog();
    this.catalog = result.entries;
    this.catalogError = result.error;
  }

  get error(): string | null {
    return this.catalogError;
  }

  entries(): BuiltinCatalogEntry[] {
    return this.catalog.map((entry) => ({ ...entry }));
  }

  private readCatalog(): { entries: BuiltinCatalogEntry[]; error: string | null } {
    const file = path.join(this.options.skillsRoot, CATALOG_FILE);
    try {
      const data = JSON.parse(readFileSync(file, 'utf8')) as CatalogFile;
      if (!Array.isArray(data.skills)) return { entries: [], error: `invalid catalog (no skills array): ${file}` };
      const entries = data.skills.filter(isCatalogEntry).map((entry) => ({
        name: String(entry.name),
        title: text(entry.title, String(entry.name)),
        description: text(entry.description, ''),
        capability: (entry.capability === 'browser' || entry.capability === 'computer'
          || entry.capability === 'channels' ? entry.capability : 'hub') as BuiltinCapability,
        default: (entry.default === 'always' || entry.default === 'auto-detect'
          ? entry.default : 'when-enabled') as BuiltinDefaultMode,
        runtime: (entry.runtime === 'browser-use' || entry.runtime === 'cua' || entry.runtime === 'ocg'
          ? entry.runtime : 'none') as BuiltinRuntimeKind,
        version: text(entry.version, ''),
        source: text(entry.source, ''),
        license: text(entry.license, ''),
        docsUrl: text(entry.docsUrl, ''),
      }));
      return { entries, error: entries.length === 0 ? `empty catalog: ${file}` : null };
    } catch (error) {
      return { entries: [], error: `cannot read ${file}: ${error instanceof Error ? error.message : String(error)}` };
    }
  }

  private get skillsRoot(): string {
    return path.join(this.options.homeDir ?? homedir(), '.core-ai', 'skills');
  }

  targetDir(name: string): string {
    return path.join(this.skillsRoot, name);
  }

  sourceDir(name: string): string {
    return path.join(this.options.skillsRoot, name);
  }

  private entry(name: string): BuiltinCatalogEntry | null {
    return this.catalog.find((item) => item.name === name) ?? null;
  }

  /**
   * A same-name skill installed outside the desktop target: flat or one namespace level. Scanning
   * frontmatter (not just directory names) keeps a `Stephen/browser-use` copy from being duplicated
   * as a top-level built-in copy.
   */
  coveredBy(entry: BuiltinCatalogEntry): string | null {
    const target = path.resolve(this.targetDir(entry.name));
    const root = this.skillsRoot;
    if (!existsSync(root)) return null;
    let found: string | null = null;
    const inspect = (skillDir: string, qualified: string): void => {
      if (found || path.resolve(skillDir) === target) return;
      const file = path.join(skillDir, SKILL_FILE);
      if (!existsSync(file)) return;
      try {
        const name = skillFrontmatterName(readFileSync(file, 'utf8')) ?? path.basename(skillDir);
        if (name === entry.name) found = qualified;
      } catch {
        // unreadable skill: ignore it, the installer will still avoid overwriting the path
      }
    };
    for (const item of readdirSync(root, { withFileTypes: true })) {
      if (item.isFile()) {
        const lower = item.name.toLowerCase();
        const base = item.name.slice(0, -3);
        if (lower.endsWith('.md') && base.toLowerCase() === entry.name.toLowerCase()) found = base;
        continue;
      }
      if (!item.isDirectory()) continue;
      const dir = path.join(root, item.name);
      if (existsSync(path.join(dir, SKILL_FILE))) {
        inspect(dir, item.name);
      } else {
        for (const child of readdirSync(dir, { withFileTypes: true })) {
          if (child.isDirectory()) inspect(path.join(dir, child.name), `${item.name}/${child.name}`);
        }
      }
    }
    return found;
  }

  status(name: string): BuiltinSkillStatus | null {
    const entry = this.entry(name);
    if (!entry) return null;
    const targetDir = this.targetDir(entry.name);
    const sourceDir = this.sourceDir(entry.name);
    const state = describeManagedSkill(sourceDir, targetDir);
    const marker = readManagedSkillMarker(targetDir);
    const coveredBy = state.installed ? null : this.coveredBy(entry);
    let detail = state.detail;
    if (!state.installed && coveredBy) detail = `provided by ${coveredBy}`;
    else if (state.installed && !marker) detail = 'installed outside desktop management';
    return {
      ...entry,
      installed: state.installed,
      managed: state.managed,
      upToDate: state.upToDate,
      detail,
      coveredBy,
      targetDir,
      sourceDir,
    };
  }

  list(): BuiltinSkillStatus[] {
    return this.catalog
      .map((entry) => this.status(entry.name))
      .filter((status): status is BuiltinSkillStatus => status !== null);
  }

  install(name: string, options: ManagedSkillInstallOptions = {}): ManagedSkillInstallResult {
    const entry = this.entry(name);
    if (!entry) return { ok: false, changed: false, files: 0, detail: `unknown built-in skill: ${name}` };
    const targetDir = this.targetDir(entry.name);
    const sourceDir = this.sourceDir(entry.name);
    if (!existsSync(path.join(sourceDir, SKILL_FILE))) {
      return { ok: false, changed: false, files: 0, detail: `bundled skill missing: ${sourceDir}` };
    }
    if (!options.force) {
      const state = describeManagedSkill(sourceDir, targetDir);
      if (state.installed && state.managed && state.upToDate) {
        return { ok: true, changed: false, files: state.files, detail: 'up to date' };
      }
      if (!state.installed) {
        const coveredBy = this.coveredBy(entry);
        if (coveredBy) return { ok: true, changed: false, files: 0, detail: `provided by ${coveredBy}` };
      }
    }
    const result = installManagedSkill(sourceDir, targetDir, {
      skill: entry.name,
      source: entry.source,
      version: entry.version,
      desktopVersion: this.options.desktopVersion,
    }, options);
    if (result.changed) this.options.onLog?.(`built-in skill ${entry.name} installed at ${targetDir}`);
    return result;
  }

  /** Explicit Restore: overwrite a locally modified or unmanaged copy. */
  restore(name: string): ManagedSkillInstallResult {
    return this.install(name, { force: true });
  }

  remove(name: string): ManagedSkillInstallResult {
    const entry = this.entry(name);
    if (!entry) return { ok: false, changed: false, files: 0, detail: `unknown built-in skill: ${name}` };
    const result = removeManagedSkill(this.targetDir(entry.name));
    if (result.changed) this.options.onLog?.(`built-in skill ${entry.name} removed from ${this.targetDir(entry.name)}`);
    return result;
  }

  installDefaults(predicate?: (entry: BuiltinCatalogEntry) => boolean): ManagedSkillInstallResult[] {
    return this.catalog
      .filter((entry) => entry.default === 'always' && (!predicate || predicate(entry)))
      .map((entry) => this.install(entry.name));
  }

  /** Whether a directory is the desktop-managed copy of a catalog entry (used by the Skills panel). */
  isManagedTarget(name: string, targetDir: string): boolean {
    const entry = this.entry(name);
    if (!entry) return false;
    return path.resolve(targetDir) === path.resolve(this.targetDir(entry.name))
      && readManagedSkillMarker(targetDir) !== null;
  }
}
