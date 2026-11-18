/**
 * Content-addressed plugin package store.
 *
 * One state file owns install intent, enablement and grants. Packages themselves are immutable,
 * digest-named snapshots; installing never mutates a package in place. The desktop only resolves
 * packages it validated here — a plugin installed by an older CLI or left in a workspace folder is
 * not executable just because it exists on disk.
 *
 * Scope note (documented deviation, see design §14): this build exposes only the `user` scope.
 * The state model already carries a scope string so workspace scopes can be added without a
 * migration, but no UI or IPC path creates them yet.
 */
import { createHash, randomUUID } from 'node:crypto';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import {
  DESKTOP_PLUGIN_API_VERSION,
  isDescriptorCompatible,
  isSupportedCapability,
  missingRequiredCapabilities,
  parsePluginManifest,
  safeRelativePath,
  type PluginComponent,
  type PluginComponentKind,
  type PluginContributions,
  type PluginView,
  type ResolvedPluginDescriptor,
} from '../../shared/pluginManifest';
import { DEFAULT_ZIP_LIMITS, readZipEntries, type ZipLimits } from './zip';

export const USER_SCOPE = 'user';
const STATE_SCHEMA_VERSION = 1;
const MAX_PACKAGE_FILES = 500;
const MAX_PACKAGE_BYTES = 32 * 1024 * 1024;
const MAX_PACKAGE_FILE_BYTES = 10 * 1024 * 1024;
const MAX_STORAGE_KEYS = 200;
const MAX_STORAGE_VALUE_BYTES = 64 * 1024;
const STORAGE_KEY = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const LOCK_TIMEOUT_MS = 15_000;
const LOCK_STALE_MS = 30_000;
const LOCK_RETRY_MS = 25;

export class PluginError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'PluginError';
    this.code = code;
  }
}

interface StoredPackage {
  pluginId: string;
  name: string;
  version: string;
  digest: string;
  source: string;
  installedAt: string;
  requiredCapabilities: string[];
}

interface StoredInstall {
  scope: string;
  pluginId: string;
  digest: string;
  enabled: boolean;
  granted: string[];
  settings: Record<string, string | number | boolean>;
  storage: Record<string, string>;
  installedAt: string;
  updatedAt: string;
}

interface PluginState {
  schemaVersion: number;
  revision: number;
  grantEpoch: number;
  packages: Record<string, StoredPackage>;
  installs: StoredInstall[];
}

export interface PluginStoreOptions {
  root: string;
  desktopApiVersion?: string;
  zipLimits?: ZipLimits;
  log?: (line: string) => void;
}

export interface EngineSkillRoot {
  name: string;
  dir: string;
  digest: string;
  grantEpoch: number;
}

export interface PluginPromptSection {
  name: string;
  title: string;
  content: string;
}

export interface PluginToolDescriptor {
  name: string;
  description: string;
  operation: 'artifacts.list' | 'memory.list';
  limit: number;
}

export interface PluginTerminalCommandDescriptor {
  pluginId: string;
  pluginName: string;
  commandId: string;
  name: string;
  description: string;
  operation: 'git.status' | 'git.log' | 'git.diff' | 'node.version';
}

export interface ResolvedPluginPage {
  pluginId: string;
  pluginName: string;
  pageId: string;
  title: string;
  view: string;
  scope: 'workspace' | 'app';
  dir: string;
  digest: string;
  granted: string[];
}

export interface ActivePluginContributions {
  pluginId: string;
  name: string;
  contributions: PluginContributions;
}

interface ResolvedPackage {
  descriptor: ResolvedPluginDescriptor;
  components: PluginComponent[];
  dir: string;
}

