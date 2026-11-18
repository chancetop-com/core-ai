/**
 * Desktop-side plugin coordinator: one store plus the engine environment derived from it.
 *
 * The engine only receives skill roots for installs that are active and granted `engine.skills`.
 * It never receives hook, MCP or command paths from a plugin in this build; those components are
 * reported as unsupported by the descriptor.
 */
import path from 'node:path';
import type { PluginView } from '../../shared/pluginManifest';
import { PluginStore, type ActivePluginContributions, type EngineSkillRoot, type PluginPromptSection, type PluginStoreOptions, type PluginToolDescriptor, type ResolvedPluginPage } from './pluginStore';

export const PLUGIN_SKILL_ROOTS_ENV = 'CORE_AI_PLUGIN_SKILL_ROOTS';
export const PLUGIN_PROMPT_SECTIONS_ENV = 'CORE_AI_PLUGIN_PROMPT_SECTIONS';
export const PLUGIN_TOOLS_ENV = 'CORE_AI_PLUGIN_TOOLS';
export const PLUGIN_STORE_ROOT_ENV = 'CORE_AI_PLUGIN_STORE_ROOT';
export const PLUGIN_STATE_FILE_ENV = 'CORE_AI_PLUGIN_STATE_FILE';
export const PLUGIN_HOME_ENV = 'CORE_AI_PLUGIN_HOME';

export interface PluginHostOptions extends PluginStoreOptions {
  onChanged?: () => void;
}

export class PluginHost {
  private readonly store: PluginStore;
  private readonly onChanged: () => void;

  constructor(options: PluginHostOptions) {
    this.store = new PluginStore(options);
    this.onChanged = options.onChanged ?? (() => undefined);
  }

  get rootDir(): string {
    return this.store.rootDir;
  }

  list(): Promise<PluginView[]> {
    return this.store.list();
  }

  async installFolder(folder: string): Promise<PluginView> {
    return this.changed(() => this.store.installFolder(folder));
  }

  async installZip(archive: string): Promise<PluginView> {
    return this.changed(() => this.store.installZip(archive));
  }

  async setEnabled(pluginId: string, enabled: boolean): Promise<PluginView> {
    return this.changed(() => this.store.setEnabled(pluginId, enabled));
  }

  async grant(pluginId: string, capabilities: string[]): Promise<PluginView> {
    return this.changed(() => this.store.grant(pluginId, capabilities));
  }

  async revoke(pluginId: string): Promise<PluginView> {
    // Revocation changes the grant epoch synchronously in the store; the engine reload is the
    // safe-point follow-up, not the authorization boundary itself.
    return this.changed(() => this.store.revoke(pluginId));
  }

  async remove(pluginId: string): Promise<void> {
    await this.changed(() => this.store.remove(pluginId));
  }

  async setSetting(pluginId: string, key: string, value: string | number | boolean): Promise<PluginView> {
    return this.store.setSetting(pluginId, key, value);
  }

  storageGet(pluginId: string, key: string): Promise<string | null> {
    return this.store.storageGet(pluginId, key);
  }

  storageKeys(pluginId: string): Promise<string[]> {
    return this.store.storageKeys(pluginId);
  }

  storageSet(pluginId: string, key: string, value: string): Promise<void> {
    return this.store.storageSet(pluginId, key, value);
  }

  storageDelete(pluginId: string, key: string): Promise<boolean> {
    return this.store.storageDelete(pluginId, key);
  }

  contributions(): Promise<ActivePluginContributions[]> {
    return this.store.activeContributions();
  }

  skillRoots(): Promise<EngineSkillRoot[]> {
    return this.store.engineSkillRoots();
  }

  promptSections(): Promise<PluginPromptSection[]> {
    return this.store.promptSections();
  }

  toolDescriptors(): Promise<PluginToolDescriptor[]> {
    return this.store.tools();
  }

  resolvePage(pluginId: string, pageId: string): Promise<ResolvedPluginPage | null> {
    return this.store.resolvePage(pluginId, pageId);
  }

  /** Engine environment for one workspace; omitted keys leave the engine unchanged. */
  async engineEnv(): Promise<Record<string, string>> {
    const roots = await this.skillRoots();
    const promptSections = await this.promptSections();
    const tools = await this.toolDescriptors();
    const env: Record<string, string> = {
      [PLUGIN_STORE_ROOT_ENV]: path.join(this.rootDir, 'packages'),
      [PLUGIN_STATE_FILE_ENV]: path.join(this.rootDir, 'state.json'),
      [PLUGIN_HOME_ENV]: this.rootDir,
    };
    if (roots.length) env[PLUGIN_SKILL_ROOTS_ENV] = JSON.stringify(roots);
    if (promptSections.length) env[PLUGIN_PROMPT_SECTIONS_ENV] = JSON.stringify(promptSections);
    if (tools.length) env[PLUGIN_TOOLS_ENV] = JSON.stringify(tools);
    return env;
  }

  private async changed<T>(action: () => Promise<T>): Promise<T> {
    const result = await action();
    this.onChanged();
    return result;
  }
}
