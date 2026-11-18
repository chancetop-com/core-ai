/**
 * Renderer-side view helpers for plugin contributions.
 *
 * The renderer never trusts the plugin to render itself: dock/settings/composer contributions are
 * plain JSON and the host draws them. These helpers filter by status and grant so a disabled or
 * unapproved plugin contributes nothing to the UI.
 */
import {
  missingRequiredCapabilities,
  pluginStatusLabel,
  type PluginComposerContribution,
  type PluginDockBlock,
  type PluginDockContribution,
  type PluginPageContribution,
  type PluginRendererContribution,
  type PluginSlotContribution,
  type PluginSlotId,
  type PluginSettingsContribution,
  type PluginView,
} from '../shared/pluginManifest';

export interface PluginDockTab {
  key: string;
  pluginId: string;
  title: string;
  blocks: PluginDockBlock[];
}

export interface PluginComposerAction {
  key: string;
  pluginId: string;
  title: string;
  insertText: string;
}

export interface PluginSlotAction {
  key: string;
  pluginId: string;
  slot: PluginSlotId;
  title: string;
  action: PluginSlotContribution['action'];
}

export interface PluginArtifactRenderer {
  key: string;
  pluginId: string;
  title: string;
  pageId: string;
  extensions: string[];
}

export interface PluginSettingsSection {
  key: string;
  pluginId: string;
  title: string;
  fields: PluginSettingsContribution['fields'];
}

export function dockTabKey(pluginId: string, contributionId: string): string {
  return `plugin:${pluginId}:${contributionId}`;
}

export function isPluginDockKey(dock: string): boolean {
  return dock.startsWith('plugin:');
}

export function dockTabs(plugins: PluginView[]): PluginDockTab[] {
  const tabs: PluginDockTab[] = [];
  for (const plugin of plugins) {
    if (plugin.status !== 'active' || !plugin.granted.includes('ui.dock')) continue;
    for (const contribution of plugin.contributions?.dock ?? []) {
      if (!contribution.blocks.length) continue;
      tabs.push({
        key: dockTabKey(plugin.id, contribution.id),
        pluginId: plugin.id,
        title: contribution.title,
        blocks: contribution.blocks,
      });
    }
  }
  return tabs;
}

export function composerActions(plugins: PluginView[]): PluginComposerAction[] {
  const actions: PluginComposerAction[] = [];
  for (const plugin of plugins) {
    if (plugin.status !== 'active' || !plugin.granted.includes('ui.composer')) continue;
    for (const contribution of plugin.contributions?.composer ?? []) {
      actions.push({
        key: `${plugin.id}:${contribution.id}`,
        pluginId: plugin.id,
        title: contribution.title,
        insertText: contribution.insertText,
      });
    }
  }
  return actions;
}

export function slotActions(plugins: PluginView[], slot: PluginSlotId): PluginSlotAction[] {
  const actions: PluginSlotAction[] = [];
  for (const plugin of plugins) {
    if (plugin.status !== 'active' || !plugin.granted.includes('ui.slots')) continue;
    for (const contribution of plugin.contributions?.slots ?? []) {
      if (contribution.slot !== slot) continue;
      actions.push({
        key: `${plugin.id}:${contribution.id}`,
        pluginId: plugin.id,
        slot,
        title: contribution.title,
        action: contribution.action,
      });
    }
  }
  return actions;
}

export function artifactRenderers(plugins: PluginView[], fileName: string): PluginArtifactRenderer[] {
  const extension = fileName.toLowerCase().match(/\.[a-z0-9]{1,10}$/)?.[0] ?? '';
  if (!extension) return [];
  const renderers: PluginArtifactRenderer[] = [];
  for (const plugin of plugins) {
    if (plugin.status !== 'active' || !plugin.granted.includes('ui.renderers')) continue;
    for (const contribution of plugin.contributions?.renderers ?? []) {
      if (!contribution.extensions.includes(extension)) continue;
      renderers.push({
        key: `${plugin.id}:${contribution.id}`,
        pluginId: plugin.id,
        title: contribution.title,
        pageId: contribution.pageId,
        extensions: contribution.extensions,
      });
    }
  }
  return renderers;
}

