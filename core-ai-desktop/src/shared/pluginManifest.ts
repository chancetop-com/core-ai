/**
 * Plugin manifest parsing and validation, shared by the desktop main process, the renderer and the
 * tests. No Electron or Node APIs live here: the store adds filesystem facts (detected legacy
 * components, digest) on top of the descriptor this module produces.
 *
 * Compatibility input is Claude Code's `.claude-plugin/plugin.json`. The desktop only executes the
 * components listed in SUPPORTED_COMPONENT_KINDS; everything else is reported as unsupported and
 * never activated, so a JSON declaration is not silently treated as executable support.
 */

export const PLUGIN_SCHEMA_VERSION = 1;
export const DESKTOP_PLUGIN_API_VERSION = '1.0.0';

export const SUPPORTED_COMPONENT_KINDS = ['skills'] as const;
export type SupportedComponentKind = (typeof SUPPORTED_COMPONENT_KINDS)[number];
export type PluginComponentKind = SupportedComponentKind | 'commands' | 'hooks' | 'agents' | 'mcp' | 'lsp';

export const SUPPORTED_CAPABILITIES = [
  'ui.dock',
  'ui.settings',
  'ui.composer',
  'ui.pages',
  'engine.skills',
  'media.assets.read',
  'media.assets.write',
  'media.models.read',
  'media.generate',
  'sessions.read',
  'memory.read',
  'workspace.read',
  'usage.read',
  'plugin.storage',
  'events.read',
  'notification.send',
] as const;
export type PluginCapability = (typeof SUPPORTED_CAPABILITIES)[number];

const MAX_CONTRIBUTIONS_PER_KIND = 20;
const MAX_DOCK_BLOCKS = 40;
const MAX_SETTINGS_FIELDS = 30;
const MAX_DOCK_ITEMS = 40;
const MAX_TEXT = 4000;
const MAX_ID = 64;

export interface PluginDockBlock {
  type: 'text' | 'key_values' | 'table' | 'divider';
  text?: string;
  items?: Array<{ label: string; value: string }>;
  columns?: string[];
  rows?: string[][];
}

export interface PluginDockContribution {
  id: string;
  title: string;
  blocks: PluginDockBlock[];
}

export type PluginSettingFieldType = 'text' | 'number' | 'boolean';

export interface PluginSettingsField {
  key: string;
  label: string;
  type: PluginSettingFieldType;
  default?: string | number | boolean;
  placeholder?: string;
}

export interface PluginSettingsContribution {
  id: string;
  title: string;
  fields: PluginSettingsField[];
}

export interface PluginComposerContribution {
  id: string;
  title: string;
  insertText: string;
}

export interface PluginPageContribution {
  id: string;
  title: string;
  /** Package-relative HTML entry. It runs in an isolated WebContentsView, never in the host renderer. */
  view: string;
  icon?: string;
  scope: 'workspace' | 'app';
}

export interface PluginContributions {
  dock: PluginDockContribution[];
  settings: PluginSettingsContribution[];
  composer: PluginComposerContribution[];
  pages: PluginPageContribution[];
}

export interface PluginComponent {
  kind: PluginComponentKind;
  path: string;
  supported: boolean;
}

export interface ResolvedPluginDescriptor {
  schemaVersion: number;
  id: string;
  name: string;
  version: string;
  description?: string;
  desktopApi: string;
  engineApi?: string;
  components: PluginComponent[];
  unsupported: string[];
  contributions: PluginContributions;
  capabilities: { required: string[]; optional: string[] };
  legacy: boolean;
}

export interface ManifestParseResult {
  ok: boolean;
  errors: string[];
  warnings: string[];
  descriptor?: ResolvedPluginDescriptor;
}

export interface ManifestParseOptions {
  /** Legacy packages carry no component list; the store supplies the directories it found. */
  detectedComponents?: Array<{ kind: PluginComponentKind; path: string }>;
}

const CAPABILITY_SET = new Set<string>(SUPPORTED_CAPABILITIES);
const COMPONENT_SET = new Set<string>(SUPPORTED_COMPONENT_KINDS);

