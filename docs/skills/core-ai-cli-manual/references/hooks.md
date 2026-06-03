# hooks.json Guide

Hooks execute shell commands at specific lifecycle events. Compatible with Claude Code hooks format.

## File Locations

| Priority | Location | Scope |
|----------|----------|-------|
| Highest | `<workspace>/.core-ai/hooks.json` | Per-project |
| Medium | `~/.core-ai/plugins/<name>/hooks/hooks.json` | Per-plugin |
| Lowest | `~/.core-ai/plugins/<name>/hooks/hooks.json` (local) | Per-plugin local |

Deduplication key: `plugin:command` (plugin hooks) or `command` (workspace hooks). If the same command appears at multiple levels, the highest priority wins.

## Hook Events

| Event | JSON Key | Fires When |
|-------|----------|------------|
| SessionStart | `"SessionStart"` | Session begins |
| SessionStop | `"SessionStop"` | Session ends |
| UserPromptSubmit | `"UserPromptSubmit"` | Before agent processes user input |
| PreToolUse | `"PreToolUse"` | Before a tool executes |
| PostToolUse | `"PostToolUse"` | After a tool executes |

## JSON Format

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "",
        "hooks": [
          { "type": "command", "command": "echo 'Session started'" }
        ]
      }
    ],
    "UserPromptSubmit": [
      {
        "matcher": "",
        "hooks": [
          { "type": "command", "command": "python3 process_prompt.py" }
        ]
      }
    ],
    "PreToolUse": [
      {
        "matcher": "write_file",
        "hooks": [
          { "type": "command", "command": "echo 'About to write file'" }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "write_file",
        "hooks": [
          { "type": "command", "command": "python3 on_file_written.py" }
        ]
      }
    ],
    "SessionStop": [
      {
        "matcher": "",
        "hooks": [
          { "type": "command", "command": "python3 backup_memory.py" }
        ]
      }
    ]
  }
}
```

## Hook Entry Fields

| Field | Type | Description |
|-------|------|-------------|
| `type` | String | Must be `"command"` |
| `command` | String | Shell command. Absolute path if starts with `/`. Home-relative if starts with `~`. Otherwise relative to workspace. |
| `matcher` | String | For PreToolUse/PostToolUse: tool name to match. Empty matches all tools. |

Multiple hooks in an array execute sequentially. Multiple hook groups execute in order.

## Environment Variables

Available to hook commands based on event:

| Event | Variables |
|-------|-----------|
| `UserPromptSubmit` | `CORE_AI_USER_QUERY` |
| `PreToolUse` | `CORE_AI_TOOL_NAME`, `CORE_AI_TOOL_ARGUMENTS` |
| `PostToolUse` | `CORE_AI_TOOL_NAME`, `CORE_AI_TOOL_OUTPUT` |

SessionStart/SessionStop hooks have no additional environment variables.

## SessionStop Hook — Return Value

SessionStop hook output is NOT injected into the agent prompt (unlike UserPromptSubmit/PostToolUse). It executes and succeeds/fails silently. Use it for cleanup, backup, or notification tasks.

## Common Patterns

### Auto-commit generated files
```json
{
  "PostToolUse": [
    {
      "matcher": "write_file",
      "hooks": [
        { "type": "command", "command": "git add -A && git commit -m 'core-ai: auto-commit generated changes'" }
      ]
    }
  ]
}
```

### Log all tool usage
```json
{
  "PostToolUse": [
    {
      "matcher": "",
      "hooks": [
        { "type": "command", "command": "echo \"$(date): $CORE_AI_TOOL_NAME\" >> tool-usage.log" }
      ]
    }
  ]
}
```

### Pre-process prompts
```json
{
  "UserPromptSubmit": [
    {
      "matcher": "",
      "hooks": [
        { "type": "command", "command": "python3 ~/scripts/enrich_prompt.py" }
      ]
    }
  ]
}
```

The stdout of UserPromptSubmit hooks is appended to the user's query before the agent processes it.
