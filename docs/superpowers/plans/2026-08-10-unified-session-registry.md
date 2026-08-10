# Unified Session Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan.

**Goal:** Make every successfully created agent session immediately discoverable and authorizable across all Core AI server pods, including Agent Builder sessions with no preloaded dependencies.

**Architecture:** Introduce a Mongo-backed `SessionRegistry` over the existing `chat_sessions` collection as the single source of truth for session identity, ownership, deletion state, and durable metadata. `AgentSessionManager` will create the registry row before returning a session ID, keep the in-memory runtime and Redis owner registry for execution routing only, and compensate partially created sessions on failure. Message persistence and SSE entry points will use the registry instead of creating metadata rows as a side effect or treating message persistence as session existence.

**Tech Stack:** Java, Core Framework MongoDB, Redis/Jedis, JUnit 5, Mockito, Gradle

## Global Constraints

- Reuse `chat_sessions`; do not add a collection or require a migration.
- Do not change the public HTTP API or the front end.
- Return a session ID only after Mongo registration succeeds.
- Do not delete registry rows during idle runtime cleanup; user deletion remains a soft delete.
- Reject missing/deleted sessions as `NotFoundException` and wrong/blank callers as `ForbiddenException("session is unavailable")`.
- Loaded-tool/skill/sub-agent updates must update an existing registry row and must never create a stub.
- Validate the caller before attaching either SSE channel or publishing a message command.
- Do not deploy or restart UAT as part of this plan.

---

### Task 1: Add the Mongo-backed session registry

**Files:**
- Create: `core-ai-server/src/main/java/ai/core/server/session/SessionRegistry.java`
- Create: `core-ai-server/src/test/java/ai/core/server/session/SessionRegistryTest.java`

**Step 1: Write failing registry tests**

Cover these behaviors with a mocked `MongoCollection<ChatSession>`:

1. `create` inserts a complete zero-message row including `id`, `userId`, `agentId`, `source`, `scheduleId`, `apiKeyId`, `messageCount=0`, and `createdAt`.
2. A duplicate-key insert rereads the row and is accepted only when immutable identity fields match.
3. A non-duplicate Mongo write error is propagated.
4. `requireAccessible` returns an active row for its owner, returns not found for missing/deleted rows, and returns forbidden for a wrong or blank caller.
5. `recordUserMessage` updates title, last-message time, and count without inserting a row; a matched-count of zero fails loudly.
6. Loaded dependency mutations use `$addToSet`/`$pullAll`, never insert a stub, and fail loudly when the target row is absent.
7. `close` is not represented in this service; `softDelete` is the only registry lifecycle deletion operation.

Run:

```bash
./gradlew :core-ai-server:test --tests ai.core.server.session.SessionRegistryTest
```

Expected: compilation failure because `SessionRegistry` does not exist.

**Step 2: Implement the minimal registry**

Add `SessionRegistry` with an injected `MongoCollection<ChatSession>` and:

```java
public ChatSession create(SessionRegistration registration)
public ChatSession get(String sessionId)
public ChatSession requireAccessible(String sessionId, String callerUserId)
public String requireUserId(String sessionId)
public String requireAgentId(String sessionId)
public void recordUserMessage(String sessionId, String content)
public void recordAgentMessage(String sessionId)
public void addLoadedTools(String sessionId, List<ToolRef> toolRefs)
public void addLoadedSkillIds(String sessionId, List<String> skillIds)
public void addLoadedSubAgentIds(String sessionId, List<String> agentIds)
public void removeLoadedSkillIds(String sessionId, List<String> skillIds)
public boolean softDelete(String userId, String sessionId)
```

Use a `SessionRegistration` record carrying `sessionId`, `userId`, `agentId`, `source`, `scheduleId`, and `apiKeyId`. Handle only Mongo duplicate-key code `11000`; after a duplicate, reread and compare all registration identity fields. Use update return counts to detect missing rows. Keep list/count/title/artifact access methods in the registry so `ChatMessageService` can delegate without retaining a second Mongo session implementation.

**Step 3: Run the focused test and commit**

```bash
./gradlew :core-ai-server:test --tests ai.core.server.session.SessionRegistryTest
git add core-ai-server/src/main/java/ai/core/server/session/SessionRegistry.java core-ai-server/src/test/java/ai/core/server/session/SessionRegistryTest.java
git commit -m "feat: add durable session registry"
```

Expected: PASS.

---

### Task 2: Make message persistence a registry consumer

