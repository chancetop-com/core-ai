export type PluginCapability =
  | 'ui.dock'
  | 'ui.settings'
  | 'ui.composer'
  | 'ui.pages'
  | 'ui.slots'
  | 'ui.renderers'
  | 'artifacts.read'
  | 'engine.skills'
  | 'prompt.sections'
  | 'browser.control'
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

export interface PluginRendererContribution {
  id: string;
  kind: 'artifact';
  title: string;
  pageId: string;
  extensions: string[];
}

export interface PluginPromptSectionContribution {
  id: string;
  title: string;
  content: string;
}

export type PluginToolOperation = 'artifacts.list' | 'memory.list';

export interface PluginToolContribution {
  id: string;
  name: string;
  description: string;
  operation: PluginToolOperation;
  limit?: number;
}

export type PluginTerminalOperation = 'git.status' | 'git.log' | 'git.diff' | 'node.version';

export interface PluginTerminalContribution {
  id: string;
  name: string;
  description: string;
  operation: PluginTerminalOperation;
}

export interface PluginGuardContribution {
  id: string;
  name: string;
  description: string;
  toolNames: string[];
  action: 'deny';
}

export interface PluginSandboxProviderContribution {
  id: string;
  name: string;
  description: string;
  isolation: 'process' | 'container' | 'os';
}

export interface PluginAgentActionContribution {
  id: string;
  name: string;
  description: string;
  kind: 'start' | 'wait' | 'cancel' | 'list';
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
      renderers?: PluginRendererContribution[];
      promptSections?: PluginPromptSectionContribution[];
      tools?: PluginToolContribution[];
      terminal?: PluginTerminalContribution[];
      guards?: PluginGuardContribution[];
      sandboxProviders?: PluginSandboxProviderContribution[];
      agentActions?: PluginAgentActionContribution[];
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
