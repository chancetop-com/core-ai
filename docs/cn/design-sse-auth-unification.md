# SSE Auth 统一设计方案

## 1. 问题分析

### 1.1 根本原因：HTTP 请求派发分叉

`core-ng` 的 `HTTPIOHandler.Handler` 在 `handle()` 时做了二选一的路由：

```java
// core.framework.internal.web.HTTPIOHandler.Handler
public void handle() {
    if (sse) {
        sseHandler.handleRequest(exchange);  // → ServerSentEventHandler — 不走拦截器链
    } else {
        exchange.dispatch(httpHandler);       // → HTTPHandler — 走 AuthInterceptor → controller
    }
}
```

SSE 请求一旦匹配路由表就进入 `PatchedServerSentEventHandler`，完全绕过 HTTPHandler 的拦截器链。

### 1.2 三条 SSE 路由当前认证状态

| 路由 | Listener | 当前认证 | 机制 |
|------|----------|---------|------|
| `PUT /api/sessions/events` | `AgentSessionChannelListener` | ❌ **无认证** | 仅校验 `agent-session-id` query param |
| `POST /api/a2a/message/stream` | `A2AStreamChannelListener` | ⚠️ 手动 | `requestAuthenticator.authenticate()` 写在 onConnect |
| `POST /api/litellm/v1/chat/completions` | `LiteLLMProxyChannelListener` | ⚠️ 手动 | `requestAuthenticator.authenticate()` 写在 onConnect |

### 1.3 HTTP vs SSE 处理流程对比

```
HTTP 路径 (core-ng 9.4.2):
  HTTPHandler.handle()
    → logManager.run("http", ..., actionLog -> {
        webContext.run(request, () -> {                   ← ScopedValue.where(context, new Context(request)).run()
          → InvocationImpl.proceed()
            → AuthInterceptor.intercept()
              → requestAuthenticator.authenticate()
              → webContext.put(USER_ID_KEY, userId)
            → Controller.execute()
        })                                                ← ScopedValue 自动清理
      })

SSE 路径 (现状):
  PatchedServerSentEventHandler.handle()
    → logManager.run("sse", ..., actionLog -> {
        connect()
          → IP 访问控制 ✓
          → Rate 限制 ✓
          → CORS headers ✓ (硬编码)
          → 认证 ❌
          → webContext.run() ❌ (从未调用)
          → listener.onConnect(request, channel, lastEventId)
      })
```

## 2. 设计目标

1. SSE 路由与 HTTP 路由使用相同的认证逻辑（`RequestAuthenticator`）
2. WebContext 在 SSE listener 内部可用（`AuthContext.userId(webContext)` 可正常工作）
3. 认证逻辑集中管理，不由每个 listener 重复实现
4. 支持认证白名单，为可能的公开 SSE 路由留扩展性
5. 不修改 core-ng 源码，仅在 Patched 层实现

## 3. threading 和 ScopedValue

### 3.1 线程模型

```
Undertow IO Thread
  → exchange.dispatch()                                  // 派发到 worker
    → VirtualThread (NEW per task)                       // Executors.newThreadPerTaskExecutor
      → logManager.run("sse", ..., actionLog -> {       // ScopedValue.where(CURRENT_ACTION_LOG, log).call()
          connect()
            → webContext.run(request, () -> {            // ScopedValue.where(context, ...).run()
                → interceptors
                → listener.onConnect()
              })                                          // ScopedValue 自动清理
        })                                                // ActionLog scope 自动清理
```

- `ThreadPools.virtualThreadExecutor()` → `Executors.newThreadPerTaskExecutor()` → 每任务新虚拟线程
- `ScopedValue` (Java 21) 替代 ThreadLocal，天然免疫 carrier thread 泄漏

### 3.2 WebContextImpl API (core-ng 9.4.2)

```java
public class WebContextImpl implements WebContext {
    private final ScopedValue<Context> context = ScopedValue.newInstance();

    public void run(Request request, Runnable task) {
        where(context, new Context(request)).run(task);
    }
    // get(), put(), request(), responseCookie() 通过 context.get() 获取当前 scope
}
```