**Files:**
- Modify: `core-ai-server/src/main/java/ai/core/server/session/ChatMessageService.java`
- Modify: `core-ai-server/src/test/java/ai/core/server/session/ChatMessageServiceTest.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/session/SessionRebuildManager.java`
- Modify: relevant rebuild tests only where the obsolete in-memory registration is asserted

**Step 1: Change tests to express the new boundary**

Inject a mocked `SessionRegistry` into `ChatMessageService` and assert:

- a user message insert is followed by `recordUserMessage`;
- an agent turn insert is followed by `recordAgentMessage`;
- metadata/list/delete/title/dependency calls delegate to the registry;
- no test expects first-message or dependency loading to insert `ChatSession`.

Run:

```bash
./gradlew :core-ai-server:test --tests ai.core.server.session.ChatMessageServiceTest
```

Expected: FAIL until the service delegates to `SessionRegistry`.

**Step 2: Refactor the service**

- Remove `chatSessionCollection`, `metaBySession`, `registerSession`, `SessionMeta`, stub creation, and first-message upsert logic.
- Inject `SessionRegistry`.
- Delegate metadata, identity, list/count/delete/title, artifacts, and loaded-resource methods to it for compatibility with existing callers.
- Keep only chat-message sequencing, message inserts, event buffering, and session-local buffer cleanup in `ChatMessageService`.
- Remove rebuild-time attempts to repopulate the deleted metadata cache.

**Step 3: Run focused regression and commit**

```bash
./gradlew :core-ai-server:test --tests ai.core.server.session.ChatMessageServiceTest --tests ai.core.server.session.SessionRebuildManagerTest
git add core-ai-server/src/main/java/ai/core/server/session/ChatMessageService.java core-ai-server/src/main/java/ai/core/server/session/SessionRebuildManager.java core-ai-server/src/test/java/ai/core/server/session/ChatMessageServiceTest.java core-ai-server/src/test/java/ai/core/server/session/SessionRebuildManagerTest.java
git commit -m "refactor: separate session registry from messages"
```

Expected: PASS.

---

### Task 3: Register sessions before returning their IDs

**Files:**
- Modify: `core-ai-server/src/main/java/ai/core/server/session/SessionAgentHelper.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/session/AgentSessionManager.java`
- Modify: `core-ai-server/src/test/java/ai/core/server/session/AgentSessionManagerCallerTest.java`
- Add or modify focused creation lifecycle tests under `core-ai-server/src/test/java/ai/core/server/session/`

**Step 1: Write failing creation lifecycle tests**

Test generic and agent-definition creation with a mocked `SessionRegistry`:

- successful creation claims Redis ownership, creates the Mongo registry row, then returns;
- a failed Redis ownership claim aborts and never exposes the session;
- a registry insert failure releases runtime/sandbox/owner state and propagates;
- definition dependency loading occurs only after the registry row exists;
- `closeSession`/idle cleanup does not soft-delete the registry row;
- caller and agent authorization read identity from `SessionRegistry`, not `ChatMessageService`.

Run:

```bash
./gradlew :core-ai-server:test --tests ai.core.server.session.AgentSessionManagerCallerTest --tests '*AgentSessionManager*Test'
```

Expected: FAIL until manager creation uses the registry.

**Step 2: Implement ordered creation and compensation**

- Inject `SessionRegistry` into `AgentSessionManager`.
- Change `SessionAgentHelper.claimOwnership` to return the boolean from `SessionOwnershipRegistry.claim`, treating a configured Redis claim failure as a creation failure.
- After the runtime has been assembled and placed locally, claim ownership and call `sessionRegistry.create(...)` before any dependency metadata update and before returning.
- Add `abortSessionCreation(String sessionId)` that removes local runtime state, releases sandbox/channel/Redis ownership, and soft-deletes the registry row only when registration already happened.
- Ensure ordinary `closeSession` remains runtime cleanup only.
- Use the registry for `requireSessionCaller`, `requireSessionOwner`, and agent-ID checks.

**Step 3: Run tests and commit**

```bash
./gradlew :core-ai-server:test --tests ai.core.server.session.AgentSessionManagerCallerTest --tests '*AgentSessionManager*Test'
git add core-ai-server/src/main/java/ai/core/server/session/SessionAgentHelper.java core-ai-server/src/main/java/ai/core/server/session/AgentSessionManager.java core-ai-server/src/test/java/ai/core/server/session
git commit -m "fix: register sessions before exposing them"
```

Expected: PASS.

---

### Task 4: Compensate web-layer session initialization failures