function emptyState(): PluginState {
  return { schemaVersion: STATE_SCHEMA_VERSION, revision: 0, grantEpoch: 0, packages: {}, installs: [] };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function assertStorageKey(key: unknown): asserts key is string {
  if (typeof key !== 'string' || !STORAGE_KEY.test(key)) {
    throw new PluginError('invalid_storage', 'storage key must match [A-Za-z0-9][A-Za-z0-9._-]{0,127}');
  }
}

function nowIso(): string {
  return new Date().toISOString();
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function exists(target: string): Promise<boolean> {
  try {
    await fs.stat(target);
    return true;
  } catch {
    return false;
  }
}

async function sha256File(file: string): Promise<string> {
  const hash = createHash('sha256');
  hash.update(await fs.readFile(file));
  return hash.digest('hex');
}

async function manifestPath(pluginDir: string): Promise<string | null> {
  for (const candidate of [path.join(pluginDir, '.claude-plugin', 'plugin.json'), path.join(pluginDir, 'plugin.json')]) {
    if (await exists(candidate)) return candidate;
  }
  return null;
}

async function detectLegacyComponents(pluginDir: string): Promise<Array<{ kind: PluginComponentKind; path: string }>> {
  const detected: Array<{ kind: PluginComponentKind; path: string }> = [];
  for (const [kind, dir] of [['skills', 'skills'], ['commands', 'commands'], ['hooks', 'hooks'], ['agents', 'agents']] as const) {
    try {
      if ((await fs.stat(path.join(pluginDir, dir))).isDirectory()) detected.push({ kind, path: dir });
    } catch {
      // absent component
    }
  }
  for (const [kind, file] of [['mcp', '.mcp.json'], ['lsp', '.lsp.json']] as const) {
    if (await exists(path.join(pluginDir, file))) detected.push({ kind, path: file });
  }
  return detected;
}

/** Copies a directory without following symlinks; any link or special file aborts the install. */
async function copyTreeNoFollow(source: string, target: string): Promise<{ files: number; bytes: number }> {
  let files = 0;
  let bytes = 0;
  const walk = async (from: string, to: string): Promise<void> => {
    const stat = await fs.lstat(from);
    if (!stat.isDirectory()) throw new PluginError('package_invalid', 'plugin source must be a directory');
    await fs.mkdir(to, { recursive: true, mode: 0o700 });
    const entries = await fs.readdir(from, { withFileTypes: true });
    for (const entry of entries) {
      if (entry.name === '.git') continue;
      const sourcePath = path.join(from, entry.name);
      const targetPath = path.join(to, entry.name);
      const entryStat = await fs.lstat(sourcePath);
      if (entryStat.isSymbolicLink()) throw new PluginError('package_invalid', `symlink is not allowed: ${entry.name}`);
      if (entryStat.isDirectory()) {
        await walk(sourcePath, targetPath);
        continue;
      }
      if (!entryStat.isFile()) throw new PluginError('package_invalid', `unsupported file type: ${entry.name}`);
      if (entryStat.size > MAX_PACKAGE_FILE_BYTES) throw new PluginError('package_too_large', `file is too large: ${entry.name}`);
      files += 1;
      bytes += entryStat.size;
      if (files > MAX_PACKAGE_FILES) throw new PluginError('package_too_large', 'package has too many files');
      if (bytes > MAX_PACKAGE_BYTES) throw new PluginError('package_too_large', 'package is too large');
      await fs.copyFile(sourcePath, targetPath);
      await fs.chmod(targetPath, 0o600).catch(() => undefined);
    }
  };
  await walk(source, target);
  return { files, bytes };
}

/** Digest covers relative paths and file contents, so equal bytes always resolve to the same package. */
async function digestTree(root: string): Promise<string> {
  const files: Array<{ relative: string; file: string }> = [];
  const walk = async (dir: string): Promise<void> => {
    for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) await walk(full);
      else if (entry.isFile()) files.push({ relative: path.relative(root, full).split(path.sep).join('/'), file: full });
    }
  };
  await walk(root);
  files.sort((a, b) => (a.relative < b.relative ? -1 : a.relative > b.relative ? 1 : 0));
  const hash = createHash('sha256');
  for (const file of files) hash.update(`file\u0000${file.relative}\u0000${await sha256File(file.file)}\n`);
  return hash.digest('hex');
}

export class PluginStore {
  private readonly root: string;
  private readonly packagesDir: string;
  private readonly stagingDir: string;
  private readonly stateFile: string;
  private readonly lockFile: string;
  private readonly desktopApiVersion: string;
  private readonly zipLimits: ZipLimits;
  private readonly log: (line: string) => void;
  private readonly resolved = new Map<string, ResolvedPackage>();
  private queue: Promise<unknown> = Promise.resolve();

