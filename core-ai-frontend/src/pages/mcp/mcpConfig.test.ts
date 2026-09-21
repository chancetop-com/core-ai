import { describe, expect, it } from 'vitest';
import {
  containsSensitiveConfigJson,
  isSensitiveConfigKey,
  mcpImportNaming,
  planMcpImport,
  suggestMcpServerName,
} from './mcpConfig';

function importError(raw: string): string | null {
  const plan = planMcpImport(raw);
  return plan.kind === 'invalid' ? plan.error : null;
}

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

describe('MCP JSON import planning', () => {
  it('accepts a standard command server', () => {
    expect(importError(JSON.stringify({
      mcpServers: {
        local: { command: 'npx', args: ['-y', '@scope/server'], env: { API_TOKEN: 'secret' } },
      },
    }))).toBeNull();
  });

  it('accepts a remote HTTP server', () => {
    expect(importError(JSON.stringify({
      mcpServers: {
        meta: { url: 'https://mcp.facebook.com', endpoint: '/ads', headers: { Authorization: 'Bearer secret' } },
      },
    }))).toBeNull();
  });

  it('plans a single server config copied from an existing server', () => {
    const plan = planMcpImport(JSON.stringify({
      command: 'uvx',
      args: ['mcp-atlassian'],
      env: { JIRA_API_TOKEN: 'secret' },
    }));

    expect(plan.kind).toBe('single');
    if (plan.kind !== 'single') return;
    expect(plan.suggestedName).toBe('mcp-atlassian');
    expect(plan.config.command).toBe('uvx');
  });

  it('takes the server name from the config when it declares one', () => {
    const plan = planMcpImport(JSON.stringify({ name: 'atlassian', url: 'https://example.com/mcp' }));

    expect(plan.kind).toBe('single');
    if (plan.kind !== 'single') return;
    expect(plan.suggestedName).toBe('atlassian');
  });

  it.each([
    ['', 'Paste an MCP configuration first.'],
    ['{nope', 'MCP configuration must be valid JSON.'],
    ['null', "MCP configuration must contain a non-empty 'mcpServers' object."],
    ['{"mcpServers":{}}', "MCP configuration must contain a non-empty 'mcpServers' object."],
    ['{}', "MCP configuration must define either 'command' or 'url'."],
    ['{"command":"uvx","url":"https://example.com/mcp"}', "MCP configuration must define only one of 'command' or 'url'."],
    ['{"url":"https://example.com/mcp","transport":"sandbox_hosted"}', "MCP configuration cannot use 'sandbox_hosted' transport with a URL."],
    ['{"mcpServers":{"broken":{"headers":{"Authorization":"Bearer secret"}}}}', "MCP server 'broken' must define either 'command' or 'url'."],
    ['{"mcpServers":{"both":{"command":"npx","url":"https://example.com"}}}', "MCP server 'both' must define only one of 'command' or 'url'."],
    ['{"mcpServers":{"remote":{"url":"https://example.com","transport":"sandbox_hosted"}}}', "MCP server 'remote' cannot use 'sandbox_hosted' transport with a URL."],
  ])('returns an actionable error without echoing input', (raw, expected) => {
    const error = importError(raw);
    expect(error).toBe(expected);
    expect(error).not.toContain('secret');
  });

  it.each([
    [{ command: 'uvx', args: ['mcp-atlassian'] }, 'mcp-atlassian'],
    [{ command: 'npx', args: ['-y', '@modelcontextprotocol/server-filesystem', '/tmp'] }, 'server-filesystem'],
    [{ command: 'python', args: 'server.py' }, 'server'],
    [{ url: 'https://mcp.facebook.com/ads' }, 'mcp.facebook.com'],
    [{ name: 'declared-name', command: 'uvx' }, 'declared-name'],
    [{ command: 'uvx' }, ''],
  ])('suggests a server name for %j', (config, expected) => {
    expect(suggestMcpServerName(config)).toBe(expected);
  });
});

describe('MCP JSON import naming', () => {
  it('names a single server config from the form', () => {
    const naming = mcpImportNaming(planMcpImport('{"command":"uvx","args":["mcp-atlassian"]}'));

    expect(naming).toEqual({ mode: 'single', suggestedName: 'mcp-atlassian' });
  });

  it('names a single wrapped server from its key', () => {
    const naming = mcpImportNaming(planMcpImport('{"mcpServers":{"meta-ads":{"url":"https://mcp.facebook.com"}}}'));

    expect(naming).toEqual({ mode: 'single', suggestedName: 'meta-ads' });
  });

  it('leaves several wrapped servers to their JSON keys', () => {
    const naming = mcpImportNaming(planMcpImport('{"mcpServers":{"a":{"command":"npx"},"b":{"command":"npx"}}}'));

    expect(naming).toEqual({ mode: 'multiple' });
  });

  it.each(['', '{nope', '{}'])('has no naming for unusable input %j', raw => {
    expect(mcpImportNaming(planMcpImport(raw))).toBeNull();
  });
});
