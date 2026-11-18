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
});