  constructor(options: PluginStoreOptions) {
    this.root = path.resolve(options.root);
    this.packagesDir = path.join(this.root, 'packages');
    this.stagingDir = path.join(this.root, 'staging');
    this.stateFile = path.join(this.root, 'state.json');
    this.lockFile = path.join(this.root, 'control.lock');
    this.desktopApiVersion = options.desktopApiVersion ?? DESKTOP_PLUGIN_API_VERSION;
    this.zipLimits = options.zipLimits ?? DEFAULT_ZIP_LIMITS;
    this.log = options.log ?? (() => undefined);
  }

  get rootDir(): string {
    return this.root;
  }

  async list(): Promise<PluginView[]> {
    const state = await this.readState();
    const views: PluginView[] = [];
    for (const install of state.installs) {
      views.push(await this.viewOf(state, install));
    }
    return views.sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  }

  async installFolder(sourceDir: string, scope = USER_SCOPE): Promise<PluginView> {
    this.assertScope(scope);
    const source = path.resolve(sourceDir);
    const stat = await fs.stat(source).catch(() => null);
    if (!stat?.isDirectory()) throw new PluginError('source_missing', `plugin folder not found: ${sourceDir}`);
    const staging = await this.stageFrom(source, async (target) => copyTreeNoFollow(source, target));
    return this.publishStaging(staging, source, scope);
  }

  async installZip(zipPath: string, scope = USER_SCOPE): Promise<PluginView> {
    this.assertScope(scope);
    const archive = await fs.readFile(zipPath).catch(() => null);
    if (!archive) throw new PluginError('source_missing', `plugin archive not found: ${zipPath}`);
    const staging = await this.stageFrom(path.resolve(zipPath), async (target) => {
      const entries = readZipEntries(archive, this.zipLimits);
      let total = 0;
      for (const entry of entries) {
        total += entry.data.length;
        if (total > MAX_PACKAGE_BYTES) throw new PluginError('package_too_large', 'archive expands beyond the package limit');
        const relative = safeRelativePath(entry.name);
        if (!relative) throw new PluginError('package_invalid', `unsafe archive path: ${entry.name}`);
        const targetFile = path.join(target, ...relative.split('/'));
        await fs.mkdir(path.dirname(targetFile), { recursive: true, mode: 0o700 });
        await fs.writeFile(targetFile, entry.data, { mode: 0o600 });
      }
    });
    return this.publishStaging(staging, path.resolve(zipPath), scope);
  }

  async setEnabled(pluginId: string, enabled: boolean, scope = USER_SCOPE): Promise<PluginView> {
    this.assertScope(scope);
    return this.mutate(async (state) => {
      const install = this.requireInstall(state, pluginId, scope);
      install.enabled = enabled;
      install.updatedAt = nowIso();
      return this.viewOf(state, install);
    });
  }

  async grant(pluginId: string, capabilities: string[], scope = USER_SCOPE): Promise<PluginView> {
    this.assertScope(scope);
    return this.mutate(async (state) => {
      const install = this.requireInstall(state, pluginId, scope);
      const resolved = await this.resolveInstall(state, install);
      if (!resolved) throw new PluginError('package_missing', `package for ${pluginId} is missing`);
      const declared = new Set([...resolved.descriptor.capabilities.required, ...resolved.descriptor.capabilities.optional]);
      const wanted = [...new Set(capabilities)];
      for (const capability of wanted) {
        if (!isSupportedCapability(capability) || !declared.has(capability)) {
          throw new PluginError('capability_not_declared', `${pluginId} does not declare capability ${capability}`);
        }
      }
      install.granted = wanted;
      install.updatedAt = nowIso();
      state.grantEpoch += 1;
      return this.viewOf(state, install);
    });
  }

  async revoke(pluginId: string, scope = USER_SCOPE): Promise<PluginView> {
    this.assertScope(scope);
    return this.mutate(async (state) => {
      const install = this.requireInstall(state, pluginId, scope);
      install.granted = [];
      install.updatedAt = nowIso();
      state.grantEpoch += 1;
      return this.viewOf(state, install);
    });
  }

