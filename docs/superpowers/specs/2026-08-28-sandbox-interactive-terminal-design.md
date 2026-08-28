# Sandbox Interactive Terminal Design

Date: 2026-08-28
Status: Approved in chat; written specification awaiting user review

## Problem

The chat UI already renders a Sandbox status card and a terminal side panel, but the panel is only a visual placeholder. A previous history-restoration fix deliberately hid the terminal icon on restored cards because the browser had no safe way to determine whether a historical Sandbox ID was still the session's live Sandbox. The resulting behavior is confusing: after switching away from a chat session and returning, the Sandbox card can be restored while its terminal action disappears.

Dev currently uses the `agent-sandbox` provider. Its runtime is reachable from core-ai-server over the cluster network, but the runtime supports only request/response command execution and has no PTY protocol.

## Goals

- Restore the terminal action on every valid `ready` Sandbox card, including cards restored from history.
- Provide a real interactive Bash PTY with persistent shell state, ANSI output, Ctrl-C, resize, REPL, and full-screen program support.
- Keep Pod addresses and Kubernetes credentials out of the browser.
- Authorize every terminal operation against the logged-in user, chat session, and current Sandbox binding.
- Work when terminal HTTP requests are load-balanced across different core-ai-server Pods.
- Provide explicit replaced, expired, unavailable, exited, and output-overflow states.
- Ship behind an environment gate, enable it in dev, and leave UAT and production unchanged.

## Non-goals

- Exposing Kubernetes exec, Pod credentials, or a Pod IP connection to the browser.
- Creating or replacing a Sandbox when a historical card is clicked.
- Supporting SSH.
- Persisting a shell after the Sandbox itself is deleted.
- Recording terminal input or output for auditing.
- Adding collaborative multi-user terminal sessions.
- Serializing a terminal screen across a full browser reload. A reload may start a new terminal after any abandoned PTY is reclaimed by its disconnect timeout.

## Selected Architecture

The Sandbox runtime owns the PTY process and its short-lived output buffer. core-ai-server is a stateless authenticated proxy that resolves the runtime from the durable session-to-Sandbox binding. The frontend uses xterm.js but communicates only with core-ai-server.

```text
xterm.js in browser
        |
        | authenticated HTTPS
        v
core-ai-server (any Pod)
        |
        | validate user, session, current Sandbox binding
        | resolve attached runtime; never trust a client host or IP
        v
Sandbox runtime internal HTTP API
        |
        v
interactive Bash PTY in /workspace
```

Direct Kubernetes exec was rejected because it would couple the product to Kubernetes RBAC and exec transport, expose a larger privilege boundary, and bypass other Sandbox providers. Reusing `/execute` was rejected because a request/response command runner cannot preserve shell state or correctly support interactive programs.

## Component Design

### Frontend

`SandboxBlock` will offer the terminal action when all of the following are true:

- the server capability `sandboxTerminalEnabled` is true;
- `sandboxType` is `ready`;
- `sandboxId` is present and is not `pending`.

The `historical` flag will no longer suppress the action. `SandboxTerminalSpec` will include `sessionId` and `sandboxId`; hostname, image, and IP remain display-only metadata and are never sent as routing authority.

`SandboxTerminalPanel` will replace the placeholder with xterm.js and its fit addon. The panel owns one terminal client state machine:

```text
connecting -> connected -> reconnecting -> connected
     |             |              |
     v             v              v
   failed        exited         failed
```

The panel will:

- create the terminal with the fitted row and column count;
- decode Base64 output and write bytes to xterm;
- batch keyboard input briefly before posting it, while immediately flushing control input such as Ctrl-C;
- send resize changes after xterm is fitted;
- reconnect the output stream using the last received event ID;
- close the PTY when the user clicks the panel close action;
- close the PTY on chat-session switch, using best effort and relying on runtime cleanup if the request is interrupted;
- show a restart action after a normal shell exit;
- show specific replaced, expired, unavailable, and overflow messages.

Network reconnection uses exponential delays of 500 ms, 1 s, 2 s, and then 5 s, with a 30-second automatic retry window. After that window the PTY remains eligible for the runtime disconnect timeout and the user receives a manual reconnect action.

### core-ai-server

A `SandboxTerminalService` will provide one authorization and runtime-resolution path for all terminal operations. It will:

1. enforce the environment feature gate;
2. use `SessionRegistry.requireAccessible(sessionId, userId)`;
3. read the durable `sandbox:<sessionId>` binding from Redis;
4. return `409` if a different Sandbox is currently bound;
5. return `410` if no Sandbox is bound or the requested Sandbox cannot be attached;
6. attach to the existing provider runtime without creating, replacing, registering, or releasing the Sandbox lifecycle resource;
7. health-check the runtime before terminal creation;
8. proxy the internal terminal request.

The resolver accepts only `sessionId`, `sandboxId`, and `terminalId`. Host, IP, port, and image values from a client are ignored and are not part of the request contract.

