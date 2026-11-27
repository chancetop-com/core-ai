import test from 'node:test';
import assert from 'node:assert/strict';
import sanitizerModule from '../dist/test/mcpSanitizer.js';

const { McpLogRedactor, sanitizeUrlForDisplay, secretHint } = sanitizerModule;

test('T5: McpLogRedactor masks secrets >=4 chars, URL userinfo/query, Bearer tokens, and ANSI codes', () => {
  const redactor = new McpLogRedactor();
  const secret = 'SUPER_SECRET_VALUE_123';
  redactor.register([secret, '', 'abc', 'a']);

  const input = `\x1b[33mERR\x1b[0m https://user:pass@mcp.example.com:8443/v1/mcp?token=${secret} `
    + `WWW-Authenticate: Bearer ${secret} raw=${secret} abc keep`;
  const output = redactor.redact(input);

  assert.equal(output.includes(secret), false);
  assert.equal(output.includes('user:pass'), false);
  assert.equal(output.includes('?token='), false);
  assert.equal(output.includes('\x1b['), false);
  assert.ok(output.includes('https://mcp.example.com:8443/v1/mcp'));
  assert.ok(output.includes('Bearer ***'));
  assert.ok(output.includes('raw=***'));
  assert.ok(output.includes('abc keep'));
});

test('T5: sanitizeUrlForDisplay strips userinfo, query, and fragment', () => {
  assert.equal(
    sanitizeUrlForDisplay('https://alice:pw@example.com:9000/api/mcp?key=secret#frag'),
    'https://example.com:9000/api/mcp',
  );
  assert.equal(
    sanitizeUrlForDisplay('http://127.0.0.1:3000/mcp?token=abc'),
    'http://127.0.0.1:3000/mcp',
  );
});

test('T5: secretHint exposes only last 4 chars for >4 char secrets and masks short values', () => {
  assert.equal(secretHint('SUPER_SECRET_VALUE_123'), '••••_123');
  assert.equal(secretHint('1234'), '••••');
  assert.equal(secretHint('ab'), '••••');
  assert.equal(secretHint(''), '••••');
});
