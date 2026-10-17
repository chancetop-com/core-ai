# Desktop 内置技能设计：Hub、浏览器、电脑与 IM 渠道

> 状态：**Phase 1 + Phase 2 基础已落码**，2026-10-17。Phase 3（公开站点 manifest 与发布流程）随 `docs/skills` 一并提交。
> 目标版本：core-ai-desktop 下一版（引擎 2.0.x），并与 core-ai-cli 的 managed skill 机制对齐。

## 0. 一句话

把四个技能做成 desktop 的**内置能力包**：安装包内置、首次启动落到 `~/.core-ai/skills`、无登录无网络可用；同一份内容再发布到 `docs/skills`（github.io）给 CLI、其他 agent 和手工安装使用。**desktop 不以 github.io 作为运行时依赖**，github.io 只是公开镜像与发现入口。

四个技能的实际映射：

| 用户说法 | 技能名 | 能力 |
| --- | --- | --- |
| hub | `core-ai-cli-manual` | 通过 `core-ai-cli` 子命令访问 server Hub（mcp / skill / api-tool / agent） |
| browser-use | `browser-use` | 驱动 desktop 内嵌浏览器 |
| computer-use | `gui-automation` | 通过 CUA CLI 操作本机桌面 |
| ocg | `ocg` | QQ / 微信 / Telegram / DingTalk / Discord 渠道 |

`gui-automation` 是上游技能名，不建议改成 `computer-use`：CLI 的 computer-use prompt 注入、CuaManager 的安装路径、上游 MIT 来源都按这个名字对齐；UI 上显示为 **Computer use** 即可。

## 1. 目标与非目标

### 目标

- **无登录、只配置 provider 的 desktop** 首次启动后，agent 的技能列表里已经能看到 `core-ai-cli-manual` 和 `browser-use`；`~/.core-ai/skills` 下能看到对应目录；删掉后不联网也能恢复。
- 四个技能与 CLI **共用同一个技能根** `~/.core-ai/skills`，不在 desktop 里另建一套。
- **不覆盖**用户手工安装或从 hub 拉取的副本；内置副本可更新、可恢复、可卸载。
- 需要外部运行时的能力（CUA、OCG）不被“假装可用”：技能、运行时状态、启用开关三者一致。
- 同一份技能内容可公开分发、可审计、可固定版本；桌面端安装包与公开站点使用**同一份源文件**，避免双份维护。

### 非目标

- 不把 `browser-use`、`cua`、`ocg` 的运行时二进制打进安装包（体积、平台、许可都不合适）。
- 不在启动时访问 github.io 检查或下载技能；断网必须可用。
- 不自动从远端更新技能指令（技能本质是给 agent 的指令，属于供应链边界）。
- 不改变 `SkillLoader` / `SkillRegistry` 的加载协议；不改 workspace / plugin / user 的优先级模型。

## 2. 现状与代码事实

### 2.1 技能加载链路

- `CliAgent.buildSkillConfig()` 注册三个来源：workspace（priority 50）、plugin（75）、user（100）；数值越小优先级越高。
- `SkillLoader` 支持两级布局：`{root}/{name}/SKILL.md` 与 `{root}/{namespace}/{name}/SKILL.md`。
- `SkillRegistry` 按 `qualifiedName` 合并；同名但不同 namespace 的技能会同时存在。
- `SkillTool` 在构造时扫描一次技能并生成 tool description，**运行中新增/删除技能不会自动生效**，需要重启引擎（或至少重建 agent/tool）。

### 2.2 Desktop 的安装现状

- `ManagedSkillProvisioner.provision()` 只被交互式 `CliApp.start()` 调用；desktop 使用的 `core-ai-cli app-server` 走 `AppServerCommand → AppServerEngine → EngineBootstrap`，**不会调用它**。
- 因此 desktop 目前没有自动安装 `browser-use`；在已经长期使用 CLI/hub 的机器上，`core-ai-cli-manual` / `browser-use` / `ocg` 常见的是手工拉取的 `Stephen/<name>` 副本，而全新机器上则完全没有。
- Computer use 是唯一已有的内置安装：`CuaManager.enable()` 调 `installManagedSkill()`，把 `core-ai-desktop/skills/gui-automation` 复制到 `~/.core-ai/skills/gui-automation`，并写 `.core-ai-desktop.json` 标记（记录每个文件的 sha256）。
- `installManagedSkill()` 已经具备“只覆盖自己管理的、未本地修改的副本”的语义，可以直接泛化。
- CLI 的 `ManagedSkillProvisioner.installFromServer()` 在登录时会对**无 marker 的同名目录**使用 `force=true`，会覆盖 desktop 托管副本。这是必须修掉的冲突。
- `SkillInstaller`（hub pull 用）对无 `.skill-hub.json` 的目录默认拒绝覆盖，行为是正确的；只有 `ManagedSkillProvisioner` 的自动安装绕过了它。

