# P0 Development Design Document

> Version: 1.0 | Branch: vidingcode | Date: 2026-03-06

This document provides implementation-level guidance for all 5 P0 items. Each section is self-contained and can be developed independently.

---

## P0-1: WriteTodos Session Restore

### Problem

`WriteTodosTool.loadTodos()` exists but is never called during session restoration. When a session is resumed (via `InProcessAgentSession` constructor or CLI `--resume`), the persisted todos are lost — the LLM has no awareness of previous task state.

### Current Flow

```
InProcessAgentSession constructor
  → agent.load(sessionId)           // restores messages only (AgentPersistence)
  → agent.addLifecycle(ServerPermissionLifecycle)
  // ❌ no todos loading
```

`WriteTodosTool.writeTodos()` persists todos to `PersistenceProvider` with key `todos:{sessionId}`, but nothing reads them back on restore.

### Design

Create `TodoLifecycle` as a new lifecycle that loads persisted todos in `beforeAgentRun` and injects them into the system prompt context via `AGENT_WRITE_TODOS_SYSTEM_PROMPT`.

#### New class: `ai.core.tool.tools.TodoLifecycle`

```java
package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.prompt.SystemVariables;
import java.util.concurrent.atomic.AtomicReference;

public class TodoLifecycle extends AbstractLifecycle {
    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext context) {
        var todos = WriteTodosTool.loadTodos(context);
        if (todos.isEmpty()) return;
        // inject into context so Mustache template {{AGENT_WRITE_TODOS_SYSTEM_PROMPT}} renders
        context.getCustomVariables().put(WriteTodosTool.TODOS_CONTEXT_KEY, todos);
    }
}
```

#### Registration in `AgentBuilder.copyValue()`

Insert `TodoLifecycle` in the lifecycle chain. It should run early since it only reads data:

```java
// in copyValue(), after processing subAgents, before configureToolDiscovery:
if (agent.toolCalls.stream().anyMatch(t -> "write_todos".equals(t.getName()))) {
    agent.agentLifecycles.addFirst(new TodoLifecycle());
}
```

#### System prompt injection

The existing system variable `AGENT_WRITE_TODOS_SYSTEM_PROMPT` is already defined in `SystemVariables`. In `Node.setupNodeSystemVariables()`, add:

```java
var todos = executionContext.getCustomVariable(WriteTodosTool.TODOS_CONTEXT_KEY);
if (todos != null) {
    systemVariables.put(SystemVariables.AGENT_WRITE_TODOS_SYSTEM_PROMPT, JSON.toJSON(todos));
}
```

This ensures the system prompt `{{AGENT_WRITE_TODOS_SYSTEM_PROMPT}}` is populated when the session is restored.

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../tool/tools/TodoLifecycle.java` | **New** — lifecycle that loads todos |
| `core-ai/src/.../agent/AgentBuilder.java` | Register TodoLifecycle when WriteTodosTool is present |
| `core-ai/src/.../agent/Node.java` | Inject todos into system variables in `setupNodeSystemVariables()` |

### Verification

1. Create an agent with `WriteTodosTool` and a `FilePersistenceProvider`
2. Run a session, have LLM create todos
3. Restart session with same sessionId
4. Verify: todos appear in system prompt, LLM acknowledges existing tasks

---

## P0-2: SubAgent Isolated Execution Environment

### Problem

Current `SubAgentToolCall.execute()` passes the parent's `ExecutionContext` directly to the sub-agent via `subAgent.run(query, context)`. The sub-agent shares the parent's messages (via `useGroupContext` + `addAssistantOrToolMessage` bubbling up to parent). This creates:

1. **Message pollution**: Sub-agent internal reasoning leaks into parent context
2. **No isolation**: Sub-agent tool calls share parent permissions
3. **No independent lifecycle**: Sub-agent cannot have its own compression/pruning behavior

### Current Flow

```
SubAgentToolCall.execute(arguments, context)
  → subAgent.run(query, context)        // shares parent ExecutionContext
    → Node.aroundExecute()
      → Agent.execute()
        → chatTurns()                   // messages bubble up to parent via addAssistantOrToolMessage