  async remove(pluginId: string, scope = USER_SCOPE): Promise<void> {
    this.assertScope(scope);
    await this.mutate(async (state) => {
      const index = state.installs.findIndex((install) => install.pluginId === pluginId && install.scope === scope);
      if (index < 0) throw new PluginError('not_installed', `plugin is not installed: ${pluginId}`);
      state.installs.splice(index, 1);
      state.grantEpoch += 1;
      // Package bytes are kept: another install may share the digest and a rollback must not depend
      // on a redownload. Explicit GC is a separate operation.
    });
  }

  async setSetting(pluginId: string, key: string, value: string | number | boolean, scope = USER_SCOPE): Promise<PluginView> {
    this.assertScope(scope);
    return this.mutate(async (state) => {
      const install = this.requireInstall(state, pluginId, scope);
      const resolved = await this.resolveInstall(state, install);
      if (!resolved) throw new PluginError('package_missing', `package for ${pluginId} is missing`);
      const field = resolved.descriptor.contributions.settings
        .flatMap((contribution) => contribution.fields)
        .find((candidate) => candidate.key === key);
      if (!field) throw new PluginError('unknown_setting', `${pluginId} does not declare setting ${key}`);
      if (field.type === 'number' && (typeof value !== 'number' || !Number.isFinite(value))) {
        throw new PluginError('invalid_setting', `${key} must be a number`);
      }
      if (field.type === 'boolean' && typeof value !== 'boolean') throw new PluginError('invalid_setting', `${key} must be a boolean`);
      if (field.type === 'text') {
        if (typeof value !== 'string' || value.length > 4000) throw new PluginError('invalid_setting', `${key} must be text`);
      }
      install.settings[key] = value;
      install.updatedAt = nowIso();
      return this.viewOf(state, install);
    });
  }

  async storageGet(pluginId: string, key: string, scope = USER_SCOPE): Promise<string | null> {
    this.assertScope(scope);
    assertStorageKey(key);
    const state = await this.readState();
    return this.requireInstall(state, pluginId, scope).storage[key] ?? null;
  }

  async storageKeys(pluginId: string, scope = USER_SCOPE): Promise<string[]> {
    this.assertScope(scope);
    const state = await this.readState();
    return Object.keys(this.requireInstall(state, pluginId, scope).storage).sort();
  }

  async storageSet(pluginId: string, key: string, value: string, scope = USER_SCOPE): Promise<void> {
    this.assertScope(scope);
    assertStorageKey(key);
    if (typeof value !== 'string' || Buffer.byteLength(value, 'utf8') > MAX_STORAGE_VALUE_BYTES) {
      throw new PluginError('invalid_storage', `${key} exceeds the storage value limit`);
    }
    await this.mutate(async (state) => {
      const install = this.requireInstall(state, pluginId, scope);
      if (!(key in install.storage) && Object.keys(install.storage).length >= MAX_STORAGE_KEYS) {
        throw new PluginError('storage_full', `${pluginId} has too many storage keys`);
      }
      install.storage[key] = value;
      install.updatedAt = nowIso();
    });
  }

  async storageDelete(pluginId: string, key: string, scope = USER_SCOPE): Promise<boolean> {
    this.assertScope(scope);
    assertStorageKey(key);
    return this.mutate(async (state) => {
      const install = this.requireInstall(state, pluginId, scope);
      if (!(key in install.storage)) return false;
      delete install.storage[key];
      install.updatedAt = nowIso();
      return true;
    });
  }

  /** Skills directories an engine may load: active installs with the engine.skills grant only. */
  async engineSkillRoots(): Promise<EngineSkillRoot[]> {
    const state = await this.readState();
    const roots: EngineSkillRoot[] = [];
    for (const install of state.installs) {
      const resolved = await this.resolveInstall(state, install);
      if (!resolved) continue;
      if (this.statusOf(install, resolved) !== 'active') continue;
      if (!install.granted.includes('engine.skills')) continue;
      for (const component of resolved.components) {
        if (component.kind !== 'skills' || !component.supported) continue;
        const dir = path.join(resolved.dir, ...component.path.split('/'));
        if (await exists(dir)) {
          roots.push({ name: install.pluginId, dir, digest: install.digest, grantEpoch: state.grantEpoch });
        }
      }
    }
    const unique = new Map(roots.map((root) => [root.dir, root]));
    return [...unique.values()];
  }

