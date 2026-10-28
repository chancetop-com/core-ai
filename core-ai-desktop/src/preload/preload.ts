// contextBridge surface: the renderer gets an explicit, minimal API — no Node, no ipcRenderer.
import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('coreai', {
  call: (workspace: string, method: string, params: Record<string, unknown>) =>
    ipcRenderer.invoke('engine:call', workspace, method, params),
  chooseWorkspace: () => ipcRenderer.invoke('workspace:choose'),
  defaultWorkspace: () => ipcRenderer.invoke('workspace:default'),
  workspaceSummaries: () => ipcRenderer.invoke('workspaces:summaries'),
  authStatus: () => ipcRenderer.invoke('auth:status'),
  authServers: () => ipcRenderer.invoke('auth:servers'),
  authSwitch: (serverUrl: string) => ipcRenderer.invoke('auth:switch', serverUrl),
  authLogin: (serverUrl: string, insecure: boolean) => ipcRenderer.invoke('auth:login', serverUrl, insecure),
  authLoginWithKey: (serverUrl: string, apiKey: string, insecure: boolean) => ipcRenderer.invoke('auth:loginWithKey', serverUrl, apiKey, insecure),
  authSetInsecure: (serverUrl: string, insecure: boolean) => ipcRenderer.invoke('auth:setInsecure', serverUrl, insecure),
  authLogout: () => ipcRenderer.invoke('auth:logout'),
  restartEngine: (workspace: string) => ipcRenderer.invoke('engine:restart', workspace),
  engineCapabilities: (workspace: string) => ipcRenderer.invoke('engine:capabilities', workspace),
  pickAttachments: () => ipcRenderer.invoke('attachments:pick'),
  stageAttachments: (sessionId: string, paths: string[]) => ipcRenderer.invoke('attachments:stage', sessionId, paths),
  stageAttachmentBuffers: (sessionId: string, entries: Array<{ name: string; data: ArrayBuffer }>) =>
    ipcRenderer.invoke('attachments:stageBuffers', sessionId, entries),
  removeAttachment: (stagedPath: string) => ipcRenderer.invoke('attachments:remove', stagedPath),
  browserStart: () => ipcRenderer.invoke('browser:start'),
  browserStop: () => ipcRenderer.invoke('browser:stop'),
  browserStatus: () => ipcRenderer.invoke('browser:status'),
  browserSetBounds: (bounds: { x: number; y: number; width: number; height: number } | null) =>
    ipcRenderer.invoke('browser:setBounds', bounds),
  browserNavigate: (url: string) => ipcRenderer.invoke('browser:navigate', url),
  browserBack: () => ipcRenderer.invoke('browser:back'),
  browserForward: () => ipcRenderer.invoke('browser:forward'),
  browserReload: () => ipcRenderer.invoke('browser:reload'),
  browserStopLoading: () => ipcRenderer.invoke('browser:stopLoading'),
  browserNewTab: (url?: string) => ipcRenderer.invoke('browser:newTab', url),
  browserCloseTab: (id: string) => ipcRenderer.invoke('browser:closeTab', id),
  browserSelectTab: (id: string) => ipcRenderer.invoke('browser:selectTab', id),
  computerStatus: () => ipcRenderer.invoke('computer:status'),
  computerInstallCli: () => ipcRenderer.invoke('computer:installCli'),
  computerEnable: () => ipcRenderer.invoke('computer:enable'),
  computerDisable: () => ipcRenderer.invoke('computer:disable'),
  computerRemoveService: () => ipcRenderer.invoke('computer:removeService'),
  builtinSkills: () => ipcRenderer.invoke('builtinSkills:list'),
  builtinInstall: (name: string, force?: boolean) => ipcRenderer.invoke('builtinSkills:install', name, force),
  builtinRemove: (name: string) => ipcRenderer.invoke('builtinSkills:remove', name),
  browserToolStatus: () => ipcRenderer.invoke('browser:toolStatus'),
  browserInstallTool: () => ipcRenderer.invoke('browser:installTool'),
  channelsStatus: () => ipcRenderer.invoke('channels:status'),
  channelsSetEnabled: (enabled: boolean) => ipcRenderer.invoke('channels:setEnabled', enabled),
  channelsInstallTool: () => ipcRenderer.invoke('channels:installTool'),
  channelsBridgeStatus: () => ipcRenderer.invoke('channels:bridgeStatus'),
  channelsBridgeSetEnabled: (enabled: boolean) => ipcRenderer.invoke('channels:bridgeSetEnabled', enabled),
  channelsAdminOverview: () => ipcRenderer.invoke('channels:adminOverview'),
  channelsAdminOp: (channelId: string, op: 'start' | 'stop' | 'restart', accountId?: string) =>
    ipcRenderer.invoke('channels:adminOp', channelId, op, accountId),
  channelsLoginStart: (channelId: string, accountId?: string) => ipcRenderer.invoke('channels:loginStart', channelId, accountId),
  channelsLoginStatus: (channelId: string, loginId: string) => ipcRenderer.invoke('channels:loginStatus', channelId, loginId),
  channelsLoginCancel: (channelId: string, loginId: string) => ipcRenderer.invoke('channels:loginCancel', channelId, loginId),
  updateStatus: () => ipcRenderer.invoke('update:status'),
  updateCheck: () => ipcRenderer.invoke('update:check'),
  updateDownload: () => ipcRenderer.invoke('update:download'),
  updateInstall: () => ipcRenderer.invoke('update:install'),
  onUpdateState: (callback: (state: unknown) => void) => {
    const listener = (_event: unknown, payload: unknown) => callback(payload);
    ipcRenderer.on('update:state', listener);
    return () => {
      ipcRenderer.removeListener('update:state', listener);
    };
  },
  onBrowserState: (callback: (status: unknown) => void) => {
    const listener = (_event: unknown, payload: unknown) => callback(payload);
    ipcRenderer.on('browser:state', listener);
    return () => {
      ipcRenderer.removeListener('browser:state', listener);
    };
  },
  info: () => ipcRenderer.invoke('app:info'),
  getSettings: () => ipcRenderer.invoke('settings:get'),
  setSettings: (patch: Record<string, unknown>) => ipcRenderer.invoke('settings:set', patch),
  openPath: (workspace: string, relativePath: string) => ipcRenderer.invoke('shell:openPath', workspace, relativePath),
  revealPath: (workspace: string, relativePath: string) => ipcRenderer.invoke('shell:revealPath', workspace, relativePath),
  copyPath: (workspace: string, relativePath: string) => ipcRenderer.invoke('shell:copyPath', workspace, relativePath),
  openExternal: (url: string) => ipcRenderer.invoke('shell:openExternal', url),
  artifactBase: (workspace: string) => ipcRenderer.invoke('artifacts:base', workspace),
  existingArtifacts: (workspace: string, relativePaths: string[]) => ipcRenderer.invoke('artifacts:existing', workspace, relativePaths),
  onEvent: (callback: (payload: unknown) => void) => {
    const listener = (_event: unknown, payload: unknown) => callback(payload);
    ipcRenderer.on('engine:event', listener);
    return () => {
      ipcRenderer.removeListener('engine:event', listener);
    };
  },
  onLog: (callback: (payload: unknown) => void) => {
    const listener = (_event: unknown, payload: unknown) => callback(payload);
    ipcRenderer.on('engine:log', listener);
    return () => {
      ipcRenderer.removeListener('engine:log', listener);
    };
  },
  onChannelState: (callback: (state: unknown) => void) => {
    const listener = (_event: unknown, payload: unknown) => callback(payload);
    ipcRenderer.on('channels:state', listener);
    return () => {
      ipcRenderer.removeListener('channels:state', listener);
    };
  },
  onChannelSession: (callback: (event: unknown) => void) => {
    const listener = (_event: unknown, payload: unknown) => callback(payload);
    ipcRenderer.on('channels:session', listener);
    return () => {
      ipcRenderer.removeListener('channels:session', listener);
    };
  },
});
