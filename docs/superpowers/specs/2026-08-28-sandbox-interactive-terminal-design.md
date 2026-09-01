# Sandbox Interactive Terminal Design

Date: 2026-08-28
Revised: 2026-08-31 — lifecycle renewal, reload recovery, snapshot/resume interaction, and deployment constraints added after verifying assumptions against the codebase and the dev/UAT environment manifests. See Revision Notes at the end.
Status: v2 direct-connect implemented 2026-09-01 (plan: docs/superpowers/plans/2026-09-01-terminal-direct-connect.md); awaiting user commit, image builds, and dev deployment.

## Problem

The chat UI already renders a Sandbox status card and a terminal side panel, but the panel is only a visual placeholder. A previous history-restoration fix deliberately hid the terminal icon on restored cards because the browser had no safe way to determine whether a historical Sandbox ID was still the session's live Sandbox. The resulting behavior is confusing: after switching away from a chat session and returning, the Sandbox card can be restored while its terminal action disappears.

Dev, UAT, and production all use the `agent-sandbox` provider (verified in the environment manifests: `SYS_SANDBOX_PROVIDER=agent-sandbox` with warm-pool mode in both dev and UAT). The runtime is reachable from core-ai-server over the cluster network, but the runtime supports only request/response command execution and has no PTY protocol.

## Goals

- Restore the terminal action on every valid `ready` Sandbox card, including cards restored from history.
- Provide a real interactive Bash PTY with persistent shell state, ANSI output, Ctrl-C, resize, REPL, and full-screen program support.
- Keep Pod addresses and Kubernetes credentials out of the browser.
- Authorize every terminal operation against the logged-in user, chat session, and current Sandbox binding.
- Work when terminal HTTP requests are load-balanced across different core-ai-server Pods.
- Keep the session and Sandbox alive while the user is actively typing in the terminal, matching the renewal semantics of chat messages.
- Recover the same PTY (with replay) after a browser reload, without waiting for any reclaim timeout.
- Provide explicit replaced, expired, unavailable, exited, and output-overflow states.
- Ship behind an environment gate, enable it in dev, and leave UAT and production configuration unchanged.

## Non-goals

- Exposing Kubernetes exec, Pod credentials, or a Pod IP connection to the browser.
- Creating or replacing a Sandbox when a historical card is clicked.
- Supporting SSH.
- Persisting a shell after the Sandbox itself is deleted.
- Recording terminal input or output for auditing.
- Adding collaborative multi-user terminal sessions.
- Serializing a terminal screen across a full browser reload. A reload recovers the same PTY through the persisted client ID and the replay ring; content older than the ring is lost and reported via the `overflow` event.
- Preserving shell process state across a Sandbox snapshot/resume cycle. Resume restores files only; the shell is always fresh (see Snapshot and Resume Interaction).

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
        | renew session + sandbox lifetime on user input
        v
Sandbox runtime internal HTTP API
        |
        v