### 2.3 公开站点现状

- `docs/skills/` 是技能源目录，`docs/skills/index.md` 是下载页。
- `docs/scripts/package_skills.py` 会把每个含 `SKILL.md` 的子目录打成 `dist/skills/<name>.zip`。
- `.github/workflows/docs-deploy.yml` 的顺序是：VitePress build → package:skills → check:links → deploy Pages。
- 目前只有 `core-ai-cli-manual`、`core-ai-session-sample`、`session-probe` 三个包；没有 manifest，也没有 browser/computer/ocg。
- 站点地址：`https://chancetop-com.github.io/core-ai/skills/`，zip 地址：`https://chancetop-com.github.io/core-ai/skills/<name>.zip`。

### 2.4 结论

desktop 的“内置技能”不是再加一个 `skills/` 目录，而是要把**安装、所有权、冲突、运行时状态、UI**这五件事补齐；`gui-automation` 的现有实现是最小原型。

## 3. 方案总览

三层分发，职责分离：

| 层 | 位置 | 面向 | 是否依赖登录/网络 | 角色 |
| --- | --- | --- | --- | --- |
| **Bundled** | desktop 安装包 `resources/skills/` | desktop 用户 | 否 | desktop 的权威来源；首次启动安装 |
| **Hub** | core-ai-server skill hub | 已登录用户 | 是 | 团队/私有技能；可选覆盖与分发 |
| **Public catalog** | `docs/skills` → github.io | CLI、其他 agent、未登录用户 | 否（手工下载时需网络） | 公开镜像、发现入口、版本说明 |

关键规则：

1. **desktop 的 bundled 副本是权威版本**，与安装包内的引擎版本一起测试、一起发布。
2. **public catalog 发布完全相同的字节**，供 CLI/其他 agent 使用；不要求 desktop 去下载。
3. **hub 是可选层**：已登录用户可以从 server hub 拉取同名技能，但默认不覆盖 desktop 内置副本；用户显式选择时才可以替换。
4. 三个层都落到 `~/.core-ai/skills`，不引入新的加载根。

## 4. 内置技能清单与默认策略

| 展示名 | skill | 能力 | 默认策略 | 运行时依赖 | 安装时机 |
| --- | --- | --- | --- | --- | --- |
| CLI & Hub manual | `core-ai-cli-manual` | Hub / CLI 操作手册 | **总是安装** | 无（引擎自带 CLI） | 每次启动校验 |
| Browser automation | `browser-use` | 内嵌浏览器自动化 | **默认安装** | `browser-use` CLI（uv 安装） | 每次启动校验 |
| Computer use | `gui-automation` | 本机 GUI 操作 | 启用 Computer use 且 ready 时安装 | `cua` CLI + `cua-spacesd` 服务 | `CuaManager.enable()` 或启动时已 ready |
| IM channels | `ocg` | QQ / 微信 / Telegram / DingTalk / Discord | 检测到 `ocg` 时自动安装；否则手动启用 | `ocg` daemon（npm 包） | 启动检测或用户启用 |

设计取舍：

- `core-ai-cli-manual` 没有运行时依赖，纯文本，默认安装不会造成失败调用；它也是“无登录也能用 Hub”的说明书。
- `browser-use` 是 desktop 的核心能力，默认安装；如果 `browser-use` CLI 缺失，Settings 显示安装入口，技能本身仍可作为安装/排障说明。
- `gui-automation` 和 `ocg` 都要求外部 daemon/服务；**没准备好就不安装**，避免模型看到技能后去调用不存在的命令。
- `ocg` 涉及读取和发送用户的 IM 消息，属于高敏感能力；首次启用必须有明确确认，桌面端不代管凭据和扫码。

## 5. Desktop 实现

### 5.1 单一源与打包

建议把技能源统一放在 `docs/skills/<name>`：

```
docs/skills/
  catalog.json
  core-ai-cli-manual/
  browser-use/
  gui-automation/
  ocg/
```