const SAFE_ID = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const SAFE_PATH_SEGMENT = /^[^\\/:*?"<>|\u0000-\u001f]+$/;
const WINDOWS_DEVICE = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i;

export function isSupportedCapability(value: string): value is PluginCapability {
  return CAPABILITY_SET.has(value);
}

export function isSupportedComponent(kind: string): kind is SupportedComponentKind {
  return COMPONENT_SET.has(kind);
}

/** Strict semver subset: `*`, `1.2.3`, `^1.2.3`, `~1.2.3`, `>=1.2.3`, `1.x`. */
export function satisfiesVersionRange(version: string, range: string): boolean {
  const current = parseVersion(version);
  if (!current) return false;
  const trimmed = range.trim();
  if (!trimmed || trimmed === '*') return true;

  const match = /^(\^|~|>=|<=|>|<|=)?\s*(.+)$/.exec(trimmed);
  if (!match) return false;
  const operator = match[1] ?? '=';
  const wanted = parseVersion(match[2]);
  if (!wanted) return false;

  if (operator === '^') {
    // Caret keeps the left-most non-zero component: ^0.2.3 allows 0.2.x, ^1.2.3 allows 1.x.
    if (wanted.major > 0) return current.major === wanted.major && compare(current, wanted) >= 0;
    if (wanted.minor > 0) return current.major === 0 && current.minor === wanted.minor && compare(current, wanted) >= 0;
    return current.major === 0 && current.minor === 0 && current.patch === wanted.patch;
  }
  if (operator === '~') {
    return current.major === wanted.major && current.minor === wanted.minor && compare(current, wanted) >= 0;
  }
  const cmp = compare(current, wanted);
  if (operator === '>=') return cmp >= 0;
  if (operator === '>') return cmp > 0;
  if (operator === '<=') return cmp <= 0;
  if (operator === '<') return cmp < 0;
  return cmp === 0;
}

interface ParsedVersion {
  major: number;
  minor: number;
  patch: number;
}

function parseVersion(value: unknown): ParsedVersion | null {
  if (typeof value !== 'string') return null;
  const match = /^v?(\d+)(?:\.(\d+|x))?(?:\.(\d+|x))?(?:[-+].*)?$/i.exec(value.trim());
  if (!match) return null;
  const part = (raw: string | undefined): number => (raw === undefined || raw.toLowerCase() === 'x' ? 0 : Number(raw));
  return { major: Number(match[1]), minor: part(match[2]), patch: part(match[3]) };
}

function compare(a: ParsedVersion, b: ParsedVersion): number {
  if (a.major !== b.major) return a.major - b.major;
  if (a.minor !== b.minor) return a.minor - b.minor;
  return a.patch - b.patch;
}

export function safePluginId(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  return SAFE_ID.test(value) ? value : null;
}

/** Package-relative paths only: a path segment may not traverse, start a drive or escape the root. */
export function safeRelativePath(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.replace(/\\/g, '/').replace(/^\.\//, '');
  if (!normalized || normalized.length > 512) return null;
  if (normalized.startsWith('/') || /^[a-zA-Z]:/.test(normalized)) return null;
  const segments = normalized.split('/');
  if (segments.some((segment) =>
    !segment
    || segment === '.'
    || segment === '..'
    || !SAFE_PATH_SEGMENT.test(segment)
    || WINDOWS_DEVICE.test(segment)
    || /[. ]$/.test(segment))) {
    return null;
  }
  return segments.join('/');
}

function boundedText(value: unknown, max = MAX_TEXT): string | null {
  return typeof value === 'string' && value.length <= max ? value : null;
}

function contributionId(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > MAX_ID) return null;
  return /^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(value) ? value : null;
}

function parseBlocks(value: unknown, unsupported: string[]): PluginDockBlock[] {
  if (!Array.isArray(value)) return [];
  const blocks: PluginDockBlock[] = [];
  for (const raw of value.slice(0, MAX_DOCK_BLOCKS)) {
    if (!raw || typeof raw !== 'object') continue;
    const block = raw as Record<string, unknown>;
    if (block.type === 'divider') {
      blocks.push({ type: 'divider' });
      continue;
    }
    if (block.type === 'text') {
      const text = boundedText(block.text);
      if (text !== null) blocks.push({ type: 'text', text });
      continue;
    }
    if (block.type === 'key_values') {
      const items = Array.isArray(block.items) ? block.items.slice(0, MAX_DOCK_ITEMS) : [];
      const parsed: Array<{ label: string; value: string }> = [];
      for (const item of items) {
        if (!item || typeof item !== 'object') continue;
        const entry = item as Record<string, unknown>;
        const label = boundedText(entry.label, 200);
        const itemValue = boundedText(entry.value, 1000);
        if (label !== null && itemValue !== null) parsed.push({ label, value: itemValue });
      }
      if (parsed.length) blocks.push({ type: 'key_values', items: parsed });
      continue;
    }
    if (block.type === 'table') {
      const columns = Array.isArray(block.columns)
        ? block.columns.slice(0, 12).map((column) => boundedText(column, 200)).filter((column): column is string => column !== null)
        : [];
      const rawRows = Array.isArray(block.rows) ? block.rows.slice(0, MAX_DOCK_ITEMS) : [];
      const rows: string[][] = [];
      for (const row of rawRows) {
        if (!Array.isArray(row)) continue;
        const cells = row.slice(0, columns.length || 12).map((cell) => boundedText(cell, 500) ?? '');
        if (cells.length) rows.push(cells);
      }
      if (columns.length && rows.length) blocks.push({ type: 'table', columns, rows });
      continue;
    }
    unsupported.push(`ui.dock.block:${String(block.type)}`);
  }
  return blocks;
}

function parseDock(value: unknown, unsupported: string[]): PluginDockContribution[] {
  if (!Array.isArray(value)) return [];
  const contributions: PluginDockContribution[] = [];
  for (const raw of value.slice(0, MAX_CONTRIBUTIONS_PER_KIND)) {
    if (!raw || typeof raw !== 'object') continue;
    const entry = raw as Record<string, unknown>;
    if (entry.view !== undefined) {
      // A scripted view needs an isolated WebContentsView host; this build only renders host blocks.
      unsupported.push('ui.dock.view');
      continue;
    }
    const id = contributionId(entry.id);
    const title = boundedText(entry.title, 120);
    if (!id || !title) continue;
    const blocks = parseBlocks(entry.blocks, unsupported);
    if (blocks.length) contributions.push({ id, title, blocks });
  }
  return contributions;
}

function parseSettings(value: unknown): PluginSettingsContribution[] {
  if (!Array.isArray(value)) return [];
  const contributions: PluginSettingsContribution[] = [];
  for (const raw of value.slice(0, MAX_CONTRIBUTIONS_PER_KIND)) {
    if (!raw || typeof raw !== 'object') continue;
    const entry = raw as Record<string, unknown>;
    const id = contributionId(entry.id);
    const title = boundedText(entry.title, 120);
    if (!id || !title) continue;
    const fields: PluginSettingsField[] = [];
    const rawFields = Array.isArray(entry.fields) ? entry.fields.slice(0, MAX_SETTINGS_FIELDS) : [];
    for (const rawField of rawFields) {
      if (!rawField || typeof rawField !== 'object') continue;
      const field = rawField as Record<string, unknown>;
      const key = contributionId(field.key);
      const label = boundedText(field.label, 200);
      const type = field.type === 'number' || field.type === 'boolean' ? field.type : 'text';
      if (!key || !label) continue;
      const parsed: PluginSettingsField = { key, label, type };
      if (type === 'boolean' && typeof field.default === 'boolean') parsed.default = field.default;
      if (type === 'number' && typeof field.default === 'number' && Number.isFinite(field.default)) parsed.default = field.default;
      if (type === 'text' && typeof field.default === 'string') parsed.default = field.default.slice(0, MAX_TEXT);
      if (type === 'text' && typeof field.placeholder === 'string') parsed.placeholder = field.placeholder.slice(0, 200);
      fields.push(parsed);
    }
    if (fields.length) contributions.push({ id, title, fields });
  }
  return contributions;
}

function parseComposer(value: unknown): PluginComposerContribution[] {
  if (!Array.isArray(value)) return [];
  const contributions: PluginComposerContribution[] = [];
  for (const raw of value.slice(0, MAX_CONTRIBUTIONS_PER_KIND)) {
    if (!raw || typeof raw !== 'object') continue;
    const entry = raw as Record<string, unknown>;
    const id = contributionId(entry.id);
    const title = boundedText(entry.title, 120);
    const insertText = boundedText(entry.insertText, MAX_TEXT);
    if (!id || !title || !insertText) continue;
    contributions.push({ id, title, insertText });
  }
  return contributions;
}

function parsePages(value: unknown): PluginPageContribution[] {
  if (!Array.isArray(value)) return [];
  const contributions: PluginPageContribution[] = [];
  for (const raw of value.slice(0, MAX_CONTRIBUTIONS_PER_KIND)) {
    if (!raw || typeof raw !== 'object') continue;
    const entry = raw as Record<string, unknown>;
    const id = contributionId(entry.id);
    const title = boundedText(entry.title, 120);
    const view = safeRelativePath(entry.view);
    if (!id || !title || !view) continue;
    const icon = boundedText(entry.icon, 16) ?? undefined;
    const scope = entry.scope === 'app' ? 'app' : 'workspace';
    contributions.push({ id, title, view, ...(icon ? { icon } : {}), scope });
  }
  return contributions;
}

function stringList(value: unknown): string[] | null {
  if (value === undefined) return [];
  if (!Array.isArray(value)) return null;
  const result: string[] = [];
  for (const item of value) {
    if (typeof item !== 'string' || item.length > 100) return null;
    result.push(item);
  }
  return [...new Set(result)];
}

/**
 * Parses either a new `coreai` manifest or the legacy Claude Code shape.
 *
 * Fatal errors mean the package cannot be installed. Reported warnings and `unsupported` entries
 * keep a partially-supported package installable but never run the unsupported parts.
 */
export function parsePluginManifest(json: string, options: ManifestParseOptions = {}): ManifestParseResult {
  const errors: string[] = [];
  const warnings: string[] = [];
  const unsupported: string[] = [];

  let root: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(json);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { ok: false, errors: ['manifest must be a JSON object'], warnings };
    }
    root = parsed as Record<string, unknown>;
  } catch {
    return { ok: false, errors: ['manifest is not valid JSON'], warnings };
  }

  const name = boundedText(root.name, 120);
  if (!name) errors.push('missing required field: name');
  const version = boundedText(root.version, 64) ?? '0.0.0';
  const description = boundedText(root.description, 500) ?? undefined;

  const coreai = root.coreai;
  const legacy = coreai === undefined;
  // Legacy packages have no id field; derive a stable local id for state keys without implying
  // that a publisher name is verified.
  const legacyId = name
    ? safePluginId(`local.${name.toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '')}`)
    : null;
  let id: string | null = legacyId;
  let schemaVersion = 0;
  let desktopApi = '*';
  let engineApi: string | undefined;
  const components: PluginComponent[] = [];
  const contributions: PluginContributions = { dock: [], settings: [], composer: [], pages: [] };
  let required: string[] = [];
  let optional: string[] = [];

  if (coreai !== undefined) {
    if (!coreai || typeof coreai !== 'object' || Array.isArray(coreai)) {
      errors.push('coreai must be an object');
    } else {
      const block = coreai as Record<string, unknown>;
      schemaVersion = typeof block.schemaVersion === 'number' ? block.schemaVersion : 0;
      if (schemaVersion !== PLUGIN_SCHEMA_VERSION) errors.push(`unsupported coreai.schemaVersion: ${String(block.schemaVersion)}`);
      id = safePluginId(block.id);
      if (!id) errors.push('coreai.id must be a lowercase namespaced id');
      desktopApi = boundedText(block.desktopApi, 40) ?? '*';
      engineApi = boundedText(block.engineApi, 40) ?? undefined;

      if (block.components !== undefined) {
        if (!Array.isArray(block.components)) {
          errors.push('coreai.components must be an array');
        } else {
          for (const raw of block.components.slice(0, 30)) {
            if (!raw || typeof raw !== 'object') continue;
            const entry = raw as Record<string, unknown>;
            const kind = typeof entry.kind === 'string' ? entry.kind : '';
            const componentPath = safeRelativePath(entry.path);
            if (!kind || !componentPath) {
              errors.push('coreai.components entries need kind and a package-relative path');
              continue;
            }
            const supported = isSupportedComponent(kind);
            if (!supported) unsupported.push(`component:${kind}`);
            components.push({ kind: kind as PluginComponentKind, path: componentPath, supported });
          }
        }
      }

      const rawContributions = block.contributes;
      if (rawContributions !== undefined) {
        if (!rawContributions || typeof rawContributions !== 'object' || Array.isArray(rawContributions)) {
          errors.push('coreai.contributes must be an object');
        } else {
          const contributes = rawContributions as Record<string, unknown>;
          contributions.dock = parseDock(contributes.dock, unsupported);
          contributions.settings = parseSettings(contributes.settings);
          contributions.composer = parseComposer(contributes.composer);
          contributions.pages = parsePages(contributes.pages);
        }
      }

      const rawCapabilities = block.capabilities;
      if (rawCapabilities !== undefined) {
        if (!rawCapabilities || typeof rawCapabilities !== 'object' || Array.isArray(rawCapabilities)) {
          errors.push('coreai.capabilities must be an object');
        } else {
          const capabilities = rawCapabilities as Record<string, unknown>;
          const requiredList = stringList(capabilities.required);
          const optionalList = stringList(capabilities.optional);
          if (!requiredList || !optionalList) {
            errors.push('capabilities.required/optional must be string arrays');
          } else {
            for (const capability of requiredList) {
              if (!isSupportedCapability(capability)) errors.push(`unsupported required capability: ${capability}`);
            }
            const keptOptional: string[] = [];
            for (const capability of optionalList) {
              if (isSupportedCapability(capability)) keptOptional.push(capability);
              else warnings.push(`ignored unsupported optional capability: ${capability}`);
            }
            required = requiredList.filter(isSupportedCapability);
            optional = keptOptional.filter((capability) => !required.includes(capability));
          }
        }
      }
    }
  } else {
    // Legacy packages stay inspectable and installable, but activation still requires the
    // capabilities the desktop will actually call. They are inferred only for display.
    for (const detected of options.detectedComponents ?? []) {
      const supported = isSupportedComponent(detected.kind);
      if (!supported) unsupported.push(`component:${detected.kind}`);
      components.push({ kind: detected.kind, path: detected.path, supported });
    }
    if (!components.some((component) => component.kind === 'skills')) {
      warnings.push('legacy manifest declares no supported component');
    }
  }

  const capabilityForContribution = (kind: 'dock' | 'settings' | 'composer' | 'pages'): string => `ui.${kind}`;
  for (const kind of ['dock', 'settings', 'composer', 'pages'] as const) {
    if (!contributions[kind].length) continue;
    const capability = capabilityForContribution(kind);
    if (!required.includes(capability) && !optional.includes(capability)) {
      if (legacy) warnings.push(`${kind} contributions need a coreai manifest with capability ${capability}; they are not exposed`);
      else errors.push(`contribution ${kind} requires capability ${capability}`);
    }
  }
  if (components.some((component) => component.kind === 'skills' && component.supported)) {
    if (legacy) {
      warnings.push('legacy skills are not injected into the engine; re-declare them in a coreai manifest');
    } else if (!required.includes('engine.skills') && !optional.includes('engine.skills')) {
      errors.push('skills component requires capability engine.skills');
    }
  }

  if (id && !satisfiesVersionRange(DESKTOP_PLUGIN_API_VERSION, desktopApi)) {
    warnings.push(`desktopApi ${desktopApi} is not satisfied by ${DESKTOP_PLUGIN_API_VERSION}`);
  }

  if (errors.length) return { ok: false, errors, warnings };
  if (!id) return { ok: false, errors: [...errors, 'coreai.id is required'], warnings };

  const descriptor: ResolvedPluginDescriptor = {
    schemaVersion,
    id,
    name: name as string,
    version,
    description,
    desktopApi,
    engineApi,
    components,
    unsupported: [...new Set(unsupported)],
    contributions,
    capabilities: { required, optional },
    legacy,
  };
  return { ok: true, errors, warnings, descriptor };
}

