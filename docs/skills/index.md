# Skills 技能包

本目录收纳随仓库发布的 skill（技能包）。每个 skill 以 `SKILL.md` 为入口、`references/` 等为补充资源，正文同时面向人和 agent 编写（均为英文），可以直接安装到 Claude Code、Codex 等工具或 core-ai-cli 使用。

## 下载与安装

站点每次部署都会把各 skill 打包成 zip（与仓库内容同步）；解压后是 `<name>/SKILL.md` 加全部资源：

`https://chancetop-com.github.io/core-ai/skills/<name>.zip`

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

下载：[core-ai-cli-manual.zip](/skills/core-ai-cli-manual.zip) · 仓库位置：[docs/skills/core-ai-cli-manual](https://github.com/chancetop-com/core-ai/tree/master/docs/skills/core-ai-cli-manual)

## 其他技能包

- [core-ai-session-sample](/skills/core-ai-session-sample/SKILL) — `core_ai_session` Python SDK 的完整可运行示例：一个脚本同时运行在本机（经 CLI）与沙箱（会话 Hub）。下载：[core-ai-session-sample.zip](/skills/core-ai-session-sample.zip)
- [session-probe](/skills/session-probe/SKILL) — 探测当前会话的可达范围：身份、可调用工具、数据集，以及一次免手写 CLI 语法的调用。下载：[session-probe.zip](/skills/session-probe.zip)
