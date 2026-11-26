# Dev — Workspace Settings UI（2026-11-26）

## 结论

Settings 页新增用户级 Workspaces 管理（2.0.397）：列表显示 name/default、agent、status、size、
idle sleep、last used；支持 wake / sleep / reset / delete（reset、delete 需输入 Workspace 名称确认）、
调整 idle sleep、刷新与过渡状态轮询；功能未启用时显示说明而不是报错。

## 环境

- 镜像 `chancetop/core-ai-server:2.0.397`（CI `Build Server` run `45783049252`，commit `09b114ad4`），
  deployment 2/2 Ready。
- 早期镜像：2.0.394 首次发布页面；2.0.395/2.0.396 收紧表格布局。2.0.396 的 Build Server 因共享索引里
  误带入另一个 workstream 的测试文件而失败（该文件对应的主代码尚未提交），2.0.397 移除该测试后重新
  发布；工作树中原测试文件保留，供原 workstream 与主代码一起提交。
- 无新迁移、无服务端变更；只改前端（`src/api/client.ts`、`src/pages/settings/Workspaces.tsx`、
  `Settings.tsx`）。

## 页面能力

- 列表：name（含 default 标记）、agent、状态（Running / Waking / Sleeping / Preparing / Volume error /
  Wake error）、size、idle sleep、last used。
- 动作：READY 时 Sleep，其余可 Wake（过渡状态禁用）；Reset（确认名称，卷被替换）；Delete（确认名称，
  软删除，卷保留 7 天）。错误信息显示在状态下方。
- idle sleep：5/15/30/60/120 分钟下拉，改动即保存；保留自定义值。
- 过渡状态（volume/runtime PROVISIONING）每 5s 轮询一次；页面在后台标签页时不轮询。
- 功能关闭时显示 “Persistent workspaces are not enabled on this deployment.”。

## 验证

### 页面与交互（dev，959px 宽浏览器面板）

- 两行 Workspace（`default`、`default-agent-builder`）均为 Sleeping、32 GB、15 min、last used 正常；
  Wake / Reset / Delete 三个动作按钮在 959px 宽度内全部可见（截图
  `2026-11-26-dev-workspace-settings-ui-list.png`）。
- 把 `default` 的 idle sleep 从 15 改成 30：页面显示
  `Idle sleep for "default" set to 30 minutes.`，刷新后仍为 30（服务端持久化）；随后通过 API 恢复 15，
  Refresh 后 UI 显示 15。
- 点击 `default-agent-builder` 的 Delete：对话框展示 7 天保留说明与名称确认输入；未输入时
  `Delete workspace` 禁用，Cancel 关闭（截图 `2026-11-26-dev-workspace-settings-ui-dialog.png`）。
  未实际删除任何 Workspace。

### 自动化

- `Workspaces.test.tsx` 3/3：状态/尺寸渲染、删除需精确名称、功能关闭提示。
- `npm run build`（tsc + vite）通过。
- 全量 frontend 测试 324/326；2 个失败为既有负载敏感用例（`SharedSession` 超时、
  `App.sandbox-settings` 权限用例），单独运行时均通过，与本次改动无关。

## 残留

- 未做：创建 Workspace、详情页（env/脚本/事件/用量）、会话头部 Workspace chip、usage/审计页面、
  管理员视图、已删除 Workspace 的 Undelete 入口（列表接口不返回已删除行）。
- 表格使用 `table-fixed` + `colgroup`；更窄的窗口仍会出现横向滚动，正常桌面宽度可完整显示。
