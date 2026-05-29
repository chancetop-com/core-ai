# Unified CLI Entry in Server Web UI

## 1. Goals

Currently, the server web UI (`core-ai-server`) and the CLI serve mode (`core-ai-cli --serve`) share the same frontend SPA but run as **separate browser tabs** on different origins. Users who want local CLI features must manually start the CLI and open a new tab at `localhost:9527`.

This design unifies the experience: **the server web UI becomes the single entry point**, with a CLI section that lets users download, launch, and use the CLI — all within the same SPA, without leaving the page or opening a new tab.

- **Single-page UX**: CLI features render inside the server SPA at `/cli/*` routes
- **Zero friction launch**: `core-ai://` custom protocol starts CLI headless in one click
- **Cross-origin API**: frontend fetches CLI APIs at `localhost:9527` via CORS
- **Minimal CLI changes**: only protocol registration commands added to CLI

## 2. Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                    core-ai-server (:8080)                     │
│  ┌────────────────────────────────────────────────────────┐  │
│  │              Single SPA (same-page navigation)          │  │
│  │                                                         │  │
│  │   Server section          CLI section                   │  │
│  │   /chat                   /cli ── (launch + download)   │  │
│  │   /traces                 /cli/chat                     │  │
│  │   /agents                 /cli/agents                   │  │
│  │   /skills                 /cli/skills                   │  │
│  │   ...                     /cli/tools                    │  │
│  │                                                         │  │
│  │   fetch('/api/...')       fetch('http://localhost:9527/ │
│  │      ▲ same-origin           ▲ cross-origin (CORS)      │
│  └──────┼──────────────────────────┼───────────────────────┘  │
└─────────┼──────────────────────────┼──────────────────────────┘
          │                          │
          ▼                          ▼
   core-ai-server              core-ai-cli --serve --headless
   (MongoDB, auth, ...)        (local files, no auth)
```

## 3. Navigation Structure

```
📦 CLI                              → /cli
  ├── 💬 Chat                      → /cli/chat
  ├── 🤖 Agents                    → /cli/agents
  ├── ✨ Skills                    → /cli/skills
  └── 🔧 Tools                     → /cli/tools
```

The CLI nav item is always visible (`show: true`), independent of server capabilities.

## 4. Route Design

| Route | Page | Backend API | Notes |
|-------|------|-------------|-------|
| `/cli` | `CliLauncher` | none (static) | Launch button + install guide |
| `/cli/chat` | `<Chat>` (reused) | `localhost:9527` | Wraps Chat with cliApi/cliSessionApi |
| `/cli/agents` | `<AgentList>` (reused) | `localhost:9527` | Wraps AgentList with cliApi |
| `/cli/skills` | `<SkillList>` (reused) | `localhost:9527` | Wraps SkillList with cliApi |
| `/cli/tools` | `<BuiltinTools>` (reused) | `localhost:9527` | Wraps BuiltinTools with cliApi |

### SPA Fallback Routes (Server)

```java
// ServerModule.java — registerStaticFiles()
"/cli", "/cli/chat", "/cli/agents", "/cli/skills", "/cli/tools"
```

## 5. `/cli` Launch Page

```
┌──────────────────────────────────────────┐
│  🚀  Launch CLI Web UI                    │
│  [Start] button  →  core-ai://serve      │
│                                           │
│  If nothing happens, run manually:        │
│  $ core-ai-cli --serve --port 9527        │
│     --headless                            │
│  ────────────────────────────────────    │
│  📥  Installation Guide                    │
│  macOS:   brew install core-ai            │
│  Windows: download .exe from releases     │
│  Linux:   curl ... | bash                 │
│                                           │
│  After install, register protocol:        │
│  $ core-ai-cli --register-protocol        │
└──────────────────────────────────────────┘
```

No health check polling — the page is static. Users click the button and navigate to `/cli/chat` directly.

## 6. Custom Protocol: `core-ai://`

### URL Scheme

```
core-ai://serve?port=9527
```

### Behavior

When the browser navigates to `core-ai://serve`, the OS invokes the registered handler:

```
core-ai-cli --serve --port 9527 --headless
```

The CLI starts its Undertow API server on the given port without opening a browser window.

### Protocol Registration

| Platform | Mechanism |
|----------|-----------|
| Windows | Registry key `HKEY_CLASSES_ROOT\core-ai\shell\open\command` |
| macOS | Launch Services `.plist` in `~/Library/Application Support/core-ai/` |
| Linux | `.desktop` file with `x-scheme-handler/core-ai` MIME type |

CLI commands:

```
core-ai-cli --register-protocol      # register core-ai:// handler
core-ai-cli --unregister-protocol    # remove registration
```

Implementation: new `ProtocolRegistrar.java` in `core-ai-cli`.

## 7. API Layer Design

### Problem

The frontend API client (`src/api/client.ts`) uses `const BASE = ''` — all requests are same-origin. CLI pages need to call `http://localhost:9527` (cross-origin).

### Solution

Create parallel API modules for CLI, structurally identical to the server API modules:

```
src/api/client.ts          BASE = ''                   → api, adminApi
src/api/session.ts         BASE = ''                   → sessionApi
src/api/cli-client.ts      BASE = 'http://localhost:9527' → cliApi
src/api/cli-session.ts     BASE = 'http://localhost:9527' → cliSessionApi
```

CLI pages import `cliApi`/`cliSessionApi` and pass them as props to the shared page components. Existing pages default to `api`/`sessionApi` when no props are provided.

### Auth

CLI mode has `authRequired = false`. The CLI API client does NOT attach `Authorization` headers. The server's auth token is not leaked to cross-origin requests.

### CORS

The CLI A2AServer already wraps all responses with:

```java
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
Access-Control-Allow-Headers: Content-Type, Accept, Authorization, ...
```

No changes needed.

### CLI API Surface

The CLI serve mode supports these endpoints (subset of server APIs):

| Endpoint | Method | Handler |
|----------|--------|---------|
| `/api/capabilities` | GET | `CapabilitiesHandler` |
| `/api/sessions` | POST | `ChatSessionCreateHandler` |
| `/api/sessions/{id}` | DELETE | `ChatSessionActionHandler` |
| `/api/sessions/{id}/history` | GET | `ChatSessionActionHandler` |
| `/api/sessions/{id}/messages` | POST | `ChatSessionActionHandler` |
| `/api/sessions/{id}/approve` | POST | `ChatSessionActionHandler` |
| `/api/sessions/{id}/cancel` | POST | `ChatSessionActionHandler` |
| `/api/sessions/{id}/tools` | POST | `ChatSessionActionHandler` |
| `/api/sessions/{id}/skills` | POST | `ChatSessionActionHandler` |
| `/api/sessions/events` | PUT | `ChatSessionSSEHandler` |
| `/api/chat/sessions` | GET | `ChatSessionsHandler` |
| `/api/agents` | GET | `LocalAgentHandler` |
| `/api/agents/{id}` | GET | `LocalAgentHandler` |
| `/api/tools` | GET | `LocalToolHandler` |
| `/api/skills` | GET | `LocalSkillHandler` |

## 8. Page Component Adaptation

Existing page components must accept an optional API client prop so they work with both backends:

```typescript
// Chat.tsx
interface ChatProps {
  apiClient?: typeof api;
  sessionClient?: typeof sessionApi;
}

// AgentList.tsx, SkillList.tsx, BuiltinTools.tsx
interface Props {
  apiClient?: typeof api;
}
```

CLI wrapper pages:

```typescript
// pages/cli/CliChat.tsx
import { cliApi } from '../../api/cli-client';
import { cliSessionApi } from '../../api/cli-session';
import Chat from '../chat/Chat';

export default function CliChat() {
  return <Chat apiClient={cliApi} sessionClient={cliSessionApi} />;
}
```

Other wrapper pages follow the same pattern.

## 9. File Changes Summary

### Frontend (core-ai-frontend)

| File | Change |
|------|--------|
| `src/components/Layout.tsx` | Add CLI navItem with children |
| `src/App.tsx` | Add `/cli`, `/cli/chat`, `/cli/agents`, `/cli/skills`, `/cli/tools` routes |
| `src/api/cli-client.ts` | **New** — API client with `BASE = 'http://localhost:9527'` |
| `src/api/cli-session.ts` | **New** — Session API with `BASE = 'http://localhost:9527'` |
| `src/pages/cli/CliLauncher.tsx` | **New** — Launch button + install guide |
| `src/pages/cli/CliChat.tsx` | **New** — wrapper injecting cliApi/cliSessionApi |
| `src/pages/cli/CliAgents.tsx` | **New** — wrapper injecting cliApi |
| `src/pages/cli/CliSkills.tsx` | **New** — wrapper injecting cliApi |
| `src/pages/cli/CliTools.tsx` | **New** — wrapper injecting cliApi |
| `src/pages/chat/Chat.tsx` | Accept optional `apiClient` / `sessionClient` props |
| `src/pages/agents/AgentList.tsx` | Accept optional `apiClient` prop |
| `src/pages/skills/SkillList.tsx` | Accept optional `apiClient` prop |
| `src/pages/tools/BuiltinTools.tsx` | Accept optional `apiClient` prop |

### Server (core-ai-server)

| File | Change |
|------|--------|
| `src/main/java/ai/core/server/ServerModule.java` | Add SPA fallback routes for `/cli`, `/cli/chat`, `/cli/agents`, `/cli/skills`, `/cli/tools` |

### CLI (core-ai-cli)

| File | Change |
|------|--------|
| `src/main/java/Main.java` | Add `--register-protocol` and `--unregister-protocol` options |
| `src/main/java/ai/core/cli/ProtocolRegistrar.java` | **New** — cross-platform protocol registration |

## 10. Out of Scope

- Health check polling (the launch page stays static)
- TUI launch from browser (browsers cannot embed native terminals without xterm.js + PTY bridge)
- Two-way state sync between server and CLI sections (they are independent) 
