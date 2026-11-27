// Bundles the desktop app with esbuild: main + preload (Electron, cjs), renderer (browser, iife)
// and the engine client for the node:test suite. No watch mode: `npm start` rebuilds first.
import { build } from 'esbuild';
import { copyFileSync, mkdirSync, readFileSync, rmSync } from 'node:fs';

// VERSION is the release's single source of truth (packaging reads it too, see electron-builder.cjs).
const version = readFileSync('VERSION', 'utf8').trim();

rmSync('dist', { recursive: true, force: true });
mkdirSync('dist/renderer', { recursive: true });
mkdirSync('dist/test', { recursive: true });

const shared = {
  bundle: true,
  sourcemap: true,
  logLevel: 'warning',
  define: { __APP_VERSION__: JSON.stringify(version) },
};

const targets = [
  { entryPoints: ['src/main/main.ts'], outfile: 'dist/main.js', platform: 'node', target: 'node20', format: 'cjs', external: ['electron'] },
  { entryPoints: ['src/preload/preload.ts'], outfile: 'dist/preload.js', platform: 'node', target: 'node20', format: 'cjs', external: ['electron'] },
  { entryPoints: ['src/preload/pluginPagePreload.ts'], outfile: 'dist/pluginPagePreload.js', platform: 'node', target: 'node20', format: 'cjs', external: ['electron'] },
  { entryPoints: ['src/main/engine/RpcClient.ts'], outfile: 'dist/test/rpcClient.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/engine/EngineSupervisor.ts'], outfile: 'dist/test/engineSupervisor.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/mcp/mcpService.ts'], outfile: 'dist/test/mcpService.js', platform: 'node', target: 'node20', format: 'cjs', external: ['electron'] },
  { entryPoints: ['src/main/mcp/mcpSanitizer.ts'], outfile: 'dist/test/mcpSanitizer.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/schedule/ScheduleWaker.ts'], outfile: 'dist/test/scheduleWaker.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/browser/CdpBridge.ts'], outfile: 'dist/test/cdpBridge.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/browser/BrowserManager.ts'], outfile: 'dist/test/browserManager.js', platform: 'node', target: 'node20', format: 'cjs', external: ['electron'] },
  { entryPoints: ['src/main/computer/managedSkill.ts'], outfile: 'dist/test/managedSkill.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/skills/BuiltinSkills.ts'], outfile: 'dist/test/builtinSkills.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/skills/RuntimeTools.ts'], outfile: 'dist/test/runtimeTools.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/channels/ChannelBridge.ts'], outfile: 'dist/test/channelBridge.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/channels/OriginStore.ts'], outfile: 'dist/test/originStore.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/observations/WatchStore.ts'], outfile: 'dist/test/watchStore.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/observations/SubscriptionService.ts'], outfile: 'dist/test/subscriptionService.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/channels/OcgClient.ts'], outfile: 'dist/test/ocgClient.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/channels/OcgCli.ts'], outfile: 'dist/test/ocgCli.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/notifications/CompletionNotifier.ts'], outfile: 'dist/test/completionNotifier.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/providers/ProviderStore.ts'], outfile: 'dist/test/providerStore.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/providers/ProviderProbe.ts'], outfile: 'dist/test/providerProbe.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/notifications.ts'], outfile: 'dist/test/notifications.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/channels/OcgRouting.ts'], outfile: 'dist/test/ocgRouting.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/channels/OcgConfig.ts'], outfile: 'dist/test/ocgConfig.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/channels/Bindings.ts'], outfile: 'dist/test/bindings.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/computer/CuaManager.ts'], outfile: 'dist/test/cuaManager.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/attachments/AttachmentStager.ts'], outfile: 'dist/test/attachmentStager.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/artifacts/artifactPath.ts'], outfile: 'dist/test/artifactPath.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/auth/AuthStore.ts'], outfile: 'dist/test/authStore.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/remote/RemoteConversationClient.ts'], outfile: 'dist/test/remoteConversation.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/home/HomeDataClient.ts'], outfile: 'dist/test/homeData.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/update/UpdateService.ts'], outfile: 'dist/test/updateService.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/update/CliUpdate.ts'], outfile: 'dist/test/cliUpdate.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/composerKeyboard.ts'], outfile: 'dist/test/composerKeyboard.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/voice.ts'], outfile: 'dist/test/voice.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/sessionSurfaces.ts'], outfile: 'dist/test/sessionSurfaces.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/localPaths.ts'], outfile: 'dist/test/localPaths.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/artifactList.ts'], outfile: 'dist/test/artifactList.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/richCard.ts'], outfile: 'dist/test/richCard.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/shared/pluginManifest.ts'], outfile: 'dist/test/pluginManifest.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/shared/protocolSchemes.ts'], outfile: 'dist/test/protocolSchemes.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/plugins/zip.ts'], outfile: 'dist/test/pluginZip.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/plugins/pluginStore.ts'], outfile: 'dist/test/pluginStore.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/plugins/PluginHost.ts'], outfile: 'dist/test/pluginHost.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/plugins/PluginPageHost.ts'], outfile: 'dist/test/pluginPageHost.js', platform: 'node', target: 'node20', format: 'cjs', external: ['electron'] },
  { entryPoints: ['src/main/plugins/workspaceTrust.ts'], outfile: 'dist/test/workspaceTrust.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/plugins/pluginPagePaths.ts'], outfile: 'dist/test/pluginPagePaths.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/plugins/pluginTerminal.ts'], outfile: 'dist/test/pluginTerminal.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/pluginView.ts'], outfile: 'dist/test/pluginView.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/transcript/reducer.ts'], outfile: 'dist/test/transcript.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/chat/transcriptView.ts'], outfile: 'dist/test/transcriptView.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/transcript/classify.ts'], outfile: 'dist/test/transcriptClassify.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/transcript/time.ts'], outfile: 'dist/test/transcriptTime.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/remoteProtocol.ts'], outfile: 'dist/test/remoteProtocol.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/app.ts'], outfile: 'dist/renderer/app.js', platform: 'browser', target: 'chrome120', format: 'iife' },
];

for (const target of targets) {
  await build({ ...shared, ...target });
}

copyFileSync('src/renderer/index.html', 'dist/renderer/index.html');
copyFileSync('src/renderer/styles.css', 'dist/renderer/styles.css');
copyFileSync('src/assets/logo.svg', 'dist/renderer/logo.svg');
copyFileSync('src/assets/logo-light.svg', 'dist/renderer/logo-light.svg');
copyFileSync('src/assets/logo.png', 'dist/logo.png');
