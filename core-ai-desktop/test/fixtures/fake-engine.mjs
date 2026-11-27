// Minimal app-server stand-in for RpcClient and EngineSupervisor tests: line-delimited JSON-RPC over stdio.
import readline from 'node:readline';

const hasDesktopMcpFlag = process.argv.includes('--desktop-mcp');
if (process.env.FAKE_ENGINE_UNKNOWN_OPTION === '1' && hasDesktopMcpFlag) {
  process.stderr.write("Unknown option: '--desktop-mcp'\nUsage: core-ai-cli app-server [--workspace=<path>]\n");
  process.exit(2);
}

const rl = readline.createInterface({ input: process.stdin });
const send = (frame) => process.stdout.write(`${JSON.stringify(frame)}\n`);
let mcpServers = [];
let mcpSecretsLoaded = false;
let mcpFinalized = false;

rl.on('line', (line) => {
  let frame;
  try {
    frame = JSON.parse(line);
  } catch {
    send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } });
    return;
  }
  const { id, method, params } = frame;
  switch (method) {
    case 'initialize': {
      if (process.env.FAKE_ENGINE_BUSY === '1') {
        process.stderr.write('another core-ai engine is already running for this workspace: engine (pid 123)\n');
        setTimeout(() => process.exit(2), 20);
        return;
      }
      const capabilities = ['scheduled_tasks'];
      if (process.env.FAKE_ENGINE_DESKTOP_MCP === '1' && hasDesktopMcpFlag) {
        capabilities.push('mcp_management_v1');
      }
      const trustState = {
        state: process.env.FAKE_ENGINE_RESTRICTED === '1' ? 'restricted' : 'trusted',
        key: hasDesktopMcpFlag ? 'C:\\ws#desktop-mcp' : 'C:\\ws',
        workspaceKey: 'C:\\ws',
        fingerprint: 'abc123',
        hasExecutableInputs: process.env.FAKE_ENGINE_RESTRICTED === '1' || process.env.FAKE_ENGINE_LEGACY_MIGRATION === '1',
        reason: process.env.FAKE_ENGINE_RESTRICTED === '1' ? 'trust_revoked' : null,
        engineRestricted: process.env.FAKE_ENGINE_RESTRICTED === '1',
        migratedFromLegacy: process.env.FAKE_ENGINE_LEGACY_MIGRATION === '1',
      };
      if (process.env.FAKE_ENGINE_LEGACY_MIGRATION === '1') {
        trustState.reason = 'migrated_from_cli_record';
        trustState.migration = {
          legacyKey: 'C:\\ws',
          legacyFingerprint: 'legacy_fp_111',
          desktopKey: 'C:\\ws#desktop-mcp',
          desktopFingerprint: 'desktop_fp_222',
        };
      }
      const reply = () => {
        send({ jsonrpc: '2.0', id, result: {
          protocolVersion: '1.0',
          engineVersion: 'fake',
          capabilities,
          trustState,
        } });
        send({ jsonrpc: '2.0', method: 'engine/ready', params: {} });
      };
      const delayMs = Number(process.env.FAKE_ENGINE_INIT_DELAY_MS ?? '0');
      if (delayMs > 0) setTimeout(reply, delayMs);
      else reply();
      return;
    }
    case 'mcp/config':
      if (process.env.FAKE_ENGINE_EXIT_ON_MCP_CONFIG === '1') {
        process.stderr.write('fatal crash during mcp/config\n');
        process.exit(1);
        return;
      }
      if (process.env.FAKE_ENGINE_FAIL_MCP_CONFIG === '1') {
        send({ jsonrpc: '2.0', id, error: { code: -32603, message: 'MCP config rejected', data: { code: 'MCP_CONFIG_INVALID' } } });
        return;
      }
      if (process.env.FAKE_ENGINE_HANG_MCP_CONFIG === '1') {
        return;
      }
      mcpServers = Array.isArray(params?.servers) ? params.servers : [];
      send({ jsonrpc: '2.0', id, result: { ok: true, servers: mcpServers.length } });
      return;
    case 'mcp/secrets':
      mcpSecretsLoaded = true;
      send({ jsonrpc: '2.0', id, result: { ok: true, loaded: Object.keys(params?.secrets ?? {}).length } });
      return;
    case 'mcp/finalize':
      mcpFinalized = true;
      send({ jsonrpc: '2.0', id, result: { ok: true, finalized: true } });
      return;
    case 'mcp/status':
      send({ jsonrpc: '2.0', id, result: {
        trusted: process.env.FAKE_ENGINE_RESTRICTED !== '1',
        restricted: process.env.FAKE_ENGINE_RESTRICTED === '1',
        ready: mcpFinalized,
        configLoaded: true,
        secretsLoaded: mcpSecretsLoaded,
        finalized: mcpFinalized,
        servers: mcpServers.map((s) => ({ name: s.name, state: 'CONNECTED', toolCount: 1 })),
      } });
      return;
    case 'mcp/probe/start': {
      const probeId = 'probe-1';
      send({ jsonrpc: '2.0', id, result: { probeId } });
      setTimeout(() => {
        send({ jsonrpc: '2.0', method: 'mcp/probe/result', params: {
          probeId,
          ok: true,
          server: params?.name,
          tools: [{ name: 'echo', description: 'echo' }],
          elapsedMs: 5,
        } });
      }, 10);
      return;
    }
    case 'shutdown':
      send({ jsonrpc: '2.0', id, result: {} });
      process.exit(0);
      return;
    case 'echo':
      send({ jsonrpc: '2.0', id, result: params ?? {} });
      return;
    case 'engine/quiesce':
      send({ jsonrpc: '2.0', id, result: { acquired: true, active: 0 } });
      return;
    case 'engine/resumeIntake':
      send({ jsonrpc: '2.0', id, result: { resumed: true } });
      return;
    case 'exit':
      process.exit(0);
      return;
    case 'fail':
      send({ jsonrpc: '2.0', id, error: { code: -32602, message: 'bad param', data: { code: 'INVALID_PARAMS' } } });
      return;
    case 'emit':
      send({ jsonrpc: '2.0', method: 'session/event', params: { sessionId: 's1', event: { type: 'text_chunk', chunk: 'hi' } } });
      send({ jsonrpc: '2.0', id, result: {} });
      return;
    case 'slow':
      setTimeout(() => send({ jsonrpc: '2.0', id, result: { slept: true } }), 300);
      return;
    case 'never':
      return; // deliberately no response, exercises the client-side timeout
    default:
      send({ jsonrpc: '2.0', id, error: { code: -32601, message: `method not found: ${method}` } });
  }
});