```

Key issue in `Node.addAssistantOrToolMessage()` (line 450-454):
```java
void addAssistantOrToolMessage(Message message) {
    this.messages.add(message);
    if (this.parent != null) this.parent.addAssistantOrToolMessage(message);  // ← bubbles up
}
```

### Design

#### 2.1 Isolated ExecutionContext for SubAgent

Create a child context that shares read-only data but isolates mutable state:

```java
// New method in ExecutionContext
public ExecutionContext createChildContext(String childSessionId) {
    return ExecutionContext.builder()
            .sessionId(childSessionId)
            .userId(this.userId)
            .persistenceProvider(this.persistenceProvider)
            // do NOT copy customVariables — child starts clean
            .build();
}
```

#### 2.2 SubAgent message isolation

Modify `SubAgentToolCall.execute()` to reset sub-agent state and use a child context:

```java
@Override
public ToolCallResult execute(String arguments, ExecutionContext context) {
    var args = JSON.fromJSON(Map.class, arguments);
    var query = (String) args.get("query");

    // Create isolated context
    var childSessionId = context.getSessionId() + ":sub:" + subAgent.getName();
    var childContext = context.createChildContext(childSessionId);

    // Reset sub-agent state for clean execution
    subAgent.reset();

    var result = subAgent.run(query, childContext);
    // ... status handling unchanged
}
```

#### 2.3 Control message bubbling

Add a flag to control whether messages bubble up:

```java
// In Node.java
private boolean isolatedMessages = false;

void setIsolatedMessages(boolean isolated) {
    this.isolatedMessages = isolated;
}

void addAssistantOrToolMessage(Message message) {
    this.messages.add(message);
    this.getMessageUpdatedEventListener().ifPresent(v -> v.eventHandler((T) this, message));
    if (this.parent != null && !this.isolatedMessages) {
        this.parent.addAssistantOrToolMessage(message);
    }
}
```

In `AgentBuilder.copyValue()`, when setting up sub-agents:

```java
for (var subAgent : this.subAgents) {
    subAgent.getSubAgent().setParentNode(agent);
    subAgent.getSubAgent().setIsolatedMessages(true);  // ← isolate by default
    agent.toolCalls.add(subAgent);
}
```

#### 2.4 Result summary instead of full messages

The parent should receive a compact summary, not the full sub-agent conversation. `SubAgentToolCall.execute()` already returns the result string — this is the correct boundary. With `isolatedMessages=true`, no internal messages leak.

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../agent/ExecutionContext.java` | Add `createChildContext()` method |
| `core-ai/src/.../agent/Node.java` | Add `isolatedMessages` flag, guard `addAssistantOrToolMessage()` |
| `core-ai/src/.../tool/tools/SubAgentToolCall.java` | Use child context, reset sub-agent state |
| `core-ai/src/.../agent/AgentBuilder.java` | Set `isolatedMessages=true` for sub-agents |

### Verification

1. Create parent agent with a sub-agent tool
2. Send a query that triggers the sub-agent
3. Verify: parent's `messages` list does NOT contain sub-agent internal tool calls
4. Verify: sub-agent's result is returned as a single tool result to parent
5. Verify: sub-agent's token usage is still propagated to parent via `addTokenCost()`

---

## P0-3: Real-time Interrupt and Message Queuing

### Problem

During agent execution, users cannot:
1. **Queue a new message** — input is blocked by the semaphore until the current turn finishes
2. **Gracefully interrupt** — `Agent.cancel()` sets a volatile boolean, but the `chatTurns` loop only checks it at the start of each iteration. If the LLM is mid-response or a tool is running, the agent continues until the current turn completes.

### Current Flow

