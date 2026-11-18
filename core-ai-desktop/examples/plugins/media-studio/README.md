# Media Studio example

A plugin page that lists generated images/videos and starts image or video generation without creating a
conversation. It exercises the P2c surface:

- `contributes.pages` in `.claude-plugin/plugin.json`
- `window.coreaiPlugin.context()`
- `window.coreaiPlugin.assets.list()`
- `window.coreaiPlugin.media.models()`
- `window.coreaiPlugin.media.generate()` and `media.job()`

Install it from **Plugins → Install folder**, then enable it and grant:

- `ui.pages`
- `media.assets.read`
- `media.models.read`
- `media.generate`

The page runs in an isolated `WebContentsView`: no Node, no network, and no direct engine RPC. The
desktop re-checks the plugin grant before every bridge call, so revoking `media.generate` stops the next
generation immediately.

Generated files are written under `.core-ai/media/` and appear in the existing Artifacts page as well.
Image generation is synchronous; video generation returns a job id and the page polls `media.job()`.
