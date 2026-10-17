# Skills 技能包

本目录收纳随仓库发布的 skill（技能包）。每个 skill 以 `SKILL.md` 为入口、`references/` 等为补充资源，正文同时面向人和 agent 编写（均为英文），可以直接安装到 Claude Code、Codex 等工具或 core-ai-cli 使用。

## 下载与安装

站点每次部署都会把各 skill 打包成 zip（与仓库内容同步）；解压后是 `<name>/SKILL.md` 加全部资源：

`https://chancetop-com.github.io/core-ai/skills/<name>.zip`

站点同时生成 `manifest.json`，列出每个包的版本、来源、许可、文件数和内容摘要（tree digest），便于脚本核对：

`https://chancetop-com.github.io/core-ai/skills/manifest.json`

| 工具 | 解压到 |
| --- | --- |
| Claude Code | `~/.claude/skills/`（个人级）或项目内 `.claude/skills/` |
| Codex | `~/.agents/skills/`（个人级）或项目内 `.agents/skills/`；`~/.codex/skills/`（内置 `$skill-installer` 的默认位置）也可用 |
| core-ai-cli | `~/.core-ai/skills/`（用户级）或工作区 `.core-ai/skills/` |
| 其他 agent（Cursor、OpenClaw 等） | 对应工具的 skills 目录 |

以 `core-ai-cli-manual` 为例：

```sh
curl -LO https://chancetop-com.github.io/core-ai/skills/core-ai-cli-manual.zip
unzip core-ai-cli-manual.zip -d ~/.claude/skills/
```

```powershell
# Windows PowerShell
Invoke-WebRequest https://chancetop-com.github.io/core-ai/skills/core-ai-cli-manual.zip -OutFile core-ai-cli-manual.zip
Expand-Archive core-ai-cli-manual.zip -DestinationPath "$env:USERPROFILE\.claude\skills"
```

**Codex**：让 Codex 的 `$skill-installer` 直接从 GitHub 安装（在 Codex 会话里执行）：

```text
$skill-installer install https://github.com/chancetop-com/core-ai/tree/master/docs/skills/core-ai-cli-manual
```

**core-ai-cli**：登录 core-ai-server 后从 skill hub 安装：

```sh
core-ai-cli skill pull <namespace>/core-ai-cli-manual --workspace
```

未登录或没有 hub 时，用上面的 zip 解压到 `~/.core-ai/skills/` 即可。

## core-ai-desktop 内置技能

core-ai-desktop 的安装包内置以下四份内容，并在首次启动时安装到 `~/.core-ai/skills/`；**不需要登录，也不需要联网**。公开 zip 与安装包内的副本同源，供未登录的 CLI 用户和其他 agent 手工安装。

| 技能 | 能力 | desktop 默认策略 | 运行时依赖 |
| --- | --- | --- | --- |
| [core-ai-cli-manual](/skills/core-ai-cli-manual/SKILL) | Hub / CLI 操作手册 | 总是安装 | 无（desktop 自带 CLI） |
| [browser-use](/skills/browser-use/SKILL) | 驱动内嵌浏览器 | 默认安装 | `browser-use` CLI |
| [gui-automation](/skills/gui-automation/SKILL) | 操作本机桌面（Computer use） | 启用 Computer use 后安装 | `cua` CLI + 本机服务 |
| [ocg](/skills/ocg/SKILL) | QQ / 微信 / Telegram / DingTalk / Discord | 检测到 `ocg` 或手动启用后安装 | `ocg` daemon |

desktop 只覆盖自己安装且未被本地修改的副本；同名的手工安装或 hub 拉取副本不会被覆盖。Skills 面板可以把内置副本恢复到安装包版本。

## core-ai-cli-manual — core-ai-cli 操作手册

覆盖安装与升级、`agent.properties` 配置、运行模式（交互 / `--prompt` 无头 / ACP）、Hub 子命令、hooks、本地 MCP、记忆与 LLM provider。