```
CLI: readInputLoop() → semaphore blocks → waits for turn
Agent: chatTurns() → while loop checks `cancelled` at top → handLLM() or ToolExecutor blocks

Cancel path:
  Agent.cancel() → cancelled=true + streamingCallback.cancelConnection()
  InProcessAgentSession.cancelTurn() → agent.cancel() + future.cancel(true)
```

The CLI has no mechanism for the user to type while the agent is running — the `readyForInput` semaphore blocks.

### Design

#### 3.1 Message queue in Agent

Add a pending message queue to the Agent that can be checked between tool calls:

```java
// In Agent.java
private final BlockingQueue<String> pendingMessages = new LinkedBlockingQueue<>();

public void queueMessage(String message) {
    pendingMessages.offer(message);
}

public boolean hasPendingMessages() {
    return !pendingMessages.isEmpty();
}

// Drain and format pending messages for injection
String drainPendingMessages() {
    var messages = new ArrayList<String>();
    pendingMessages.drainTo(messages);
    if (messages.isEmpty()) return null;
    return messages.stream()
        .map(m -> "<system-reminder>The user has sent a new message: \"" + m
            + "\". Please address this message and continue your current task.</system-reminder>")
        .collect(Collectors.joining("\n"));
}
```

#### 3.2 Check pending messages in chatTurns loop

Modify `Agent.chatTurns()` to check for pending messages between turns:

```java
protected void chatTurns(String query, Map<String, Object> variables,
        BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
    buildUserQueryToMessage(query, variables);
    var currentIteCount = 0;
    var agentOut = new StringBuilder();
    do {
        if (cancelled) break;

        // Check for pending user messages between turns
        var pending = drainPendingMessages();
        if (pending != null) {
            addMessage(Message.of(RoleType.USER, pending));
        }

        var turnMsgList = turn(getMessages(), AgentHelper.toReqTools(toolCalls), constructionAssistantMsg);
        // ... rest unchanged
    } while (AgentHelper.lastIsToolMsg(getMessages()) && currentIteCount < maxTurnNumber);
    // ...
}
```

#### 3.3 CLI: allow input during agent execution

Modify the CLI to allow concurrent input. Split the input loop into two modes: normal and "steering".

In `AgentSessionRunner.readInputLoop()`:

```java
private void readInputLoop(ReplCommandHandler commands, BlockingQueue<String> queue, Semaphore readyForInput) {
    boolean showFrame = true;
    while (true) {
        if (showFrame) ui.printInputFrame();

        // Non-blocking check: if agent is busy, allow steering input
        if (readyForInput.tryAcquire()) {
            // Agent is idle — normal input mode
            var input = ui.readInput();
            // ... existing logic: dispatch commands or queue message
        } else {
            // Agent is running — steering mode
            var input = ui.readInput();
            if (input == null) continue;
            var trimmed = input.trim();
            if (trimmed.equalsIgnoreCase("stop") || trimmed.equalsIgnoreCase("/stop")) {
                session.cancelTurn();
                waitForReady(readyForInput);
                showFrame = true;
                continue;
            }
            if (!trimmed.isEmpty()) {
                // Queue message for agent to pick up between turns
                agent.queueMessage(trimmed);
                ui.printStreamingChunk(AnsiTheme.MUTED + "  ↳ message queued" + AnsiTheme.RESET + "\n");
            }
            showFrame = false;
            continue;
        }
        // ...
    }
}
```

#### 3.4 InProcessAgentSession: propagate cancel to tools

Enhance `cancelTurn()` to also clear the pending state of the permission gate:

```java
@Override
public void cancelTurn() {
    agent.cancel();
    permissionGate.cancelAll();  // new method — complete all pending futures with DENY
    Future<?> task = currentTask.get();
    if (task != null && !task.isDone()) {
        task.cancel(true);
    }
}
```

Add `cancelAll()` to `PermissionGate`:

