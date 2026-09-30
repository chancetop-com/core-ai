# CLI 跨平台排障

先运行 `core-ai-cli --version` 和对应子命令 `--help`，记录系统、架构、版本和脱敏错误。本页依据源码和系统 shell 行为；Windows 尚未实机验证，安装、升级与权限调整未在本次执行。

## Windows

| 现象 | 检查 | 处理与预期 |
| --- | --- | --- |
| 找不到命令 | `Get-Command core-ai-cli -All`、`where.exe core-ai-cli` | 确认 `core-ai-cli.exe` 所在用户 bin 已加入 PATH，重开终端 |
| 仍是旧版本 | `Get-Command core-ai-cli -All` | 用新 exe 完整路径检查版本，调整自己的旧 PATH 项 |
| 路径含空格 | 引号和调用运算符 | PowerShell 用 `& 'C:\path with spaces\core-ai-cli.exe' --version` |
| JSON 被改写 | shell 引号与输入文件 | 使用 UTF-8 `args.json` 和 `--args-file args.json` |
| 配置未读取 | home、扩展名、覆盖规则 | 检查 `%USERPROFILE%\.core-ai\agent.properties`，避免 `.properties.txt` |
| hook 缺少 sh | `Get-Command sh` | hooks 当前用 `sh -c`，属源码推断；先停用或由环境提供受控 sh |
| 升级无法替换 exe | 是否有运行中的会话 | 正常退出后用批准的发布包手动替换，再检查版本 |

PowerShell 当前目录程序用 `./core-ai-cli.exe`，cmd.exe 规则不同。Bash `export` 与反斜杠续行不能直接复制到 PowerShell；环境变量用 `$env:NAME`，退出码看 `$LASTEXITCODE`。内置 shell 检测是 `pwsh.exe` → `powershell.exe` → `cmd.exe`，`run_bash_command` 工具名不意味着 Windows 必须安装 Bash。

PowerShell 5.1 和 7 默认编码不同。JSON 建议 UTF-8；Java Properties 按 `Properties.load(InputStream)` 解析，中文可用 `\uXXXX`，路径可用 `/` 或正确转义的反斜杠。[完整 Windows 步骤](manual/#_20-windows-常见问题与解决步骤)。

## macOS

| 现象 | 检查 | 处理与预期 |
| --- | --- | --- |
| command not found | `command -v core-ai-cli`、`echo "$SHELL"` | 用户 bin 加入实际 shell 的 PATH，重开终端 |
| permission denied | `ls -l` 目标文件 | 确认自己拥有的文件，按安装步骤设置该文件用户执行位 |
| bad CPU type | `file` 目标文件、`uname -m` | 选择明确支持本机架构的发布资产 |
| 系统阻止打开 | 下载来源与发布信息 | 按 Apple 官方流程核对后由用户打开，保留系统保护 |
| 版本未变化 | `command -v`、`type -a core-ai-cli` | 检查旧 PATH，用新二进制完整路径复测 |
| IDE 配置不同 | 可执行路径、home、workspace | 指定实际程序与工作区，检查覆盖配置 |

zsh 交互终端通常读取 `~/.zshrc`，bash 按实际登录方式选择启动文件。升级可指定 `--upgrade-dir`，本次没有执行升级。[完整 macOS 步骤](manual/#_21-macos-常见问题与解决步骤)。

## 两个系统都适用

- 登录数据在用户 `.core-ai/auth.json`，模型配置在 `.core-ai/agent.properties`，工作区同名配置覆盖全局。见[路径与优先级](manual/#_15-配置和资源路径)。
- Hub 退出码 3 / 4 / 5 分别排查认证、授权与资源名称；6 先查任务状态，7 处理用户输入。见[退出码](manual/#_18-会话数据集与退出码)。
- JSON 参数优先使用文件；共享诊断保留版本、退出码和脱敏错误。
- [安装步骤](cli.md#安装与确认)和[完整手册](manual/)提供操作、预期结果与验证范围。