| 页面 | 内容 |
|------|------|
| [手册主页](/skills/core-ai-cli-manual/SKILL) | 安装与升级、配置文件清单、快速开始、功能开关总览 |
| [Hub 子命令与沙箱 SDK](/skills/core-ai-cli-manual/references/hub) | search → describe → call 约定、认证优先级、退出码；`core_ai_session` Python SDK 与沙箱 Hub |
| [全部配置项](/skills/core-ai-cli-manual/references/agent-properties) | `agent.properties` 每个键的默认值与说明 |
| [CLI 模式与命令](/skills/core-ai-cli-manual/references/cli-modes) | 模式与参数、斜杠命令、自定义 agent |
| [hooks](/skills/core-ai-cli-manual/references/hooks) | `hooks.json` 格式、事件与环境变量 |
| [本地 MCP](/skills/core-ai-cli-manual/references/mcp) | stdio 与 HTTP 本地 MCP 服务器配置 |
| [记忆系统](/skills/core-ai-cli-manual/references/memory) | 架构、属性与模式 |
| [LLM Providers](/skills/core-ai-cli-manual/references/providers) | Server 登录、LiteLLM、OpenAI、DeepSeek、OpenRouter、Azure |

下载：[core-ai-cli-manual.zip](/skills/core-ai-cli-manual.zip) · 仓库位置：[docs/skills/core-ai-cli-manual](https://github.com/chancetop-com/core-ai/tree/master/docs/skills/core-ai-cli-manual) · 来源与许可：[SOURCE.md](/skills/core-ai-cli-manual/SOURCE)

## browser-use — 浏览器自动化

通过 CDP 直接控制浏览器：打开页面、点击、输入、读取 DOM/可访问性树、截图，并包含 anti-bot 处理（silent mode、标签页自关闭后的恢复）。在 core-ai-desktop 中它驱动的是应用内嵌的浏览器面板，而不是另开 Chrome/Edge；`BU_CDP_URL` 由桌面端注入。

- 需要 `browser-use` 可执行文件（官方推荐 `uv tool install browser-use`）。
- desktop 的 Skills / Settings 面板会探测并给出安装入口。
- 下载：[browser-use.zip](/skills/browser-use.zip) · [SKILL](/skills/browser-use/SKILL) · [SOURCE](/skills/browser-use/SOURCE)

## gui-automation — Computer use

通过 CUA CLI 操作真实 GUI：截图、点击、输入、拖拽、窗口管理，适合 shell 和浏览器都覆盖不到的原生应用。core-ai-desktop 的 **Settings → Computer use** 会一次性完成 CUA CLI、桌面授权、本机服务和该技能的安装；启用后 agent 才会被告知桌面可用。

- 需要 `cua` CLI；上游固定 commit 见 [SOURCE.md](/skills/gui-automation/SOURCE)。
- 下载：[gui-automation.zip](/skills/gui-automation.zip) · [SKILL](/skills/gui-automation/SKILL) · [命令参考](/skills/gui-automation/references/command-reference)

## ocg — IM 渠道（QQ / 微信 / Telegram / DingTalk / Discord）

通过本地 OpenClaw Channel Gateway（OCG）daemon 以“邮箱模式”收发 IM 消息：`ocg inbox wait` 拉取，`ocg reply` 回复。适合让 agent 值守某个渠道或处理跨平台消息。

- 首次安装：`npm install -g openclaw-channel-gateway`；微信还需要 `@tencent-weixin/openclaw-weixin` 插件和扫码登录。
- 凭据、渠道配置与扫码都在用户本机完成；desktop 不保存这些信息。
- 下载：[ocg.zip](/skills/ocg.zip) · [SKILL](/skills/ocg/SKILL) · [SOURCE](/skills/ocg/SOURCE)

## 其他技能包

- [core-ai-session-sample](/skills/core-ai-session-sample/SKILL) — `core_ai_session` Python SDK 的完整可运行示例：一个脚本同时运行在本机（经 CLI）与沙箱（会话 Hub）。下载：[core-ai-session-sample.zip](/skills/core-ai-session-sample.zip)
- [session-probe](/skills/session-probe/SKILL) — 探测当前会话的可达范围：身份、可调用工具、数据集，以及一次免手写 CLI 语法的调用。下载：[session-probe.zip](/skills/session-probe.zip)
