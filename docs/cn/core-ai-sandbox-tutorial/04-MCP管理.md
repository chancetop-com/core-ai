# 04 - MCP 管理

> 🎯 **学习目标**: 深入理解 MCP（Model Context Protocol）进程管理的实现机制
> 
> ⏱️ **预计时间**: 2 天

---

## 📚 本章内容

- [4.1 MCP 概述](#41-mcp-概述)
- [4.2 MCP 进程管理器](#42-mcp-进程管理器)
- [4.3 启动 MCP 服务器](#43-启动-mcp-服务器)
- [4.4 停止 MCP 服务器](#44-停止-mcp-服务器)
- [4.5 JSON-RPC 通信](#45-json-rpc-通信)
- [4.6 进程生命周期管理](#46-进程生命周期管理)
- [4.7 实战: MCP 集成](#47-实战-mcp-集成)
- [4.8 常见问题](#48-常见问题)
- [4.9 最佳实践](#49-最佳实践)
- [4.10 验证学习成果](#410-验证学习成果)

---

## 4.1 MCP 概述

### 4.1.1 什么是 MCP

**MCP (Model Context Protocol)** 是一个开放协议，用于标准化 AI 模型与外部工具和数据源的交互。

**核心价值**:
- 🔄 **标准化**: 统一的协议规范
- 🔌 **可插拔**: 轻松集成新工具
- 🌐 **跨平台**: 支持多种语言和平台
- 🔒 **安全性**: 沙箱隔离执行

### 4.1.2 MCP 架构

```
┌─────────────────────────────────────────┐
│         AI Agent / Client               │
│    (core-ai-server, Claude, etc.)       │
└─────────────────┬───────────────────────┘
                  │ JSON-RPC over stdio
                  ↓
┌─────────────────────────────────────────┐
│         MCP Server                      │
│    (sandbox-runtime 管理的子进程)       │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │  stdin  ← JSON-RPC 请求        │   │
│  │  stdout → JSON-RPC 响应        │   │
│  │  stderr → 日志输出             │   │
│  └─────────────────────────────────┘   │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │  工具实现                        │   │
│  │  - 文件系统访问                  │   │
│  │  - 数据库查询                    │   │
│  │  - API 调用                      │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

### 4.1.3 sandbox-runtime 中的 MCP

sandbox-runtime 提供以下 MCP 功能：

| 功能 | 说明 |
|------|------|
| **进程管理** | 启动、停止、监控 MCP 服务器进程 |
| **JSON-RPC 桥接** | 通过 HTTP 转发 JSON-RPC 请求到 MCP 服务器 |
| **生命周期管理** | 自动检测进程崩溃，清理资源 |
| **并发控制** | 支持多个 MCP 服务器同时运行 |

---

## 4.2 MCP 进程管理器

### 4.2.1 核心结构

**位置**: `mcp.go:17-51`

```go
// MCP 进程管理器
type McpProcessManager struct {
    mu      sync.RWMutex
    servers map[string]*McpServerProcess
}

// MCP 服务器进程
type McpServerProcess struct {
    ID         string
    Config     McpStartRequest
    Cmd        *exec.Cmd
    stdin      io.WriteCloser
    stdout     *bufio.Reader
    stdoutFile *os.File       // 用于设置读取超时
    mu         sync.Mutex    // 序列化并发的 JSON-RPC 交换
    started    time.Time
}
```

💡 **设计要点**:
- **读写锁**: 支持并发读取，独占写入
- **进程映射**: 按 ID 管理多个 MCP 服务器
- **互斥锁**: 保证同一进程的 JSON-RPC 请求串行执行
- **stdin/stdout**: 通过管道与子进程通信

### 4.2.2 全局实例

**位置**: `mcp.go:49-51`

```go
var mcpManager = &McpProcessManager{
    servers: make(map[string]*McpServerProcess),
}
```

💡 **说明**: 使用全局单例管理所有 MCP 进程。

---

## 4.3 启动 MCP 服务器

### 4.3.1 HTTP API

**位置**: `mcp.go:312-339`

```go
// POST /mcp/start
func handleMcpStart(w http.ResponseWriter, r *http.Request) {
    if r.Method != http.MethodPost {
        http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
        return
    }

    var req McpStartRequest
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        writeMcpJSON(w, http.StatusBadRequest, McpStartResponse{
            Status: "error", 
            Error: "invalid request: " + err.Error()
        })
        return
    }
    if req.ID == "" || req.Command == "" {
        writeMcpJSON(w, http.StatusBadRequest, McpStartResponse{
            Status: "error", 
            Error: "id and command are required"
        })
        return
    }

    proc, err := mcpManager.Start(req)
    if err != nil {
        writeMcpJSON(w, http.StatusConflict, McpStartResponse{
            ID: req.ID, 
            Status: "error", 
            Error: err.Error()
        })
        return
    }

    writeMcpJSON(w, http.StatusOK, McpStartResponse{
        ID:     proc.ID,
        Status: "running",
        PID:    proc.Cmd.Process.Pid,
    })
}
```

### 4.3.2 请求/响应结构

**位置**: `mcp.go:19-31`

```go
type McpStartRequest struct {
    ID      string            `json:"id"`
    Command string            `json:"command"`
    Args    []string          `json:"args"`
    Env     map[string]string `json:"env,omitempty"`
}

type McpStartResponse struct {
    ID      string `json:"id"`
    Status  string `json:"status"` // running, error
    PID     int    `json:"pid,omitempty"`
    Error   string `json:"error,omitempty"`
}
```

### 4.3.3 启动流程

**位置**: `mcp.go:66-130`

```go
func (m *McpProcessManager) Start(req McpStartRequest) (*McpServerProcess, error) {
    // 阶段 1: 启动进程
    proc, err := m.startOnce(req)
    if err != nil {
        return nil, err
    }

    // 阶段 2: 崩溃检测 (3 秒)
    exitCh := make(chan error, 1)
    go func() {
        exitCh <- proc.Cmd.Wait()
    }()

    select {
    case waitErr := <-exitCh:
        // 进程立即退出
        m.mu.Lock()
        delete(m.servers, req.ID)
        m.mu.Unlock()
        if waitErr != nil {
            return nil, fmt.Errorf("mcp server exited immediately: %w", waitErr)
        }
        return nil, fmt.Errorf("mcp server exited cleanly on startup")
    case <-time.After(crashDetectWindow):
        // 进程存活超过 3 秒，继续
    }

    // 阶段 3: 就绪探测 (120 秒)
    readyCh := make(chan error, 1)
    go func() {
        _, err := proc.SendJSONRPC(
            []byte(`{"jsonrpc":"2.0","id":"sandbox-ready-probe","method":"ping","params":{}}`),
            mcpStartupTimeout,
        )
        readyCh <- err
    }()

    select {
    case waitErr := <-exitCh:
        // 进程在就绪前退出
        m.mu.Lock()
        delete(m.servers, req.ID)
        m.mu.Unlock()
        return nil, fmt.Errorf("mcp server exited before ready: %w", waitErr)
    case probeErr := <-readyCh:
        if probeErr != nil {
            // 就绪探测失败
            proc.Cmd.Process.Kill()
            m.mu.Lock()
            delete(m.servers, req.ID)
            m.mu.Unlock()
            return nil, fmt.Errorf("mcp server not ready within %v: %w", mcpStartupTimeout, probeErr)
        }
        // 就绪成功，启动退出监控
        go m.exitWatcher(req.ID, exitCh)
        return proc, nil
    }
}
```

💡 **两阶段启动**:

```
┌─────────────────────────────────────────┐
│ 阶段 1: 启动进程                        │
│ - 创建子进程                            │
│ - 连接 stdin/stdout/stderr              │
└─────────────────┬───────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ 阶段 2: 崩溃检测 (3 秒)                │
│ - 检测进程是否立即退出                  │
│ - 捕获启动错误（如命令不存在）          │
└─────────────────┬───────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ 阶段 3: 就绪探测 (120 秒)              │
│ - 发送 ping 请求                        │
│ - 等待服务器响应                        │
│ - 超时则标记为失败                      │
└─────────────────┬───────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ 阶段 4: 启动监控                        │
│ - 启动 goroutine 监控进程退出           │
│ - 自动清理退出的进程                    │
└─────────────────────────────────────────┘
```

### 4.3.4 进程启动细节

**位置**: `mcp.go:144-195`

```go
func (m *McpProcessManager) startOnce(req McpStartRequest) (*McpServerProcess, error) {
    m.mu.Lock()
    defer m.mu.Unlock()

    // 检查是否已存在
    if _, exists := m.servers[req.ID]; exists {
        return nil, fmt.Errorf("mcp server already running: %s", req.ID)
    }

    // 创建命令
    cmd := exec.Command(req.Command, req.Args...)
    cmd.Env = buildMcpEnv(req.Env)

    // 连接管道
    stdin, err := cmd.StdinPipe()
    if err != nil {
        return nil, fmt.Errorf("stdin pipe: %w", err)
    }
    stdout, err := cmd.StdoutPipe()
    if err != nil {
        return nil, fmt.Errorf("stdout pipe: %w", err)
    }
    stdoutFile, _ := stdout.(*os.File)
    
    stderr, err := cmd.StderrPipe()
    if err != nil {
        return nil, fmt.Errorf("stderr pipe: %w", err)
    }

    // 启动进程
    if err := cmd.Start(); err != nil {
        return nil, fmt.Errorf("start command: %w", err)
    }

    // 创建进程对象
    proc := &McpServerProcess{
        ID:         req.ID,
        Config:     req,
        Cmd:        cmd,
        stdin:      stdin,
        stdout:     bufio.NewReader(stdout),
        stdoutFile: stdoutFile,
        started:    time.Now(),
    }

    // 后台读取 stderr
    go func() {
        scanner := bufio.NewScanner(stderr)
        for scanner.Scan() {
            log.Printf("[mcp:%s stderr] %s", req.ID, scanner.Text())
        }
    }()

    // 注册到管理器
    m.servers[req.ID] = proc
    log.Printf("[mcp:%s] started: command=%s args=%v pid=%d", 
        req.ID, req.Command, req.Args, cmd.Process.Pid)
    
    return proc, nil
}
```

💡 **关键点**:
- **命令不存在**: 如果命令不存在，`cmd.Start()` 会返回错误
- **管道连接**: 使用 `StdinPipe()`、`StdoutPipe()`、`StderrPipe()`
- **stderr 日志**: 后台 goroutine 读取 stderr 并记录日志
- **环境变量**: 继承沙箱环境变量 + 自定义环境变量

### 4.3.5 使用示例

#### 启动简单的 MCP 服务器

```bash
# 启动一个 echo 服务器（测试用）
curl -X POST http://localhost:8080/mcp/start \
  -H "Content-Type: application/json" \
  -d '{
    "id": "echo-server",
    "command": "node",
    "args": ["-e", "process.stdin.on(\"data\", d => process.stdout.write(d))"]
  }'

# 响应
{
  "id": "echo-server",
  "status": "running",
  "pid": 12345
}
```

#### 启动文件系统 MCP 服务器

```bash
# 启动官方文件系统 MCP 服务器
curl -X POST http://localhost:8080/mcp/start \
  -H "Content-Type: application/json" \
  -d '{
    "id": "filesystem",
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"],
    "env": {
      "MCP_LOG_LEVEL": "debug"
    }
  }'

# 响应
{
  "id": "filesystem",
  "status": "running",
  "pid": 12346
}
```

#### 启动 GitHub MCP 服务器

```bash
# 启动 GitHub MCP 服务器
curl -X POST http://localhost:8080/mcp/start \
  -H "Content-Type: application/json" \
  -d '{
    "id": "github",
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-github"],
    "env": {
      "GITHUB_TOKEN": "ghp_xxxx"
    }
  }'

# 响应
{
  "id": "github",
  "status": "running",
  "pid": 12347
}
```

---

## 4.4 停止 MCP 服务器

### 4.4.1 HTTP API

**位置**: `mcp.go:437-461`

```go
// POST /mcp/stop
func handleMcpStop(w http.ResponseWriter, r *http.Request) {
    if r.Method != http.MethodPost {
        http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
        return
    }

    var req struct {
        ID string `json:"id"`
    }
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        writeMcpJSON(w, http.StatusBadRequest, map[string]string{
            "error": "invalid request: " + err.Error()
        })
        return
    }
    if req.ID == "" {
        writeMcpJSON(w, http.StatusBadRequest, map[string]string{
            "error": "id is required"
        })
        return
    }

    if err := mcpManager.Stop(req.ID); err != nil {
        writeMcpJSON(w, http.StatusNotFound, map[string]string{
            "error": err.Error()
        })
        return
    }

    writeMcpJSON(w, http.StatusOK, map[string]string{
        "status": "stopped",
        "id": req.ID,
    })
}
```

### 4.4.2 停止实现

**位置**: `mcp.go:197-215`

```go
func (m *McpProcessManager) Stop(id string) error {
    m.mu.Lock()
    proc, exists := m.servers[id]
    if !exists {
        m.mu.Unlock()
        return fmt.Errorf("mcp server not found: %s", id)
    }
    delete(m.servers, id)
    m.mu.Unlock()

    // 关闭 stdin
    if proc.stdin != nil {
        proc.stdin.Close()
    }
    
    // 杀死进程
    if proc.Cmd != nil && proc.Cmd.Process != nil {
        proc.Cmd.Process.Kill()
    }
    
    log.Printf("[mcp:%s] stopped", id)
    return nil
}
```

💡 **停止流程**:
```
1. 从管理器中移除进程
2. 关闭 stdin 管道
3. 杀死进程（SIGKILL）
4. 记录日志
```

⚠️ **注意**: 使用 `Kill()` 发送 SIGKILL，进程无法优雅退出。

### 4.4.3 使用示例

```bash
# 停止 MCP 服务器
curl -X POST http://localhost:8080/mcp/stop \
  -H "Content-Type: application/json" \
  -d '{"id": "filesystem"}'

# 响应
{
  "status": "stopped",
  "id": "filesystem"
}

# 尝试停止不存在的服务器
curl -X POST http://localhost:8080/mcp/stop \
  -H "Content-Type: application/json" \
  -d '{"id": "nonexistent"}'

# 响应 (404)
{
  "error": "mcp server not found: nonexistent"
}
```

---

## 4.5 JSON-RPC 通信

### 4.5.1 JSON-RPC 协议

JSON-RPC 是一个轻量级的远程过程调用协议，基于 JSON 格式。

**请求格式**:
```json
{
  "jsonrpc": "2.0",
  "id": "request-1",
  "method": "tools/list",
  "params": {}
}
```

**响应格式**:
```json
{
  "jsonrpc": "2.0",
  "id": "request-1",
  "result": {
    "tools": [...]
  }
}
```

**错误响应**:
```json
{
  "jsonrpc": "2.0",
  "id": "request-1",
  "error": {
    "code": -32601,
    "message": "Method not found"
  }
}
```

### 4.5.2 SendJSONRPC 实现

**位置**: `mcp.go:256-307`

```go
func (p *McpServerProcess) SendJSONRPC(requestJSON []byte, timeout time.Duration) ([]byte, error) {
    p.mu.Lock()
    defer p.mu.Unlock()

    // 1. 提取请求 ID
    reqID := extractJSONRPCID(requestJSON)

    // 2. 写入请求到 stdin
    if _, err := p.stdin.Write(requestJSON); err != nil {
        return nil, fmt.Errorf("write to stdin: %w", err)
    }
    if _, err := p.stdin.Write([]byte("\n")); err != nil {
        return nil, fmt.Errorf("write newline to stdin: %w", err)
    }

    // 3. 通知（无 ID）直接返回
    if reqID == "" {
        return nil, nil
    }

    // 4. 设置读取超时
    if p.stdoutFile != nil {
        p.stdoutFile.SetReadDeadline(time.Now().Add(timeout))
        defer p.stdoutFile.SetReadDeadline(time.Time{})
    }

    // 5. 读取响应直到匹配请求 ID
    for {
        line, err := p.stdout.ReadString('\n')
        if err != nil {
            if os.IsTimeout(err) {
                return nil, fmt.Errorf("timeout waiting for response (id=%s)", reqID)
            }
            return nil, fmt.Errorf("read from stdout: %w", err)
        }
        line = strings.TrimSpace(line)
        if line == "" {
            continue
        }
        
        // 6. 匹配请求 ID
        respID := extractJSONRPCID([]byte(line))
        if respID == reqID {
            return []byte(line), nil
        }
        
        // 跳过不匹配的响应（可能是通知）
        log.Printf("[mcp:%s] skipping non-matching message: id=%s (waiting for %s)", 
            p.ID, respID, reqID)
    }
}
```

💡 **关键点**:
- **互斥锁**: 保证同一进程的请求串行执行
- **行分隔**: 每条 JSON-RPC 消息以换行符分隔
- **ID 匹配**: 只返回匹配请求 ID 的响应
- **超时控制**: 使用 `SetReadDeadline()` 实现读取超时
- **通知处理**: 无 ID 的请求是通知，不需要响应

### 4.5.3 HTTP 桥接

**位置**: `mcp.go:399-433`

```go
// handleMcpBridge 转发 JSON-RPC 请求到 MCP 服务器
func handleMcpBridge(w http.ResponseWriter, r *http.Request) {
    // 1. 获取服务器 ID
    serverID := r.Header.Get("X-Mcp-Server-Id")
    if serverID == "" {
        writeMcpJSON(w, http.StatusBadRequest, map[string]string{
            "error": "X-Mcp-Server-Id header is required"
        })
        return
    }

    // 2. 获取进程
    proc := mcpManager.Get(serverID)
    if proc == nil {
        writeMcpJSON(w, http.StatusNotFound, map[string]string{
            "error": "mcp server not found: " + serverID
        })
        return
    }

    // 3. 读取请求体
    body, err := io.ReadAll(r.Body)
    if err != nil {
        writeMcpJSON(w, http.StatusBadRequest, map[string]string{
            "error": "failed to read body: " + err.Error()
        })
        return
    }

    // 4. 发送 JSON-RPC 请求
    response, err := proc.SendJSONRPC(body, 120*time.Second)
    if err != nil {
        log.Printf("[mcp:%s] bridge error: %v", serverID, err)
        writeMcpJSON(w, http.StatusBadGateway, map[string]string{
            "error": "mcp bridge failed: " + err.Error()
        })
        return
    }

    // 5. 返回响应
    if response == nil {
        // 通知已接受
        w.WriteHeader(http.StatusAccepted)
        return
    }

    w.Header().Set("Content-Type", "application/json")
    w.Write(response)
}
```

💡 **桥接流程**:
```
HTTP 请求
    ↓
提取 X-Mcp-Server-Id
    ↓
查找 MCP 进程
    ↓
读取 HTTP Body
    ↓
SendJSONRPC (stdin)
    ↓
等待响应 (stdout)
    ↓
返回 HTTP 响应
```

### 4.5.4 使用示例

#### 列出可用工具

```bash
# 发送 JSON-RPC 请求
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-Mcp-Server-Id: filesystem" \
  -d '{
    "jsonrpc": "2.0",
    "id": "list-tools",
    "method": "tools/list",
    "params": {}
  }'

# 响应
{
  "jsonrpc": "2.0",
  "id": "list-tools",
  "result": {
    "tools": [
      {
        "name": "read_file",
        "description": "Read the contents of a file",
        "inputSchema": {
          "type": "object",
          "properties": {
            "path": {"type": "string"}
          },
          "required": ["path"]
        }
      },
      {
        "name": "write_file",
        "description": "Write content to a file",
        "inputSchema": {
          "type": "object",
          "properties": {
            "path": {"type": "string"},
            "content": {"type": "string"}
          },
          "required": ["path", "content"]
        }
      }
    ]
  }
}
```

#### 调用工具

```bash
# 调用 read_file 工具
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-Mcp-Server-Id: filesystem" \
  -d '{
    "jsonrpc": "2.0",
    "id": "read-file-1",
    "method": "tools/call",
    "params": {
      "name": "read_file",
      "arguments": {
        "path": "/workspace/test.txt"
      }
    }
  }'

# 响应
{
  "jsonrpc": "2.0",
  "id": "read-file-1",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Hello, World!"
      }
    ]
  }
}
```

#### 发送通知（无响应）

```bash
# 发送通知（无 ID）
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-Mcp-Server-Id: filesystem" \
  -d '{
    "jsonrpc": "2.0",
    "method": "notifications/initialized"
  }'