Terminal REST operations use fixed session-scoped API paths so they reuse the existing HTTP authentication and `chat.use` authorization layers:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/sessions/sandbox-terminal` | Create a PTY from `sessionId`, `sandboxId`, `clientId`, rows, and columns |
| `POST` | `/api/sessions/sandbox-terminal/input` | Send Base64-encoded input |
| `PUT` | `/api/sessions/sandbox-terminal/size` | Change PTY rows and columns |
| `DELETE` | `/api/sessions/sandbox-terminal` | Close the PTY |
| `GET` SSE | `/api/sessions/sandbox-terminal/events` | Stream output and lifecycle events |

The SSE endpoint receives `sessionId`, `sandboxId`, and `terminalId` as query parameters and uses `Last-Event-ID` for replay. The existing SSE authentication interceptor already maps `/api/sessions...` to `chat.use`; the listener additionally calls the shared terminal authorization path.

core-ai-server will bridge the runtime event stream to the browser using the existing internal `EventSource` and raw SSE channel pattern. Terminal content is not published through the chat event bus because it is ephemeral, high-volume data and must not enter chat history or the cross-Pod session event buffer.

No session-owner Pod routing is required. PTY state lives in the Sandbox runtime, and any core-ai-server Pod can independently validate the durable binding and attach to that runtime. This removes load-balancer affinity from the correctness boundary.

### Sandbox runtime

The Go runtime will add a dedicated `TerminalRegistry` and use a PTY library to start Bash. Terminal code will live outside `main.go` so process lifecycle, buffering, and HTTP transport can be tested independently.

The internal runtime API is:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/terminal` | Create an idempotent PTY using a random `client_id` |
| `GET` | `/terminal/{id}/events` | Stream output and lifecycle events |
| `POST` | `/terminal/{id}/input` | Write Base64-decoded bytes to the PTY |
| `PUT` | `/terminal/{id}/size` | Apply terminal rows and columns |
| `DELETE` | `/terminal/{id}` | Terminate the process group and release the PTY |

Creation is idempotent for the same `client_id`, which prevents a lost create response from leaving the browser unable to recover. A different client receives `429` while a terminal is active.

Bash starts as an interactive shell in `/workspace` with the runtime's minimal environment plus `TERM=xterm-256color`, initial rows and columns, and a deterministic prompt. The runtime does not use `bash -c`; the shell remains alive until exit, explicit close, disconnect cleanup, or Sandbox deletion.

Each terminal owns:

- a cryptographically random terminal ID;
- the Bash process and PTY file descriptor;
- a monotonically increasing output-event sequence;
- a 512 KiB byte-bounded replay ring;
- current subscriber count and disconnect timestamp;
- process exit status and cleanup state.

Output is read continuously from the PTY, split into bounded chunks, Base64 encoded, and emitted as SSE. Slow consumers do not grow memory without limit. When replay data has been evicted, the stream emits an `overflow` event before continuing with the oldest retained event.

The runtime event types are:

- `ready`: terminal accepted the stream;
- `output`: Base64-encoded PTY bytes;
- `overflow`: output before a sequence boundary was discarded;
- `exit`: shell exited with an exit code;
- `error`: runtime terminal failure.

Closing a terminal closes the PTY, signals the shell process group, waits for a short grace period, and force-kills remaining children before removing the registry entry. A terminal with no output subscribers is reclaimed after 10 minutes. A connected but idle terminal remains alive until explicitly closed or until the Sandbox lifecycle ends.

## Lifecycle and User Experience

Opening a ready card immediately opens the side panel in a connecting state. The server then produces one of four outcomes:

- current and live: create or idempotently recover the PTY;
- replaced: show `Sandbox 已替换，请使用最新 Sandbox 卡片`;
- expired: show `Sandbox 已过期` without creating another Sandbox;
- unavailable: show a retryable runtime connection error.

The terminal shares `/workspace` with Agent tools. The UI will state that simultaneous Agent and terminal edits affect the same files and can conflict. No file lock is introduced.

Clicking the side-panel close action means close the shell, not merely hide it. Switching chat sessions also closes the shell. A transient SSE disconnect does not close the shell and is handled by the reconnect policy above.

## Security and Resource Controls

- Existing HTTP authentication and the `chat.use` permission protect every public endpoint.
- Session ownership is checked on every create, input, resize, close, and stream connection.
- The requested Sandbox ID must match the current Redis binding on every operation.
- A terminal ID alone grants no access.
- The browser never supplies or selects a runtime address.
- Runtime endpoints remain cluster-internal, matching the trust boundary of existing runtime execution and file APIs.
- Terminal input and output are never written to application logs, traces, chat messages, or audit records.
- Audit metadata may include user ID, session ID, Sandbox ID, terminal ID, timestamps, and close/error reason.
- One active terminal is allowed per Sandbox.
- Decoded input is limited to 64 KiB per request.
- Replay storage is limited to 512 KiB per terminal.
- Resize values are constrained to 20-500 columns and 5-200 rows.
- Invalid Base64, invalid dimensions, missing identifiers, and unknown terminals are rejected without reaching the PTY.

