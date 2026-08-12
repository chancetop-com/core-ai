import { describe, expect, it } from 'vitest';
import { containsSensitiveConfigJson, isSensitiveConfigKey, validateMcpImportJson } from './mcpConfig';

describe('MCP configuration safety', () => {
  it.each([
    'Authorization',
    'headers',
    'API_TOKEN',
    'clientSecret',
    'password',
    'private_key',
    'cookie',
    'env',
  ])('treats %s as sensitive', key => {
    expect(isSensitiveConfigKey(key)).toBe(true);
  });

  it.each(['url', 'endpoint', 'transport', 'command', 'connectTimeout'])(
    'does not hide non-sensitive key %s',
    key => {
      expect(isSensitiveConfigKey(key)).toBe(false);
    },
  );

  it('detects nested credentials in raw dynamic MCP JSON', () => {
    expect(containsSensitiveConfigJson('{"command":"npx","env":{"API_TOKEN":"secret"}}')).toBe(true);
    expect(containsSensitiveConfigJson('{"command":"npx","args":["-y","server"]}')).toBe(false);
  });
});

describe('MCP JSON import validation', () => {
  it('accepts a standard command server', () => {
    expect(validateMcpImportJson(JSON.stringify({
      mcpServers: {
        local: { command: 'npx', args: ['-y', '@scope/server'], env: { API_TOKEN: 'secret' } },
      },
    }))).toBeNull();
  });

  it('accepts a remote HTTP server', () => {
    expect(validateMcpImportJson(JSON.stringify({
      mcpServers: {
        meta: { url: 'https://mcp.facebook.com', endpoint: '/ads', headers: { Authorization: 'Bearer secret' } },
      },
    }))).toBeNull();
  });

  it.each([
    ['', 'Paste an MCP configuration first.'],
    ['{nope', 'MCP configuration must be valid JSON.'],
    ['null', "MCP configuration must contain a non-empty 'mcpServers' object."],
    ['{}', "MCP configuration must contain a non-empty 'mcpServers' object."],
    ['{"mcpServers":{"broken":{"headers":{"Authorization":"Bearer secret"}}}}', "MCP server 'broken' must define either 'command' or 'url'."],
    ['{"mcpServers":{"both":{"command":"npx","url":"https://example.com"}}}', "MCP server 'both' must define only one of 'command' or 'url'."],
    ['{"mcpServers":{"remote":{"url":"https://example.com","transport":"sandbox_hosted"}}}', "MCP server 'remote' cannot use 'sandbox_hosted' transport with a URL."],
  ])('returns an actionable error without echoing input', (raw, expected) => {
    const error = validateMcpImportJson(raw);
    expect(error).toBe(expected);
    expect(error).not.toContain('secret');
  });
});