  /** Contributions exposed to the host; each kind needs its matching grant on an active install. */
  async activeContributions(): Promise<ActivePluginContributions[]> {
    const state = await this.readState();
    const result: ActivePluginContributions[] = [];
    for (const install of state.installs) {
      const resolved = await this.resolveInstall(state, install);
      if (!resolved || this.statusOf(install, resolved) !== 'active') continue;
      const dock = install.granted.includes('ui.dock') ? resolved.descriptor.contributions.dock : [];
      const settings = install.granted.includes('ui.settings') ? resolved.descriptor.contributions.settings : [];
      const composer = install.granted.includes('ui.composer') ? resolved.descriptor.contributions.composer : [];
      const pages = install.granted.includes('ui.pages') ? resolved.descriptor.contributions.pages : [];
      const slots = install.granted.includes('ui.slots') ? resolved.descriptor.contributions.slots : [];
      const renderers = install.granted.includes('ui.renderers') ? resolved.descriptor.contributions.renderers : [];
      const promptSections: typeof resolved.descriptor.contributions.promptSections = [];
      if (!dock.length && !settings.length && !composer.length && !pages.length && !slots.length && !renderers.length) continue;
      result.push({
        pluginId: install.pluginId,
        name: resolved.descriptor.name,
        contributions: { dock, settings, composer, pages, slots, renderers, promptSections, tools: [], terminal: [] },
      });
    }
    return result.sort((a, b) => a.name.localeCompare(b.name));
  }

  /** Prompt sections of active installs that granted prompt.sections. */
  async promptSections(): Promise<PluginPromptSection[]> {
    const state = await this.readState();
    const sections: PluginPromptSection[] = [];
    for (const install of state.installs) {
      const resolved = await this.resolveInstall(state, install);
      if (!resolved || this.statusOf(install, resolved) !== 'active') continue;
      if (!install.granted.includes('prompt.sections')) continue;
      for (const section of resolved.descriptor.contributions.promptSections) {
        sections.push({
          name: `${install.pluginId}:${section.id}`,
          title: section.title,
          content: section.content,
        });
      }
    }
    return sections;
  }

  /** Read-only host tools of active installs that granted tools.register. */
  async tools(): Promise<PluginToolDescriptor[]> {
    const state = await this.readState();
    const tools: PluginToolDescriptor[] = [];
    for (const install of state.installs) {
      const resolved = await this.resolveInstall(state, install);
      if (!resolved || this.statusOf(install, resolved) !== 'active') continue;
      if (!install.granted.includes('tools.register')) continue;
      const prefix = install.pluginId.replace(/[^a-z0-9_]/g, '_');
      for (const tool of resolved.descriptor.contributions.tools) {
        tools.push({
          name: `plugin_${prefix}_${tool.name}`,
          description: `${resolved.descriptor.name}: ${tool.description}`,
          operation: tool.operation,
          limit: tool.limit,
        });
      }
    }
    return tools;
  }

  /** Fixed-argv commands of active installs that granted terminal.exec. */
  async terminalCommands(pluginId?: string): Promise<PluginTerminalCommandDescriptor[]> {
    const state = await this.readState();
    const commands: PluginTerminalCommandDescriptor[] = [];
    for (const install of state.installs) {
      if (pluginId && install.pluginId !== pluginId) continue;
      const resolved = await this.resolveInstall(state, install);
      if (!resolved || this.statusOf(install, resolved) !== 'active') continue;
      if (!install.granted.includes('terminal.exec')) continue;
      for (const command of resolved.descriptor.contributions.terminal) {
        commands.push({
          pluginId: install.pluginId,
          pluginName: resolved.descriptor.name,
          commandId: command.id,
          name: command.name,
          description: command.description,
          operation: command.operation,
        });
      }
    }
    return commands;
  }