`catalog.json` 描述 desktop 关心的一切：

```json
{
  "schema": 1,
  "skills": [
    {
      "name": "core-ai-cli-manual",
      "title": "CLI & Hub manual",
      "capability": "hub",
      "default": "always",
      "source": "chancetop-com/core-ai",
      "license": "Apache-2.0",
      "version": "2.1"
    },
    {
      "name": "browser-use",
      "title": "Browser automation",
      "capability": "browser",
      "default": "always",
      "source": "browser-use/browser-use@<pinned-commit>",
      "license": "MIT",
      "version": "<pinned>"
    },
    {
      "name": "gui-automation",
      "title": "Computer use",
      "capability": "computer",
      "default": "when-enabled",
      "source": "trycua/cua@0f921000",
      "license": "MIT",
      "version": "<pinned>"
    },
    {
      "name": "ocg",
      "title": "IM channels",
      "capability": "channels",
      "default": "auto-detect",
      "source": "openclaw-channel-gateway@1.3.1",
      "license": "MIT",
      "version": "1.3.1"
    }
  ]
}
```

- `core-ai-desktop/scripts/stage-skills.mjs` 读取 catalog，把 `docs/skills/<name>` 复制到 `core-ai-desktop/skills/<name>`；`npm run dist` 前执行。
- 开发模式直接解析 `path.resolve(app.getAppPath(), '..', 'docs', 'skills', name)`；打包模式解析 `process.resourcesPath/skills/<name>`。
- `electron-builder.cjs` 继续用 `extraResources: [{ from: 'skills', to: 'skills' }]`，不需要改格式。
- 不再提交 `core-ai-desktop/skills/gui-automation` 的独立副本；现有目录迁移到 `docs/skills/gui-automation`，避免双份漂移。

### 5.2 `BuiltinSkills` 管理器

把 `src/main/computer/managedSkill.ts` 提升为通用模块（建议路径 `src/main/skills/managedSkill.ts` + `src/main/skills/BuiltinSkills.ts`）：

```ts
interface BuiltinSkillSpec {
  id: string;                 // catalog 的 name
  title: string;
  capability: 'hub' | 'browser' | 'computer' | 'channels';
  sourceDir: string;          // 打包/开发解析后的目录
  targetDir: string;          // ~/.core-ai/skills/<name>
  defaultMode: 'always' | 'when-enabled' | 'auto-detect';
  requires?: RuntimeProbe;    // browser-use / cua / ocg
  docsUrl: string;
}

interface BuiltinSkillStatus {
  spec: BuiltinSkillSpec;
  installed: boolean;
  managed: boolean;           // 有 .core-ai-desktop.json
  upToDate: boolean;
  detail: string;             // not installed / up to date / modified locally / unmanaged / covered by ...
  coveredBy?: string;         // 例如 Stephen/browser-use
  runtime: { present: boolean; version?: string; path?: string; detail: string };
}
```

核心方法：

- `list(): BuiltinSkillStatus[]`
- `install(id, { force?: boolean }): InstallResult`
- `installDefaults(): void` — `always` 全部安装；`when-enabled` / `auto-detect` 满足条件才安装；失败只记录，不阻塞启动。
- `remove(id): { ok: boolean; detail: string }` — 只删除 desktop 托管且未被本地修改的副本。
- `restore(id)` — 显式覆盖（带确认），用于修好“用户改坏了内置副本”。

安装算法（`install`）：

1. `targetDir` 不存在 → 复制整棵树，写 marker。
2. `targetDir` 有 `SKILL.md`，且有 `.core-ai-desktop.json`：
   - 本地文件哈希与 marker 一致 → 视为未被修改；如果与 bundled 源哈希不同，则替换（应用升级）。
   - 本地文件哈希与 marker 不一致 → `modified`，不覆盖；`force` 时才替换。
3. `targetDir` 有 `SKILL.md`，但没有 desktop marker → `unmanaged`，不覆盖；`force` 时才替换。
4. 安装前扫描 `~/.core-ai/skills`（含一层 namespace）中 frontmatter `name` 相同的技能：
   - 找到非 `targetDir` 的同名技能 → 返回 `coveredBy`，**不安装**，避免 `browser-use` 与 `Stephen/browser-use` 在 `SkillTool` 里同时出现。
5. 复制时保留相对路径；拒绝 `..` / 绝对路径；计算每个文件的 sha256，写入 marker。

