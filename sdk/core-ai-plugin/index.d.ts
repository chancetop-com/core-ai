export type PluginCapability =
  | 'ui.dock'
  | 'ui.settings'
  | 'ui.composer'
  | 'ui.pages'
  | 'ui.slots'
  | 'engine.skills'
  | 'media.assets.read'
  | 'media.assets.write'
  | 'media.models.read'
  | 'media.generate'
  | 'sessions.read'
  | 'sessions.write'
  | 'memory.read'
  | 'memory.write'
  | 'workspace.read'
  | 'usage.read'
  | 'plugin.storage'
  | 'events.read'
  | 'notification.send'
  | 'schedules.read'
  | 'schedules.write';
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

export type PluginSlotId =
  | 'sidebar.footer.action'
  | 'composer.dock'
  | 'conversation.turn.tail'
  | 'conversation.message.actions';

export type PluginSlotAction =
  | { kind: 'openPage'; pageId: string }
  | { kind: 'insertText'; text: string };

export interface PluginSlotContribution {
  id: string;
  slot: PluginSlotId;
  title: string;
  action: PluginSlotAction;
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
        capabilities?: {
          required?: PluginCapability[];
          optional?: PluginCapability[];
        };
        provides?: Array<{ id: string; version?: string }>;
        requires?: string[];
        contributes?: {
      dock?: PluginDockContribution[];
      settings?: PluginSettingsContribution[];
      composer?: PluginComposerContribution[];
      pages?: PluginPageContribution[];
      slots?: PluginSlotContribution[];
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
