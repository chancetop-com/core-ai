export type PluginCapability =
  | 'ui.dock'
  | 'ui.settings'
  | 'ui.composer'
  | 'ui.pages'
  | 'engine.skills'
  | 'media.assets.read'
  | 'media.models.read'
  | 'media.generate';
export type PluginComponentKind = 'skills' | 'commands' | 'hooks' | 'agents' | 'mcp' | 'lsp';

export interface PluginComponent {
  kind: PluginComponentKind;
  path: string;
}

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
  /** Scripted views are not supported by the current desktop; prefer blocks. */
  view?: string;
  blocks?: PluginDockBlock[];
}

export interface PluginSettingField {
  key: string;
  label: string;
  type?: 'text' | 'number' | 'boolean';
  default?: string | number | boolean;
  placeholder?: string;
}

export interface PluginSettingsContribution {
  id: string;
  title: string;
  fields: PluginSettingField[];
}

export interface PluginComposerContribution {
  id: string;
  title: string;
  insertText: string;
}

export interface PluginPageContribution {
  id: string;
  title: string;
  /** Package-relative HTML entry, hosted in an isolated page view. */
  view: string;
  icon?: string;
  scope?: 'workspace' | 'app';
}

export interface PluginManifest {
  name: string;
  version: string;
  description?: string;
  coreai: {
    schemaVersion: 1;
    id: string;
    desktopApi?: string;
    engineApi?: string;
    components?: PluginComponent[];
    capabilities?: { required?: PluginCapability[]; optional?: PluginCapability[] };
    contributes?: {
      dock?: PluginDockContribution[];
      settings?: PluginSettingsContribution[];
      composer?: PluginComposerContribution[];
      pages?: PluginPageContribution[];
    };
  };
}

export declare const PLUGIN_SCHEMA_VERSION: 1;
export declare const DESKTOP_API_VERSION: string;
export declare const CAPABILITIES: readonly PluginCapability[];
export declare const COMPONENT_KINDS: readonly PluginComponentKind[];

export declare function definePlugin<T extends PluginManifest>(manifest: T): T;

export interface ValidationResult {
  ok: boolean;
  errors: string[];
  warnings: string[];
}

export declare function validateManifest(input: PluginManifest | string): ValidationResult;