# 响应: 202 Accepted (无 Body)
```

---

## 4.6 进程生命周期管理

### 4.6.1 退出监控

**位置**: `mcp.go:132-142`

```go
func (m *McpProcessManager) exitWatcher(id string, exitCh <-chan error) {
    err := <-exitCh
    m.mu.Lock()
    delete(m.servers, id)
    m.mu.Unlock()
    if err != nil {
        log.Printf("[mcp:%s] process exited: %v", id, err)
    } else {
        log.Printf("[mcp:%s] process exited cleanly", id)
    }
}
```

💡 **说明**: 后台 goroutine 监控进程退出，自动从管理器中移除。

### 4.6.2 获取进程信息

**位置**: `mcp.go:217-235`

```go
func (m *McpProcessManager) Get(id string) *McpServerProcess {
    m.mu.RLock()
    defer m.mu.RUnlock()
    return m.servers[id]
}

func (m *McpProcessManager) List() []McpStartResponse {
    m.mu.RLock()
    defer m.mu.RUnlock()
    result := make([]McpStartResponse, 0, len(m.servers))
    for _, p := range m.servers {
        result = append(result, McpStartResponse{
            ID:     p.ID,
            Status: "running",
            PID:    p.Cmd.Process.Pid,
        })
    }
    return result
}
```

💡 **注意**: 目前没有 HTTP API 暴露 List 方法，需要自行添加。

### 4.6.3 环境变量构建

**位置**: `mcp.go:465-486`

```go
func buildMcpEnv(customEnv map[string]string) []string {
    // 继承沙箱环境变量
    parent := minimalEnv()
    
    if len(customEnv) > 0 {
        envMap := make(map[string]string)
        
        // 转换父环境变量为 map
        for _, e := range parent {
            key, val, found := strings.Cut(e, "=")
            if found {
                envMap[key] = val
            }
        }
        
        // 覆盖自定义环境变量
        for k, v := range customEnv {
            envMap[k] = v
        }
        
        // 转换回切片
        result := make([]string, 0, len(envMap))
        for k, v := range envMap {
            result = append(result, k+"="+v)
        }
        return result
    }
    
    return parent
}
```

💡 **说明**: MCP 服务器继承沙箱的最小化环境变量，可以添加自定义变量。

---

## 4.7 实战: MCP 集成

### 4.7.1 完整示例: 文件系统 MCP

```bash
#!/bin/bash