marker 继续使用 `.core-ai-desktop.json`（兼容现有 `gui-automation`）：

```json
{
  "skill": "browser-use",
  "source": "chancetop-com/core-ai@<commit>",
  "desktopVersion": "0.1.x",
  "installedAt": "2026-10-17T...",
  "files": { "SKILL.md": "<sha256>", "references/...": "<sha256>" }
}
```

> 注意：marker 文件名是 desktop 与 CLI 的**所有权契约**，不要改。CLI 侧只需要识别这个文件名。

### 5.3 启动时序与引擎重启

`app.whenReady()`：

1. `settings = loadSettings()`
2. 创建 `BuiltinSkills`，`await installDefaults()`（纯本地文件复制，总计约 100 KB，耗时可忽略）
3. 创建窗口、`EngineSupervisor`（引擎仍然是 lazy spawn）
4. 用户第一次发消息时才 `spawn` 引擎，因此能看到已安装的技能

如果用户在运行中启用/禁用技能：

- 安装/删除完成后调用 `supervisor.shutdownAll()`（与登录切换同一策略），下一次消息重建引擎和 `SkillTool`。
- UI 提示“对下一个会话生效”；当前正在跑的 turn 不打断。
- 如果只想重启当前 workspace，调用 `supervisor.restart(workspace)` 即可；但技能在 user 层，其他 workspace 的引擎也会 stale，建议 `shutdownAll()`。

### 5.4 PATH 与引擎环境

`core-ai-cli-manual` 里的命令必须以 `core-ai-cli` 可执行为前提。desktop 的引擎二进制在：

- 打包：`process.resourcesPath/engine/core-ai-cli(.exe)`
- 开发：`build/core-ai-cli/install/core-ai-cli/bin/core-ai-cli(.bat)`

在 `EngineSupervisor.extraEnv()` 中把该目录**前置**到 `PATH`，并设置：

```text
CORE_AI_CLI_BIN=<absolute engine path>
```

这样：

- agent 在 shell 里执行 `core-ai-cli --version` / `core-ai-cli mcp search` 时，解析到与 desktop 完全一致的 pinned 引擎；
- 用户机器上另装的 CLI 不会抢先生效；
- 未登录时 hub 子命令会得到明确的未登录错误，而不是 “command not found”。

浏览器技能还需要一个状态标志（可选，但建议）：

```text
CORE_AI_BROWSER_TOOL=ready|missing
```

`CliAgentManagedBrowserPrompt` 根据它补充一句：`missing` 时不要假装已连接，提示用户到 Settings → Browser automation 安装 `browser-use` CLI。现有 prompt 说“browser-use is already attached”，在 CLI 缺失时是错误信息。

### 5.5 冲突与优先级

| 场景 | 行为 |
| --- | --- |
| target 不存在 | 安装 |
| target 是 desktop 托管、未修改 | bundled 变了就升级；没变就 up-to-date |
| target 是 desktop 托管、本地修改过 | 保留，状态 `modified`；用户点 Restore 才覆盖 |
| target 是无 marker 的目录/文件 | 保留，状态 `unmanaged`；用户点 “Use built-in” 才覆盖（先确认） |
| 同名技能在 namespace 下（如 `Stephen/browser-use`） | 状态 `covered by Stephen/browser-use`，不安装；提供 “Use built-in instead” |
| workspace 有同名技能 | SkillRegistry 中 workspace priority 50 覆盖 user 100；不处理，行为符合预期 |
| 从 hub pull 同名技能 | `SkillInstaller` 已拒绝无 `.skill-hub.json` 的目录；需要 `--force` 才覆盖 |
| CLI 自动 provisioning | 见 §6，必须识别 desktop marker 并跳过 |

“Use built-in instead” 的安全做法：**不直接删除** hub 副本，而是先把它重命名为 `.backup-<timestamp>` 或提示用户到 Skills 面板手动删除；v1 建议只提供 “Open hub copy” 链接，不做自动迁移。

### 5.6 UI

#### Skills 面板

在现有本地技能表上方增加一块 **Built-in capabilities** 卡片区：

| 卡片 | 状态 | 操作 |
| --- | --- | --- |
| CLI & Hub manual | Installed / Update available / Missing | Restore · Docs |
| Browser automation | Installed / Missing runtime / Update available | Restore · Install CLI · Docs |
| Computer use | Enabled / Not enabled / Missing skill | Enable in Settings · Restore · Docs |
| IM channels | Installed / Not configured / Detected | Enable · Restore · Setup guide |