```java
public void cancelAll() {
    pending.forEach((id, future) -> future.complete(ApprovalDecision.DENY));
    pending.clear();
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../agent/Agent.java` | Add `pendingMessages` queue, `queueMessage()`, inject in `chatTurns()` |
| `core-ai/src/.../session/InProcessAgentSession.java` | Enhanced `cancelTurn()` with permissionGate.cancelAll() |
| `core-ai/src/.../session/PermissionGate.java` | Add `cancelAll()` method |
| `core-ai-cli/src/.../agent/AgentSessionRunner.java` | Steering mode input during agent execution |

### Verification

1. Start a multi-step task
2. While agent is executing, type a new message → verify "message queued"
3. Verify agent picks up the message between tool calls
4. Type "stop" during execution → verify agent stops gracefully
5. Verify permission gate doesn't deadlock on cancel

---

## P0-4: Multi-level Permission Model

### Problem

Current permission system is binary: `ToolCall.needAuth=true/false` + `ToolPermissionStore` stores tool names globally. This lacks:

1. **Granular actions** — no distinction between "always allow", "always deny", "ask each time"
2. **Pattern matching** — cannot set permissions by tool+arguments pattern (e.g., allow `read_file` but deny `shell_command rm *`)
3. **Scope isolation** — no per-session permission overrides
4. **Priority merging** — no layered rules (agent defaults → user config → session overrides)

### Current Flow

```
ServerPermissionLifecycle.beforeTool()
  → if autoApproveAll → skip
  → if permissionStore.isApproved(toolName) → skip
  → else → ask user, block on PermissionGate
    → APPROVE → continue
    → APPROVE_ALWAYS → permissionStore.approve(toolName)
    → DENY → throw ToolCallDeniedException
```

### Design

#### 4.1 Permission rule model

```java
package ai.core.session;

public record PermissionRule(
    String toolPattern,       // glob pattern: "shell_command", "read_*", "*"
    String argumentPattern,   // optional regex on arguments: ".*rm\\s+-rf.*"
    PermissionAction action   // ALLOW, DENY, ASK
) {
    public enum PermissionAction {
        ALLOW,   // execute without asking
        DENY,    // reject immediately
        ASK      // prompt user for approval
    }

    public boolean matches(String toolName, String arguments) {
        if (!globMatches(toolPattern, toolName)) return false;
        if (argumentPattern == null) return true;
        return arguments != null && arguments.matches(argumentPattern);
    }

    // simple glob: "*" matches all, "read_*" matches prefix, exact otherwise
    private static boolean globMatches(String pattern, String value) {
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith("*")) return value.startsWith(pattern.substring(0, pattern.length() - 1));
        return pattern.equals(value);
    }
}
```

#### 4.2 Permission evaluator

```java
package ai.core.session;

import java.util.List;

public class PermissionEvaluator {
    private final List<PermissionRule> rules;  // ordered, last match wins

    public PermissionEvaluator(List<PermissionRule> rules) {
        this.rules = rules;
    }

    public PermissionRule.PermissionAction evaluate(String toolName, String arguments) {
        PermissionRule.PermissionAction result = PermissionRule.PermissionAction.ASK;  // default
        for (var rule : rules) {
            if (rule.matches(toolName, arguments)) {
                result = rule.action();
            }
        }
        return result;
    }

    // Merge multiple rule lists with priority: later lists override earlier
    public static PermissionEvaluator merge(List<PermissionRule>... ruleSets) {
        var merged = new java.util.ArrayList<PermissionRule>();
        for (var ruleSet : ruleSets) {
            if (ruleSet != null) merged.addAll(ruleSet);
        }
        return new PermissionEvaluator(merged);
    }
}
```

#### 4.3 Upgrade ServerPermissionLifecycle