# 1. 启动文件系统 MCP 服务器
echo "Starting filesystem MCP server..."
curl -X POST http://localhost:8080/mcp/start \
  -H "Content-Type: application/json" \
  -d '{
    "id": "filesystem",
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"]
  }'

echo ""
sleep 5  # 等待服务器就绪

# 2. 列出可用工具
echo "Listing available tools..."
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-Mcp-Server-Id: filesystem" \
  -d '{
    "jsonrpc": "2.0",
    "id": "list-tools",
    "method": "tools/list",
    "params": {}
  }' | jq .

echo ""

# 3. 写入文件
echo "Writing file..."
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-Mcp-Server-Id: filesystem" \
  -d '{
    "jsonrpc": "2.0",
    "id": "write-file",
    "method": "tools/call",
    "params": {
      "name": "write_file",
      "arguments": {
        "path": "/workspace/test.txt",
        "content": "Hello from MCP!"
      }
    }
  }' | jq .

echo ""

# 4. 读取文件
echo "Reading file..."
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-Mcp-Server-Id: filesystem" \
  -d '{
    "jsonrpc": "2.0",
    "id": "read-file",
    "method": "tools/call",
    "params": {
      "name": "read_file",
      "arguments": {
        "path": "/workspace/test.txt"
      }
    }
  }' | jq .