The shell has the same operating-system privileges as existing Sandbox commands. The feature does not expand access outside the already isolated Sandbox container.

## Error Contract

| Status | Meaning |
| --- | --- |
| `400` | Invalid identifier, Base64 payload, or terminal size |
| `403` | Caller cannot access the session or lacks `chat.use` |
| `409` | Requested Sandbox was replaced by a newer session binding |
| `410` | Sandbox or terminal expired or exited |
| `429` | Another terminal client already owns the Sandbox PTY |
| `502` | Sandbox runtime is unavailable or returned an invalid response |
| `504` | Sandbox runtime connection timed out |

Normal Bash exit is delivered as an `exit` event before subsequent operations return `410`. Output overflow is not fatal; the shell remains usable and the UI warns that earlier output was omitted.

## Feature Gate

core-ai-server adds `SYS_SANDBOX_TERMINAL_ENABLED`, defaulting to `false`. The effective value is exposed as `sandboxTerminalEnabled` in the existing capabilities response.

Dev will set the gate to `true`. UAT and production configuration will not be changed. The server rejects terminal creation while disabled even if a stale frontend attempts the call.

## Test Strategy

Implementation follows red-green-refactor. Each behavior receives a failing test before production code.

### Runtime tests

- starts Bash in `/workspace`;
- preserves shell state across commands;
- round-trips UTF-8, ANSI, and control bytes through Base64 transport;
- interrupts a foreground `sleep` with Ctrl-C;
- reports new dimensions through `stty size` after resize;
- replays retained events from `Last-Event-ID`;
- emits overflow when requested events were evicted;
- performs idempotent create for the same client ID;
- rejects a second client while a terminal is active;
- closes the process group and removes the registry entry;
- reclaims a disconnected terminal after its timeout;
- enforces input, buffer, and resize limits.

### core-ai-server tests

- permits only an accessible session with `chat.use`;
- distinguishes current, replaced, and expired Sandbox bindings;
- never creates a Sandbox as part of terminal resolution;
- resolves the runtime from provider state rather than client metadata;
- enforces the feature gate for REST and SSE;
- proxies create, input, resize, close, output, and lifecycle events;
- maps runtime failures to the public error contract;
- reconnects to an existing PTY through a different core-ai-server instance;
- closes temporary runtime clients without deleting or releasing the Sandbox resource.

### Frontend tests

- shows the terminal action for live and historical ready cards;
- hides the action for pending cards and when the capability is disabled;
- sends only session, Sandbox, terminal, size, and encoded input data;
- renders decoded output;
- sends resize and Ctrl-C input;
- reconnects with the last event ID and bounded backoff;
- closes on panel close and session switch;
- renders replaced, expired, unavailable, overflow, and exited states;
- restarts after a normal shell exit.

### Local verification

- `go test ./...` in `core-ai-sandbox-runtime`;
- server/API Gradle checks covering the changed modules;
- frontend Vitest suite, lint, TypeScript compilation, and production build;
- repository status and diff review before each commit.

## Dev Deployment and Acceptance

The implementation will bump the server version so the existing workflow builds the image. The user has authorized `latest` tags for this dev delivery. Both core-ai-server and sandbox-runtime images must nevertheless be verified by resolved registry and Pod image digests rather than tag names alone.

Deployment steps:

1. build and publish core-ai-server and sandbox-runtime;
2. update only dev configuration to enable the terminal gate;
3. ensure the dev SandboxTemplate/WarmPool resolves the new runtime image;
4. roll core-ai-server and recycle dev warm Sandbox capacity as required;
5. verify every core-ai-server Pod and a newly allocated Sandbox use the expected digests;
6. verify runtime `/health` reports the new version;
7. leave UAT and production workloads and settings unchanged.

Live acceptance requires a fresh chat session and Sandbox:

1. confirm the ready card displays a terminal action;
2. run `pwd`, `cd`, Chinese output, and a Python REPL in the terminal;
3. interrupt `sleep 30` with Ctrl-C;
4. resize the panel and validate `stty size`;
5. write a unique file in the terminal and read the exact content through the Agent Sandbox tool;
6. switch to another chat session and back, confirming the card and action remain visible;
7. reconnect a deliberately interrupted output stream to the same PTY;
8. verify replaced and expired IDs produce explicit UI states;
9. confirm server and runtime logs contain lifecycle metadata but no terminal content;
10. read back dev image digests, readiness, capability state, and HTTP health after rollout.

A successful build, an HTTP 200, or ready Pods alone is not acceptance. The interactive PTY path and shared-workspace readback must both pass.

## Rollback

The immediate rollback is to set `SYS_SANDBOX_TERMINAL_ENABLED=false`, which removes the frontend capability and makes the server reject new terminals without affecting existing Agent Sandbox tool execution. If necessary, dev can then be rolled back to the previously recorded server and runtime digests. UAT and production require no rollback because they are outside this deployment scope.
