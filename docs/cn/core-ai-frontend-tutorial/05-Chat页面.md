# 05 - Chat 页面

> 🎯 **学习目标**：深入理解 Chat 页面——前端最复杂的组件，掌握 SSE 流式对话、消息渲染、会话管理和流恢复机制
> 
> ⏱️ **预计时间**：1.5 天

---

## 📚 本章内容

- [0.1 Chat 页面总览](#01-chat-页面总览)
- [0.2 核心状态与常量](#02-核心状态与常量)
- [0.3 消息类型系统](#03-消息类型系统)
- [0.4 SSE 事件流](#04-sse-事件流)
- [0.5 流恢复机制](#05-流恢复机制)
- [0.6 消息渲染管线](#06-消息渲染管线)
- [0.7 子组件体系](#07-子组件体系)
- [0.8 Agent 选择与资源管理](#08-agent-选择与资源管理)
- [0.9 会话管理](#09-会话管理)
- [0.10 高级功能](#010-高级功能)

---

## 0.1 Chat 页面总览

### 0.1.1 页面定位

`Chat.tsx` 是整个前端**最复杂**的页面，约 1200+ 行代码，承担了与 AI Agent 实时交互的全部职责：

```
┌──────────────────────────────────────────────────────┐
│ Chat                                                  │
│ ┌──────────┬────────────────────────┬──────────────┐ │
│ │ 会话     │    消息面板            │  侧边栏      │ │
│ │ 侧边栏   │  ChatMessagesPanel    │  (可选)      │ │
│ │          │                       │  • 语音转写   │ │
│ │ 搜索     │  ┌─────────────────┐  │  • Artifact  │ │
│ │ 分页     │  │ 用户/Agent 消息  │  │  • 反馈弹窗  │ │
│ │          │  │ 思考/工具/沙箱   │  │              │ │
│ │          │  └─────────────────┘  │              │ │
│ │          │                       │              │ │
│ │          │  ChatComposer (输入)  │              │ │
│ └──────────┴────────────────────────┴──────────────┘ │
└──────────────────────────────────────────────────────┘
```

### 0.1.2 文件结构

| 文件 | 职责 |
|------|------|
| `pages/chat/Chat.tsx` | 主页面，核心状态与 SSE 处理 |
| `pages/chat/types.ts` | 消息类型定义 |
| `pages/chat/utils.ts` | 历史消息转换工具 |
| `pages/chat/streamRecovery.ts` | SSE 流恢复逻辑 |
| `pages/chat/components/` | 子组件目录 |
| `pages/chat/hooks/` | 自定义 Hooks |
| `api/session.ts` | 会话 API 与 SSE 连接 |

---

## 0.2 核心状态与常量

### 0.2.1 关键常量

```ts
// pages/chat/Chat.tsx

// 草稿会话 ID：用户打开新聊天但还没发送消息时使用
const DRAFT_CHAT_SESSION_ID = '__new_chat_draft__'

// 看门狗间隔：每 10 秒检查一次是否有卡住的轮次
const TURN_WATCHDOG_INTERVAL_MS = 10000

// SSE 最大重连次数
const MAX_TURN_RECONNECTS = 3
```

💡 **设计意图**：`DRAFT_CHAT_SESSION_ID` 解决了一个鸡生蛋的问题——用户打开新聊天页面时还没有创建真正的会话（会话在第一条消息发送时才由后端创建），但 UI 需要一个 session ID 来渲染。使用特殊字符串作为占位符，发送第一条消息后替换为真实 ID。

### 0.2.2 核心状态

```ts
// Chat.tsx 中的主要状态（简化）

// 消息列表
const [messages, setMessages] = useState<ChatMessage[]>([])

// 对话状态：idle（空闲）/ running（正在生成）
const [status, setStatus] = useState<'idle' | 'running'>('idle')

// 模型是否正在"思考"（reasoning 阶段）
const [isThinking, setIsThinking] = useState(false)

// 等待用户输入的信息（如工具审批）
const [awaitInfo, setAwaitInfo] = useState<AwaitInfo | null>(null)

// 计划/任务进度
const [planTodos, setPlanTodos] = useState<PlanTodo[]>([])

// 上下文压缩信息
const [compressionInfo, setCompressionInfo] = useState<CompressionInfo | null>(null)

// Agent 列表
const [myAgents, setMyAgents] = useState<Agent[]>([])
const [otherAgents, setOtherAgents] = useState<Agent[]>([])
const [selectedAgentId, setSelectedAgentId] = useState<string>('')

// 当前会话 ID
const [sessionId, setSessionId] = useState<string>(DRAFT_CHAT_SESSION_ID)

// 变量值（用于会话配置）
const [variableValues, setVariableValues] = useState<Record<string, string>>({})

// 已加载的资源和技能
const [loadedToolIds, setLoadedToolIds] = useState<string[]>([])
const [loadedSkillIds, setLoadedSkillIds] = useState<string[]>([])
```

💡 **设计意图**：状态数量多是 Chat 页面复杂度的根源。每个状态对应 SSE 流中的一种事件类型或 UI 的一个独立维度。理解这些状态是理解整个页面的前提。

---

## 0.3 消息类型系统

### 0.3.1 ChatMessage

```ts
// pages/chat/types.ts
export interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  segments: MessageSegment[]    // 消息由多个片段组成
  timestamp: number
  sessionId?: string
  // 用于渲染优化的标识
  id?: string
}
```

### 0.3.2 MessageSegment 类型

一条 Agent 消息可能同时包含文本、工具调用、思考过程等多种内容，因此用 `segments` 数组表达：

```ts
// pages/chat/types.ts
export type MessageSegment =
  | TextSegment        // 文本内容
  | ToolsSegment       // 工具调用结果
  | SandboxSegment     // 沙箱执行
  | ThinkingSegment    // 思考/推理过程
  | PlanSegment        // 计划/任务进度

interface TextSegment {
  type: 'text'
  content: string
}

interface ToolsSegment {
  type: 'tools'
  events: ToolEvent[]
}

interface SandboxSegment {
  type: 'sandbox'
  output: string
  status: 'running' | 'completed' | 'error'
}

interface ThinkingSegment {
  type: 'thinking'
  content: string
}

interface PlanSegment {
  type: 'plan'
  todos: PlanTodo[]
}
```

💡 **设计意图**：用 segments 数组而非单一 string 来表达消息内容，是因为 Agent 的一次回复是"多模态"的——它可能先输出一段文字，然后调用工具，再输出结果。segments 保留了这种时序结构。

### 0.3.3 ToolEvent 与 PlanTodo

```ts
export interface ToolEvent {
  toolId: string
  name: string
  status: 'running' | 'completed' | 'error' | 'waiting_approval'
  input?: string
  output?: string
  error?: string
}

export interface PlanTodo {
  id: string
  content: string
  status: 'pending' | 'in_progress' | 'completed'
}
```

### 0.3.4 AwaitInfo

当 Agent 需要用户确认（如工具审批）时，使用 `AwaitInfo` 表达等待状态：

```ts
export interface AwaitInfo {
  type: 'tool_approval' | 'user_input'
  toolCallId?: string
  toolName?: string
  message: string
}
```

---

## 0.4 SSE 事件流

### 0.4.1 事件类型

Chat 页面通过 Server-Sent Events (SSE) 接收后端的实时推送：

| 事件名 | 含义 | 处理逻辑 |
|--------|------|---------|
| `text_chunk` | 文本片段 | 追加到当前消息的 TextSegment |
| `reasoning_chunk` | 推理/思考片段 | 追加到 ThinkingSegment |
| `tool_start` | 工具开始执行 | 创建新的 ToolEvent |
| `tool_result` | 工具执行结果 | 更新 ToolEvent 的 output |
| `tool_approval_request` | 工具需要审批 | 设置 awaitInfo |
| `turn_complete` | 本轮对话结束 | status → idle |
| `plan_update` | 计划更新 | 更新 planTodos |
| `environment_output` | 沙箱输出 | 更新 SandboxSegment |
| `compression` | 上下文压缩通知 | 更新 compressionInfo |
| `error` | 错误 | 显示错误消息 |
| `status_change` | 状态变化 | 更新 UI 状态指示 |
| `sandbox` | 沙箱事件 | 更新 SandboxSegment |

### 0.4.2 SSE 连接管理

```ts
// api/session.ts (简化)
export const sessionApi = {
  // 创建 SSE 连接
  connect(sessionId: string, onEvent: (event: SSEEvent) => void) {
    const eventSource = new EventSource(
      `/api/sessions/${sessionId}/stream`
    )

    // 注册各类事件监听
    SSE_EVENT_TYPES.forEach(type => {
      eventSource.addEventListener(type, (e) => {
        const data = JSON.parse(e.data)
        onEvent({ type, data })
      })
    })

    eventSource.onerror = () => {
      // 连接断开，触发重连逻辑
      onEvent({ type: 'error', data: { reconnecting: true } })
    }

    return eventSource
  },

  // 发送消息（触发 Agent 开始响应）
  sendMessage(sessionId: string, content: string, options?: SendOptions) {
    return request<{ message_id: string }>(
      `/api/sessions/${sessionId}/messages`,
      {
        method: 'POST',
        body: JSON.stringify({ content, ...options })
      }
    )
  },

  // 获取历史消息
  getHistory(sessionId: string) {
    return request<HistoryMessage[]>(
      `/api/sessions/${sessionId}/messages`
    )
  },

  // CRUD 操作
  createSession(config: SessionConfig) { /* ... */ },
  deleteSession(sessionId: string) { /* ... */ },
  listSessions(params: ListParams) { /* ... */ },
}
```

💡 **设计意图**：SSE 而非 WebSocket，是因为通信模式是单向的（后端→前端推送），前端只需要普通 HTTP POST 发送消息。SSE 更简单、自动重连、对 HTTP/2 友好。

---

## 0.5 流恢复机制

### 0.5.1 为什么需要流恢复

SSE 连接可能因为网络波动、页面刷新、浏览器休眠等原因断开。用户刷新页面后，需要能恢复到断开前的状态，而不是丢失正在进行的对话。

### 0.5.2 streamRecovery.ts 核心函数

```ts
// pages/chat/streamRecovery.ts

// 清除尾部的 Agent 气泡（用于幂等重放）
export function clearActiveAgentBubble(messages: ChatMessage[]): ChatMessage[] {
  // 找到最后一条 assistant 消息
  // 如果它是"进行中"状态（还有未完成的 segment），清除它
  // 这样重放历史时不会重复
  // ...
}

// 合并历史记录与实时流
export function mergeHistoryWithLive(
  history: ChatMessage[],
  live: ChatMessage[]
): ChatMessage[] {
  // 如果历史还没有包含正在进行的 Agent 回复，
  // 保留 live 中的"飞行中" Agent 气泡
  // ...
}

// 解析恢复策略
export function resolveRestoredTurn(
  sessionId: string,
  messages: ChatMessage[]
): 'resume' | 'resync' | 'none' {
  // resume: 从断点继续（SSE 还能重连）
  // resync: 重新同步（用历史 API 刷新）
  // none: 无需恢复
  // ...
}

// 确保用户消息后有一个 Agent 气泡
export function ensureTrailingAgentBubble(messages: ChatMessage[]): ChatMessage[] {
  // 在用户消息后面添加一个空的 assistant 气泡
  // 这样 SSE 事件可以直接往里追加内容
  // ...
}
```

💡 **设计意图**：流恢复的关键在于"幂等性"——无论重放多少次，结果都一样。`clearActiveAgentBubble` 就是为了确保在重放前先清空"半成品"，再从历史中完整重建。

### 0.5.3 看门狗机制

```ts
// Chat.tsx 中的看门狗
useEffect(() => {
  if (status !== 'running') return

  const timer = setInterval(() => {
    // 检查最后一次收到事件的时间
    const elapsed = Date.now() - lastEventTimeRef.current

    if (elapsed > TURN_WATCHDOG_INTERVAL_MS) {
      // 超过 10 秒没有新事件，可能卡住了
      if (reconnectCountRef.current < MAX_TURN_RECONNECTS) {
        reconnectCountRef.current++
        // 尝试重连 SSE
        reconnectSSE()
      } else {
        // 超过重连次数，放弃
        setStatus('idle')
        setError('连接超时，请刷新页面重试')
      }
    }
  }, TURN_WATCHDOG_INTERVAL_MS)

  return () => clearInterval(timer)
}, [status])
```

💡 **设计意图**：SSE 断开时 `onerror` 不一定会触发（比如网络"半开"状态——TCP 连接还在但数据不流动）。看门狗用"最后事件时间"作为活性判据，补充了 SSE 原生重连机制的不足。

---

## 0.6 消息渲染管线

### 0.6.1 历史消息转换

后端返回的历史消息格式与前端 `ChatMessage` 不同，需要转换：

```ts
// pages/chat/utils.ts
export function historyToChatMessages(
  history: HistoryMessage[]
): ChatMessage[] {
  return history.map(msg => ({
    role: msg.role,
    segments: parseContentToSegments(msg.content),
    timestamp: new Date(msg.created_at).getTime(),
    sessionId: msg.session_id,
    id: msg.id
  }))
}

function parseContentToSegments(content: HistoryContent): MessageSegment[] {
  const segments: MessageSegment[] = []

  // 解析文本部分
  if (content.text) {
    segments.push({ type: 'text', content: content.text })
  }

  // 解析工具调用
  if (content.tool_calls?.length) {
    segments.push({
      type: 'tools',
      events: content.tool_calls.map(tc => ({
        toolId: tc.id,
        name: tc.name,
        status: tc.status,
        input: tc.arguments,
        output: tc.result
      }))
    })
  }

  // 解析思考过程
  if (content.reasoning) {
    segments.push({ type: 'thinking', content: content.reasoning })
  }

  return segments
}
```

### 0.6.2 缓存恢复

```ts
// pages/chat/utils.ts
export function restoreCachedChatMessages(
  sessionId: string
): ChatMessage[] | null {
  try {
    const cached = sessionStorage.getItem(`chat_${sessionId}`)
    if (!cached) return null
    return JSON.parse(cached)
  } catch {
    return null
  }
}
```

💡 **设计意图**：用 `sessionStorage`（而非 `localStorage`）缓存消息，因为聊天内容是临时的、会话级别的。切换标签页时不需要保留，关闭标签页后自动清理。

### 0.6.3 toToolRef：工具 ID 分类

```ts
// Chat.tsx 中的辅助函数
interface ToolRef {
  id: string
  type: 'mcp' | 'config' | 'api' | 'llm'
  source: string
}

function toToolRef(toolId: string): ToolRef {
  if (toolId.startsWith('mcp-tool:')) {
    return { id: toolId.slice(9), type: 'mcp', source: 'MCP Server' }
  }
  if (toolId.startsWith('config:')) {
    return { id: toolId.slice(7), type: 'config', source: '内置工具' }
  }
  if (toolId.startsWith('api-app:')) {
    return { id: toolId.slice(8), type: 'api', source: 'API 工具' }
  }
  if (toolId.startsWith('llm-call:')) {
    return { id: toolId.slice(9), type: 'llm', source: 'LLM 调用' }
  }
  return { id: toolId, type: 'config', source: '未知' }
}
```

💡 **设计意图**：不同来源的工具 ID 有不同的前缀约定。`toToolRef()` 统一解析，使下游组件不需要关心工具到底来自 MCP、内置配置还是 API 应用。

---

## 0.7 子组件体系

### 0.7.1 ChatMessagesPanel

```tsx
// pages/chat/components/ChatMessagesPanel.tsx

// 虚拟化常量
const INITIAL_VISIBLE_MESSAGES = 20   // 首次渲染消息数
const MESSAGE_RENDER_BATCH = 10       // 滚动加载更多时的批量

export function ChatMessagesPanel({ messages, status }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [visibleCount, setVisibleCount] = useState(INITIAL_VISIBLE_MESSAGES)

  // 只渲染最近的 N 条消息
  const visibleMessages = messages.slice(-visibleCount)

  // 滚动到顶部时加载更多
  const handleScroll = () => {
    if (containerRef.current?.scrollTop === 0) {
      setVisibleCount(prev => prev + MESSAGE_RENDER_BATCH)
    }
  }

  return (
    <div ref={containerRef} onScroll={handleScroll}
         className="flex-1 overflow-y-auto">
      {visibleMessages.map(msg => (
        <MessageBubble key={msg.id} message={msg} />
      ))}
    </div>
  )
}
```

💡 **设计意图**：长对话可能包含数百条消息，全部渲染会导致性能问题。通过"虚拟滚动"——只渲染可见区域附近的消息——将渲染成本控制在常数级别。

### 0.7.2 ChatComposer

输入区域组件，支持文本输入、附件上传、语音输入、资源选择：

```tsx
// pages/chat/components/ChatComposer.tsx
export function ChatComposer({
  onSend,
  status,
  awaitInfo,
  onApprove,      // 工具审批通过
  onReject        // 工具审批拒绝
}: Props) {
  const [input, setInput] = useState('')
  const [attachments, setAttachments] = useState<File[]>([])

  const handleSend = () => {
    if (!input.trim() && !attachments.length) return
    onSend(input, attachments)
    setInput('')
    setAttachments([])
  }

  return (
    <div className="border-t p-4">
      {/* 工具审批提示 */}
      {awaitInfo && (
        <ApprovalBanner info={awaitInfo}
          onApprove={onApprove} onReject={onReject} />
      )}

      {/* 附件列表 */}
      {attachments.map(f => <AttachmentChip key={f.name} file={f} />)}

      {/* 输入区域 */}
      <div className="flex gap-2">
        <ResourcePickerButton />
        <textarea
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              handleSend()
            }
          }}
          placeholder="输入消息..."
        />
        <VoiceInputButton />
        <button onClick={handleSend} disabled={status === 'running'}>
          发送
        </button>
      </div>
    </div>
  )
}
```

### 0.7.3 ThinkingBlock

```tsx
// pages/chat/components/ThinkingBlock.tsx
export function ThinkingBlock({ content }: { content: string }) {
  const [expanded, setExpanded] = useState(false)

  return (
    <div className="my-2 border-l-2 border-gray-300 pl-3">
      <button
        onClick={() => setExpanded(!expanded)}
        className="text-sm text-gray-500 flex items-center gap-1"
      >
        {expanded ? '▼' : '▶'} 思考过程
      </button>
      {expanded && (
        <div className="mt-1 text-sm text-gray-600 whitespace-pre-wrap">
          {content}
        </div>
      )}
    </div>
  )
}
```

💡 **设计意图**：思考过程默认折叠，因为大多数用户只关心最终答案。但对开发者调试 Agent 行为时，展开思考过程非常有价值。

### 0.7.4 ToolsBlock

```tsx
// pages/chat/components/ToolsBlock.tsx
export function ToolsBlock({ events }: { events: ToolEvent[] }) {
  return (
    <div className="my-2 space-y-2">
      {events.map(event => (
        <div key={event.toolId}
             className="border rounded p-2 bg-gray-50">
          <div className="flex items-center gap-2">
            <StatusIcon status={event.status} />
            <span className="font-mono text-sm">{event.name}</span>
          </div>
          {event.input && (
            <details className="mt-1">
              <summary className="text-xs text-gray-500">输入</summary>
              <pre className="text-xs mt-1 overflow-x-auto">
                {event.input}
              </pre>
            </details>
          )}
          {event.output && (
            <details className="mt-1">
              <summary className="text-xs text-gray-500">输出</summary>
              <pre className="text-xs mt-1 overflow-x-auto">
                {event.output}
              </pre>
            </details>
          )}
        </div>
      ))}
    </div>
  )
}
```

### 0.7.5 其他子组件一览

| 组件 | 文件 | 功能 |
|------|------|------|
| **SandboxBlock** | `SandboxBlock.tsx` | 展示沙箱代码执行输出 |
| **SandboxTerminalPanel** | `SandboxTerminalPanel.tsx` | 内嵌终端 |
| **PlanUpdateBlock** | `PlanUpdateBlock.tsx` | 展示计划/任务进度条 |
| **ArtifactCard / ArtifactDrawer** | `ArtifactCard.tsx` / `ArtifactDrawer.tsx` | Artifact 查看 |
| **FeedbackModal** | `FeedbackModal.tsx` | 轮次反馈（点赞/点踩） |
| **VoiceTranscriberSidebar** | `VoiceTranscriberSidebar.tsx` | 语音转文字（懒加载） |
| **AuthedImage** | `AuthedImage.tsx` | 带鉴权的图片加载 |
| **CopyButton** | `CopyButton.tsx` | 复制到剪贴板 |
| **AgentSelector** | `AgentSelector.tsx` | Agent 选择下拉框 |
| **ChatSessionsSidebar** | `ChatSessionsSidebar.tsx` | 会话列表侧边栏 |
| **ResourcePicker** | `ResourcePicker.tsx` | 选择工具/技能/数据集 |
| **ChatConfigModal** | `ChatConfigModal.tsx` | 会话配置弹窗 |

💡 **设计意图**：`VoiceTranscriberSidebar`、`ArtifactDrawer`、`FeedbackModal` 采用**懒加载**（`React.lazy`），因为它们使用了较重的第三方库（语音 SDK、代码渲染器等），不需要在页面初始加载时就下载。

---

## 0.8 Agent 选择与资源管理

### 0.8.1 AgentSelector

```tsx
// pages/chat/components/AgentSelector.tsx
export function AgentSelector({
  myAgents, otherAgents, selectedId, onSelect
}: Props) {
  return (
    <select value={selectedId} onChange={e => onSelect(e.target.value)}>
      <optgroup label="我的 Agent">
        {myAgents.map(a => (
          <option key={a.id} value={a.id}>{a.name}</option>
        ))}
      </optgroup>
      <optgroup label="其他 Agent">
        {otherAgents.map(a => (
          <option key={a.id} value={a.id}>{a.name}</option>
        ))}
      </optgroup>
    </select>
  )
}
```

### 0.8.2 ResourcePicker

```tsx
// pages/chat/components/ResourcePicker.tsx
// 用于选择当前会话要使用的工具、技能、数据集
export function ResourcePicker({
  availableTools, availableSkills, availableDatasets,
  selectedToolIds, selectedSkillIds, selectedDatasetIds,
  onChange
}: Props) {
  return (
    <div className="space-y-4">
      <section>
        <h3>工具</h3>
        {availableTools.map(tool => (
          <Checkbox key={tool.id} label={tool.name}
            checked={selectedToolIds.includes(tool.id)}
            onChange={...} />
        ))}
      </section>
      <section>
        <h3>技能</h3>
        {availableSkills.map(skill => (
          <Checkbox key={skill.id} label={skill.name}
            checked={selectedSkillIds.includes(skill.id)}
            onChange={...} />
        ))}
      </section>
      <section>
        <h3>数据集</h3>
        {availableDatasets.map(ds => (
          <Checkbox key={ds.id} label={ds.name}
            checked={selectedDatasetIds.includes(ds.id)}
            onChange={...} />
        ))}
      </section>
    </div>
  )
}
```

---

## 0.9 会话管理

### 0.9.1 ChatSessionsSidebar

```tsx
// pages/chat/components/ChatSessionsSidebar.tsx
export function ChatSessionsSidebar({
  sessions, currentId, onSelect, onDelete
}: Props) {
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)
  const PAGE_SIZE = 20

  const filtered = sessions.filter(s =>
    s.title?.toLowerCase().includes(search.toLowerCase())
  )

  const paged = filtered.slice(
    (page - 1) * PAGE_SIZE,
    page * PAGE_SIZE
  )

  return (
    <div className="w-64 border-r h-full flex flex-col">
      {/* 搜索 */}
      <input
        value={search}
        onChange={e => setSearch(e.target.value)}
        placeholder="搜索会话..."
        className="p-2 border-b"
      />

      {/* 新建会话按钮 */}
      <button className="m-2 p-2 bg-blue-500 text-white rounded">
        + 新会话
      </button>

      {/* 会话列表 */}
      <div className="flex-1 overflow-y-auto">
        {paged.map(session => (
          <div key={session.id}
               className={`p-2 cursor-pointer hover:bg-gray-100
                 ${session.id === currentId ? 'bg-blue-50' : ''}`}
               onClick={() => onSelect(session.id)}>
            <div className="font-medium truncate">
              {session.title || '新会话'}
            </div>
            <div className="text-xs text-gray-500">
              {formatTime(session.updated_at)}
            </div>
          </div>
        ))}
      </div>

      {/* 分页 */}
      <div className="p-2 border-t flex justify-between">
        <button disabled={page <= 1} onClick={() => setPage(p => p - 1)}>
          上一页
        </button>
        <span>{page}</span>
        <button disabled={paged.length < PAGE_SIZE}
                onClick={() => setPage(p => p + 1)}>
          下一页
        </button>
      </div>
    </div>
  )
}
```

### 0.9.2 会话生命周期

```
用户点击"新会话"
    │
    ▼
sessionId = DRAFT_CHAT_SESSION_ID（草稿）
messages = []
    │
    ▼
用户发送第一条消息
    │
    ▼
POST /api/sessions  → 后端创建真实会话
    │
    ▼
sessionId = 真实 ID（替换草稿 ID）
POST /api/sessions/{id}/messages → 触发 Agent 响应
    │
    ▼
SSE 连接建立，开始接收流式事件
    │
    ▼
用户切换到其他会话
    │
    ▼
当前消息缓存到 sessionStorage
关闭 SSE 连接
加载目标会话的历史消息
```

💡 **设计意图**：草稿会话 ID 的设计让"新会话"页面的渲染逻辑和"已有会话"完全一致，不需要特殊分支处理。直到用户真正发送消息时，才创建后端资源，避免了创建大量空会话。

---

## 0.10 高级功能

### 0.10.1 语音转写

```ts
// pages/chat/hooks/useSpeechRecognition.ts
export function useSpeechRecognition() {
  const [transcript, setTranscript] = useState('')
  const [isListening, setIsListening] = useState(false)

  const start = () => {
    // 使用 Web Speech API
    const recognition = new (window as any).webkitSpeechRecognition()
    recognition.lang = 'zh-CN'
    recognition.continuous = true
    recognition.interimResults = true

    recognition.onresult = (event: any) => {
      const result = event.results[event.results.length - 1]
      if (result.isFinal) {
        setTranscript(prev => prev + result[0].transcript)
      }
    }

    recognition.start()
    setIsListening(true)
  }

  const stop = () => {
    recognition?.stop()
    setIsListening(false)
  }

  return { transcript, isListening, start, stop }
}
```

💡 **设计意图**：使用浏览器原生 Web Speech API 而非外部语音服务，零成本且隐私友好。`VoiceTranscriberSidebar` 作为侧边栏懒加载组件，不使用时不会加载语音相关代码。

### 0.10.2 Markdown 安全渲染

```ts
// pages/chat/markdownSanitizeSchema.ts
// rehype-sanitize 的 schema 配置
export const sanitizeSchema = {
  tagNames: [
    'p', 'br', 'strong', 'em', 'code', 'pre',
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'ul', 'ol', 'li', 'a', 'blockquote',
    'table', 'thead', 'tbody', 'tr', 'th', 'td',
    'img', 'hr', 'del', 'input'   // input 用于 task list
  ],
  attributes: {
    a: ['href', 'title'],
    img: ['src', 'alt', 'width', 'height'],
    input: ['type', 'checked', 'disabled'],
    code: ['className'],            // 用于语法高亮
    td: ['align'], th: ['align']
  },
  // 禁止 javascript: 协议
  protocols: {
    a: { href: ['http', 'https', 'mailto'] },
    img: { src: ['http', 'https', 'data'] }
  }
}
```

💡 **设计意图**：Agent 返回的内容包含 Markdown，直接渲染 HTML 有 XSS 风险。通过白名单 schema 过滤，只允许安全的标签和属性通过，同时保留 Markdown 的常用格式能力。

### 0.10.3 上下文压缩

当对话过长时，后端会自动压缩历史上下文。前端通过 `compression` SSE 事件收到通知：

```ts
// Chat.tsx 中处理 compression 事件
case 'compression':
  setCompressionInfo({
    originalTokens: data.original_tokens,
    compressedTokens: data.compressed_tokens,
    strategy: data.strategy   // "summary" | "truncation"
  })
  // 在 UI 上显示提示："上下文已压缩，节省 XX% token"
  break
```

---

### 🔧 动手实践

#### 练习 1：观察 SSE 事件

```bash
# 1. 启动前端和后端
# 2. 打开浏览器开发者工具 → Network → EventStream
# 3. 在 Chat 页面发送一条消息
# 4. 找到对应的 SSE 连接，观察事件流
# 5. 记录你看到的事件类型和顺序
```

#### 练习 2：添加自定义消息段

```tsx
// 1. 在 pages/chat/types.ts 中添加新的 Segment 类型
export interface ImageSegment {
  type: 'image'
  url: string
  caption?: string
}

// 2. 更新 MessageSegment 联合类型
export type MessageSegment =
  | TextSegment | ToolsSegment | SandboxSegment
  | ThinkingSegment | PlanSegment | ImageSegment

// 3. 创建对应的渲染组件
// pages/chat/components/ImageBlock.tsx
export function ImageBlock({ url, caption }: ImageSegment) {
  return (
    <figure className="my-2">
      <img src={url} alt={caption} className="max-w-full rounded" />
      {caption && <figcaption className="text-sm text-gray-500 mt-1">{caption}</figcaption>}
    </figure>
  )
}

// 4. 在 MessageBubble 中添加渲染分支
```

#### 练习 3：调试流恢复

```bash
# 1. 在 Chat 页面发送一条较长的消息（触发 Agent 长时间响应）
# 2. 打开 DevTools → Network → 找到 SSE 连接
# 3. 右键 → "Close connection" 模拟断连
# 4. 观察看门狗是否检测到断连并尝试重连
# 5. 刷新页面，观察消息是否从缓存中恢复
```

#### 练习 4：查看 sessionStorage 缓存

```js
// 在浏览器 Console 中
// 列出所有缓存的聊天消息
Object.keys(sessionStorage)
  .filter(k => k.startsWith('chat_'))
  .forEach(k => {
    const msgs = JSON.parse(sessionStorage.getItem(k))
    console.log(k, '→', msgs.length, '条消息')
  })
```

---

### 📝 自测题

1. `DRAFT_CHAT_SESSION_ID` 的作用是什么？为什么不在用户点击"新会话"时就创建后端会话？

2. `ChatMessage` 为什么用 `segments` 数组而不是单个 `content` 字符串？

3. SSE 事件 `tool_approval_request` 触发后，UI 会如何响应？用户如何完成审批？

4. `TURN_WATCHDOG_INTERVAL_MS` 和 `MAX_TURN_RECONNECTS` 分别解决什么问题？

5. `clearActiveAgentBubble()` 为什么需要在流恢复时调用？如果不调用会发生什么？

6. `toToolRef()` 函数处理了哪几种工具 ID 前缀？每种对应什么工具来源？

7. `sanitizeSchema` 为什么要禁止 `javascript:` 协议？它允许了哪些 `<a>` 标签的属性？

8. 为什么 `VoiceTranscriberSidebar` 使用 `React.lazy` 懒加载，而 `ChatMessagesPanel` 不需要？

---

## 🎉 本章小结

本章你学会了：

✅ 理解 Chat 页面的整体架构和核心状态  
✅ 掌握消息类型系统（ChatMessage、MessageSegment）  
✅ 理解 SSE 事件流的 12 种事件类型和处理逻辑  
✅ 掌握流恢复机制（看门狗、断点续传、幂等重放）  
✅ 理解消息渲染管线（历史转换、缓存恢复、虚拟化）  
✅ 熟悉所有子组件的职责和交互  
✅ 掌握 Agent 选择和资源管理的实现  
✅ 了解会话生命周期和草稿会话 ID 的设计  
✅ 学会语音转写、Markdown 安全渲染等高级功能  

---

## 🚀 下一章

准备好进入 [06-Agent与工作流](./06-Agent与工作流.md)，学习 Agent 管理和工作流编排的实现！

---

*最后更新: 2026-08-31*