现有表格增加 `Origin` 列：

- `Built-in`：路径等于某个 `targetDir`
- `Hub`：有 `.skill-hub.json`
- `Workspace` / `User`：按根目录
- `Local`：其他

Delete 按钮对 built-in 行给出不同确认文案：“This is a built-in skill. The desktop can restore it from the installer.” 删除后刷新卡片状态。

#### Settings

新增/调整三个区块：

1. **Built-in skills**：四行状态表，包含版本、路径、运行时、Restore/Docs。它回答“为什么我的 agent 没有这个能力”。
2. **Browser automation**：`browser-use` CLI 的探测结果、版本、路径；`Install browser-use CLI` 按钮（优先 `uv tool install`，没有 uv 时给出官方安装命令）；安装后重启引擎。
3. **IM channels**：`ocg` CLI 与 daemon 状态；`Install OCG`（`npm install -g openclaw-channel-gateway`，需 Node ≥ 22.12）；Enable 开关；一段只读的首次配置命令/说明（QQ Bot token、微信插件与扫码由用户在本地完成，desktop 不保存凭据）。

现有 **Computer use** 区块增加一行 `gui-automation skill` 的 Restore 按钮；状态和步骤列表保持现状。

#### IPC / preload

新增：

```text
builtinSkills:list
builtinSkills:install   { name, force? }
builtinSkills:remove    { name }
browser:toolStatus
browser:installTool
channels:status
channels:enable         { enabled }
channels:install
```

preload 只暴露白名单方法，不暴露任意命令执行。

### 5.7 失败处理

- `installDefaults()` 永不抛到启动流程；每个技能独立 try/catch，失败写入状态并在 Settings 显示。
- bundled 目录缺失（打包损坏）→ 状态 `bundled copy missing`，不删除本地副本。
- 磁盘只读/权限错误 → 状态 `install failed: ...`，给出路径。
- 运行时探测超时（5s）不阻塞启动；状态显示 `unknown`，可手动 Refresh。
- 安装/删除后重启引擎失败 → toast 提示，不影响文件状态。

## 6. CLI 侧改动

只有两处必须改：

1. **`ManagedSkillProvisioner` 识别 desktop marker**
   - `isInstalled()` 或 `findLocal()` 发现目录含 `.core-ai-desktop.json` 时，视为已安装并直接返回；
   - `installFromServer()` 不得对 desktop 托管目录使用 `force=true`；
   - 否则已登录用户每次启动 CLI/desktop 都可能用 server 副本覆盖内置副本。

2. **fallback URL 指向公开包**
   - 现在是 `https://raw.githubusercontent.com/browser-use/browser-use/main/skills/browser-use/SKILL.md`，内容随上游漂移；
   - 改成 `https://raw.githubusercontent.com/chancetop-com/core-ai/master/docs/skills/browser-use/SKILL.md`（单文件）或公开 zip（多文件）；
   - 如果保留单文件 fallback，browser-use 不要依赖 `references/`；如果需要多文件，使用 zip 解压并写一个非 hub 的 managed marker。

可选改动：

- `LocalSkills` 增加 `origin: "builtin"`（检测 `.core-ai-desktop.json`），让 `core-ai-cli skill list` 也能区分；
- `CliAgentManagedBrowserPrompt` 根据 `CORE_AI_BROWSER_TOOL` 补充“缺少 CLI 时去 Settings”的说明。

## 7. 是否发布到 public core-ai github.io

### 结论

**要发布，但不是 desktop 的运行时依赖。**

四个技能都进 `docs/skills/`：

| 包 | 来源 | 许可 | 发布理由 |
| --- | --- | --- | --- |
| `core-ai-cli-manual` | 本仓库 | Apache-2.0 | 已经发布；继续作为 CLI/Hub 的公开手册 |
| `browser-use` | browser-use/browser-use + core-ai 的 anti-bot/Windows 补充 | MIT | CLI 未登录用户、Claude Code/Codex 等外部 agent 需要稳定安装包 |
| `gui-automation` | trycua/cua | MIT | 让其他 agent 也能复用 Computer use；与 desktop 内置同源 |
| `ocg` | openclaw-channel-gateway@1.3.1（npm 生成） | MIT | 让未登录 CLI 用户和外部 agent 能安装 IM 渠道技能 |