  /** One scripted page, only while its install is active and the ui.pages grant is present. */
  async resolvePage(pluginId: string, pageId: string): Promise<ResolvedPluginPage | null> {
    const state = await this.readState();
    const install = state.installs.find((candidate) => candidate.pluginId === pluginId && candidate.scope === USER_SCOPE);
    if (!install) return null;
    const resolved = await this.resolveInstall(state, install);
    if (!resolved || this.statusOf(install, resolved) !== 'active') return null;
    if (!install.granted.includes('ui.pages')) return null;
    const page = resolved.descriptor.contributions.pages.find((candidate) => candidate.id === pageId);
    if (!page) return null;
    return {
      pluginId,
      pluginName: resolved.descriptor.name,
      pageId: page.id,
      title: page.title,
      view: page.view,
      scope: page.scope,
      dir: resolved.dir,
      digest: install.digest,
      granted: [...install.granted],
    };
  }

  private assertScope(scope: string): void {
    if (scope !== USER_SCOPE) {
      throw new PluginError('unsupported_scope', `only the ${USER_SCOPE} scope is available in this build`);
    }
  }

  private async readState(): Promise<PluginState> {
    let raw: string;
    try {
      raw = await fs.readFile(this.stateFile, 'utf8');
    } catch {
      return emptyState();
    }
    try {
      const parsed: unknown = JSON.parse(raw);
      if (!isRecord(parsed) || parsed.schemaVersion !== STATE_SCHEMA_VERSION) return emptyState();
      const packages: Record<string, StoredPackage> = {};
      if (isRecord(parsed.packages)) {
        for (const [digest, value] of Object.entries(parsed.packages)) {
          if (!isRecord(value)) continue;
          if (typeof value.pluginId !== 'string' || typeof value.name !== 'string' || typeof value.version !== 'string') continue;
          packages[digest] = {
            pluginId: value.pluginId,
            name: value.name,
            version: value.version,
            digest,
            source: typeof value.source === 'string' ? value.source : '',
            installedAt: typeof value.installedAt === 'string' ? value.installedAt : nowIso(),
            requiredCapabilities: Array.isArray(value.requiredCapabilities)
              ? value.requiredCapabilities.filter((item): item is string => typeof item === 'string')
              : [],
          };
        }
      }
      const installs: StoredInstall[] = [];
      if (Array.isArray(parsed.installs)) {
        for (const value of parsed.installs) {
          if (!isRecord(value)) continue;
          if (typeof value.pluginId !== 'string' || typeof value.digest !== 'string') continue;
          if (typeof value.scope !== 'string') continue;
          const granted = Array.isArray(value.granted) ? value.granted.filter((item): item is string => typeof item === 'string') : [];
          const settings: Record<string, string | number | boolean> = {};
          if (isRecord(value.settings)) {
            for (const [key, setting] of Object.entries(value.settings)) {
              if (typeof setting === 'string' || typeof setting === 'number' || typeof setting === 'boolean') settings[key] = setting;
            }
          }
          const storage: Record<string, string> = {};
          if (isRecord(value.storage)) {
            for (const [key, item] of Object.entries(value.storage)) {
              if (STORAGE_KEY.test(key) && typeof item === 'string' && Buffer.byteLength(item, 'utf8') <= MAX_STORAGE_VALUE_BYTES) {
                storage[key] = item;
              }
            }
          }
          installs.push({
            scope: value.scope,
            pluginId: value.pluginId,
            digest: value.digest,
            enabled: value.enabled === true,
            granted,
            settings,
            storage,
            installedAt: typeof value.installedAt === 'string' ? value.installedAt : nowIso(),
            updatedAt: typeof value.updatedAt === 'string' ? value.updatedAt : nowIso(),
          });
        }
      }
      return {
        schemaVersion: STATE_SCHEMA_VERSION,
        revision: typeof parsed.revision === 'number' ? parsed.revision : 0,
        grantEpoch: typeof parsed.grantEpoch === 'number' ? parsed.grantEpoch : 0,
        packages,
        installs,
      };
    } catch {
      // A corrupt state file must not crash the app; installs disappear until repaired. Grants are
      // not inferred from a broken file.
      this.log('plugin state is unreadable; treating it as empty');
      return emptyState();
    }
  }

  private async writeState(state: PluginState): Promise<void> {
    await fs.mkdir(this.root, { recursive: true, mode: 0o700 });
    const temp = `${this.stateFile}.tmp-${process.pid}-${randomUUID()}`;
    await fs.writeFile(temp, `${JSON.stringify(state, null, 2)}\n`, { mode: 0o600 });
    try {
      await fs.rename(temp, this.stateFile);
    } catch {
      await fs.rm(this.stateFile, { force: true }).catch(() => undefined);
      await fs.rename(temp, this.stateFile);
    }
  }