export function settingsSections(plugins: PluginView[]): PluginSettingsSection[] {
  const sections: PluginSettingsSection[] = [];
  for (const plugin of plugins) {
    if (plugin.status === 'invalid' || plugin.status === 'incompatible') continue;
    if (!plugin.granted.includes('ui.settings')) continue;
    for (const contribution of plugin.contributions?.settings ?? []) {
      if (!contribution.fields.length) continue;
      sections.push({
        key: `${plugin.id}:${contribution.id}`,
        pluginId: plugin.id,
        title: `${plugin.name} — ${contribution.title}`,
        fields: contribution.fields,
      });
    }
  }
  return sections;
}

export function pluginNeedsConsent(plugin: PluginView): boolean {
  return plugin.status === 'awaiting-consent' || missingRequiredCapabilities({
    requiredCapabilities: plugin.requiredCapabilities,
    granted: plugin.granted,
  }).length > 0;
}

export function pluginSummary(plugin: PluginView): string {
  const version = plugin.version ? ` v${plugin.version}` : '';
  return `${plugin.name}${version} · ${pluginStatusLabel(plugin.status)}${plugin.reason ? ` — ${plugin.reason}` : ''}`;
}

export function pluginCapabilityLabel(capability: string): string {
  switch (capability) {
    case 'engine.skills':
      return 'Add skills to the local agent';
    case 'ui.dock':
      return 'Add a session dock panel';
    case 'ui.settings':
      return 'Add settings controls';
    case 'ui.composer':
      return 'Add a composer action';
    default:
      return capability;
  }
}