发布产物：

```text
https://chancetop-com.github.io/core-ai/skills/
https://chancetop-com.github.io/core-ai/skills/<name>.zip
https://chancetop-com.github.io/core-ai/skills/manifest.json   # 新增
```

`manifest.json` 建议包含：

```json
{
  "schema": 1,
  "generatedAt": "2026-10-17T...",
  "skills": [
    {
      "name": "browser-use",
      "version": "0.1.9",
      "license": "MIT",
      "source": "browser-use/browser-use@<commit>",
      "zip": "browser-use.zip",
      "digest": "sha256:<tree digest>",
      "files": 1
    }
  ]
}
```

digest 建议按“文件相对路径 + 文件内容”的稳定树哈希计算，便于脚本核对；不要用 zip 字节哈希（zip 含时间戳，不可复现）。

### 为什么值得发布

1. **无登录模式需要公开入口**：desktop 可以只配置 provider 使用；用户误删内置技能、或在另一台机器上使用 CLI 时，github.io 是无认证、稳定的重装入口。
2. **CLI/其他 agent 已经在这个站点取技能**：`docs/skills/index.md` 明确写了 Claude Code、Codex、core-ai-cli 的安装方式；把 browser/computer/ocg 补进去，站点才完整。
3. **发现与文档**：每个技能有可读页面、来源、许可、版本；比散落在 hub namespace 里更容易维护。
4. **与 desktop 解耦**：技能内容可以在不重新发 desktop 安装包的情况下让 CLI/外部 agent 先用上；desktop 仍然按发布版本固定。

### 为什么 desktop 不应从 github.io 启动时拉取

1. **离线/弱网**：首次启动必须无网络可用；github.io 在部分网络环境不可达。
2. **供应链**：技能是给 agent 的指令，远程内容被篡改等于远程代码执行；不能让启动路径依赖一个可变的 Web 内容。
3. **版本一致性**：desktop 的 bundled 技能与 pinned 引擎一起测试；远端更新可能引用旧引擎没有的能力。
4. **已有先例**：`gui-automation` 就是随安装包 vendored 的；把其余三个改成远端拉取会引入不一致。

如果未来要做“技能更新检查”，至少需要：

- 独立的签名 manifest（cosign/minisign）或把 digest 固定在安装包里；
- 只在用户手动点击时检查；
- 明确显示来源、版本、变更；
- 绝不自动替换正在使用的技能。

### 许可与归属

- `browser-use`、`gui-automation`、`ocg` 都是 MIT；每个包放 `SOURCE.md`（上游仓库、固定 commit/tag、许可正文、core-ai 的修改说明）。
- `core-ai-cli-manual` 是本仓库内容，按 Apache-2.0 发布；加 `SOURCE.md` 说明 canonical 位置。
- `package_skills.py` 会把 `SOURCE.md` 一并打进 zip；这满足 MIT 的版权与许可声明要求。
- 不把上游 `SKILL.md` 改得面目全非；core-ai 的补充（如 desktop/Windows/anti-bot 说明）单独标注，便于下次同步上游。

## 8. 迁移

1. `core-ai-desktop/skills/gui-automation` → `docs/skills/gui-automation`；更新 `SOURCE.md` 中指向 `src/main/computer/` 的说明。
2. `CuaManager` 改为调用通用 `BuiltinSkills.install('gui-automation')`；现有 `~/.core-ai/skills/gui-automation/.core-ai-desktop.json` 继续有效。
3. 已存在的 `~/.core-ai/skills/Stephen/<name>`：
   - 内置管理器扫描到同名技能 → 状态 `covered by Stephen/...`，不安装；
   - Skills 面板显示来源，不强行迁移；
   - 用户想用内置版时，先删除 hub 副本，再点 Restore。
4. 全新用户：首次启动安装 `core-ai-cli-manual` + `browser-use`；Computer use/IM channels 按开关/检测结果安装。
5. 升级用户：bundled 哈希变化且本地未修改 → 自动升级；本地修改过 → 保留并提示。

## 9. 验收标准

### 功能