echo ""

# 5. 停止 MCP 服务器
echo "Stopping MCP server..."
curl -X POST http://localhost:8080/mcp/stop \
  -H "Content-Type: application/json" \
  -d '{"id": "filesystem"}'

echo ""
echo "Done!"
```

### 4.7.2 多 MCP 服务器管理

```bash
#!/bin/bash

# 启动多个 MCP 服务器
echo "Starting multiple MCP servers..."

# 文件系统服务器
curl -s -X POST http://localhost:8080/mcp/start \
  -H "Content-Type: application/json" \
  -d '{
    "id": "filesystem",
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"]
  }' &

# GitHub 服务器
curl -s -X POST http://localhost:8080/mcp/start \
  -H "Content-Type: application/json" \
  -d '{
    "id": "github",
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-github"],
    "env": {
      "GITHUB_TOKEN": "ghp_xxxx"
    }
  }' &

# 等待所有服务器启动
wait
sleep 10

echo ""
echo "All MCP servers started!"

# 使用文件系统服务器
echo "Using filesystem server..."
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-Mcp-Server-Id: filesystem" \
  -d '{
    "jsonrpc": "2.0",
    "id": "list-files",
    "method": "tools/call",
    "params": {
      "name": "list_directory",
      "arguments": {
        "path": "/workspace"
      }
    }
  }' | jq .

