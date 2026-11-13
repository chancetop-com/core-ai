# Public Session Sharing Implementation Plan

> **For agentic workers:** Use the design and the ownership boundaries below. Implement and test each part, then perform an independent security and integration review.

**Goal:** Anonymous, revocable, bounded read-only sharing of a completed chat session, its traces and files.

**Architecture:** A dedicated share record freezes message and resource scope. Every public read validates the share and source permissions. Existing chat/trace rendering is reused with explicit read-only behavior and public loaders.

**Tech Stack:** Java 21, core-ng, Mongo/PostgreSQL, React/TypeScript, JUnit/Vitest.

**Spec:** `docs/superpowers/specs/2026-11-13-public-session-sharing-design.md`

## Global Constraints

- Anonymous recipients; owner-only management plus existing Trace permission. No deployment or Git commit in this task.
- Default expiry 7 days; accept only 1, 7, 30 days. No new rounds in existing shares.
- Every trace/span/file read checks token, expiry, revocation, source session and owner permission.
- No inline Java comments, import classes, author new Java classes as Xander. Mongo fields use boxed concrete types.
- Work in `codex/public-session-sharing`; workers own disjoint files and must preserve others' work.

## Review Focus

- Guessed resource IDs must not expose another trace, span, file or owner.
- Concurrent creation and new turns must not expand a frozen scope.
- Raw and nested payloads must apply the same redaction and file URL rewriting.
- Read-only rendering must never invoke replay, approval, session restore or image editing.
- Deleted sessions, revoked permissions, expiry and revoked shares must fail across all public routes.

### Task 1: Storage and bounded message queries

Owner: storage worker. Files: new `domain/SessionShare.java`, `domain/SessionShareFile.java`, `share/SessionShareStore.java`, `share/MongoSessionShareStore.java`, `share/PgSessionShareStore.java`, supporting Pg row and SQL migration; modify `session/ChatMessageQuery.java`, Mongo/Pg message stores and their targeted tests. Root owns ServerApp, module loading and Mongo migration registration.

Interfaces: `SessionShareStore.getBySessionId(String)`, `getByToken(String)` return Optional; `replace(SessionShare value, String expectedId)` atomically inserts when expectedId is null or replaces the session record only if old id matches; `revoke(String sessionId, String shareId, ZonedDateTime now)` conditionally revokes. Record fields: id, sessionId, ownerUserId, token, title, maxMessageSeq, messageCount, createdAt, expiresAt, revokedAt, List<String> traceIds, List<SessionShareFile> files. File fields: id, fileId, attachmentRefId, container, blobName, fileName, contentType, size, sourceUrl, title, description. Query adds `Long maxSeq`.

- [x] Add query and store tests for upper-bound count/find, atomic creation, stale replacement/revocation and token lookup.
- [x] Implement Mongo/Pg adapters and required schema changes following existing migration conventions.
- [x] Run targeted store tests and report exact commands; root checks registration and full compile.

### Task 2: Sharing lifecycle, APIs and trace projection

Owner: backend worker. Files: new `core-ai-api/.../server/share/*`, server `share/SessionShareService.java`, dedicated API implementations, controllers, projection/access helpers and tests. Do not edit root-owned file/sanitizer helpers or storage files.

Interfaces: owner POST `/api/sessions/:sessionId/shares` request `{expires_in_days}`, returns `SessionShareView`; GET same returns `{share?: SessionShareView, artifacts: SharedSessionArtifact[]}`; DELETE `/:shareId` revokes. Public `/api/public/session-shares/:token` returns `{title,max_message_seq,created_at,expires_at,message_count,artifacts}`; `/messages?offset&limit` returns `{messages,next_offset?}`; `/traces/:traceId`, `/traces/:traceId/spans`, `/traces/:traceId/spans/:spanId` reuse trace view shapes. Share view includes id, session_id, title, url, max_message_seq, message_count, created_at, expires_at, revoked_at. Artifact fields: file_id (manifest id), file_name, content_type, size, title, description, download_url. Root provides `SessionShareFileService.capture(ChatSession,List<ChatMessage>)`, `rewrite(String,SessionShare)`, and `SessionShareSanitizer.sanitize(String)`.

- [x] Write lifecycle tests for expiry values, owner/Trace checks, active turn, last-sequence race, reuse, revoke, source deletion and owner disabled.
- [x] Implement bounded capture, public pagination and strict trace/span membership with current visibility checks, no impersonation.
- [x] Project messages/trace/span DTOs using shared sanitizer and URL rewrite. Preserve executed prompts, omit sandbox connection data and interactive cards.
- [x] Test anonymous API paths and failure cases; report binding order and exact public signatures to root.

### Task 3: Frontend sharing and read-only viewer

Owner: frontend worker; owns all frontend changes. Consume Task 2's REST shapes. New API `sessionShares`, owner dialog and `SharedSession` page. Add public route and header/sidebar entry. Preserve `seq/traceId` in history mapping. Adapt messages/TraceInspector through read-only props and injected loaders. Download files only through provided `download_url`.

- [x] Add tests for mapping, public loads without private requests, failed links and hidden write controls.
- [x] Implement copy/create/revoke dialog, expiry choices, public disclosure and artifact list. Disable create during active turn; always allow existing link management.
- [x] Implement paged shared chat and lazily loaded trace/span details, public images and file downloads, no SSE or editor mount.
- [x] Run targeted Vitest and TypeScript/build checks.

### Task 4: Files, redaction, wiring and integration

Owner: root. Files: `share/SessionShareFileService.java`, `share/SessionShareSanitizer.java`, public file controller, `SessionShareModule`, ServerApp/module/schema registration, public auth allowlist, transport headers/log masking and corresponding tests.

- [x] Capture only owned artifacts and attachment references visible in covered messages; use stable manifest IDs and never accept arbitrary fetch paths from anonymous callers.
- [x] Rewrite known platform URLs, proxy file bytes with no-store, block active content from inline execution and bound payload sizes.
- [x] Redact structured credentials recursively including nested JSON and URL secrets, retaining complete useful trace data.
- [x] Register stores before services, collections before binding, routes and auth exemption, request headers and no token logging.
- [x] Run targeted backend suite, frontend tests/typecheck/build and git diff checks. Independently review the combined diff; fix material findings before delivery.

## Execution Ledger

- 2026-11-13: User said continue after reviewing design; implementation performed using disjoint ownership for parallel work. No additional process confirmation needed.
- Design choice: include normal subagent spans in the authorized trace; do not expand into separate child traces. References, not durable snapshots.
- Storage, backend service, frontend and root integration implemented. Independent backend/frontend reviews completed; all material findings fixed and scoped re-reviews passed.
- Final backend regression: 82 tests passed, including an actual core-ng HTTP lifecycle and isolated PostgreSQL. Frontend full suite: 270 passed, plus 11 scoped tests after the final image rendering change; TypeScript and production build passed.
- Main/API/Test Checkstyle passed. HTTP test-only style refactor retained all assertions and its isolated rerun passed. No Git commit or deployment.
