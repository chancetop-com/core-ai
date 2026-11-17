# Hello Plugin (example)

A minimal desktop plugin used by the plugin-system tests and for manual smoke checks.

It declares only supported components:

- `skills/hello-plugin/SKILL.md` — injected into the local engine after the user grants
  `engine.skills`.
- A read-only dock panel rendered by the host from declarative blocks.
- Declarative settings (text / number / boolean) stored in the plugin store.
- A composer action that inserts text; it cannot send a message.

Install it from **Settings → Plugins → Install folder…** and pick this directory. The package is
installed disabled; open **Review permissions…** to grant its capabilities, then enable it.

Hook, MCP, command, agent, LSP and scripted `view` components are intentionally absent: this build
reports them as unsupported and never executes them.
