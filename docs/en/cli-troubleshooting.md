# CLI Troubleshooting

Record OS, architecture, `core-ai-cli --version`, relevant `--help` and a sanitized error. These checks follow source and shell behavior. Windows execution, installation, upgrade and permission changes were not performed.

## Windows {#windows}

| Symptom | Check | Resolution |
| --- | --- | --- |
| Command not found | `Get-Command core-ai-cli -All`, `where.exe core-ai-cli` | Put the user bin containing `core-ai-cli.exe` on PATH, then reopen terminal |
| Still an old version | `Get-Command core-ai-cli -All` | Check the new executable by full path and adjust old user PATH entries |
| Spaces in paths | Quoting and call operator | Use `& 'C:\path with spaces\core-ai-cli.exe' --version` in PowerShell |
| JSON is changed | Shell quoting | Use UTF-8 `args.json` with `--args-file args.json` |
| Config not found | Home, extension and overrides | Check `%USERPROFILE%\.core-ai\agent.properties`; avoid `.properties.txt` |
| Hook cannot find sh | `Get-Command sh` | Hooks use `sh -c`; disable the hook initially or provide an approved sh environment |
| Upgrade cannot replace exe | Running sessions | Exit normally, manually replace from an approved release, then recheck version |

PowerShell uses `./core-ai-cli.exe` for the current directory. Use `$env:NAME` for environment variables and `$LASTEXITCODE` for exit status. Bash `export` and backslash continuation do not copy directly to PowerShell. Built-in shell detection prefers `pwsh.exe`, then `powershell.exe`, then `cmd.exe`.

PowerShell 5.1 and 7 differ in default encoding. JSON should be UTF-8. Java Properties use `Properties.load(InputStream)` rules; Unicode escapes and correctly escaped paths may be needed. [Full Windows steps (Chinese)](/cn/manual/#_20-windows-常见问题与解决步骤).

## macOS {#macos}

| Symptom | Check | Resolution |
| --- | --- | --- |
| command not found | `command -v core-ai-cli`, `echo "$SHELL"` | Add user bin to the actual shell's PATH and reopen terminal |
| permission denied | `ls -l` on the target | Check the file you own and follow installation steps for its user executable bit |
| bad CPU type | `file` on target, `uname -m` | Choose an asset explicitly supporting your architecture |
| System blocks opening | Download source and release | Follow Apple's verification/open flow while retaining system protections |
| Version unchanged | `command -v`, `type -a core-ai-cli` | Check old PATH entries and test the new binary by full path |
| IDE differs from terminal | Executable, home and workspace | Specify actual paths and inspect workspace overrides |

Interactive zsh usually reads `~/.zshrc`; bash startup files depend on login mode. Upgrades can specify `--upgrade-dir`; upgrades were not executed. [Full macOS steps (Chinese)](/cn/manual/#_21-macos-常见问题与解决步骤).

## Both systems {#两个系统都适用}

- Login data is in user `.core-ai/auth.json`; workspace model configuration can override global settings.
- Hub codes 3 / 4 / 5 indicate authentication, authorization or a missing resource. For 6, inspect the existing task; 7 requires user input.
- Prefer argument files for JSON. Keep diagnostics sanitized.
- [Install and verify](cli.md#安装与确认) and the [full manual (Chinese)](/cn/manual/) separate instructions, expected results and actual evidence.