```java
@Override
public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {
    var toolName = functionCall.function.name;
    var arguments = functionCall.function.arguments;

    dispatcher.accept(ToolStartEvent.of(sessionId, callId, toolName, arguments));

    var action = evaluator.evaluate(toolName, arguments);

    switch (action) {
        case ALLOW -> { /* proceed silently */ }
        case DENY -> throw new ToolCallDeniedException(toolName);
        case ASK -> {
            // existing approval flow
            permissionGate.prepare(callId);
            dispatcher.accept(ToolApprovalRequestEvent.of(sessionId, callId, toolName, arguments));
            var decision = permissionGate.waitForApproval(callId, 300_000);
            if (decision == ApprovalDecision.DENY) throw new ToolCallDeniedException(toolName);
            if (decision == ApprovalDecision.APPROVE_ALWAYS) {
                permissionStore.approve(toolName);
            }
        }
    }
}
```

#### 4.4 Rule sources and layering

Rules are merged in priority order (last match wins):

```
1. Agent defaults     — from AgentBuilder (e.g., sub-agent: deny shell_command)
2. Project config     — from .core-ai/permissions.json (future P2-14)
3. PermissionStore    — runtime user approvals (converted to ALLOW rules)
4. Session overrides  — per-session via ExecutionContext
```

For now (P0 scope), support layers 1, 3, and 4. Layer 2 deferred to P2-14 (multi-layer config).

#### 4.5 AgentBuilder integration

```java
// New builder method
public AgentBuilder permissionRules(List<PermissionRule> rules) {
    this.permissionRules = rules;
    return this;
}
```

In `InProcessAgentSession` constructor, construct the evaluator:

```java
var storeRules = permissionStore.approvedTools().stream()
    .map(name -> new PermissionRule(name, null, PermissionRule.PermissionAction.ALLOW))
    .toList();
var evaluator = PermissionEvaluator.merge(agentRules, storeRules, sessionRules);
agent.addLifecycle(new ServerPermissionLifecycle(sessionId, this::dispatch, permissionGate, evaluator, permissionStore));
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../session/PermissionRule.java` | **New** — rule record with pattern matching |
| `core-ai/src/.../session/PermissionEvaluator.java` | **New** — evaluates rules with priority merge |
| `core-ai/src/.../session/ServerPermissionLifecycle.java` | Use `PermissionEvaluator` instead of boolean checks |
| `core-ai/src/.../session/InProcessAgentSession.java` | Construct evaluator from multiple rule sources |
| `core-ai/src/.../agent/AgentBuilder.java` | Add `permissionRules()` builder method |
| `core-ai/src/.../tool/ToolCall.java` | Deprecate `needAuth` boolean (backward compat: `needAuth=true` → rule `ASK`) |

### Backward Compatibility

- `autoApproveAll=true` → single rule `("*", null, ALLOW)` at highest priority
- `ToolCall.needAuth=true` → converted to agent-level rule `(toolName, null, ASK)` during build
- `ToolPermissionStore.isApproved(name)` → converted to `ALLOW` rules at runtime

### Verification

1. Configure rules: allow `read_file`, deny `shell_command` with `rm -rf`, ask for all others
2. Trigger `read_file` → no prompt
3. Trigger `shell_command rm -rf /tmp` → denied
4. Trigger `write_file` → user prompted
5. Approve always → verify persistent
6. Verify `autoApproveAll` backward compatibility

---

## P0-5: Doom Loop Detection

### Problem

LLM can enter an infinite loop calling the same tool with the same arguments. For example: `read_file → error → read_file → error → ...`. The current `maxTurnNumber=20` eventually stops this, but wastes tokens and time.

### Current Flow

```
Agent.chatTurns():
  do {
      if (cancelled) break;
      turn(messages, tools, handLLM);
      // tool calls processed inside turn()
      currentIteCount++;
  } while (lastIsToolMsg(messages) && currentIteCount < maxTurnNumber);
```

No tracking of recent tool call patterns.

### Design

#### 5.1 Tool call history tracker