  private async acquireLock(): Promise<() => Promise<void>> {
    await fs.mkdir(this.root, { recursive: true, mode: 0o700 });
    const deadline = Date.now() + LOCK_TIMEOUT_MS;
    while (Date.now() < deadline) {
      try {
        const handle = await fs.open(this.lockFile, 'wx', 0o600);
        await handle.writeFile(JSON.stringify({ pid: process.pid, at: nowIso() }));
        await handle.close();
        return async () => {
          await fs.rm(this.lockFile, { force: true }).catch(() => undefined);
        };
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
        const stat = await fs.stat(this.lockFile).catch(() => null);
        if (stat && Date.now() - stat.mtimeMs > LOCK_STALE_MS) {
          await fs.rm(this.lockFile, { force: true }).catch(() => undefined);
          continue;
        }
        await sleep(LOCK_RETRY_MS);
      }
    }
    throw new PluginError('store_busy', 'the plugin store is locked by another process');
  }

  private async mutate<T>(action: (state: PluginState) => Promise<T>): Promise<T> {
    const run = this.queue.then(async () => {
      const release = await this.acquireLock();
      try {
        const state = await this.readState();
        const result = await action(state);
        state.revision += 1;
        await this.writeState(state);
        return result;
      } finally {
        await release();
      }
    });
    this.queue = run.then(() => undefined, () => undefined);
    return run;
  }

  private requireInstall(state: PluginState, pluginId: string, scope: string): StoredInstall {
    const install = state.installs.find((candidate) => candidate.pluginId === pluginId && candidate.scope === scope);
    if (!install) throw new PluginError('not_installed', `plugin is not installed: ${pluginId}`);
    return install;
  }

  private async resolvePackage(digest: string): Promise<ResolvedPackage | null> {
    const cached = this.resolved.get(digest);
    if (cached) return cached;
    const dir = path.join(this.packagesDir, digest);
    const manifest = await manifestPath(dir);
    if (!manifest) return null;
    let json: string;
    try {
      json = await fs.readFile(manifest, 'utf8');
    } catch {
      return null;
    }
    const detected = await detectLegacyComponents(dir);
    const parsed = parsePluginManifest(json, { detectedComponents: detected });
    if (!parsed.ok || !parsed.descriptor) return null;
    const resolved: ResolvedPackage = {
      descriptor: parsed.descriptor,
      components: parsed.descriptor.components,
      dir,
    };
    this.resolved.set(digest, resolved);
    return resolved;
  }

  private async resolveInstall(state: PluginState, install: StoredInstall): Promise<ResolvedPackage | null> {
    const stored = state.packages[install.digest];
    if (!stored || stored.pluginId !== install.pluginId) return null;
    return this.resolvePackage(install.digest);
  }

  private statusOf(install: StoredInstall, resolved: ResolvedPackage): PluginView['status'] {
    // Legacy packages can be inspected and even installed for reference, but they cannot declare
    // capabilities, so they never activate or inject components.
    if (resolved.descriptor.legacy) return 'incompatible';
    if (!isDescriptorCompatible(resolved.descriptor, this.desktopApiVersion)) return 'incompatible';
    if (!install.enabled) return 'disabled';
    if (missingRequiredCapabilities({ requiredCapabilities: resolved.descriptor.capabilities.required, granted: install.granted }).length > 0) {
      return 'awaiting-consent';
    }
    return 'active';
  }

