# core-ai-plugin SDK

Types, a JSON Schema and a local validator for core-ai desktop plugins. It is intentionally small:
the desktop is the enforcing validator and the server registry only stores metadata plus a pinned
package URL and digest.

## Install

From this monorepo (once published, use the npm package name):

```json
{
  "devDependencies": {
    "core-ai-plugin": "file:../../sdk/core-ai-plugin"
  }
}
```

## A plugin package

```text
hello-plugin/
  .claude-plugin/plugin.json
  skills/hello-plugin/SKILL.md
```

`.claude-plugin/plugin.json`:

```json
{
  "name": "Hello Plugin",
  "version": "1.0.0",
  "description": "Example plugin",
  "coreai": {
    "schemaVersion": 1,
    "id": "example.hello-plugin",
    "desktopApi": "^1.0",
    "components": [{ "kind": "skills", "path": "skills" }],
    "capabilities": {
      "required": ["engine.skills", "ui.composer"],
      "optional": []
    },
    "contributes": {
      "composer": [
        { "id": "greet", "title": "Insert greeting", "insertText": "Greet the user." }
      ]
    }
  }
}
```

`components` declares what the package contains. The current desktop only activates `skills`; other
components are reported as unsupported and are never executed. A component is not a permission —
`engine.skills` must also be in `capabilities.required` (or optional) and the user must grant it.

## Authoring API

```js
import { definePlugin, validateManifest } from 'core-ai-plugin';

const manifest = definePlugin({ /* ... */ });
const result = validateManifest(manifest);
if (!result.ok) console.error(result.errors);
```

`validateManifest` accepts an object or a JSON string, returns every error/warning, and never
executes package code. Warnings include scripted dock views (`contributes.dock[].view`), which the
current desktop reports as unsupported in favour of host-rendered `blocks`. A `pages` contribution
requires `id`, `title` and a package-relative `view`; the matching capability is `ui.pages`.

## Host services

A plugin page gets only `window.coreaiPlugin`. Besides `context`, `assets` and `media`, the current
page bridge exposes:

- `sessions.list({ limit?, cursor? })` with `sessions.read`
- `memory.list({ limit?, cursor? })` and `memory.read(path)` with `memory.read`
- `workspace.summary()` with `workspace.read` (counts are `null` for capabilities not granted)
- `usage.summary(range)` with `usage.read` (`all`, `7d`, `30d`; token/cost totals from transcripts)
- `storage.get/set/delete/keys` with `plugin.storage` (string values, 64 KiB per value)
- `events.subscribe(types)` and `events.on(callback)` with `events.read`
- `notify.send(text)` with `notification.send` (configured target; 5/minute per page, 2000 chars)

Events are allowlisted and redacted: `session.status`, `turn.completed`, `tool.started`,
`tool.finished`, `turn.failed`, `plan.updated`, `task.updated`, `tool.batch_started`, `agent.wait`
and `custom`. They never carry message text, tool arguments or tool results.

## Capabilities

| Capability | What it enables |
|---|---|
| `engine.skills` | injects the package's `skills/` root into the local agent after grant |
| `ui.dock` | host-rendered read-only dock panel |
| `ui.settings` | declarative settings fields stored in the plugin store |
| `ui.composer` | insert-only composer action |
| `ui.slots` | action-only host slots: sidebar footer, composer dock, turn tail and message actions |
| `ui.pages` | a package-local HTML page in an isolated `WebContentsView` |
| `media.assets.read` | lists generated images/videos and serves their local URLs to a page |
| `media.assets.write` | imports a reference image into the workspace media directory |
| `sessions.read` | lists session metadata for the current workspace |
| `memory.read` | lists and reads memory files in the workspace |
| `workspace.read` | reads a small workspace summary |
| `usage.read` | reads workspace token/cost totals from persisted transcripts |
| `plugin.storage` | namespaced string storage persisted with the plugin install |
| `events.read` | subscribes to an allowlist of redacted session events |
| `notification.send` | sends a bounded text message to the configured notification target |
| `media.models.read` | reads the configured image/video model catalog |
| `media.generate` | starts image/video generation through the engine's media service |

A page is declared as `contributes.pages` and must use a package-relative `view`. It runs without Node,
without network access, and receives only the fixed `window.coreaiPlugin` bridge (`context`, `assets`,
`media`); the desktop re-checks the plugin grant before every bridge call. TypeScript authors can import
the bridge types from `core-ai-plugin/ui`.

Packages install **disabled**. The user reviews capabilities, grants them, then enables the plugin.
A digest change requires a new grant.

## Server registry

The desktop can browse `GET /api/plugins` from a signed-in server and install a package only after
verifying its `sha256` against the registry entry. Publishing is a server-side operation
(`POST /api/plugins`, `plugin.manage`) and stores the manifest plus a `download_url`/`sha256`; the
registry does not rewrite package bytes.

## Versioning

- `coreai.schemaVersion` is the manifest contract (`1`).
- `desktopApi` follows the desktop extension API range (`^1.0` for this phase).
- `engineApi` is reserved for engine component compatibility.
- `package.json` version is independent of the manifest contract.