# 使用 GitHub 服务器
echo ""
echo "Using GitHub server..."
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-Mcp-Server-Id: github" \
  -d '{
    "jsonrpc": "2.0",
    "id": "get-repo",
    "method": "tools/call",
    "params": {
      "name": "get_repository",
      "arguments": {
        "owner": "octocat",
        "repo": "Hello-World"
      }
    }
  }' | jq .

# 停止所有服务器
echo ""
echo "Stopping all MCP servers..."
curl -s -X POST http://localhost:8080/mcp/stop \
  -H "Content-Type: application/json" \
  -d '{"id": "filesystem"}'

curl -s -X POST http://localhost:8080/mcp/stop \
  -H "Content-Type: application/json" \
  -d '{"id": "github"}'

echo ""
echo "Done!"
```

### 4.7.3 错误处理

```bash
#!/bin/bash

# 尝试启动不存在的命令
echo "Testing error handling..."
curl -s -X POST http://localhost:8080/mcp/start \
  -H "Content-Type: application/json" \
  -d '{
    "id": "invalid",
    "command": "nonexistent-command"
  }' | jq .

# 响应:
# {
#   "id": "invalid",
#   "status": "error",
#   "error": "start command: exec: \"nonexistent-command\": executable file not found in $PATH"
# }

echo ""

