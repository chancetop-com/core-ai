export function formatToolSummary(toolName: string, args?: string): string {
  if (!args || args === '{}' || !args.trim()) return toolName;

  try {
    const parsed = JSON.parse(args);
    const keys = Object.keys(parsed);
    if (keys.length === 0) return toolName;

    const priorityKeys = ['description', 'command', 'script_path', 'file_path', 'path', 'url', 'pattern', 'query', 'prompt'];
    let primaryKey = keys.length === 1 ? keys[0] : priorityKeys.find(k => k in parsed);

    if (primaryKey) {
      let val = String(parsed[primaryKey]);
      if (val.length > 100) val = val.slice(0, 97) + '...';
      const prefix = parsed.subagent_type ? `${parsed.subagent_type}:` : '';
      return `${toolName}(${prefix}${val})`;
    }

    const parts = keys.slice(0, 3).map(k => {
      let v = String(parsed[k]);
      if (v.length > 60) v = v.slice(0, 57) + '...';
      return `${k}: ${v}`;
    });
    return `${toolName}(${parts.join(', ')})`;
  } catch {
    const oneLine = args.replace(/\s+/g, ' ').trim();
    return oneLine.length > 100 ? `${toolName}(${oneLine.slice(0, 97)}...)` : `${toolName}(${oneLine})`;
  }
}

export function formatResult(result?: string, maxLen = 120): string {
  if (!result) return '(empty)';
  const oneLine = result.replace(/[\r\n]+/g, ' \u21B5 ').trim();
  return oneLine.length > maxLen ? oneLine.slice(0, maxLen - 3) + '...' : oneLine;
}
