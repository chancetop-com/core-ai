# CLI 上手

`core-ai-cli` 提供本地 Agent、编辑器 ACP 模式和 Server Hub 命令。可以独立连接模型，也可以登录 Server 使用代理模型和已授权资源。

## 安装与确认

从[官方 Releases](https://github.com/chancetop-com/core-ai/releases)选择适合系统和处理器架构的版本。资产名为 `core-ai-cli-darwin.tar.gz` 与 `core-ai-cli-windows.zip`，包内文件为 `core-ai-cli-darwin` 与 `core-ai-cli-windows.exe`。安装到自己的用户 bin 并加入用户 PATH。

- [macOS 安装](manual/#_10-macos-安装与检查)：解压、核对架构、用户执行位和 shell PATH。
- [Windows 安装](manual/#_11-windows-安装与检查)：PowerShell 解压、exe 命名、用户 PATH 和空格路径。
- [跨平台排障](cli-troubleshooting.md)：旧版本覆盖、JSON 引号、编码及 hooks。

原生发布包自带运行时，源码构建需要 Java 25。发布包安装与 Windows 实机未在本次验证。

```sh
core-ai-cli --version
core-ai-cli --help
core-ai-cli mcp call --help
```

预期显示所选版本和参数。图中是本次初始核对的 2.0.19 Java 源码输出，后续 2.0.21 的 8 组 help/version 也全部通过。截图来自真实 stdout 的浏览器展示，未执行原生发布包。

![CLI help，本次真实 stdout 截图](manual/screenshots/cli-source-help.jpg)

## 连接 Server

替换成自己的测试服务地址：

```sh
core-ai-cli --login=https://core-ai.example.com
```

按交互完成登录。登录数据在 `~/.core-ai/auth.json`（Windows 通常在用户目录），保留在自己的机器上。先进行只读发现：

```sh
core-ai-cli catalog --json
core-ai-cli mcp search echo --json
```

把搜索结果的实际 `server/tool` 传给 `mcp describe`，按返回 schema 创建参数文件，最后调用 `mcp call ... --args-file args.json --json`。`demo/echo` 是离线样例名，服务上可能不存在。这些联网步骤已核对源码，未在本次执行。

根命令没有 `--server`，Hub 子命令仍支持它。精确参数位置看对应 help；共享截图和命令历史中不要包含实际 key。

## 独立模型与工作区

独立模型配置在 `~/.core-ai/agent.properties`，工作区 `.core-ai/agent.properties` 可覆盖全局。provider 属性与初始化条件见[模型配置](manual/#_13-登录-server-或独立配置模型)。

```sh
core-ai-cli --workspace /path/to/my-project
core-ai-cli --continue
core-ai-cli --resume
```

这些命令进入 Agent 使用流程，可能调用模型；先配置自己的测试环境。交互用 `/help` 查看命令，当前 `/init` 创建 `.core-ai/AGENTS.md`。见[配置和资源路径](manual/#_15-配置和资源路径)。

## 更多操作

[Hub 发现与调用](manual/#_16-hub-先搜索再描述再调用) · [Agent / Skill / API / 报告](manual/#_17-agent-skill-api-与报告) · [数据集与退出码](manual/#_18-会话数据集与退出码) · [本地 MCP 与 hooks](manual/#_19-本地-mcp-skills-与-hooks)
