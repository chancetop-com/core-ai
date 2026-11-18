/**
 * Types for the fixed bridge exposed to a plugin page as `window.coreaiPlugin`.
 * The page runs in an isolated WebContentsView; this is the only host surface it gets.
 */

export interface PluginPageContext {
  pluginId: string;
  pluginName: string;
  pageId: string;
  scope: 'workspace' | 'app';
  workspace: string;
  capabilities: string[];
  theme: 'light' | 'dark';
}

export interface PluginAsset {
  name: string;
  kind: 'image' | 'video';
  size: number;
  createdAt: string;
  url: string;
  path?: string;
}

export interface PluginImportedAsset {
  name: string;
  kind: 'image';
  size: number;
  url: string;
  path: string;
}

export interface PluginMediaModel {
  modelId: string;
  displayName?: string;
  providerName?: string;
}

export interface PluginMediaModels {
  imageModels: PluginMediaModel[];
  imageEditModels?: PluginMediaModel[];
  videoModels: PluginMediaModel[];
  signedIn?: boolean;
  imageSource?: string;
  videoSource?: string;
  effectiveImageModel?: string;
  effectiveVideoModel?: string;
  [key: string]: unknown;
}

export interface PluginMediaJob {
  id: string;
  kind: 'video';
  status: string;
  model?: string;
  prompt?: string;
  createdAt?: number;
  progress?: number;
  error?: string;
  path?: string;
}

export interface PluginGenerateParams {
  kind: 'image' | 'video';
  prompt: string;
  model?: string;
  size?: string;
  seconds?: number;
  n?: number;
  quality?: string;
  outputFormat?: string;
  outputCompression?: number;
  background?: string;
  providerExtra?: string;
  previousInteractionId?: string;
  previousVideoId?: string;
  inputImages?: Array<{ path: string; name?: string; role?: string }>;
  inputReferences?: Array<{ path: string; name?: string; role?: string }>;
}

export interface PluginGenerateResult {
  kind: 'image' | 'video';
  files?: Array<{ path: string; name: string; contentType: string; size: number }>;
  job?: PluginMediaJob;
  mediaId?: string;
  interactionId?: string;
  notes?: string[];
}

export interface PluginSession {
  sessionId: string;
  title: string;
  state: string;
  status: 'running' | 'idle' | string;
  updatedAt: string;
}

export interface PluginMemoryFile {
  path: string;
  size: number;
  modifiedAt: string;
}

export interface PluginWorkspaceSummary {
  path: string;
  sessionCount: number | null;
  artifactCount: number | null;
  memoryFileCount: number | null;
}

export interface PluginEvent {
  type: string;
  version: number;
  occurredAt: string;
  scope: { workspace: string; sessionId: string };
  data: Record<string, unknown>;
}

export interface CoreAiPluginBridge {
  context(): Promise<PluginPageContext>;
  assets: {
    list(): Promise<{ files: PluginAsset[] }>;
    import(input: { name: string; data: string }): Promise<{ file: PluginImportedAsset }>;
  };
  media: {
    models(): Promise<PluginMediaModels>;
    generate(params: PluginGenerateParams): Promise<PluginGenerateResult>;
    job(id: string): Promise<PluginMediaJob | { job: PluginMediaJob }>;
    jobs(): Promise<{ jobs: PluginMediaJob[] }>;
  };
  sessions: {
    list(params?: { limit?: number; cursor?: number }): Promise<{ sessions: PluginSession[]; nextCursor?: string }>;
  };
  memory: {
    list(params?: { limit?: number; cursor?: number }): Promise<{ files: PluginMemoryFile[]; nextCursor?: string }>;
    read(path: string): Promise<{ path: string; content: string }>;
  };
  workspace: {
    summary(): Promise<PluginWorkspaceSummary>;
  };
  storage: {
    get(key: string): Promise<{ value: string | null }>;
    set(key: string, value: string): Promise<{ ok: true }>;
    delete(key: string): Promise<{ deleted: boolean }>;
    keys(): Promise<{ keys: string[] }>;
  };
  events: {
    subscribe(types: string[]): Promise<{ types: string[] }>;
    on(callback: (event: PluginEvent) => void): void;
  };
}

declare global {
  interface Window {
    coreaiPlugin: CoreAiPluginBridge;
  }
}
