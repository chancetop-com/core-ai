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
  providerList: () => ipcRenderer.invoke('providers:list'),
  providerSave: (payload: Record<string, unknown>) => ipcRenderer.invoke('providers:save', payload),
  providerDelete: (id: string) => ipcRenderer.invoke('providers:delete', id),
  providerSetDefault: (connection: string) => ipcRenderer.invoke('providers:setDefault', connection),
  providerTest: (payload: Record<string, unknown>) => ipcRenderer.invoke('providers:test', payload),
  providerDiscover: (payload: Record<string, unknown>) => ipcRenderer.invoke('providers:discover', payload),
  providerReset: () => ipcRenderer.invoke('providers:reset'),
  onEngineReloaded: (callback: (payload: { workspace?: string }) => void) => {
    const listener = (_event: unknown, payload: { workspace?: string }) => callback(payload);
    ipcRenderer.on('engine:reloaded', listener);
    return () => ipcRenderer.removeListener('engine:reloaded', listener);
  },
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
  computerUpdateCli: () => ipcRenderer.invoke('computer:updateCli'),
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
  channelsRouteToDesktop: (channelId: string) => ipcRenderer.invoke('channels:routeToDesktop', channelId),
  channelsLoginStart: (channelId: string, accountId?: string) => ipcRenderer.invoke('channels:loginStart', channelId, accountId),
  channelsLoginStatus: (channelId: string, loginId: string) => ipcRenderer.invoke('channels:loginStatus', channelId, loginId),
  channelsLoginCancel: (channelId: string, loginId: string) => ipcRenderer.invoke('channels:loginCancel', channelId, loginId),
  notificationsTargets: () => ipcRenderer.invoke('notifications:targets'),
  notificationsSessionGet: (workspace: string, sessionId: string) => ipcRenderer.invoke('notifications:sessionGet', workspace, sessionId),
  notificationsSessionSet: (workspace: string, sessionId: string, enabled: boolean) =>
    ipcRenderer.invoke('notifications:sessionSet', workspace, sessionId, enabled),
  notificationsTest: () => ipcRenderer.invoke('notifications:test'),
  notificationsVerify: (workspace?: string, sessionId?: string) =>
    ipcRenderer.invoke('notifications:verify', workspace, sessionId),
  onNotificationDelivery: (callback: (state: unknown) => void) => {
    const listener = (_event: unknown, payload: unknown) => callback(payload);
    ipcRenderer.on('notifications:delivery', listener);
    return () => {
      ipcRenderer.removeListener('notifications:delivery', listener);
    };
  },
  updateStatus: () => ipcRenderer.invoke('update:status'),
  updateCheck: () => ipcRenderer.invoke('update:check'),
  updateDownload: () => ipcRenderer.invoke('update:download'),
  updateInstall: () => ipcRenderer.invoke('update:install'),
  pluginsList: () => ipcRenderer.invoke('plugins:list'),
  pluginsContributions: () => ipcRenderer.invoke('plugins:contributions'),
  pluginsInstallFolder: () => ipcRenderer.invoke('plugins:installFolder'),
  pluginsInstallZip: () => ipcRenderer.invoke('plugins:installZip'),
  pluginsEnable: (pluginId: string) => ipcRenderer.invoke('plugins:enable', pluginId),
  pluginsDisable: (pluginId: string) => ipcRenderer.invoke('plugins:disable', pluginId),
  pluginsGrant: (pluginId: string, capabilities: string[]) => ipcRenderer.invoke('plugins:grant', pluginId, capabilities),
  pluginsRevoke: (pluginId: string) => ipcRenderer.invoke('plugins:revoke', pluginId),
  pluginsRemove: (pluginId: string) => ipcRenderer.invoke('plugins:remove', pluginId),
  pluginsSetSetting: (pluginId: string, key: string, value: string | number | boolean) =>
    ipcRenderer.invoke('plugins:setSetting', pluginId, key, value),
  onPluginsChanged: (callback: () => void) => {
    const listener = () => callback();
    ipcRenderer.on('plugins:changed', listener);
    return () => {
      ipcRenderer.removeListener('plugins:changed', listener);
    };
  },
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
  remoteServers: () => ipcRenderer.invoke('remote:servers'),
  remoteCapabilities: (serverUrl: string) => ipcRenderer.invoke('remote:capabilities', serverUrl),
  remoteAgents: (serverUrl: string, query: string, limit?: number) => ipcRenderer.invoke('remote:agents', serverUrl, query, limit),
  remoteSessions: (serverUrl: string, offset?: number, limit?: number) => ipcRenderer.invoke('remote:sessions', serverUrl, offset, limit),
  remoteCreate: (serverUrl: string, agentId: string) => ipcRenderer.invoke('remote:create', serverUrl, agentId),
  remoteSnapshot: (serverUrl: string, sessionId: string) => ipcRenderer.invoke('remote:snapshot', serverUrl, sessionId),
  remoteHistory: (serverUrl: string, sessionId: string, beforeSeq?: number | null, limit?: number) =>
    ipcRenderer.invoke('remote:history', serverUrl, sessionId, beforeSeq, limit),
  remoteSend: (serverUrl: string, sessionId: string, message: string) => ipcRenderer.invoke('remote:send', serverUrl, sessionId, message),
  remoteCancel: (serverUrl: string, sessionId: string) => ipcRenderer.invoke('remote:cancel', serverUrl, sessionId),
  remoteRename: (serverUrl: string, sessionId: string, title: string) => ipcRenderer.invoke('remote:rename', serverUrl, sessionId, title),
  remoteSubscribe: (serverUrl: string, sessionId: string) => ipcRenderer.invoke('remote:subscribe', serverUrl, sessionId),
  remoteUnsubscribe: (serverUrl: string, sessionId: string) => ipcRenderer.invoke('remote:unsubscribe', serverUrl, sessionId),
  onRemotePush: (callback: (payload: unknown) => void) => {
    const listener = (_event: unknown, payload: unknown) => callback(payload);
    ipcRenderer.on('remote:push', listener);
    return () => {
      ipcRenderer.removeListener('remote:push', listener);
    };
  },
  getSettings: () => ipcRenderer.invoke('settings:get'),
  setSettings: (patch: Record<string, unknown>) => ipcRenderer.invoke('settings:set', patch),
  openPath: (workspace: string, relativePath: string) => ipcRenderer.invoke('shell:openPath', workspace, relativePath),
  revealPath: (workspace: string, relativePath: string) => ipcRenderer.invoke('shell:revealPath', workspace, relativePath),
  copyPath: (workspace: string, relativePath: string) => ipcRenderer.invoke('shell:copyPath', workspace, relativePath),
  openExternal: (url: string) => ipcRenderer.invoke('shell:openExternal', url),
  artifactBase: (workspace: string) => ipcRenderer.invoke('artifacts:base', workspace),
  existingArtifacts: (workspace: string, relativePaths: string[]) => ipcRenderer.invoke('artifacts:existing', workspace, relativePaths),
  transcriptOpenBlob: (sessionId: string, path: string) => ipcRenderer.invoke('transcript:openBlob', sessionId, path),
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
