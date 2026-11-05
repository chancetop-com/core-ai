// Pure classification helpers shared by the transcript reducer and its renderer.

export type ToolUseKind = 'skill' | 'subagent' | 'mcp' | 'api' | 'agent' | 'web';

export interface TaskNotification {
  taskId: string | null;
  status: string;
  label: string | null;
  detail: string;
}

export function parseArguments(value: unknown): Record<string, unknown> | null {
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value;
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

export function skillNameFrom(argumentsText: unknown): string | null {
  const parsed = parseArguments(argumentsText);
  const value = parsed?.name;
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

export function subagentLabelFrom(argumentsText: unknown): string | null {
  const parsed = parseArguments(argumentsText);
  const rawType = parsed?.subagent_type;
  const rawDescription = parsed?.description;
  const type = typeof rawType === 'string' && rawType.trim() ? rawType.trim() : null;
  const description = typeof rawDescription === 'string' && rawDescription.trim() ? rawDescription.trim() : null;
  if (type && description) return `${type} · ${description}`;
  return type;
}

export function toolUseKind(toolName: string, toolType: unknown): ToolUseKind | null {
  if (toolName === 'use_skill') return 'skill';
  if (toolName === 'web_search' || toolName === 'web_fetch') return 'web';
  if (toolType === 'subagent' || toolType === 'mcp' || toolType === 'api' || toolType === 'agent') return toolType;
  return toolName === 'task' ? 'subagent' : null;
}

export function toolUseLabel(kind: ToolUseKind, toolName: string, argumentsText: unknown): string {
  if (kind === 'skill') return skillNameFrom(argumentsText) ?? toolName;
  if (kind === 'subagent') return subagentLabelFrom(argumentsText) ?? toolName;
  if (kind === 'web') return webUseLabel(toolName, argumentsText) ?? toolName;
  return toolName;
}

/** What a web call touched: the query for a search, the URL for a fetch. */
function webUseLabel(toolName: string, argumentsText: unknown): string | null {
  const parsed = parseArguments(argumentsText);
  const target = parsed?.[toolName === 'web_search' ? 'query' : 'url'];
  if (typeof target !== 'string' || !target.trim()) return null;
  return `${toolName === 'web_search' ? 'search' : 'fetch'} · ${target.trim()}`;
}

export function systemReminderText(text: string | undefined): string | null {
  if (!text) return null;
  const blocks = [...text.matchAll(/<system-reminder>([\s\S]*?)<\/system-reminder>/g)];
  if (!blocks.length) return null;
  if (text.replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g, '').trim()) return null;
  return blocks.map((block) => block[1].trim()).filter(Boolean).join('\n');
}

export function parseTaskNotifications(text: string | undefined): TaskNotification[] | null {
  if (!text) return null;
  const blocks = [...text.matchAll(/<task-notification>([\s\S]*?)<\/task-notification>/g)];
  if (!blocks.length) return null;
  if (text.replace(/<task-notification>[\s\S]*?<\/task-notification>/g, '').trim()) return null;
  return blocks.map((block) => taskNotificationOf(block[1]));
}

function taskNotificationOf(body: string): TaskNotification {
  return {
    taskId: xmlTag(body, 'task-id'),
    status: xmlTag(body, 'status') ?? 'done',
    label: xmlTag(body, 'tool') ?? xmlTag(body, 'task-type'),
    detail: xmlTag(body, 'result') ?? xmlTag(body, 'error') ?? body.trim(),
  };
}

function xmlTag(xml: string, tag: string): string | null {
  const match = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(xml);
  const value = match?.[1].trim();
  return value ? value : null;
}