**Files:**
- Modify: `core-ai-server/src/main/java/ai/core/server/web/AgentSessionWebServiceImpl.java`
- Modify: `core-ai-server/src/test/java/ai/core/server/web/AgentSessionWebServiceImplTest.java`

**Step 1: Write a failing compensation test**

Arrange for manager creation to return `s-1`, then make `loadToolsOnSessionCreate`, skill loading, sub-agent loading, or state saving fail. Assert the original exception propagates and `sessionManager.abortSessionCreation("s-1")` is called. Also assert no abort occurs when failure happens before a session ID is obtained.

Run:

```bash
./gradlew :core-ai-server:test --tests ai.core.server.web.AgentSessionWebServiceImplTest
```

Expected: FAIL because create currently has no compensation boundary.

**Step 2: Wrap post-create initialization**

Keep permission and quota checks outside the compensation block. Once a session ID has been obtained, wrap loaded-tool/skill/sub-agent initialization and legacy state saving in `try/catch`; call `abortSessionCreation` and rethrow on failure.

**Step 3: Run and commit**

```bash
./gradlew :core-ai-server:test --tests ai.core.server.web.AgentSessionWebServiceImplTest
git add core-ai-server/src/main/java/ai/core/server/web/AgentSessionWebServiceImpl.java core-ai-server/src/test/java/ai/core/server/web/AgentSessionWebServiceImplTest.java
git commit -m "fix: compensate failed session initialization"
```

Expected: PASS.

---

### Task 5: Validate both SSE paths against the registry

**Files:**
- Modify: `core-ai-server/src/main/java/ai/core/server/web/sse/AgentMessageStreamChannelListener.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/web/sse/AgentSessionChannelListener.java`
- Create: `core-ai-server/src/test/java/ai/core/server/web/sse/AgentMessageStreamChannelListenerTest.java`
- Create: `core-ai-server/src/test/java/ai/core/server/web/sse/AgentSessionChannelListenerTest.java`

**Step 1: Write failing SSE tests**

For each listener, mock `WebContext`, `Request`, `Channel`, and `SessionRegistry`. Assert:

- authenticated owner is validated before `connect` and `join`;
- missing/deleted sessions and wrong owners do not connect;
- the message-stream listener does not publish a command when validation fails;
- the message-stream listener publishes the authenticated user ID after validation succeeds.

Run:

```bash
./gradlew :core-ai-server:test --tests 'ai.core.server.web.sse.*Session*ChannelListenerTest' --tests ai.core.server.web.sse.AgentMessageStreamChannelListenerTest
```

Expected: FAIL because listeners use `ChatMessageService` and one listener lacks `WebContext`.

**Step 2: Update listeners**

- Inject `SessionRegistry` into both listeners.
- Inject `WebContext` into `AgentSessionChannelListener`.
- Read `userId = AuthContext.userId(webContext)` and call `sessionRegistry.requireAccessible(sessionId, userId)` before `sessionChannelService.connect`, `channel.join`, or command publication.
- Remove listener-local existence checks and unused `NotFoundException` imports.

**Step 3: Run and commit**

```bash
./gradlew :core-ai-server:test --tests 'ai.core.server.web.sse.*Session*ChannelListenerTest' --tests ai.core.server.web.sse.AgentMessageStreamChannelListenerTest
git add core-ai-server/src/main/java/ai/core/server/web/sse core-ai-server/src/test/java/ai/core/server/web/sse
git commit -m "fix: authorize SSE sessions through registry"
```

Expected: PASS.

---

### Task 6: Full server regression and review

**Files:**
- Modify only files required by compilation or discovered regressions.

**Step 1: Run the complete server test suite**

```bash
./gradlew :core-ai-server:test
```

Expected: PASS.

**Step 2: Run the focused server quality gate**

```bash
./gradlew :core-ai-server:check
```

Expected: PASS. If root-project SpotBugs is run separately and reports unrelated pre-existing findings, report it separately from the focused module result.

**Step 3: Inspect the final diff**

```bash
git status --short
git diff --check
git diff HEAD~5 -- core-ai-server/src/main core-ai-server/src/test
```

Review specifically for:

- any remaining `chat_sessions` creation as a message/dependency side effect;
- an endpoint returning an ID before registry creation;
- SSE connect/join happening before authorization;
- idle cleanup soft-deleting a durable session;
- swallowed registry invariant failures;
- unrelated worktree changes.

**Step 4: Commit any narrowly scoped regression fixes**

Use a separate commit with a specific message, then rerun the affected focused test and `:core-ai-server:check`.