export function isDescriptorCompatible(descriptor: ResolvedPluginDescriptor, desktopApiVersion = DESKTOP_PLUGIN_API_VERSION): boolean {
  return satisfiesVersionRange(desktopApiVersion, descriptor.desktopApi);
}

export type PluginStatus = 'active' | 'disabled' | 'awaiting-consent' | 'incompatible' | 'invalid' | 'error';

export interface PluginView {
  id: string;
  name: string;
  version: string;
  digest: string;
  scope: string;
  enabled: boolean;
  granted: string[];
  requiredCapabilities: string[];
  optionalCapabilities: string[];
  status: PluginStatus;
  reason: string | null;
  source: string | null;
  components: PluginComponent[];
  unsupported: string[];
  contributions: PluginContributions;
  settings: Record<string, string | number | boolean>;
}

export type PluginOperationResult =
  | { ok: true; plugin: PluginView | null }
  | { ok: false; code: string; message: string };

export function missingRequiredCapabilities(plugin: Pick<PluginView, 'requiredCapabilities' | 'granted'>): string[] {
  return plugin.requiredCapabilities.filter((capability) => !plugin.granted.includes(capability));
}

export function pluginStatusLabel(status: PluginStatus): string {
  switch (status) {
    case 'active':
      return 'Active';
    case 'disabled':
      return 'Disabled';
    case 'awaiting-consent':
      return 'Needs permission';
    case 'incompatible':
      return 'Incompatible';
    case 'invalid':
      return 'Invalid';
    default:
      return 'Error';
  }
}
