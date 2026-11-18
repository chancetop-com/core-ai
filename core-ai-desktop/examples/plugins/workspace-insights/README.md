# Workspace Insights example

A read-only P2d page that demonstrates the general host services:

- `workspace.summary()` for workspace-level counts
- `usage.summary()` for token/cost totals
- `sessions.list()` for session metadata
- `memory.list()` for memory files
- `plugin.storage` for a page-owned counter
- `events.subscribe()` + `events.on()` for redacted live events

Install it from **Plugins → Install folder**, enable it, then grant all seven declared capabilities. It
does not create conversations, read message bodies, or receive tool arguments/results.
