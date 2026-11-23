// Minimal app-server stand-in for RpcClient tests: line-delimited JSON-RPC over stdio.
import readline from 'node:readline';

const rl = readline.createInterface({ input: process.stdin });
const send = (frame) => process.stdout.write(`${JSON.stringify(frame)}\n`);

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
    case 'initialize':
      if (process.env.FAKE_ENGINE_BUSY === '1') {
        process.stderr.write('another core-ai engine is already running for this workspace: engine (pid 123)\n');
        setTimeout(() => process.exit(2), 20);
        return;
      }
      send({ jsonrpc: '2.0', id, result: {
        protocolVersion: '1.0',
        engineVersion: 'fake',
        capabilities: ['scheduled_tasks'],
        trustState: { state: 'trusted', key: 'C:\\ws', fingerprint: 'abc123', hasExecutableInputs: false, reason: null, engineRestricted: false },
      } });
      send({ jsonrpc: '2.0', method: 'engine/ready', params: {} });
      return;
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
