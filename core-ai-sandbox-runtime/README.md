# Sandbox Runtime

A lightweight HTTP server that executes code execution and file operation tools in an isolated container environment.

## Features

- HTTP API for tool execution
- Health check endpoint
- Timeout support (5 minutes default)
- Isolated execution environment with restricted environment variables
- Non-root user execution
- Path security (all file operations are restricted to workspace directory)

## Supported Tools

### Code Execution
- `run_bash_command` - Execute bash commands
- `run_python_script` - Execute Python scripts

### File Operations
- `read_file` - Read file contents
- `write_file` - Write file contents
- `edit_file` - Edit file contents (replace text)
- `glob_file` - Find files matching pattern
- `grep_file` - Search file contents

## API

### POST /execute

Execute a tool with arguments.

**Request:**
```json
{
  "tool": "read_file",
  "arguments": "{\"file_path\": \"src/main.java\", \"offset\": 0, \"limit\": 100}"
}
```

**Response:**
```json
{
  "status": "completed",
  "result": "package com.example;\n\npublic class Main { ... }",
  "durationMs": 45
}
```

### GET /health

Health check endpoint.

**Response:**
```json
{
  "status": "ok"
}
```

## Tool Arguments

### read_file
```json
{
  "file_path": "/path/to/file",
  "offset": 0,
  "limit": 100
}
```

### write_file
```json
{
  "file_path": "/path/to/file",
  "content": "file content here"
}
```

### edit_file
```json
{
  "file_path": "/path/to/file",
  "old_string": "old text",
  "new_string": "new text",
  "replace_all": false
}
```

### glob_file
```json
{
  "path": "/path/to/search",
  "pattern": "**/*.java"
}
```

### grep_file
```json
{
  "path": "/path/to/search",
  "pattern": "class.*Test",
  "output_mode": "content",
  "head_limit": 50,
  "case_insensitive": false
}
```

## Build

```bash
# Build Docker image
docker build -t core-ai-sandbox:latest .

# Or build Go binary directly
go build -o core-ai-sandbox-runtime main.go
```

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| PORT | 8080 | HTTP server port |
| WORKSPACE_DIR | /workspace | Base directory for file operations |

## Security

- Runs as non-root user (UID 1001)
- Restricted PATH and environment variables
- All file operations are restricted to workspace directory
- Path traversal attacks are blocked (../ is not allowed)
- No network access by default (depends on container configuration)
- Read-only filesystem recommended (configured by Kubernetes/Docker)
