import type { SlashCommand, CommandContext } from '../types.js';
import { theme } from '../utils/theme.js';
import { copyToClipboard } from '../utils/clipboard.js';

export const commands: SlashCommand[] = [
  {
    name: 'help', aliases: ['?', ''],
    description: 'Show available commands',
    action: (ctx) => {
      const lines = commands
        .filter(c => !c.hidden)
        .map(c => `  ${theme.cmdName(`/${c.name}`.padEnd(16))} ${theme.cmdDesc(c.description)}`)
        .join('\n');
      ctx.actions.addHistoryItem({ id: `cmd-${Date.now()}`, type: 'help', content: lines });
    },
  },
  {
    name: 'model',
    description: 'Show or switch model',
    action: (ctx, args) => {
      if (args) {
        ctx.actions.addHistoryItem({
          id: `cmd-${Date.now()}`, type: 'info',
          content: theme.muted(`Model switching via A2A not yet supported. Current: ${ctx.state.modelName}`),
        });
      } else {
        ctx.actions.setActiveDialog('model');
      }
    },
  },
  {
    name: 'stats',
    description: 'Show session statistics',
    action: (ctx) => {
      const { modelName, turnCount, totalTokens, inputTokens, outputTokens } = ctx.state;
      const lines = [
        `  Model:    ${modelName}`,
        `  Turns:    ${turnCount}`,
        `  Tokens:   ${totalTokens} (↑ ${inputTokens} ↓ ${outputTokens})`,
      ].join('\n');
      ctx.actions.addHistoryItem({ id: `cmd-${Date.now()}`, type: 'stats', content: lines });
    },
  },
  {
    name: 'tools',
    description: 'List available tools',
    action: async (ctx) => {
      try {
        const res = await fetch(`${ctx.baseUrl}/api/capabilities`);
        const data = await res.json();
        const tools = data?.tools || [];
        const lines = tools.map((t: any) => `  ${theme.cmdName(t.name?.padEnd(24) || '')} ${theme.muted(t.description || '')}`).join('\n');
        ctx.actions.addHistoryItem({ id: `cmd-${Date.now()}`, type: 'tools_list', content: lines || theme.muted('No tools available') });
      } catch {
        ctx.actions.addHistoryItem({ id: `cmd-${Date.now()}`, type: 'info', content: theme.muted('Could not fetch tools') });
      }
    },
  },
  {
    name: 'copy',
    description: 'Copy last response to clipboard',
    action: (ctx) => {
      if (!ctx.state.lastAssistantText) {
        ctx.actions.addHistoryItem({ id: `cmd-${Date.now()}`, type: 'warning', content: 'No response to copy' });
        return;
      }
      const ok = copyToClipboard(ctx.state.lastAssistantText);
      ctx.actions.addHistoryItem({
        id: `cmd-${Date.now()}`, type: 'info',
        content: ok ? theme.success('Copied to clipboard') : theme.error('Failed to copy'),
      });
    },
  },
  {
    name: 'compact',
    description: 'Compress conversation history',
    action: (ctx) => {
      ctx.actions.sendMessage('/compact');
    },
  },
  {
    name: 'export',
    description: 'Export conversation to file',
    action: async (ctx, args) => {
      const fs = await import('node:fs');
      const filename = args || `session-${Date.now()}.md`;
      const content = ctx.state.history
        .map(item => {
          if (item.type === 'user') return `## User\n\n${item.content}`;
          if (item.type === 'assistant') return `## Assistant\n\n${item.content}`;
          return '';
        })
        .filter(Boolean)
        .join('\n\n---\n\n');
      fs.writeFileSync(filename, content);
      ctx.actions.addHistoryItem({ id: `cmd-${Date.now()}`, type: 'info', content: theme.success(`Exported to ${filename}`) });
    },
  },
  {
    name: 'resume',
    description: 'Resume a previous session',
    action: (ctx) => {
      ctx.actions.setActiveDialog('session');
    },
  },
  {
    name: 'mcp',
    description: 'Show MCP server status',
    action: async (ctx) => {
      ctx.actions.addHistoryItem({
        id: `cmd-${Date.now()}`, type: 'mcp_status',
        content: theme.muted('MCP status available via /tools'),
      });
    },
  },
  {
    name: 'clear',
    description: 'Clear terminal',
    action: () => {
      process.stdout.write('\x1b[2J\x1b[H');
    },
  },
  {
    name: 'debug',
    description: 'Toggle debug mode',
    action: (ctx) => {
      const isOn = !process.env.CORE_AI_DEBUG;
      process.env.CORE_AI_DEBUG = isOn ? '1' : '';
      ctx.actions.addHistoryItem({
        id: `cmd-${Date.now()}`, type: 'info',
        content: `Debug mode: ${isOn ? 'ON' : 'OFF'}`,
      });
    },
  },
  {
    name: 'init',
    description: 'Create .core-ai/instructions.md',
    action: async (ctx) => {
      const fs = await import('node:fs');
      const path = await import('node:path');
      const dir = path.join(process.cwd(), '.core-ai');
      const file = path.join(dir, 'instructions.md');
      if (fs.existsSync(file)) {
        ctx.actions.addHistoryItem({ id: `cmd-${Date.now()}`, type: 'info', content: theme.muted(`${file} already exists`) });
        return;
      }
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(file, '# Project Instructions\n\n## Guidelines\n\n## Project Structure\n\n## Conventions\n');
      ctx.actions.addHistoryItem({ id: `cmd-${Date.now()}`, type: 'info', content: theme.success(`Created ${file}`) });
    },
  },
  {
    name: 'exit', aliases: ['quit'],
    description: 'Exit',
    hidden: true,
    action: (ctx) => ctx.actions.quit(),
  },
];

export function findCommand(input: string): { command: SlashCommand; args: string } | null {
  const trimmed = input.trim();
  if (!trimmed.startsWith('/')) return null;
  const [name, ...rest] = trimmed.slice(1).split(/\s+/);
  const cmd = commands.find(c => c.name === name || c.aliases?.includes(name || ''));
  if (!cmd) return null;
  return { command: cmd, args: rest.join(' ') };
}