# 尝试向不存在的服务器发送请求
echo "Testing invalid server ID..."
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-Mcp-Server-Id: nonexistent" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test",
    "method": "ping"
  }' | jq .

# 响应:
# {
#   "error": "mcp server not found: nonexistent"
# }

echo ""

# 尝试停止不存在的服务器
echo "Testing stop invalid server..."
curl -s -X POST http://localhost:8080/mcp/stop \
  -H "Content-Type: application/json" \
  -d '{"id": "nonexistent"}' | jq .

# 响应:
# {
#   "error": "mcp server not found: nonexistent"
# }
```

---

## 4.8 常见问题

### 4.8.1 启动超时

**症状**:
```json
{
  "status": "error",
  "error": "mcp server not ready within 2m0s: timeout waiting for response"
}
```

**原因**:
- MCP 服务器启动缓慢（如 npx 下载包）
- 服务器未实现 ping 方法
- 网络问题

**解决方案**:
```bash
# 1. 检查服务器日志
docker logs sandbox | grep "mcp:server-id"

# 2. 预安装包（避免 npx 下载）
# 修改 Dockerfile 添加:
# RUN npm install -g @modelcontextprotocol/server-filesystem

# 3. 手动测试服务器
docker exec -it sandbox bash
npx -y @modelcontextprotocol/server-filesystem /workspace
```

### 4.8.2 进程崩溃

**症状**:
```
[mcp:server-id] process exited: signal: killed
```

**原因**:
- 内存不足
- 未捕获的异常
- 资源限制

**解决方案**:
```bash
# 1. 检查容器资源使用
docker stats sandbox

