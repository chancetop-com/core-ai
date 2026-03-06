## 建设目标
1. 对齐claudecode
2. 能够应付项目管理以及编码任务
3. 工程设计的沉淀

##  claudeCode逆向工程特性 analysis_claude_code
> 工程路径：/Users/lim/workspace/code/analysis_claude_code
### 执行引擎层

- Agent loop：nO主循环引擎，async generator实现的核心调度器
- 工具调度器：MH1工具引擎，6阶段执行管道（发现→验证→权限→资源→执行→回收）
- Subagent/多agent并发：I2A子任务代理，隔离执行环境，支持agent_/synthesis_任务分组
- 流式响应处理（Streaming）：async generator流式输出，SSE消费（fromSSEResponse()），content_block_start事件处理，128KB缓冲区阈值：多LLM provider轮换（firstParty/bedrock/vertex），中断恢复（A.interrupted检测），异常分层处理
- 错误恢复与模型降级：多LLM provider轮换（firstParty/bedrock/vertex），中断恢复（A.interrupted检测），异常分层处理
- Token计数与用量追踪：VE()/HY5()/zY5()函数，追踪input/output/cache_creation/cache_read四类token，Prompt Caching支持

### 交互与控制层

- Human-in-the-loop：用户确认/拒绝工具执行
- Steering实时打断：h2A双重缓冲异步消息队列，用户可在Claude工作时排队消息
- PlanMode/EditMode：Shift+Tab循环切换，default→acceptEdits→plan→(bypassPermissions)→default
- UI组件系统（React/Ink）：基于React+Ink的终端UI，11个核心状态变量，标准化工具渲染接口（Task/Bash/UpdateFile等组件）
- Notification/Tips系统：tipOfTheDay提示，setNotificationHandler()通知监听，cooldownSessions冷却机制，使用次数追踪
- 斜杠命令系统：三类命令：local-jsx(React组件)、local(函数)、prompt(AI驱动)，rN5()执行/cw1()查找

### 状态与记忆层

- 上下文恢复与文件追踪：编辑前强制读取验证，文件完整性校验，原子MultiEdit
- Compact上下文压缩：92%阈值自动触发（h11=0.92），8段式结构化摘要，60%/80%渐进预警
- Todo任务管理：initialTodos，todoFeatureEnabled特性开关控制
- CLAUDE.md持久记忆系统：三层记忆架构：短期(messages)→中期(compactSummary)→长期(CLAUDE.md)，跨会话知识持久化
- Session会话管理：-r/--resume [sessionId]恢复会话，tengu_continue事件追踪，对话历史持久化
- 多层配置系统：三级配置优先级：local > project > user，含dynamic动态配置作用域

### 安全与权限层

- 多层权限验证网关 ： 6层验证链（UI输入→消息路由→工具调用→参数内容→系统资源→输出过滤），sM()权限检查返回allow/deny
- 沙箱隔离执行：macOS sandbox-exec系统级隔离（gZ0类），--dangerously-skip-permissions绕过标志（仅限离线沙箱）
- LLM驱动的命令安全分析 ： uJ1()函数用LLM分析命令安全性，注入检测，路径遍历防护
- Shell进程隔离：每个shell独立ID+状态追踪，生命周期管理（运行/完成/错误），多shell并发隔离

### 扩展与集成层

- Hooks 插件机制：shell命令响应工具调用事件
- MCP协议集成：完整MCP实现：5种传输（HTTP/SSE/WebSocket/STDIO/IDE），OAuth 2.0认证，DV管理器/ue客户端工厂
- IDE集成：双向通信（SSE-IDE/WS-IDE协议），LSP诊断支持，支持VS Code/Cursor/Windsurf
- Git深度集成：环境变量控制（GIT_TERMINAL_PROMPT=0），并行化git操作，worktree支持多会话并行
- Deferred Tool延迟加载 ：工具按需加载机制（ToolSearch发现后才可调用），减少初始化开销

### Prompt工程层

- 动态System Prompt生成: ga0()函数根据上下文动态生成系统提示，包含工具配置、模式限制、环境信息
- System Reminder注入: Ie1()函数在对话中注入<system-reminder>标签，事件驱动的提醒生成（WD5()）
- 多模态内容处理: 支持PNG/JPG/JPEG图片，base64编码，iTerm内联图片显示（d6.image()）