/** Filters the IPC payload down to the shape the renderer can safely draw. */
export function normalizePlugins(value: unknown): PluginView[] {
  if (!Array.isArray(value)) return [];
  const plugins: PluginView[] = [];
  for (const raw of value) {
    if (!raw || typeof raw !== 'object') continue;
    const entry = raw as Record<string, unknown>;
    if (typeof entry.id !== 'string' || typeof entry.name !== 'string') continue;
    const status = entry.status;
    if (status !== 'active' && status !== 'disabled' && status !== 'awaiting-consent'
      && status !== 'incompatible' && status !== 'invalid' && status !== 'error') continue;
    plugins.push({
      id: entry.id,
      name: entry.name,
      version: typeof entry.version === 'string' ? entry.version : '0.0.0',
      digest: typeof entry.digest === 'string' ? entry.digest : '',
      scope: typeof entry.scope === 'string' ? entry.scope : 'user',
      enabled: entry.enabled === true,
      granted: stringArray(entry.granted),
      requiredCapabilities: stringArray(entry.requiredCapabilities),
      optionalCapabilities: stringArray(entry.optionalCapabilities),
      provides: normalizeProvides(entry.provides),
      requires: stringArray(entry.requires),
      status,
      reason: typeof entry.reason === 'string' ? entry.reason : null,
      source: typeof entry.source === 'string' ? entry.source : null,
      components: Array.isArray(entry.components)
        ? entry.components.filter((component): component is PluginView['components'][number] =>
          Boolean(component) && typeof component === 'object'
          && typeof (component as Record<string, unknown>).kind === 'string'
          && typeof (component as Record<string, unknown>).path === 'string')
        : [],
      unsupported: stringArray(entry.unsupported),
      contributions: normalizeContributions(entry.contributions),
      settings: normalizeSettings(entry.settings),
    });
  }
  return plugins;
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function normalizeProvides(value: unknown): PluginView['provides'] {
  if (!Array.isArray(value)) return [];
  return value
    .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object' && !Array.isArray(item))
    .filter((item) => typeof item.id === 'string')
    .map((item) => ({ id: item.id as string, ...(typeof item.version === 'string' ? { version: item.version } : {}) }));
}

function normalizeContributions(value: unknown): PluginView['contributions'] {
  const empty: PluginView['contributions'] = { dock: [], settings: [], composer: [], pages: [], slots: [], renderers: [], promptSections: [] };
  if (!value || typeof value !== 'object') return empty;
  const raw = value as Record<string, unknown>;
  return {
    dock: Array.isArray(raw.dock) ? raw.dock.filter(isDockContribution) : [],
    settings: Array.isArray(raw.settings) ? raw.settings.filter(isSettingsContribution) : [],
    composer: Array.isArray(raw.composer) ? raw.composer.filter(isComposerContribution) : [],
    pages: Array.isArray(raw.pages) ? raw.pages.filter(isPageContribution) : [],
    slots: Array.isArray(raw.slots) ? raw.slots.filter(isSlotContribution) : [],
    renderers: Array.isArray(raw.renderers) ? raw.renderers.filter(isRendererContribution) : [],
    promptSections: [],
  };
}

function isRendererContribution(value: unknown): value is PluginRendererContribution {
  if (!value || typeof value !== 'object') return false;
  const entry = value as Record<string, unknown>;
  return entry.kind === 'artifact'
    && typeof entry.id === 'string'
    && typeof entry.title === 'string'
    && typeof entry.pageId === 'string'
    && Array.isArray(entry.extensions);
}

function isSlotContribution(value: unknown): value is PluginSlotContribution {
  if (!value || typeof value !== 'object') return false;
  const entry = value as Record<string, unknown>;
  if (typeof entry.id !== 'string' || typeof entry.title !== 'string' || typeof entry.slot !== 'string') return false;
  if (entry.slot !== 'sidebar.footer.action'
    && entry.slot !== 'composer.dock'
    && entry.slot !== 'conversation.turn.tail'
    && entry.slot !== 'conversation.message.actions') return false;
  const action = entry.action;
  if (!action || typeof action !== 'object') return false;
  const kind = (action as Record<string, unknown>).kind;
  if (kind === 'openPage') return typeof (action as Record<string, unknown>).pageId === 'string';
  if (kind === 'insertText') return typeof (action as Record<string, unknown>).text === 'string';
  return false;
}

function isPageContribution(value: unknown): value is PluginPageContribution {
  if (!value || typeof value !== 'object') return false;
  const entry = value as Record<string, unknown>;
  return typeof entry.id === 'string' && typeof entry.title === 'string' && typeof entry.view === 'string';
}

function isDockContribution(value: unknown): value is PluginDockContribution {
  if (!value || typeof value !== 'object') return false;
  const entry = value as Record<string, unknown>;
  return typeof entry.id === 'string' && typeof entry.title === 'string' && Array.isArray(entry.blocks);
}

function isSettingsContribution(value: unknown): value is PluginSettingsContribution {
  if (!value || typeof value !== 'object') return false;
  const entry = value as Record<string, unknown>;
  return typeof entry.id === 'string' && typeof entry.title === 'string' && Array.isArray(entry.fields);
}

function isComposerContribution(value: unknown): value is PluginComposerContribution {
  if (!value || typeof value !== 'object') return false;
  const entry = value as Record<string, unknown>;
  return typeof entry.id === 'string' && typeof entry.title === 'string' && typeof entry.insertText === 'string';
}

function normalizeSettings(value: unknown): Record<string, string | number | boolean> {
  if (!value || typeof value !== 'object') return {};
  const settings: Record<string, string | number | boolean> = {};
  for (const [key, setting] of Object.entries(value as Record<string, unknown>)) {
    if (typeof setting === 'string' || typeof setting === 'number' || typeof setting === 'boolean') settings[key] = setting;
  }
  return settings;
}