# 2. 增加资源限制
docker run -d --name sandbox \
  --memory="2g" \
  --cpus="2" \
  core-ai-sandbox:latest

# 3. 查看 stderr 日志
docker logs sandbox | grep "mcp:server-id stderr"
```

### 4.8.3 JSON-RPC 超时

**症状**:
```json
{
  "error": "mcp bridge failed: timeout waiting for response (id=request-1)"
}
```

**原因**:
- MCP 服务器处理缓慢
- 死锁或无限循环
- 网络问题

**解决方案**:
```bash
# 1. 检查服务器状态
docker exec sandbox ps aux | grep mcp

# 2. 重启 MCP 服务器
curl -X POST http://localhost:8080/mcp/stop -d '{"id": "server-id"}'
curl -X POST http://localhost:8080/mcp/start -d '{"id": "server-id", ...}'

# 3. 检查服务器日志
docker logs sandbox | grep "mcp:server-id"
```

### 4.8.4 并发请求冲突

**症状**: 多个请求同时发送到同一 MCP 服务器，响应错乱

**原因**: JSON-RPC 请求并发执行，响应 ID 匹配错误

**解决方案**: 已内置互斥锁，保证同一进程的请求串行执行。无需额外处理。

---

## 4.9 最佳实践

### 4.9.1 服务器选择

✅ **推荐**:
- 使用官方 MCP 服务器（稳定、安全）
- 预安装常用包（避免 npx 下载延迟）
- 为不同用途使用不同服务器（filesystem、github、database）

❌ **避免**:
- 使用未经验证的第三方服务器
- 在 MCP 服务器中执行危险操作
- 共享敏感信息（如 API Key）

### 4.9.2 资源管理

✅ **推荐**:
```bash
# 1. 及时停止不使用的服务器
curl -X POST http://localhost:8080/mcp/stop -d '{"id": "server-id"}'