interactive Bash PTY in /workspace
```

Direct Kubernetes exec was rejected because it would couple the product to Kubernetes RBAC and exec transport, expose a larger privilege boundary, and bypass other Sandbox providers. Reusing `/execute` was rejected because a request/response command runner (`bash -c` + buffered `CombinedOutput`, 30KB truncation) cannot preserve shell state or correctly support interactive programs.

The runtime-owned PTY works identically for all three providers (agent-sandbox, kubernetes, docker): they run the same runtime image on the same port 8080 and are wrapped by the same `SandboxClient`.

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

- generate a cryptographically random `clientId` on first open and persist it in `localStorage` keyed by `(sessionId, sandboxId)`, so a browser reload reuses the same `clientId` and idempotently recovers the same PTY with replay;
- create the terminal with the fitted row and column count;
- decode Base64 output and write bytes to xterm;
- batch keyboard input briefly before posting it, while immediately flushing control input such as Ctrl-C;
- send resize changes after xterm is fitted;
- reconnect the output stream using the last received event ID;
- close the PTY when the user clicks the panel close action;
- close the PTY on chat-session switch, using best effort and relying on runtime cleanup if the request is interrupted;
- show a restart action after a normal shell exit;
- show specific replaced, expired, unavailable, and overflow messages.

Stream transport: the existing chat SSE client accumulates `xhr.responseText` and parses only `data:` lines, which is unsuitable here — a terminal stream is long-lived and high-volume, so accumulated response text grows without bound, and event IDs are required for resume. The terminal panel therefore uses `fetch` with a `ReadableStream` reader (Bearer auth header as today), parses both `id:` and `data:` lines, remembers the last event ID, and sends it as `Last-Event-ID` on reconnect. The existing chat SSE code is not changed.

Network reconnection uses exponential delays of 500 ms, 1 s, 2 s, and then 5 s, with a 30-second automatic retry window. After that window the PTY remains eligible for the runtime disconnect timeout and the user receives a manual reconnect action.

### core-ai-server

A `SandboxTerminalService` will provide one authorization and runtime-resolution path for all terminal operations. It will:

1. enforce the environment feature gate;
2. use `SessionRegistry.requireAccessible(sessionId, userId)` (Mongo-backed, so it works on any Pod);
3. read the durable `sandbox:<sessionId>` binding from Redis;
4. return `409` if a different Sandbox is currently bound;
5. return `410` if no Sandbox is bound or the requested Sandbox cannot be attached;
6. attach to the existing provider runtime without creating, replacing, registering, or releasing the Sandbox lifecycle resource;
7. health-check the runtime before terminal creation;
8. proxy the internal terminal request;
9. on terminal creation and on user input, record terminal activity (see Activity renewal), throttled to at most once per 60 seconds per session.

**Attach constraint (snapshot epoch hazard).** Attaching MUST go through `provider.attach` directly. It MUST NOT use `SandboxService.reattachOrCreateSandbox` or any path that calls `SandboxSnapshotService.beginEpoch`: incrementing the epoch fails the capture CAS re-check in `SandboxSnapshotService.captureBeforeRelease` and silently discards a concurrent filesystem snapshot taken by the session-owning Pod. Terminal operations must be invisible to the snapshot epoch.

**Address cache.** `attach` resolves the runtime address from the Kubernetes API (`serviceFQDN` / `podIPs[0]`). Input is keystroke-frequency traffic, so each Pod keeps a short-TTL in-memory cache of `sandboxId -> host:port`, invalidated on connection failure. A Sandbox Pod's address is stable for its lifetime, so a small TTL (e.g. 60 s) plus failure-driven invalidation is sufficient.

**Activity renewal.** Both release timers are pod-local on the session-owner Pod: `cleanupIdleSessions` iterates the owner Pod's in-memory `sessionLastActivity` map (non-owner Pods discard their entries), and `SandboxService.renewSandbox` no-ops on a Pod that does not hold the sandbox in its in-memory `sessionSandboxes`. A terminal request proxied by the non-owner Pod therefore cannot renew anything by calling `touchActivity` locally. Renewal is split in two:

- **Any Pod (terminal service):** on create and input, write a durable activity timestamp to Redis (`session-activity:<sessionId>`, TTL slightly above the idle threshold), throttled to once per 60 seconds; additionally call the local `touchActivity`, which is fully effective when this Pod happens to be the owner.
- **Owner Pod (idle guard):** before `cleanupIdleSessions` closes a session that looks idle in its local map, it checks the Redis activity timestamp; if fresh, it refreshes its local `sessionLastActivity` and calls the local `renewSandbox` (which updates the in-memory `createdAt` used by `SandboxManager.cleanupExpired` and PATCHes the Kubernetes `shutdownTime`), then skips the close. The 5-minute job cadence against the 60/65-minute thresholds leaves ample margin.

This follows the established Redis pattern from the turn-state registry fix and keeps the no-affinity property intact — the non-owner Pod only writes a timestamp; all lifecycle mutation stays on the owner Pod. Only user input renews; output events do not — an abandoned terminal left running `top` must not keep a Sandbox alive forever. An actively typing user extends the session indefinitely, which matches existing chat semantics where every message renews.

The resolver accepts only `sessionId`, `sandboxId`, and `terminalId`. Host, IP, port, and image values from a client are ignored and are not part of the request contract.

Terminal REST operations use fixed session-scoped API paths so they reuse the existing HTTP authentication and `chat.use` authorization layers (the SSE listen framework requires static paths, and `SseAuthInterceptor` already maps the `/api/sessions` prefix to `chat.use`):

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/sessions/sandbox-terminal` | Create a PTY from `sessionId`, `sandboxId`, `clientId`, rows, and columns |
| `POST` | `/api/sessions/sandbox-terminal/input` | Send Base64-encoded input |
| `PUT` | `/api/sessions/sandbox-terminal/size` | Change PTY rows and columns |
| `POST` | `/api/sessions/sandbox-terminal/close` | Close the PTY (POST with a request bean rather than DELETE — the repo's CoreNG DELETE convention carries only a single `@PathParam`, and close must be authorized against `sessionId` + `sandboxId` + `terminalId`) |
| `GET` SSE | `/api/sessions/sandbox-terminal/events` | Stream output and lifecycle events |

The SSE endpoint receives `sessionId`, `sandboxId`, and `terminalId` as query parameters and uses `Last-Event-ID` for replay; the server forwards the header to the runtime and never buffers terminal events itself. The existing SSE authentication interceptor already maps `/api/sessions...` to `chat.use`; the listener additionally calls the shared terminal authorization path.

core-ai-server will bridge the runtime event stream to the browser using the existing internal `EventSource` and raw SSE channel pattern (as `GatewayProxyService.streamEvents` does today). Terminal content is not published through the chat event bus because it is ephemeral, high-volume data and must not enter chat history or the cross-Pod session event buffer.

Each open terminal holds one browser connection plus one internal server-to-runtime connection for its whole lifetime. To bound resources on a 2-replica deployment, each server Pod enforces a configurable cap on concurrently bridged terminal streams (default 50); above the cap, stream connection returns `429`.

No session-owner Pod routing is required. PTY state lives in the Sandbox runtime, and any core-ai-server Pod can independently validate the durable binding and attach to that runtime. This removes load-balancer affinity from the correctness boundary.

### Sandbox runtime

The Go runtime will add a dedicated `TerminalRegistry` and use a PTY library (`creack/pty`, pure Go, compatible with `CGO_ENABLED=0`) to start Bash. This is the runtime's first external dependency: it introduces `go.sum`, and the Dockerfile — which currently copies only `go.mod` — must copy `go.sum` as well. Terminal code will live outside `main.go` so process lifecycle, buffering, and HTTP transport can be tested independently. The existing `TaskRegistry` pattern, where the runner goroutine writes task fields without holding the mutex, is a data race and must not be copied — all cross-goroutine `TerminalRegistry` state is accessed under the lock.

The internal runtime API is:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/terminal` | Create or recover a PTY keyed by `client_id` |
| `GET` | `/terminal/{id}/events` | Stream output and lifecycle events |
| `POST` | `/terminal/{id}/input` | Write Base64-decoded bytes to the PTY |
| `PUT` | `/terminal/{id}/size` | Apply terminal rows and columns |
| `DELETE` | `/terminal/{id}` | Terminate the process group and release the PTY |

Creation semantics:

- Same `client_id` as the active terminal: idempotent recovery — return the existing terminal; replay proceeds from `Last-Event-ID`. This covers both a lost create response and a browser reload (the frontend persists `client_id`). Multiple concurrent subscribers with the same `client_id` are allowed and mirror output (e.g. two tabs of the same browser).
- Different `client_id` while the active terminal has **zero** connected subscribers: takeover — the old PTY is closed and a fresh shell is created. This removes any reclaim-timeout lockout after storage-cleared reloads or a different browser.
- Different `client_id` while another subscriber is actively connected: `429`.

Bash starts as an interactive shell in `/workspace` with the runtime's minimal environment plus `TERM=xterm-256color` (the runtime's `minimalEnv` does not set `TERM` today), initial rows and columns, and a deterministic prompt. The runtime does not use `bash -c`; the shell remains alive until exit, explicit close, disconnect cleanup, or Sandbox deletion.

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

Closing a terminal closes the PTY, signals the shell process group, waits for a short grace period, and force-kills remaining children before removing the registry entry. A terminal with no output subscribers is reclaimed after 10 minutes; with the takeover rule above this is a resource backstop, not a user-visible gate. A connected but idle terminal remains alive until explicitly closed or until the Sandbox lifecycle ends.

**Versioning.** This change bumps the runtime **minor** version, never the major version: snapshot restore eligibility matches on `runtime_major`, and a major bump would silently strand every existing snapshot.

## Snapshot and Resume Interaction

Sandbox snapshot/resume (v1, kill switch default off; currently effective in dev only — UAT has neither the flag nor the storage container yet) interacts with the terminal as follows. All statements below were verified against the implementation.

- **Files survive resume; the shell does not.** The capture roots include `/tmp`, `/skill`, and `/workspace` (`liveSnapshotRoots()` in the runtime), so files written from the terminal are captured and restored. Processes, working directory, exported variables, activated virtualenvs, history, and background jobs are not. The terminal panel states this after a resume: files are restored, the shell is fresh.
- **Terminal-only work is captured.** The capture gate is `policy.effective` plus a valid epoch — there is no "tool executed" dirty flag — so a user who only used the terminal still gets their files captured at session close.
- **Terminal operations never touch the snapshot epoch.** See the attach constraint in the server section. A test asserts that terminal create/input/stream/close leave the session's epoch counter unchanged.
- **Capture with an open terminal.** Session close (explicit or 60-minute idle) captures a live filesystem tar (best effort; a file mid-write may be truncated — same semantics as today), then releases the Sandbox, which kills the PTY. The frontend's reconnect attempts must land on the `expired` state, not retry forever. Terminal input renewal delays this path while the user is present.
- **Resume flow from the user's perspective:** old card click after release returns `410` expired; sending a message creates a new Sandbox, restores files, and emits a new READY card; the terminal opened from the new card is a fresh shell over the restored files; the old card then returns `409` replaced.
- **No mid-restore attach race.** Restore completes before the READY event is dispatched (`LazySandbox`), so the new card — the only source of the new `sandboxId` — cannot appear while restore is in flight. During the restore window the old card yields `409` before the new card exists; this transient is acceptable.

## Lifecycle and User Experience

Opening a ready card immediately opens the side panel in a connecting state. The server then produces one of four outcomes:

- current and live: create or idempotently recover the PTY;
- replaced: show `Sandbox 已替换，请使用最新 Sandbox 卡片`;
- expired: show `Sandbox 已过期` without creating another Sandbox;
- unavailable: show a retryable runtime connection error.

While the user types, input renewal keeps the session and Sandbox alive (see Activity renewal). A terminal left open without input follows the existing reclamation timers — Sandbox `shutdownTime` (default 65 minutes) and the 60-minute idle session close — and ends in the `expired` state.

The terminal shares `/workspace` with Agent tools. The UI will state that simultaneous Agent and terminal edits affect the same files and can conflict. No file lock is introduced.

Clicking the side-panel close action means close the shell, not merely hide it. Switching chat sessions also closes the shell. A transient SSE disconnect does not close the shell and is handled by the reconnect policy above.

## Security and Resource Controls

- Existing HTTP authentication and the `chat.use` permission protect every public endpoint.
- Session ownership is checked on every create, input, resize, close, and stream connection.
- The requested Sandbox ID must match the current Redis binding on every operation.
- A terminal ID alone grants no access.
- The browser never supplies or selects a runtime address.
- Runtime endpoints remain cluster-internal, matching the trust boundary of existing runtime execution and file APIs (the runtime has no request auth today; the terminal endpoints inherit that posture and do not widen it — `/execute` already runs arbitrary commands).
- Terminal input and output are never written to application logs, traces, chat messages, or audit records.
- Audit metadata may include user ID, session ID, Sandbox ID, terminal ID, timestamps, and close/error reason.
- One active terminal is allowed per Sandbox.
- Decoded input is limited to 64 KiB per request.
- Replay storage is limited to 512 KiB per terminal.
- Resize values are constrained to 20-500 columns and 5-200 rows.
- Invalid Base64, invalid dimensions, missing identifiers, and unknown terminals are rejected without reaching the PTY.
- Each server Pod caps concurrently bridged terminal streams (default 50).

The shell has the same operating-system privileges as existing Sandbox commands. The feature does not expand access outside the already isolated Sandbox container.

**Known environment gap (separate change, fbr-env-project, handle with care):** the SandboxTemplates mount size-limited emptyDirs for `/tmp` (10Gi) and `/skill` (50Mi), but `/workspace` lives on the container writable layer with no `ephemeral-storage` limit. An interactive terminal makes it trivial to fill node disk (`dd`) and trigger Pod eviction. Before broad rollout, add an `ephemeral-storage` resource limit to the template. Not part of this delivery.

## Error Contract

| Status | Meaning |
| --- | --- |
| `400` | Invalid identifier, Base64 payload, or terminal size |
| `403` | Caller cannot access the session or lacks `chat.use` |
| `409` | Requested Sandbox was replaced by a newer session binding |
| `410` | Sandbox or terminal expired or exited |
| `429` | Another terminal client is actively connected to the Sandbox PTY, or the server Pod's stream cap is reached |
| `502` | Sandbox runtime is unavailable or returned an invalid response |
| `504` | Sandbox runtime connection timed out |

Normal Bash exit is delivered as an `exit` event before subsequent operations return `410`. Output overflow is not fatal; the shell remains usable and the UI warns that earlier output was omitted.

## Feature Gate

core-ai-server adds `SYS_SANDBOX_TERMINAL_ENABLED`, defaulting to `false`. The effective value is exposed as `sandboxTerminalEnabled` in the `/api/capabilities` response.

The current `/api/capabilities` implementation returns the `A2ACapabilities` DTO, which is also part of the A2A protocol surface and consumed by the CLI. To keep the terminal flag out of the A2A agent card, the web endpoint gets its own response bean carrying the existing fields plus `sandboxTerminalEnabled`; the A2A DTO is unchanged. The endpoint remains unauthenticated (the flag is a harmless boolean).

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
- performs idempotent create for the same client ID, including a second subscriber mirroring output;
- takes over (closes old PTY, creates fresh shell) for a different client ID when zero subscribers are connected;
- rejects a different client ID with `429` while a subscriber is connected;
- closes the process group and removes the registry entry;
- reclaims a disconnected terminal after its timeout;
- enforces input, buffer, and resize limits;
- registry state is race-free under `-race` with concurrent input/output/subscribe.

### core-ai-server tests

- permits only an accessible session with `chat.use`;
- distinguishes current, replaced, and expired Sandbox bindings;
- never creates a Sandbox as part of terminal resolution;
- resolves the runtime from provider state rather than client metadata;
- terminal operations leave the session's snapshot epoch counter unchanged (never call `beginEpoch`);
- writes the Redis activity timestamp on create and input, throttled to once per 60 s; output events do not renew;
- the idle guard on the owner Pod skips closing a session whose Redis activity timestamp is fresh, refreshing local activity and renewing the sandbox; a stale timestamp still closes;
- terminal input proxied through the non-owner Pod prevents both the idle session close and the in-memory sandbox TTL release (end-to-end two-Pod test);
- caches the resolved runtime address and invalidates it on connection failure;
- enforces the feature gate for REST and SSE;
- proxies create, input, resize, close, output, and lifecycle events;
- maps runtime failures to the public error contract;
- reconnects to an existing PTY through a different core-ai-server instance;
- closes temporary runtime clients without deleting or releasing the Sandbox resource;
- enforces the per-Pod stream cap.

### Frontend tests

- shows the terminal action for live and historical ready cards;
- hides the action for pending cards and when the capability is disabled;
- sends only session, Sandbox, terminal, size, and encoded input data;
- persists `clientId` per `(sessionId, sandboxId)` and reuses it after a reload;
- parses `id:` lines and reconnects with `Last-Event-ID` and bounded backoff;
- renders decoded output;
- sends resize and Ctrl-C input;
- closes on panel close and session switch;
- renders replaced, expired, unavailable, overflow, and exited states;
- restarts after a normal shell exit.

### Local verification

- `go test -race ./...` in `core-ai-sandbox-runtime`;
- server/API Gradle checks covering the changed modules;
- frontend Vitest suite, lint, TypeScript compilation, and production build;
- repository status and diff review before each commit.

## Dev Deployment and Acceptance

**Shared `:latest` reality.** Both the dev and UAT SandboxTemplates pin `chancetop/core-ai-sandbox-runtime:latest` with no `imagePullPolicy` (Kubernetes defaults to `Always` for `:latest`), and both environments run `chancetop/core-ai-server:latest`. Pushing the runtime image therefore reaches UAT automatically: every newly created UAT warm-pool Sandbox runs the new runtime immediately, and UAT server Pods pull the new server on their next restart. "UAT unchanged" holds for configuration only, not binaries. Consequences for this delivery:

- the new runtime must be strictly backward-compatible with the existing execute/files/mcp/snapshot APIs;
- the runtime version bump is minor, never major (snapshot compatibility);
- the terminal gate default `false` is the behavioral guard for UAT/production server binaries;
- after pushing images, run a UAT smoke check (existing Agent Sandbox tool execution on a fresh Sandbox + `/health` version) even though the terminal stays disabled there;
- pinning versioned tags in the SandboxTemplates is the structural fix, recorded as a follow-up for the env repo (not part of this delivery).

Both core-ai-server and sandbox-runtime images must be verified by resolved registry and Pod image digests rather than tag names alone.

Deployment steps:

1. build and publish core-ai-server and sandbox-runtime;
2. update only dev configuration to enable the terminal gate;
3. ensure the dev SandboxTemplate/WarmPool resolves the new runtime image;
4. roll core-ai-server and recycle dev warm Sandbox capacity as required;
5. verify every core-ai-server Pod and a newly allocated Sandbox use the expected digests;
6. verify runtime `/health` reports the new version;
7. run the UAT smoke check described above;
8. leave UAT and production workloads and settings unchanged.

Live acceptance requires a fresh chat session and Sandbox:

1. confirm the ready card displays a terminal action;
2. run `pwd`, `cd`, Chinese output, and a Python REPL in the terminal;
3. interrupt `sleep 30` with Ctrl-C;
4. resize the panel and validate `stty size`;
5. write a unique file in the terminal and read the exact content through the Agent Sandbox tool;
6. switch to another chat session and back, confirming the card and action remain visible;
7. reload the browser and confirm the same PTY is recovered with replay (persisted client ID);
8. reconnect a deliberately interrupted output stream to the same PTY;
9. verify terminal input extends the Sandbox `shutdownTime` (inspect the CR after typing past the renew threshold);
10. resume end-to-end (dev has snapshot enabled): write a unique file in the terminal, close the session, send a new message so restore runs, open the terminal from the new card, and confirm the file content is intact, the shell is fresh, and the old card reports replaced/expired;
11. verify replaced and expired IDs produce explicit UI states;
12. confirm server and runtime logs contain lifecycle metadata but no terminal content;
13. read back dev image digests, readiness, capability state, and HTTP health after rollout.

A successful build, an HTTP 200, or ready Pods alone is not acceptance. The interactive PTY path, shared-workspace readback, and the resume round trip must all pass.

## Rollback

The immediate rollback is to set `SYS_SANDBOX_TERMINAL_ENABLED=false`, which removes the frontend capability and makes the server reject new terminals without affecting existing Agent Sandbox tool execution. If necessary, dev can then be rolled back to the previously recorded server and runtime digests. Because UAT warm-pool Sandboxes float on `:latest`, a runtime rollback must also confirm that UAT Sandboxes created since the push still execute correctly (they will pick up the rolled-back `latest` on their next creation). UAT and production configuration requires no change.

## Revision Notes (2026-08-31)

Changes from the 2026-08-28 draft, each verified against code or environment manifests:

1. **Activity renewal added.** Sandbox `shutdownTime` (65 min) and the 60-minute idle session close were fed only by chat messages; a user typing in the terminal would have had the Sandbox deleted underneath them. Terminal create/input now call `touchActivity` (throttled); output does not renew.
2. **Reload recovery redefined.** `clientId` is persisted per `(sessionId, sandboxId)`; zero-subscriber takeover replaces the previous behavior where a reload could hit `429` until the 10-minute reclaim.
3. **Stream transport specified.** The existing XHR/`responseText` SSE client is unsuitable for a long-lived high-volume stream and drops `id:` lines; the terminal uses `fetch` streaming with `Last-Event-ID` support.
4. **Snapshot epoch hazard made explicit.** `reattachOrCreateSandbox` calls `beginEpoch`, which fails the capture CAS and silently discards concurrent snapshots; terminal attach uses `provider.attach` only. New Snapshot and Resume Interaction section documents verified semantics (roots include `/workspace`; no tool-dirty gate; restore-before-READY).
5. **Deployment reality corrected.** Dev and UAT templates both pin `:latest` (pull policy Always), so "leave UAT unchanged" holds for configuration only; backward compatibility, minor version bump, and a UAT smoke check are now delivery requirements.
6. **Capabilities decoupled.** `/api/capabilities` currently returns the shared A2A DTO; the flag goes into a web-only response bean.
7. **Resource bounds added.** Per-Pod bridged-stream cap; runtime address cache; `/workspace` ephemeral-storage gap in the SandboxTemplates recorded as a follow-up env change.
8. **Implementation hazards recorded.** `creack/pty` is the runtime's first external dependency (go.sum + Dockerfile change); the existing `TaskRegistry` locking pattern is a data race and must not be copied; `minimalEnv` lacks `TERM`.
9. **Cross-Pod renewal corrected.** `touchActivity`/`renewSandbox` are pod-local (`sessionLastActivity` in-memory map, `sessionSandboxes.get` no-op on non-owner Pods) and `cleanupIdleSessions` decides on the owner Pod only — a plain `touchActivity` from a proxied terminal request cannot prevent release. Replaced with a Redis activity timestamp written by any Pod plus an idle-guard check on the owner Pod that renews locally before closing.

## Implementation Errata (2026-09-01)

Verified deviations found while implementing against this spec:

a. Busy responses carry the framework-fixed errorCode `TOO_MANY_REQUESTS` (HTTP 429), not a custom `TERMINAL_BUSY` code; the frontend keys on status 429.
b. Error codes shipped: `SANDBOX_REPLACED` (409), `SANDBOX_EXPIRED` (404 pre-create / 410 post-create), `TERMINAL_DISABLED` (404), `TERMINAL_RUNTIME_UNAVAILABLE` (502 via `RemoteServiceException`).
c. REST auth is the class-level `@PermissionsRequired(CHAT_USE)` annotation on the web impl (CoreNG has no path-prefix permission map for REST; the prefix map exists only for SSE) — the spec's "reuse existing authorization layers" sentence holds via annotation, not path.
d. `/api/capabilities` now returns a web-only `ServerCapabilities` bean (decoupled from `A2ACapabilities` as specified).
e. The runtime's overflow SSE frame's data payload is the client's requested cursor, not the evicted-through sequence; no consumer reads the payload value.
f. Terminal wiring lives in `SessionModule` (module load order), with terminal runtime resolution extracted to `SandboxTerminalRuntimeResolver`.
g. `TERMINAL_DISABLED` currently renders the same 已过期-style message as `SANDBOX_EXPIRED` in the panel (no dedicated disabled state).
h. Session/sandbox renewal from terminal input is implemented cross-pod via the Redis session-activity timestamp + an owner-pod idle-cleanup guard (as specified in the Activity renewal section).

## v2: Direct-Connect Transport (approved 2026-09-01)

v1 shipped with core-ai-server bridging every terminal byte (REST input, SSE output). v2 moves the data plane off core-ai-server onto a ticketed WebSocket path while keeping every v1 invariant: authorization and lifecycle arbitration stay on core-ai-server; the sandbox runtime keeps its PTY/registry/replay-ring layer unchanged and stays cluster-internal; the frontend keeps its state machine, clientId persistence, and reconnect semantics. The v1 SSE bridge and the input/size/close REST endpoints are REMOVED (full replacement, per user decision), as are the runtime's terminal HTTP/SSE endpoints.

### Architecture

```text
browser (xterm.js)
    | 1. POST /api/sessions/sandbox-terminal/ticket   (existing auth: chat.use + session ownership + Redis binding)
    v
core-ai-server ── mints ticket{sessionId, sandboxId, clientId, podIp, port, iat, exp=30s, nonce} + HMAC-SHA256
    | 2. wss://<gateway-host>/terminal?ticket=<b64>   (browser -> public gateway; TLS at ingress)
    v
sandbox-terminal-gateway   (new deployable, ~200-line Go service; verifies HMAC + expiry + one-shot nonce,
    |                       then dials the podIp:port FROM THE TICKET — no Kubernetes API, no discovery)
    | 3. ws://podIp:8080/terminal/ws?client_id=&rows=&cols=&last_seq=   (cluster-internal)
    v
sandbox runtime WS endpoint ── create-or-recover on the existing TerminalRegistry; duplex frames
```

- The runtime remains reachable only inside the cluster; the gateway is the sole public entry and holds no state beyond an in-memory nonce cache.
- Any core-ai-server pod can mint tickets (Mongo ownership + Redis binding + transient resolve, unchanged from v1). The pod address is embedded in the signed ticket, so the gateway needs no resolver.
- The v1 decision matrix survives at ticket time: REPLACED -> 409 SANDBOX_REPLACED, MISSING -> SANDBOX_EXPIRED, gate off -> TERMINAL_DISABLED. Busy/takeover moves to WS connect time (runtime close codes, below).

### Ticket

- Payload: base64url(JSON `{sid, sbid, cid, ip, port, iat, exp, nonce}`) + "." + base64url(HMAC-SHA256(payload, secret)). exp = iat + 30s. nonce = 16 random bytes hex.
- Secret: `SYS_SANDBOX_TERMINAL_TICKET_SECRET`, delivered to core-ai-server and the gateway from the same Kubernetes Secret. Rotation = rolling both deployments with the new value (30s tickets make overlap windows trivial).
- One-shot: the gateway caches seen nonces until their exp and rejects replays. The nonce cache is per-gateway-instance memory; dev runs 1 replica. Multi-replica replay protection (shared Redis nonce set) is explicitly deferred until the gateway scales.
- The ticket authorizes exactly one WS connection to exactly one pod address. It carries no Kubernetes credentials and grants nothing at rest; a leaked expired ticket is inert.

### Runtime WS endpoint (replaces /terminal REST+SSE)

`GET /terminal/ws?client_id=&rows=&cols=&last_seq=` upgrades to WebSocket and performs the v1 create-or-recover on the existing TerminalRegistry (same-client recover; zero-subscriber takeover; busy otherwise). Frames are JSON text messages:

- server -> client: `{"t":"ready","id":"<terminalId>","recovered":bool}`, `{"t":"o","seq":N,"d":"<base64>"}` (output), `{"t":"overflow"}`, `{"t":"exit","code":N}`, `{"t":"err","m":"..."}`
- client -> server: `{"t":"i","d":"<base64>"}` (input, decoded cap 64 KiB), `{"t":"resize","rows":N,"cols":N}` (v1 bounds)

Replay: `last_seq` drives the same ring replay as v1's Last-Event-ID; overflow semantics unchanged. Close codes: 4001 invalid params, 4003 busy (another client connected), 4004 unknown/exited terminal on a pure-attach path, 1000 normal. The busy/replaced distinction the frontend needs at connect time: busy = 4003; replaced/expired remain ticket-time REST errors. The logging middleware gains http.Hijacker forwarding (v1 review noted it only forwards Flusher). Subscriber counting, the 10-minute no-subscriber reclaim, and the fd/process-group close semantics are unchanged. terminal_http.go (REST+SSE) is deleted with its tests; the WS endpoint reuses Terminal/TerminalRegistry/eventRing as-is.

### core-ai-server changes

- New: `POST /api/sessions/sandbox-terminal/ticket` (request: session_id, sandbox_id, client_id, rows, cols — rows/cols echoed into the WS URL by the frontend, not the ticket; response: ticket, gateway_url, terminal hints). Auth path identical to v1 authorize(); resolve via SandboxTerminalRuntimeResolver (unchanged); mints and signs the ticket. No health pre-check (a dead pod surfaces as a WS dial failure; the frontend retries with a fresh ticket).
- New: `POST /api/sessions/sandbox-terminal/activity` (request: session_id) — the renewal heartbeat. The frontend calls it at most once per 60s and only when terminal INPUT occurred since the last beat, preserving the v1 invariant that output never renews. It drives the same SessionActivityRegistry.touch + local touchActivity as v1.
- Removed: input/size/close REST endpoints, the SSE bridge listener (TerminalStreamChannelListener, StreamSlots, TerminalStreamParams), SandboxTerminalClient and its exceptions (server no longer talks to the runtime terminal API at all).
- Capabilities: `sandbox_terminal_enabled` unchanged; new `sandbox_terminal_gateway_url` (public wss base, from `SYS_SANDBOX_TERMINAL_GATEWAY_URL`) so the frontend needs no hardcoded host. Gate off or URL unset -> feature hidden.
- SandboxTerminalService slims to authorize/resolve/mint/recordActivity; the 60s address cache and epoch-safety constraint (provider.attach only, never reattachOrCreateSandbox/beginEpoch) carry over verbatim.

### sandbox-terminal-gateway (new component)

- New top-level dir `core-ai-terminal-gateway/`: Go, two deps (websocket lib; no K8s client). Behavior: parse ticket -> verify HMAC/exp/nonce -> dial `ws://ip:port/terminal/ws` with the query params forwarded -> bidirectional byte pump -> close both sides on either end; propagate the runtime's close code outward. Health endpoint `/health`. Config via env: `TICKET_SECRET`, `LISTEN` (default :8080). Structured logs carry sessionId/sandboxId/terminalId only — never frame payloads.
- Deploy (dev): 1 replica, Service + Ingress with TLS at `SYS_SANDBOX_TERMINAL_GATEWAY_URL`'s host, in fbr-env-project's dev tree; the shared secret as a k8s Secret mounted into both core-ai-server and the gateway. CI: a `gateway-build.yml` workflow mirroring sandbox-build.yml (VERSION-file trigger + workflow_dispatch).

### Frontend changes

- terminal.ts transport rewrite: `createTerminal` becomes `requestTicket` (same errorCode mapping at ticket time); the stream is a WebSocket to `gateway_url` with the ticket; input/resize/close become WS frames (close = WS close). parseSseBuffer is deleted. Every reconnect attempt first fetches a FRESH ticket (tickets are one-shot and 30s), then dials; the v1 backoff (500/1000/2000/5000ms in a 30s episode) and lastSeq resume carry over.
- Panel: state machine, clientId persistence, batching (16ms/control flush), renewal heartbeat timer (input-gated 60s), overflow/exit/busy/replaced/expired states all carry over; only the transport calls change. WS close 4003 -> busy; ticket REST errors keep their v1 mappings.

### Invariants explicitly preserved (the v1 review battles that must not regress)

1. Terminal resolution never touches sandbox lifecycle or snapshot epochs (ticket minting uses the same transient resolver).
2. Renewal works cross-pod (Redis activity timestamp + owner-pod idle guard) and is input-gated; output never renews.
3. Reload recovers the same PTY via persisted clientId; zero-subscriber takeover; busy only when another client is live.
4. Replay ring + seq resume across disconnects; overflow signaled, never fatal.
5. The runtime stays cluster-internal with no request auth; the only public surface is the gateway, which forwards nothing without a valid server-signed ticket.

### v2 rollback

`SYS_SANDBOX_TERMINAL_ENABLED=false` hides the feature exactly as in v1. The gateway deployment can be scaled to zero independently; v1's transport no longer exists as a fallback (accepted trade-off, user decision 2026-09-01).

#### v2 implementation errata

Verified deviations found while implementing v2 against this section:

a. The ticket request carries `session_id`, `sandbox_id`, `client_id` only — rows/cols travel in the WS URL query string that the frontend dials, not in the ticket-minting request (this section's prose said rows/cols were part of the ticket request; the "core-ai-server changes" subsection's parenthetical is the accurate one).
b. Busy is signaled exclusively via WS close code 4003 (another client already attached) — there is no ticket-time 429/`TOO_MANY_REQUESTS` path in v2; the v1-era busy-at-request-time behavior is fully retired.
c. The v1 60s address-resolution cache was removed, not carried over — resolution is fresh on every ticket mint (the "SandboxTerminalService slims to..." bullet's "60s address cache... carries over verbatim" is superseded by this).
d. The ticket secret (`SYS_SANDBOX_TERMINAL_TICKETSECRET` / `TICKET_SECRET`) is interpreted as raw UTF-8 bytes on BOTH the core-ai-server signer and the gateway verifier — never hex-decoded, even though it is generated and stored as a hex string. A 32-byte-hex-looking value therefore yields a 64-byte HMAC key on both sides; consistent, but worth knowing before assuming key length.
e. There is no server-side "close terminal" endpoint in v2. A panel close is purely a WS disconnect; the runtime reclaims the PTY via its existing zero-subscriber timeout, same as an ungraceful drop.
f. **Env var naming for the two new server properties does NOT match the literal names used earlier in this section.** core-ng's `PropertyManager.envVarName()` only turns `.` into `_` and uppercases the rest — it does not insert an underscore at camelCase boundaries (the existing `sys.sandbox.agentSandbox.template` -> `SYS_SANDBOX_AGENTSANDBOX_TEMPLATE` mapping in the dev ConfigMap is the working precedent). Consequently:
   - `sys.sandbox.terminal.ticketSecret` binds to env var `SYS_SANDBOX_TERMINAL_TICKETSECRET`, not `SYS_SANDBOX_TERMINAL_TICKET_SECRET`.
   - `sys.sandbox.terminal.gatewayUrl` binds to env var `SYS_SANDBOX_TERMINAL_GATEWAYURL`, not `SYS_SANDBOX_TERMINAL_GATEWAY_URL`.
   Using the underscored forms this section originally named would silently leave both properties empty (core-ng's `PropertyManager.property()` only lets an env var override a key that already has an entry in `sys.properties`; a wrong env var name is not an error, it is simply never read). The dev manifests written for Task 6 use the correct un-underscored names; the Go gateway's own `TICKET_SECRET` env var is unaffected (it is read directly via `os.Getenv`, no core-ng property indirection).
