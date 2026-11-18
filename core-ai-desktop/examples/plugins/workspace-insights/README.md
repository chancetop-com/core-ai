# Workspace Insights example

A read-only P2d page that demonstrates the general host services:

- `workspace.summary()` for workspace-level counts
- `usage.summary()` for token/cost totals
- `sessions.list()` for session metadata
- `ui.slots` actions in the sidebar footer, composer dock, turn tail and message actions
- `ui.renderers` + `artifacts.read` for a text artifact opened from the Artifacts panel
- `tools.register` read-only tools (`list_artifacts`, `list_memory`)
- `terminal.exec` fixed operation `git.status` (no shell, no page arguments)
- `hooks.guard` denies direct `shell`/`bash` tool calls in this workspace
- `sandbox.provider` reserves a descriptor; no sandbox execution is available yet
- `memory.list()` for memory files
- `memory.write()` for plugin notes under `knowledge/`
- `plugin.storage` for a page-owned counter
- `events.subscribe()` + `events.on()` for redacted live events
- `notify.send()` for the configured notification target

Install it from **Plugins → Install folder**, enable it, then grant all twelve declared capabilities. It
does not create conversations, read message bodies, or receive tool arguments/results.