# 2. 限制并发服务器数量
# 在代码中添加检查
if len(mcpManager.servers) >= maxServers {
    return error("too many MCP servers")
}

# 3. 监控资源使用
docker stats sandbox
```

❌ **避免**:
- 启动大量 MCP 服务器不关闭
- 在 MCP 服务器中加载大文件
- 长时间运行不检查状态

### 4.9.3 错误处理

✅ **推荐**:
```go
// 1. 检查启动结果
if resp.Status == "error" {
    log.Printf("Failed to start MCP server: %s", resp.Error)
    return err
}

// 2. 重试机制
for i := 0; i < 3; i++ {
    resp, err := startMcpServer(config)
    if err == nil && resp.Status == "running" {
        break
    }
    time.Sleep(time.Second)
}

// 3. 超时处理
ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
defer cancel()
```

❌ **避免**:
```go
// 忽略错误
startMcpServer(config)  // 不检查返回值

// 无限重试
for {
    startMcpServer(config)  // 没有退出条件
}
```

### 4.9.4 安全考虑

✅ **推荐**:
- 使用环境变量传递敏感信息
- 限制 MCP 服务器的文件访问范围
- 定期更新 MCP 服务器版本

❌ **避免**:
- 在命令行参数中传递密码
- 允许 MCP 服务器访问整个文件系统
- 使用过时的 MCP 服务器版本

---

## 4.10 验证学习成果

### 4.10.1 自测题

1. **MCP 服务器通过什么方式通信？**
   - A. HTTP
   - B. stdin/stdout (JSON-RPC)
   - C. WebSocket
   
   **答案**: B

2. **两阶段启动的第二阶段是什么？**
   - A. 崩溃检测
   - B. 就绪探测 (ping)
   - C. 初始化
   
   **答案**: B

3. **JSON-RPC 通知的特点是什么？**
   - A. 需要响应
   - B. 不需要响应
   - C. 需要确认
   
   **答案**: B

4. **如何指定目标 MCP 服务器？**
   - A. URL 参数
   - B. X-Mcp-Server-Id Header
   - C. Body 中的 ID 字段
   
   **答案**: B

### 4.10.2 动手实践

1. **启动并测试 MCP 服务器**
   ```bash
   # 启动文件系统服务器
   curl -X POST http://localhost:8080/mcp/start -d '{...}'
   
   # 列出工具
   curl -X POST http://localhost:8080/mcp -d '{...}'
   
   # 调用工具
   curl -X POST http://localhost:8080/mcp -d '{...}'
   
   # 停止服务器
   curl -X POST http://localhost:8080/mcp/stop -d '{...}'
   ```

2. **实现多服务器管理**
   - 启动 3 个不同的 MCP 服务器
   - 分别在它们之间切换
   - 测试错误处理

3. **编写自动化脚本**
   - 启动 MCP 服务器
   - 执行一系列操作
   - 验证结果
   - 清理资源

### 4.10.3 思考题

1. **为什么需要两阶段启动？**
   
   **提示**:
   - 阶段 1: 检测立即崩溃（命令错误、依赖缺失）
   - 阶段 2: 检测服务器就绪（MCP 协议初始化完成）

2. **如何保证 JSON-RPC 请求的正确匹配？**
   
   **提示**:
   - 每个请求有唯一 ID
   - 互斥锁保证串行执行
   - 按 ID 匹配响应

3. **如何扩展 MCP 管理功能？**
   
   **提示**:
   - 添加 List API 查看所有服务器
   - 添加重启 API
   - 添加健康检查
   - 添加性能指标

---

## 🎉 本章小结

本章你学会了:

✅ MCP 协议的基本概念  
✅ MCP 进程管理器的实现  
✅ 两阶段启动机制（崩溃检测 + 就绪探测）  
✅ JSON-RPC 通信协议  
✅ HTTP 桥接实现  
✅ 进程生命周期管理  
✅ 多 MCP 服务器管理  
✅ 错误处理和最佳实践  

---

## 🚀 下一步

准备好进入 [05-快照系统](./05-快照系统.md)，学习如何捕获和恢复沙箱状态！

---

## 📚 参考资料

- **源码**: `core-ai-sandbox-runtime/mcp.go`
- **MCP 协议**: https://modelcontextprotocol.io/
- **JSON-RPC**: https://www.jsonrpc.org/specification
- **官方 MCP 服务器**: https://github.com/modelcontextprotocol/servers

---

*最后更新: 2026-08-31*
