// Read-only import sources for MCP configuration. The desktop never watches or writes these files:
// each source is read once when the user asks for an import, then copied into the workspace store.
import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const SERVER_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

export interface McpImportServer {
  name: string;
  config: Record<string, unknown>;
}

export interface McpImportSource {
  id: string;
  label: string;
  file: string;
  kind: 'mcp-json' | 'agent-properties';
  servers: McpImportServer[];
  warnings: string[];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function readServers(text: string): { servers: McpImportServer[]; warnings: string[] } {
  const warnings: string[] = [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    return { servers: [], warnings: [`not valid JSON: ${error instanceof Error ? error.message : String(error)}`] };
  }
  if (!isRecord(parsed)) return { servers: [], warnings: ['expected a JSON object'] };
  const entries = isRecord(parsed.mcpServers) ? parsed.mcpServers : parsed;
  const servers: McpImportServer[] = [];
  for (const [name, config] of Object.entries(entries)) {
    if (name === 'mcpServers') continue;
    if (!isRecord(config)) {
      warnings.push(`${name}: skipped, not an object`);
      continue;
    }
    if (typeof config.command !== 'string' && typeof config.url !== 'string') {
      warnings.push(`${name}: skipped, needs a command or url`);
      continue;
    }
    if (!SERVER_NAME.test(name)) {
      warnings.push(`${name}: skipped, invalid server name`);
      continue;
    }
    servers.push({ name, config });
  }
  return { servers, warnings };
}

/** Reads `mcp.servers.json` from a Java properties file, following `\` continuations. */
function propertyValue(text: string, key: string): string | null {
  const prefix = new RegExp(`^\\s*${key.replace(/\./g, '\\.')}\\s*=`);
  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length; index++) {
    if (!prefix.test(lines[index])) continue;
    let value = lines[index].replace(prefix, '');
    while (value.endsWith('\\') && index + 1 < lines.length) {
      value = value.slice(0, -1) + lines[++index].trimStart();
    }
    return value.replace(/\\\\/g, '\\');
  }
  return null;
}

async function readText(file: string): Promise<string | null> {
  try {
    return await fs.readFile(file, 'utf8');
  } catch {
    return null;
  }
}

async function fileSource(file: string, label: string, kind: McpImportSource['kind'], id: string): Promise<McpImportSource | null> {
  const text = await readText(file);
  if (text === null) return null;
  const json = kind === 'agent-properties' ? propertyValue(text, 'mcp.servers.json') : text;
  if (json === null || !json.trim()) return null;
  const { servers, warnings } = readServers(json);
  return { id, label, file, kind, servers, warnings };
}

/** Import sources for one workspace, cheapest first. Missing files are simply absent. */
export async function previewImports(workspace: string): Promise<McpImportSource[]> {
  const sources: Array<McpImportSource | null> = [];
  const coreAi = path.join(workspace, '.core-ai');
  sources.push(await fileSource(path.join(coreAi, 'MCP.json'), 'Workspace .core-ai/MCP.json', 'mcp-json', 'workspace-mcp'));
  sources.push(await fileSource(path.join(coreAi, 'mcp.json'), 'Workspace .core-ai/mcp.json', 'mcp-json', 'workspace-mcp-lower'));
  sources.push(await fileSource(path.join(coreAi, 'agent.properties'), 'Workspace agent.properties', 'agent-properties', 'workspace-properties'));
  sources.push(await fileSource(path.join(os.homedir(), '.core-ai', 'agent.properties'), 'User agent.properties', 'agent-properties', 'user-properties'));
  return sources.filter((source): source is McpImportSource => source !== null && source.servers.length > 0);
}

/** Ad-hoc import from pasted text (Claude Desktop / Cursor `mcpServers` shape). */
export function parsePastedConfig(text: string): { servers: McpImportServer[]; warnings: string[] } {
  return readServers(text);
}