  private async viewOf(state: PluginState, install: StoredInstall): Promise<PluginView> {
    const stored = state.packages[install.digest] ?? null;
    const resolved = await this.resolveInstall(state, install);
    let status: PluginView['status'];
    let reason: string | null = null;
    if (!resolved) {
      status = 'invalid';
      reason = 'package files are missing or the manifest is invalid';
    } else {
      status = this.statusOf(install, resolved);
      if (resolved.descriptor.legacy) reason = 'legacy manifest: re-declare this package with a coreai block to activate it';
      else if (status === 'incompatible') reason = `needs desktop API ${resolved.descriptor.desktopApi}`;
      if (status === 'awaiting-consent') {
        reason = `missing permission: ${missingRequiredCapabilities({
          requiredCapabilities: resolved.descriptor.capabilities.required,
          granted: install.granted,
        }).join(', ')}`;
      }
      if (!install.enabled && status === 'disabled') reason = 'disabled by the user';
    }
    const descriptor = resolved?.descriptor;
    return {
      id: install.pluginId,
      name: descriptor?.name ?? stored?.name ?? install.pluginId,
      version: descriptor?.version ?? stored?.version ?? '0.0.0',
      digest: install.digest,
      scope: install.scope,
      enabled: install.enabled,
      granted: [...install.granted],
      requiredCapabilities: descriptor?.capabilities.required ?? [],
      optionalCapabilities: descriptor?.capabilities.optional ?? [],
      provides: descriptor?.provides ?? [],
      requires: descriptor?.requires ?? [],
      status,
      reason,
      source: stored?.source ?? null,
      components: resolved?.components ?? [],
      unsupported: descriptor?.unsupported ?? [],
      contributions: descriptor?.contributions ?? { dock: [], settings: [], composer: [], pages: [], slots: [], renderers: [], promptSections: [], tools: [], terminal: [] },
      settings: { ...install.settings },
    };
  }

  private async stageFrom(source: string, populate: (target: string) => Promise<unknown>): Promise<string> {
    await fs.mkdir(this.stagingDir, { recursive: true, mode: 0o700 });
    const staging = path.join(this.stagingDir, `${Date.now()}-${randomUUID()}`);
    await fs.mkdir(staging, { recursive: true, mode: 0o700 });
    try {
      await populate(staging);
      if (!(await manifestPath(staging))) throw new PluginError('manifest_missing', 'plugin.json not found in the package');
      this.log(`staged ${source}`);
      return staging;
    } catch (error) {
      await fs.rm(staging, { recursive: true, force: true }).catch(() => undefined);
      throw error;
    }
  }

  private async publishStaging(staging: string, source: string, scope: string): Promise<PluginView> {
    let digest: string;
    let descriptor: ResolvedPluginDescriptor;
    try {
      digest = await digestTree(staging);
      const manifest = await manifestPath(staging);
      const json = await fs.readFile(manifest as string, 'utf8');
      const detected = await detectLegacyComponents(staging);
      const parsed = parsePluginManifest(json, { detectedComponents: detected });
      if (!parsed.ok || !parsed.descriptor) {
        throw new PluginError('manifest_invalid', parsed.errors.join('; ') || 'manifest is invalid');
      }
      descriptor = parsed.descriptor;
    } catch (error) {
      await fs.rm(staging, { recursive: true, force: true }).catch(() => undefined);
      throw error;
    }
    const target = path.join(this.packagesDir, digest);

    await fs.mkdir(this.packagesDir, { recursive: true, mode: 0o700 });
    if (await exists(target)) {
      await fs.rm(staging, { recursive: true, force: true }).catch(() => undefined);
    } else {
      try {
        await fs.rename(staging, target);
      } catch (error) {
        // Another writer may have published the same digest first; that is success.
        if (await exists(target)) await fs.rm(staging, { recursive: true, force: true }).catch(() => undefined);
        else throw error;
      }
      await fs.chmod(target, 0o700).catch(() => undefined);
    }

    return this.mutate(async (state) => {
      state.packages[digest] = {
        pluginId: descriptor.id,
        name: descriptor.name,
        version: descriptor.version,
        digest,
        source,
        installedAt: state.packages[digest]?.installedAt ?? nowIso(),
        requiredCapabilities: [...descriptor.capabilities.required],
      };
      let install = state.installs.find((candidate) => candidate.pluginId === descriptor.id && candidate.scope === scope);
      if (install) {
        if (install.digest !== digest) {
          // A different digest is different content: the old grant does not carry over.
          install.digest = digest;
          install.granted = [];
        }
        install.updatedAt = nowIso();
      } else {
        install = {
          scope,
          pluginId: descriptor.id,
          digest,
          enabled: false,
          granted: [],
          settings: {},
          storage: {},
          installedAt: nowIso(),
          updatedAt: nowIso(),
        };
        state.installs.push(install);
      }
      return this.viewOf(state, install);
    });
  }
}
