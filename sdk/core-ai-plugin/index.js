/**
 * core-ai plugin authoring SDK.
 *
 * This package gives plugin authors the manifest contract (types + JSON schema + a fast local
 * validator). The desktop remains the enforcing validator: a package that passes here can still be
 * rejected there if the schema or capability catalog moves on.
 *
 * A plugin is a directory with `.claude-plugin/plugin.json`; it can contribute skills, declarative
 * dock/settings/composer slots, and (in future phases) other components. Installation is disabled
 * by default and activation requires an explicit capability grant.
 */

export const PLUGIN_SCHEMA_VERSION = 1;
export const DESKTOP_API_VERSION = '1.0.0';

export const CAPABILITIES = Object.freeze([
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
  'plugin.storage',
  'events.read',
]);
export const COMPONENT_KINDS = Object.freeze(['skills', 'commands', 'hooks', 'agents', 'mcp', 'lsp']);

const SAFE_ID = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const SAFE_SEGMENT = /^[^\\/:*?"<>|\u0000-\u001f]+$/;
const WINDOWS_DEVICE = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i;

/** Identity helper for editors; no runtime behaviour. */
export function definePlugin(manifest) {
  return manifest;
}

function isObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function safePath(value) {
  if (typeof value !== 'string') return false;
  const normalized = value.replace(/\\/g, '/').replace(/^\.\//, '');
  if (!normalized || normalized.startsWith('/') || /^[a-zA-Z]:/.test(normalized)) return false;
  return normalized.split('/').every((segment) =>
    segment && segment !== '.' && segment !== '..' && SAFE_SEGMENT.test(segment)
    && !WINDOWS_DEVICE.test(segment) && !/[. ]$/.test(segment));
}

/**
 * Local, non-executing validation. Returns all problems instead of throwing so editors can list
 * them; the plugin is never loaded or run by this function.
 */
export function validateManifest(input) {
  const errors = [];
  const warnings = [];
  let manifest;
  try {
    manifest = typeof input === 'string' ? JSON.parse(input) : input;
  } catch {
    return { ok: false, errors: ['manifest is not valid JSON'], warnings };
  }
  if (!isObject(manifest)) return { ok: false, errors: ['manifest must be a JSON object'], warnings };
  if (typeof manifest.name !== 'string' || !manifest.name.trim()) errors.push('missing required field: name');
  if (typeof manifest.version !== 'string' || !manifest.version.trim()) errors.push('missing required field: version');

  const coreai = manifest.coreai;
  if (!isObject(coreai)) {
    return { ok: false, errors: [...errors, 'manifest must contain a coreai block'], warnings };
  }
  if (coreai.schemaVersion !== PLUGIN_SCHEMA_VERSION) errors.push(`unsupported coreai.schemaVersion: ${String(coreai.schemaVersion)}`);
  if (typeof coreai.id !== 'string' || !SAFE_ID.test(coreai.id)) errors.push('coreai.id must be a lowercase namespaced id');
  if (coreai.desktopApi !== undefined && typeof coreai.desktopApi !== 'string') errors.push('coreai.desktopApi must be a string');
  if (coreai.engineApi !== undefined && typeof coreai.engineApi !== 'string') errors.push('coreai.engineApi must be a string');

  const components = Array.isArray(coreai.components) ? coreai.components : [];
  if (coreai.components !== undefined && !Array.isArray(coreai.components)) errors.push('coreai.components must be an array');
  for (const component of components) {
    if (!isObject(component) || typeof component.kind !== 'string' || !COMPONENT_KINDS.includes(component.kind) || !safePath(component.path)) {
      errors.push('each component needs a known kind and a package-relative path');
    }
  }

  const capabilities = isObject(coreai.capabilities) ? coreai.capabilities : {};
  const required = Array.isArray(capabilities.required) ? capabilities.required : [];
  const optional = Array.isArray(capabilities.optional) ? capabilities.optional : [];
  for (const capability of required) {
    if (!CAPABILITIES.includes(capability)) errors.push(`unsupported required capability: ${String(capability)}`);
  }
  for (const capability of optional) {
    if (!CAPABILITIES.includes(capability)) warnings.push(`ignored unsupported optional capability: ${String(capability)}`);
  }

  const contributes = isObject(coreai.contributes) ? coreai.contributes : {};
  for (const kind of ['dock', 'settings', 'composer', 'pages']) {
    if (contributes[kind] === undefined) continue;
    if (!Array.isArray(contributes[kind])) {
      errors.push(`coreai.contributes.${kind} must be an array`);
      continue;
    }
    const capability = `ui.${kind}`;
    if (!required.includes(capability) && !optional.includes(capability)) {
      errors.push(`contribution ${kind} requires capability ${capability}`);
    }
    if (kind === 'dock') {
      for (const item of contributes.dock) {
        if (isObject(item) && item.view !== undefined) warnings.push('scripted dock views are reported as unsupported by the current desktop');
      }
    }
    if (kind === 'pages') {
      for (const item of contributes.pages) {
        if (!isObject(item)
          || typeof item.id !== 'string' || !item.id
          || typeof item.title !== 'string' || !item.title
          || !safePath(item.view)) {
          errors.push('each page needs id, title and a package-relative view path');
        }
      }
    }
  }
  const hasSkills = components.some((component) => isObject(component) && component.kind === 'skills');
  if (hasSkills && !required.includes('engine.skills') && !optional.includes('engine.skills')) {
    errors.push('skills component requires capability engine.skills');
  }

  return { ok: errors.length === 0, errors, warnings };
}