### 可观测性层

- 遥测/Analytics: E1()事件追踪函数，事件类型tengu_init/tengu_startup/tengu_continue/tengu_flicker/tengu_mode_cycle等
- Sentry错误上报: bJA变量配置Sentry端点，异常自动上报
- CLI启动框架: Commander.js解析，tq5()构建CLI/aq5()初始化，环境变量+命令行参数双通道配置

## opencode
> 工程路径：/Users/lim/workspace/code/opencode
> 和claudecode 功能上很相似，关键的差异为：
> 1. 不绑定特定提供商。推荐使用 OpenCode Zen 的模型，但也可搭配 Claude、OpenAI、Google 甚至本地模型。模型迭代会缩小差异、降低成本，因此保持 provider-agnostic 很重要。
> 2. 内置 LSP 支持。
> 3. 聚焦终端界面 (TUI)。OpenCode 由 Neovim 爱好者和 terminal.shop 的创建者打造，会持续探索终端的极限。
> 4. 客户端/服务器架构。可在本机运行，同时用移动设备远程驱动。TUI 只是众多潜在客户端之一。

# OpenCode 工程能力与结构全景分析

## 执行引擎层

| 特性 | 说明 |
|------|------|
| Agent Loop | SessionPrompt.loop() 同步while(true)循环引擎（非async generator），每次迭代=一次LLM调用+工具执行周期。从DB加载消息历史→找lastUser/lastAssistant→处理subtask/compaction→创建SessionProcessor→流式LLM调用→根据返回值("continue"/"stop"/"compact")决定下一步。终止条件：finish reason非tool-calls、权限拒绝、abort信号、steps上限、不可重试错误。每session仅允许一个loop运行，后续请求通过Promise callback队列等待。 |
| 工具调度器 | 6阶段执行管道：ToolRegistry发现→ProviderTransform.schema()适配→Zod参数验证→PermissionNext权限评估→tool.execute()执行→Truncate.output()截断回收。支持Plugin.trigger("tool.execute.before/after") hook。工具注册三源：内置20+工具（硬编码列表）+ config目录自定义文件（{tool,tools}/*.{js,ts}）+ Plugin.list()插件工具。条件过滤：apply_patch vs edit/write按模型切换，websearch/codesearch仅Zen用户，lsp/batch/plan_exit实验性标记。 |
| Subagent/多Agent并发 | TaskTool子任务代理：创建子Session（parentID关联父session），独立消息历史/权限/模型配置，隔离执行环境。内置4个agent模式：build(primary全权限)、plan(primary只读)、general(subagent多步骤)、explore(subagent只读搜索)。支持task_id恢复之前的子session。BatchTool实验性并行：单次调用内Promise.all并行执行最多25个内置工具。子agent默认禁止todowrite/todoread/task嵌套，可通过config.experimental.primary_tools放行。 |
| 流式响应处理（Streaming） | 基于Vercel AI SDK streamText()的fullStream事件流消费。SessionProcessor处理12类事件：text-start/delta/end、reasoning-start/delta/end（thinking models）、tool-input-start/call/result/error、start-step/finish-step。delta增量通过Bus.publish(PartDelta)实时推送到SSE客户端。experimental_repairToolCall自动修复大小写错误的工具名，无效调用重定向到invalid工具。 |
| 错误恢复与重试 | SessionRetry分层处理：retryable()判断可重试性→指数退避delay(2s×2^attempt)→SessionRetry.sleep()等待→重新stream。ContextOverflowError触发自动compaction而非重试。权限拒绝(RejectedError)直接stop。LiteLLM代理兼容：自动检测LiteLLM，注入_noop工具满足消息历史含tool_call时的验证。Provider层面：20+ SDK内置，但无自动降级轮换（单provider per session），通过config可手动指定备选模型。 |
| Token计数与用量追踪 | Session.getUsage()精确计费：区分input/output/reasoning/cache.read/cache.write五类token。Anthropic特殊处理：inputTokens不含cached tokens（其他provider含）。Decimal.js精确计算成本。支持experimentalOver200K分段定价。每个step-finish记录tokens+cost，累加到assistant message级别。Prompt Caching支持：Anthropic beta header cache-control，ephemeral caching via ProviderTransform。 |
| Context管理与压缩 | SessionCompaction自动触发：token使用量≥(model.limit.input - reserved)时启动。reserved默认20K或maxOutputTokens。压缩流程：compaction agent总结历史→创建CompactionPart标记→旧消息从context中过滤。Pruning策略：保留最近40K token工具输出，擦除更早输出（skill工具受保护不prune）。可通过config关闭。 |

## 交互与控制层

| 特性 | 说明 |
|------|------|
| Human-in-the-loop | PermissionNext三级权限模型：allow/deny/ask。ctx.ask()触发权限请求→Bus发布permission.asked→客户端展示→用户回复once/always/reject→Bus发布permission.replied。always选项持久化到DB。QuestionTool直接向用户提问（仅app/cli/desktop客户端），支持选项列表。 |
| 实时消息排队 | loop运行期间用户发送的消息作为新user message存入DB，下一次循环迭代时被检测到并处理。step>1时，排队消息被包裹在`<system-reminder>`标签中提醒LLM"请处理此消息并继续你的任务"。cancel()方法通过AbortController.abort()实现即时打断，清理所有pending tool parts状态为error。 |
| Agent模式切换 | Tab键循环切换primary agents：build(全权限)↔plan(只读分析)。@agent_name语法调用子agent。plan agent禁止edit/write/bash工具（deny），仅允许在.opencode/plans/目录写入。实验性plan_enter/plan_exit工具支持LLM自主切换模式（需OPENCODE_EXPERIMENTAL_PLAN_MODE标记）。 |
| UI组件系统（SolidJS/TUI） | 基于SolidJS + opentui的终端渲染引擎，15+层Context Provider栈（Exit→KV→Toast→Route→TuiConfig→SDK→Sync→Theme→Local→Keybind→PromptStash→Dialog→Command→Frecency→History）。Worker线程架构：TUI运行在主线程，Server在Worker中，RPC via postMessage通信。路由：Home(会话浏览)、Session(活跃会话)。支持Dialog/Toast/Keybind等标准UI模式。 |
| Doom Loop检测 | processor.ts内置循环检测：最近3个tool part为相同工具+相同输入时触发ask权限。防止LLM陷入无限重复调用。doom_loop权限默认为ask，用户可选择允许继续或阻止。配合experimental.continue_loop_on_deny配置：deny时是否终止整个loop还是仅跳过当前工具。 |
| Skill命令系统 | SkillTool加载markdown-based skill定义（SKILL.md + frontmatter）。多源发现：.claude/skills/、.agents/skills/（全局+项目级）、.opencode配置目录、config指定路径、远程URL。Skill内容注入为system prompt的一部分，支持文件引用和agent引用（@agent_name语法）。远程skill支持：从URL下载index.json→解压到cache→按需加载。 |
| 结构化输出 | 支持JSON Schema格式输出：注入StructuredOutput工具→toolChoice设为required→LLM必须调用该工具返回结构化数据。onSuccess回调捕获输出→存入message.structured字段→立即退出loop。未调用时报StructuredOutputError。 |

## 工具生态层

| 特性 | 说明 |
|------|------|
| 文件操作工具 | read（分页读取+图片/PDF支持）、edit（9种模糊匹配策略：Simple→LineTrimmed→BlockAnchor→WhitespaceNormalized→IndentationFlexible→EscapeNormalized→TrimmedBoundary→ContextAware→MultiOccurrence，Levenshtein相似度评分）、write（创建/覆写）、multiedit（单文件多处编辑）、apply_patch（GPT-5+专用patch格式）。FileTime.withLock()防止并发编辑，编辑后自动触发LSP diagnostics并附加到输出。 |
| 搜索工具 | glob（模式匹配，max 100结果，按mtime排序）、grep（regex搜索，自动下载ripgrep v14.1.1，JSON输出解析）、ls（目录列表，max 100，忽略node_modules等40+目录）、codesearch（Exa API代码语义搜索）、websearch（Exa API网页搜索，支持livecrawl）。codesearch/websearch仅Zen用户或OPENCODE_ENABLE_EXA开启。 |
| Shell执行 | bash工具：tree-sitter AST解析命令→检测危险操作(cd/rm/cp/mv/mkdir/touch/chmod/chown/cat)→提取文件参数解析为绝对路径→external_directory权限检查。支持timeout/abort/workdir参数。stdout/stderr通过ctx.metadata()实时流式推送。arity.ts映射100+命令前缀用于权限识别。 |
| Agent编排工具 | task（子任务代理：创建子session→独立loop→结果以`<task_result>`返回）、batch（实验性并行：Promise.all执行最多25个内置工具，每个子调用独立状态跟踪，不支持MCP/外部工具）、question（用户交互：选项列表）、todowrite（TODO状态管理）、skill（技能加载）。 |
| 网络工具 | webfetch（URL获取：HTML→Markdown转换，5MB限制，Cloudflare CF_MITIGATED重试，支持text/markdown/html格式）。websearch/codesearch通过Exa MCP API实现，支持query/numResults/livecrawl/tokensNum参数。 |
| IDE集成工具 | lsp工具（实验性）：goToDefinition/findReferences/hover/documentSymbols/workspaceSymbols/callHierarchy。编辑后自动运行diagnostics并附加错误信息到工具输出。plan_enter/plan_exit控制agent模式。 |
| 输出截断系统 | Truncate.output()：默认2000行 OR 50KB（先到先停）。截断后完整输出存文件（7天保留，每小时GC）。有task权限的agent：提示"委托给explore agent"；无task权限：提示"用Grep/Read with offset"。工具可通过metadata.truncated=true自行处理截断逻辑，跳过自动截断。 |
| MCP工具集成 | 支持远程(HTTP/SSE)和本地(stdio) MCP server。OAuth认证流(PKCE+state)+端口19876本地回调。MCP工具自动注册到ToolRegistry，经过权限系统（默认ask）。支持prompt/resource/tool三种MCP能力。状态追踪：connected/disabled/failed/needs_auth/needs_client_registration。 |
| 自定义工具扩展 | 三种扩展方式：(1) config目录下{tool,tools}/*.{js,ts}文件，export ToolDefinition自动注册 (2) @opencode-ai/plugin SDK定义工具 (3) MCP server提供工具。Plugin hook: tool.definition允许修改已有工具的description和parameters。 |

## Provider与模型层

| 特性 | 说明 |
|------|------|
| 多Provider统一抽象 | 基于Vercel AI SDK（ai 5.x）的LanguageModelV2接口统一20+ provider：Anthropic、OpenAI、Google(Gemini)、Azure、Bedrock、Vertex AI、Groq、Mistral、xAI、DeepInfra、Cerebras、Cohere、Perplexity、OpenRouter、Cloudflare、GitLab、TogetherAI、Vercel、GitHub Copilot、通用OpenAI-compatible（本地/代理模型）。每个provider有独立CUSTOM_LOADER处理特殊逻辑。 |
| 模型发现与配置 | 模型元数据从models.dev API获取（每小时刷新），离线回退到内置snapshot。7级配置优先级：managed→inline(env)→.opencode/→项目→custom(env path)→全局→远程.well-known。支持whitelist/blacklist过滤模型，per-model覆写id/cost/limit/capabilities。默认模型选择：config.model→最近使用缓存(state/model.json)→首个可用provider的首个模型。 |
| Provider特殊适配 | Anthropic: beta header(claude-code/interleaved-thinking/fine-grained-tool-streaming)，ephemeral caching。OpenAI: GPT-5+使用Responses API，其他用Chat API。Copilot: reasoning_opaque多轮推理状态透传。Bedrock: 复杂跨区域推理profile(us./eu./jp./apac./au./global.前缀)，AWS credential chain。Vertex: GoogleAuth自动认证，bearer token注入。Mistral: tool call ID归一化(9字符zero-padded)。消息归一化：ProviderTransform.message()处理空内容过滤、reasoning part提取、tool call ID清理等。 |
| 认证体系 | 四种认证方式：API Key（环境变量）、OAuth（Codex/Copilot PKCE流程）、AWS credential chain（Bedrock）、Google Auth（Vertex AI）。存储：~/.opencode/auth.json。Codex插件：ChatGPT Pro/Plus OAuth + JWT解析 + token刷新 + device authorization(headless)。Copilot插件：GitHub device code flow + Enterprise支持 + vision请求检测 + 成本归零。 |
| Model Variants | 推理强度变体（Reasoning Effort）：per-model配置，如 low/medium/high/max。Anthropic: thinking budget tokens配置。OpenAI: reasoningEffort参数。用户可通过variant选择。Agent级别可指定variant，也可在prompt时通过input.variant传入。 |
| Small Model | Provider.getSmallModel()用于标题生成、摘要、compaction等辅助任务。优先选择haiku/fast/nano等轻量模型，可通过config.small_model指定。成本远低于主模型。 |

## 权限与安全层

| 特性 | 说明 |
|------|------|
| 三级权限模型 | PermissionNext.Rule = {permission, pattern, action: "allow"\|"deny"\|"ask"}。四层合并：Agent默认→Agent特定→用户配置(config)→Session级别。最后匹配的规则生效。支持glob通配符路径匹配，~/和$HOME/自动展开。disabled()函数批量过滤被deny的工具。 |
| 关键权限类别 | 全局(*)、文件操作(bash/read/edit/write)、外部目录(external_directory，模式匹配路径)、循环检测(doom_loop)、子任务(task)、用户提问(question)、模式切换(plan_enter/plan_exit)。.env文件默认ask权限，.env.example允许读取。Truncate输出目录始终允许（除非显式deny）。 |
| 外部目录保护 | assertExternalDirectory()：检查文件路径是否在Instance.directory内，超出则请求external_directory权限。bash工具通过tree-sitter AST解析提取所有文件参数→解析为绝对路径→逐一检查。Skill目录和Truncate输出目录自动加入白名单。 |
| 权限持久化 | PermissionTable数据库表存储project级别权限。always回复的权限规则写入DB，跨session生效。Session.permission字段支持per-session权限覆写。子session继承父agent权限并附加额外限制。 |
| Agent权限隔离 | build: *=allow, question=allow, plan_enter=allow。plan: edit=deny(除.opencode/plans/), plan_exit=allow。general: todoread/todowrite=deny。explore: *=deny, 仅允许grep/glob/list/bash/read/webfetch/websearch/codesearch。子agent额外限制：默认deny todowrite/todoread/task（防嵌套），config.experimental.primary_tools可放行。 |

## 客户端/服务器架构层

| 特性 | 说明 |
|------|------|
| HTTP Server | Hono框架 + Bun runtime，默认端口4096。REST API + SSE + WebSocket三种通信方式。13个路由组：Session/Project/Provider/Config/File/Mcp/Pty/Permission/Question/Tui/Global/Experimental/Workspace。CORS白名单(*.opencode.ai + localhost)，Basic Auth可选(OPENCODE_SERVER_PASSWORD)。启动模式：serve(无头)、web(+浏览器)、attach(远程TUI)、workspace-serve(工作区专用)。 |
| Event Bus | BusEvent.define()类型安全事件注册（Zod schema），Bus.publish()/subscribe()/subscribeAll()发布订阅。实例级Bus + GlobalBus（跨实例EventEmitter）。所有Bus事件通过/event SSE端点桥接到客户端。关键事件：session.created/updated/deleted/error/diff、message.updated/part.updated/part.delta、permission.asked/replied、lsp.updated、session.compacted、session.status。心跳10秒防代理超时。 |
| TUI Worker架构 | 本地模式：主线程TUI + Worker线程Server，RPC via postMessage（fetch/server/checkUpgrade/reload/shutdown）。Worker内创建Server.App().fetch()（无网络HTTP），SDK client订阅/event SSE后转发Rpc.emit("event")到TUI。远程模式：opencode attach `<url>`直接HTTP/SSE连接到远程server，无需Worker。 |
| 远程连接与发现 | mDNS服务发现（bonjour-service），--mdns标记启用，默认域opencode.local，零配置局域网发现。移动端场景：opencode serve暴露API→手机通过mDNS发现→REST/SSE连接。支持X-Opencode-Workspace/X-Opencode-Directory头部路由到不同工作区。 |
| Workspace适配器 | 适配器模式（control-plane/adaptors/）：configure/create/remove/fetch接口。当前实现worktree适配器（git worktree隔离），预留远程workspace支持。WorkspaceRouterMiddleware自动检测remote workspace并通过adaptor.fetch()代理请求。workspace-serve子命令启动workspace专用事件服务器（独立端口）。 |
| SDK/OpenAPI | 从server路由自动生成OpenAPI spec（hono-openapi），SDK位于packages/sdk/js/。createOpencodeClient()封装所有API调用，支持event.subscribe() SSE流。Web客户端(packages/app SolidJS)和Desktop客户端(packages/desktop Tauri)共用同一SDK。 |
| ACP协议 | Agent Client Protocol v1实现（acp/），用于IDE集成。支持initialize(版本协商)、session/new/load/prompt、权限请求、文件操作、工具调用报告。当前限制：无流式响应、无持久化、终端支持为占位。 |

## IDE与代码智能层

| 特性 | 说明 |
|------|------|
| LSP集成 | 70+ LSP server定义（lsp/server.ts 75KB），自动按文件扩展名匹配语言（120+扩展名映射）。支持：TypeScript(tsserver/tsgo)、Python(pyright/ty)、Go(gopls)、Rust(rust-analyzer)、Java、C/C++、Ruby、PHP、Lua、Zig、Nix、Terraform等。自动下载和启动LSP进程。功能：diagnostics(150ms防抖)、hover、document/workspace symbols、定义跳转、引用查找、调用层次。NearestRoot策略：从文件位置向上搜索项目标记文件(package.json/go.mod等)确定LSP根目录。 |
| 编辑后诊断 | edit/write工具执行后自动触发LSP diagnostics，将错误信息附加到工具输出。LLM可直接看到编辑引入的类型错误/语法错误并自动修复，无需额外工具调用。Format集成：文件编辑事件触发自动格式化（可配置per-formatter enable/disable）。 |
| 文件监控 | Parcel Watcher实现高效文件监控：macOS用fs-events、Linux用inotify、Windows用Windows API。忽略node_modules等40+目录模式。Git目录(.git)独立监控用于检测分支切换。FileTime追踪per-session读取时间 + per-file写锁（防并发编辑）。50ms容差处理NTFS时间戳精度。 |
| 代码搜索 | ripgrep集成（v14.1.1自动下载）：JSON输出解析、文件树生成、分页、符号链接跟踪。Exa API代码语义搜索(codesearch)：支持1000-50000 tokens范围的代码上下文检索。glob工具：max 100结果按mtime排序，支持dotfiles。File.search()模糊文件名搜索+目录优先排序。 |

## 数据持久与状态层

| 特性 | 说明 |
|------|------|
| SQLite + Drizzle ORM | Bun SQLite driver，存储路径~/.local/share/opencode/opencode.db（XDG标准）。Pragmas：WAL模式、NORMAL同步、5s busy_timeout、64MB cache、FK约束。AsyncLocalStorage上下文绑定，Database.use()/transaction()/effect()事务管理。核心表：project/session/message/part/todo/permission/session_share/workspace。CASCADE删除。 |
| 消息存储模型 | Session→Message→Part三层结构。Message存储role+metadata，Part存储具体内容（text/tool/reasoning/patch/snapshot等）。Part为JSON blob存储，支持灵活扩展类型。消息通过MessageV2.stream()逆序分批读取（50 per batch）。Session支持fork（从指定message分叉）、parent-child关系（子session）、时间线归档。 |
| Snapshot系统 | 独立git仓库（$DATA_DIR/snapshot/）跟踪工作区文件变更。每个LLM step前后各创建tree snapshot（git write-tree）。Snapshot.patch()计算两个tree间的diff。SessionRevert支持回退到任意snapshot。7天过期自动GC（每小时scheduler），可通过config.snapshot=false关闭。Git配置：autocrlf=false、longpaths=true、symlinks=true、fsmonitor=false（防内存泄漏）。 |
| ID生成系统 | 前缀标识：ses_(session)、msg_(message)、prt_(part)、per_(permission)、que_(question)等。26字符结构：prefix + HEX(6B timestamp) + base62(14 random)。单调递增计数器防毫秒内碰撞。双模式：ascending（按创建时间排序）、descending（反向排序，用于session列表最新在前）。 |
| 全局路径（XDG） | data: ~/.local/share/opencode（DB/日志/bin）、config: ~/.config/opencode、cache: ~/.cache/opencode（版本v21）、state: ~/.local/state/opencode、log: data/log。OPENCODE_TEST_HOME支持测试隔离。所有目录模块加载时自动创建。 |
| 迁移系统 | JsonMigration.run()：从旧JSON文件存储迁移到SQLite。批量读取(1000/batch)、事务批量插入。迁移优化：WAL OFF + sync OFF + 高cache + MEMORY temp store。预扫描文件→并行读取(Promise.allSettled)。首次运行时显示进度条，迁移后写marker文件防重复迁移。 |
| 会话分享 | ShareNext.create()上传到远程API生成分享URL。1秒防抖同步session/message/part增量更新。支持auto share（config.share="auto"或OPENCODE_AUTO_SHARE标记）。session_share表存储id/secret/url。share/unshare操作触发Session.Event.Updated事件通知UI更新。 |

## 插件与扩展层

| 特性 | 说明 |
|------|------|
| Plugin系统 | 7个hook点覆盖全链路：tool.definition（修改工具定义）、tool.execute.before/after（工具执行前后）、experimental.chat.system.transform（修改system prompt）、experimental.chat.messages.transform（修改消息）、experimental.text.complete（修改文本输出）、chat.params（修改LLM参数）、chat.headers（修改HTTP头）。内置插件：Codex(OpenAI OAuth)、Copilot(GitHub device code)。外部npm插件动态加载。默认附带opencode-anthropic-auth@0.0.13。 |
| 自定义Agent | config.agent配置自定义agent：name/description/model/variant/prompt/temperature/topP/mode/color/hidden/steps。Agent.generate()通过LLM自动生成agent配置（输入描述→generateObject→返回identifier/whenToUse/systemPrompt）。自定义agent可指定独立模型、权限规则集、步数上限。disable=true可禁用内置agent。 |
| 项目指令系统 | InstructionPrompt多源加载：.opencode/instructions.md、AGENTS.md（项目根目录）、全局~/.config/opencode/instructions。SystemPrompt.environment()注入运行环境信息（OS/shell/项目结构/VCS状态等）。用户可在prompt中通过input.system传入临时指令。Plugin可通过system.transform hook动态修改。 |
| Worktree隔离 | Git worktree创建独立工作副本：随机命名(形容词-名词)、opencode/命名空间分支、自动子模块处理。支持启动命令执行（项目和worktree级别）、canonical路径解析（处理符号链接）、进程清理(fsmonitor daemon)。worktree.ready/worktree.failed事件通知。 |
| Scheduler | 简单interval调度器：register({id, interval, run, scope})。scope分instance级和global级。timer.unref()不阻止进程退出。用于：Snapshot GC(每小时)、Truncate输出清理(每小时)、文件监控等。首次注册立即执行一次，后续按interval重复。支持清除和替换已有任务。 |
| 导入/导出 | export命令序列化session为可传输格式。import命令从文件恢复session。支持跨项目、跨机器迁移会话历史。 |

## CLI命令全景

| 命令 | 说明 |
|------|------|
| `opencode [dir]` | 默认命令：启动TUI（Worker线程架构，内嵌Server） |
| `opencode serve` | 无头API服务器（--port 指定端口，--mdns 启用网络发现） |
| `opencode web` | 启动服务器+打开浏览器Web UI |
| `opencode attach <url>` | TUI远程连接到运行中的server |
| `opencode run` | 非交互式单次prompt执行 |
| `opencode auth` | 管理provider认证（login/logout/status） |
| `opencode agent` | 管理自定义agent |
| `opencode models` | 列出可用模型 |
| `opencode mcp` | 管理MCP服务器 |
| `opencode generate` | 生成Agent/SDK等资源 |
| `opencode export` / `import` | 导出/导入会话 |
| `opencode session` | 会话管理 |
| `opencode pr` | PR相关操作 |
| `opencode github` | GitHub集成 |
| `opencode stats` | 使用统计 |
| `opencode upgrade` | 自动升级 |
| `opencode uninstall` | 卸载 |
| `opencode db` | 数据库管理 |
| `opencode debug` | 调试工具 |
| `opencode acp` | ACP协议服务 |
| `opencode completion` | Shell补全脚本生成 |