```java
package ai.core.agent.internal;

import ai.core.llm.domain.FunctionCall;
import java.util.LinkedList;

public class DoomLoopDetector {
    private final int windowSize;
    private final LinkedList<String> recentCalls = new LinkedList<>();

    public DoomLoopDetector(int windowSize) {
        this.windowSize = windowSize;  // default: 3
    }

    // Returns true if doom loop detected
    public boolean record(FunctionCall call) {
        String fingerprint = call.function.name + ":" + normalizeArgs(call.function.arguments);
        recentCalls.addLast(fingerprint);
        if (recentCalls.size() > windowSize) {
            recentCalls.removeFirst();
        }
        // Detect: all recent calls are identical
        if (recentCalls.size() < windowSize) return false;
        return recentCalls.stream().distinct().count() == 1;
    }

    public void reset() {
        recentCalls.clear();
    }

    // Normalize arguments to handle trivial differences (whitespace, key order)
    private String normalizeArgs(String args) {
        if (args == null) return "";
        return args.replaceAll("\\s+", " ").trim();
    }
}
```

#### 5.2 Integration point: `Agent.handleFunc()`

The doom loop check belongs in `Agent.handleFunc()` where each tool call is processed:

```java
// In Agent.java
private final DoomLoopDetector doomLoopDetector = new DoomLoopDetector(3);

public List<Message> handleFunc(Message funcMsg) {
    return funcMsg.toolCalls.stream().map(tool -> {
        var msg = new ArrayList<Message>();

        // Doom loop detection
        if (doomLoopDetector.record(tool)) {
            logger.warn("doom loop detected for tool: {}", tool.function.name);
            var result = ToolCallResult.failed(
                "Repeated identical tool call detected (doom loop). "
                + "You have called " + tool.function.name + " with the same arguments "
                + doomLoopDetector.getWindowSize() + " times in a row. "
                + "Please try a different approach or ask the user for help."
            );
            msg.add(AgentHelper.buildToolMessage(tool, result));
            return msg;
        }

        var result = getToolExecutor().execute(tool, getExecutionContext());
        // ... rest unchanged
    }).flatMap(List::stream).toList();
}
```

#### 5.3 Configuration

Add doom loop configuration to `AgentBuilder`:

```java
// In AgentBuilder
private int doomLoopWindowSize = 3;
private boolean doomLoopEnabled = true;

public AgentBuilder doomLoopDetection(boolean enabled) {
    this.doomLoopEnabled = enabled;
    return this;
}

public AgentBuilder doomLoopWindowSize(int size) {
    this.doomLoopWindowSize = size;
    return this;
}
```

In `copyValue()`:

```java
if (this.doomLoopEnabled) {
    agent.doomLoopDetector = new DoomLoopDetector(this.doomLoopWindowSize);
}
```

#### 5.4 Reset on new user message

Reset the detector when a new user message arrives (in `chatTurns` when pending messages are injected, or in `buildUserQueryToMessage`):

```java
// In Agent.buildUserQueryToMessage():
if (doomLoopDetector != null) doomLoopDetector.reset();
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../agent/internal/DoomLoopDetector.java` | **New** — sliding window detector |
| `core-ai/src/.../agent/Agent.java` | Add detector field, check in `handleFunc()`, reset in `buildUserQueryToMessage()` |
| `core-ai/src/.../agent/AgentBuilder.java` | Add `doomLoopDetection()` and `doomLoopWindowSize()` config |

### Verification

1. Create a mock tool that always returns the same error
2. Send a query that triggers repeated calls to this tool
3. Verify: after 3 identical calls, detector kicks in with a failure message
4. Verify: LLM receives the doom loop message and changes approach
5. Verify: detector resets on new user input
6. Verify: different arguments to the same tool do NOT trigger detection

---

## Implementation Order

Recommended order based on dependencies and risk:

```
1. P0-5 Doom Loop Detection     — standalone, no dependencies, low risk
2. P0-1 WriteTodos Restore      — standalone, low risk, simple lifecycle
3. P0-2 SubAgent Isolation       — changes Node core, moderate risk
4. P0-4 Multi-level Permissions  — refactors permission system, test thoroughly
5. P0-3 Interrupt & Message Queue — touches CLI + Agent loop, highest complexity
```

Each item should include unit tests covering the core logic and at least one integration test for the end-to-end flow.
