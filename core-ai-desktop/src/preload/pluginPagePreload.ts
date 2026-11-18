// Fixed bridge for plugin pages. The page never receives ipcRenderer, Node or arbitrary engine RPC:
// every call goes through PluginPageHost's allowlist and capability check.
import { contextBridge, ipcRenderer } from 'electron';

const tokenPrefix = '--coreai-plugin-page-token=';
const tokenArgument = process.argv.find((argument) => argument.startsWith(tokenPrefix));
const token = tokenArgument?.slice(tokenPrefix.length) ?? '';

function call(method: string, params: Record<string, unknown> = {}): Promise<unknown> {
  return ipcRenderer.invoke('pluginPage:call', token, method, params);
}

contextBridge.exposeInMainWorld('coreaiPlugin', {
  context: () => call('context'),
  assets: {
    list: () => call('assets.list'),
    import: (input: { name: string; data: string }) => call('assets.import', input),
  },
  media: {
    models: () => call('media.models'),
    generate: (params: Record<string, unknown>) => call('media.generate', params),
    job: (id: string) => call('media.jobs.get', { id }),
    jobs: () => call('media.jobs.list'),
  },
  sessions: {
    list: (params: Record<string, unknown> = {}) => call('sessions.list', params),
    rename: (sessionId: string, title: string) => call('sessions.rename', { sessionId, title }),
  },
  schedules: {
    list: (params: Record<string, unknown> = {}) => call('schedules.list', params),
    get: (id: string) => call('schedules.get', { id }),
    update: (params: Record<string, unknown>) => call('schedules.update', params),
    delete: (id: string) => call('schedules.delete', { id }),
    trigger: (id: string) => call('schedules.trigger', { id }),
  },
  memory: {
    list: (params: Record<string, unknown> = {}) => call('memory.list', params),
    read: (path: string) => call('memory.read', { path }),
    write: (path: string, content: string, mode: 'append' | 'overwrite' = 'append') =>
      call('memory.write', { path, content, mode }),
  },
  workspace: {
    summary: () => call('workspace.summary'),
  },
  usage: {
    summary: (range = 'all') => call('usage.summary', { range }),
  },
  storage: {
    get: (key: string) => call('storage.get', { key }),
    set: (key: string, value: string) => call('storage.set', { key, value }),
    delete: (key: string) => call('storage.delete', { key }),
    keys: () => call('storage.keys'),
  },
  events: {
    subscribe: (types: string[]) => call('events.subscribe', { types }),
    on: (callback: (event: unknown) => void) => {
      ipcRenderer.on('pluginPage:event', (_event, payload) => callback(payload));
    },
  },
  notify: {
    send: (text: string) => call('notify.send', { text }),
  },
  services: {
    list: () => call('services.list'),
  },
});