- [ ] 断网、无 `~/.core-ai/auth.json`、只配置 `agent.properties` 的首次启动：`~/.core-ai/skills/core-ai-cli-manual/SKILL.md` 与 `browser-use/SKILL.md` 存在，agent 的 skill 列表能看到两者。
- [ ] 启动过程没有任何对 github.io / server hub 的技能请求；断网启动不增加可感知延迟。
- [ ] 删除 `core-ai-cli-manual` 后，Skills 面板点 Restore 不联网恢复。
- [ ] 手工修改内置 `browser-use/SKILL.md` 后，应用升级不覆盖；点 Restore 确认后才覆盖。
- [ ] 存在 `Stephen/browser-use` 时，内置卡片显示 `covered by Stephen/browser-use`，不产生第二个同名技能。
- [ ] 已登录 CLI 启动时，`ManagedSkillProvisioner` 不覆盖 desktop 托管副本。
- [ ] 启用 Computer use 后 `gui-automation` 安装；禁用/删除后可从 Settings 恢复。
- [ ] 检测到 `ocg` 时 IM channels 卡片出现；未检测到时可通过 Enable 安装；首次启用有敏感权限确认。
- [ ] 运行中安装/删除技能后，引擎重启，下一条消息的 skill 列表更新。
- [ ] `core-ai-cli --version` 在 agent shell 中解析到 desktop bundled 引擎。

### 公开站点

- [ ] `docs/skills/{core-ai-cli-manual,browser-use,gui-automation,ocg}/SKILL.md` 存在。
- [ ] `npm run build && npm run package:skills && npm run check:links` 通过；四个 zip 可下载，解压后目录名与 frontmatter `name` 一致。
- [ ] `manifest.json` 的 digest 与解压内容一致；版本与来源可读。
- [ ] 每个第三方包有 `SOURCE.md` + MIT 许可正文；`core-ai-cli-manual` 标注 Apache-2.0。
- [ ] `docs/skills/index.md` 有四个技能的说明、桌面内置标识和安装方式。

### 回归

- [ ] 现有 `gui-automation` 安装路径和 `.core-ai-desktop.json` 兼容。
- [ ] Skills 面板的 hub 更新、删除、`skills/list` 两阶段加载不回归。
- [ ] Computer use 的 enable/disable/remove service 流程不回归。
- [ ] macOS 打包后 `resources/skills` 路径正确；Windows NSIS 安装包包含四个技能目录。

## 10. 分阶段实施

### Phase 1：基础内置（建议先做）

- `docs/skills/catalog.json` + 四个技能包（先放 `core-ai-cli-manual` / `browser-use` / `gui-automation`，`ocg` 可紧随）。
- `stage-skills.mjs` + `BuiltinSkills` 管理器 + `.core-ai-desktop.json` 通用化。
- 启动安装 `always` 技能；Skills 面板 Built-in 卡片 + Restore。
- 引擎 PATH 注入 `CORE_AI_CLI_BIN`。
- CLI `ManagedSkillProvisioner` 识别 desktop marker。
- 测试：unit + 无登录/断网启动 E2E。

### Phase 2：运行时与开关

- Browser automation Settings（`browser-use` CLI 探测/安装/重启）。
- IM channels Settings（`ocg` 探测、安装提示、enable 确认）。
- Computer use 的 Restore 入口与启动时 ready 校验。
- `CORE_AI_BROWSER_TOOL` + prompt 注入。

### Phase 3：公开目录与发布

- `docs/skills` 四个包 + `SOURCE.md` + `index.md`。
- `package_skills.py` 输出 `manifest.json`。
- 桌面 release workflow 增加 `stage:skills`。
- 可选：把 `docs/skills` 注册为 server hub 的 repo skill source，让登录用户也能在 Hub 看到（不是默认安装）。

## 11. 待确认的问题

1. **OCG 默认策略**：建议“检测到 `ocg` 自动安装，否则手动启用”。如果希望所有用户默认看到 IM 能力，可改为默认安装 + 首次使用时提示配置。
2. **`browser-use` CLI 安装方式**：优先 `uv tool install`；没有 uv 时是引导安装 uv，还是只显示官方命令让用户自己执行？建议前者提供按钮，后者保底。
3. **公开站点的第三方技能是否要逐字 vendored**：建议 vendored + `SOURCE.md` 记录修改；不直接链接上游 raw，避免上游变更导致行为漂移。
4. **是否需要在 v1 做签名 manifest**：建议不做；v1 只发布 digest 供人工/CLI 校验，desktop 不自动更新。
5. **Server hub 是否同步这四个技能**：可选；如果做，建议作为 repo skill source，而不是 migration 种子，避免与本地内置副本重复。