`webContext.run(request, task)` 在 task 执行期间绑定 context，执行完自动清理。无需手动 initialize/cleanup。

## 4. 方案设计

### 4.1 新接口：SseChannelInterceptor

`core-ai` 模块，`ai.core.sse` 包：

```java
@FunctionalInterface
public interface SseChannelInterceptor {
    void onConnect(Request request, WebContext webContext);
}
```

### 4.2 PatchedServerSentEventHandler 改造

在 `connect()` 中，用 `webContext.run()` 包裹拦截器链和 listener 调用：

```java
private void connect(...) {
    // ... 现有的 parsing, access control, rate limiting, channel setup ...

    // Run SSE interceptors + listener within WebContext scope
    webContext.run(request, () -> {
        for (var interceptor : interceptors) {
            interceptor.onConnect(request, webContext);
        }
        support.listener.onConnect(request, channel, lastEventId);
    });
}
```

### 4.3 PatchedServerSentEventConfig 改造

```java
public void intercept(SseChannelInterceptor interceptor) {
    if (patchedServerSentEventHandler == null) {
        // lazy init
        patchedServerSentEventHandler = new PatchedServerSentEventHandler(...);
        context.httpServer.sseHandler = patchedServerSentEventHandler;
    }
    if (patchedServerSentEventHandler.webContext == null) {
        patchedServerSentEventHandler.webContext = context.httpServer.httpHandler.webContext;
    }
    patchedServerSentEventHandler.addInterceptor(interceptor);
}
```

### 4.4 SseAuthInterceptor（core-ai-server）

```java
public class SseAuthInterceptor implements SseChannelInterceptor {
    private final RequestAuthenticator requestAuthenticator;

    public SseAuthInterceptor(RequestAuthenticator requestAuthenticator) {
        this.requestAuthenticator = requestAuthenticator;
    }

    @Override
    public void onConnect(Request request, WebContext webContext) {
        var path = request.path();
        if (isPublicRoute(path)) return;
        var userId = requestAuthenticator.authenticate(request);
        webContext.put(AuthContext.USER_ID_KEY, userId);
    }
}
```

### 4.5 ServerModule 注册

```java
sseConfig.intercept(new SseAuthInterceptor(bean(RequestAuthenticator.class)));
```

### 4.6 清理各 Listener

- **A2AStreamChannelListener**：删除 `requestAuthenticator` 注入和 `authenticate()` 调用
- **LiteLLMProxyChannelListener**：删除 `requestAuthenticator` 注入和 `authenticate()` 调用
- **AgentSessionChannelListener**：无需修改，认证由拦截器自动注入

## 5. 设计决策

| 决策 | 原因 |
|------|------|
| 不复用 `core.framework.web.Interceptor` | `Interceptor.intercept(Invocation)` 依赖 `Invocation.proceed()` → controller，SSE 没有 controller |
| 新接口在 `core-ai` | `core-ai-server` → `core-ai`，避免循环依赖 |
| `webContext.run()` 而非 initialize/cleanup | 9.4.2 实际 API，ScopedValue 自动清理 |
| `intercept()` 在 `listen()` 之前调用 | 保证 handler 实例存在，WebContext 可用 |

## 6. 改动文件清单

| 模块 | 文件 | 操作 |
|------|------|------|
| core-ai | `ai/core/sse/SseChannelInterceptor.java` | 新增 |
| core-ai | `ai/core/sse/internal/PatchedServerSentEventHandler.java` | 修改 |
| core-ai | `ai/core/sse/PatchedServerSentEventConfig.java` | 修改 |
| core-ai-server | `ai/core/server/web/sse/SseAuthInterceptor.java` | 新增 |
| core-ai-server | `ai/core/server/ServerModule.java` | 修改 |
| core-ai-server | `ai/core/server/a2a/A2AStreamChannelListener.java` | 修改 |
| core-ai-server | `ai/core/server/web/sse/LiteLLMProxyChannelListener.java` | 修改 |
