# My Home

A plugin page for the desktop app:

- **Reports** — your dataset reports from core-ai-server (`report.view`), with a compact
  METRIC / TABLE / BAR / LINE preview.
- **Todos** — your todo list (`dashboard.view`), with add / complete / delete.
- **Daily summary** — a server job turns yesterday's chat sessions into todos once a day
  (Asia/Shanghai), and the page shows the latest run result.

## Install

The package is a regular desktop plugin. In the app, install this folder, enable it, then
grant:

```
ui.pages, reports.read, todos.read, todos.write, plugin.storage
```

## Security notes

- The page runs in an isolated `WebContentsView` with no Node and no network.
- `reports.read` / `todos.read` / `todos.write` are served by the main process with the saved
  login; the API key never reaches the page and the page cannot choose a server URL.
- The page caches only a small snapshot (report titles and todo text) in plugin storage, used
  when the server is unreachable.
