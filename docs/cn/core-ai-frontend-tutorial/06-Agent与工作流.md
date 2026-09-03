# 06 - Agent 与工作流

> 🎯 **学习目标**：掌握 core-ai-frontend 中 Agent 管理、工作流编排和系统提示词的完整使用，理解页面组件结构与 API 交互方式
>
> ⏱️ **预计时间**：1.5 天

---

## 📋 本章内容

- [0.1 Agent 概览与页面结构](#01-agent-概览与页面结构)
- [0.2 Agent 列表与筛选](#02-agent-列表与筛选)
- [0.3 Agent 编辑器详解](#03-agent-编辑器详解)
- [0.4 Agent 记忆管理](#04-agent-记忆管理)
- [0.5 Agent 运行详情](#05-agent-运行详情)
- [0.6 工作流概览](#06-工作流概览)
- [0.7 工作流列表与探索](#07-工作流列表与探索)
- [0.8 工作流可视化编辑器](#08-工作流可视化编辑器)
- [0.9 工作流版本与发布](#09-工作流版本与发布)
- [0.10 工作流运行与调试](#10-工作流运行与调试)
- [0.11 系统提示词管理](#11-系统提示词管理)
- [0.12 数据类型与 API 总结](#12-数据类型与-api-总结)

---

## 0.1 Agent 概览与页面结构

Agent 是 core-ai 平台的核心运行单元。在前端，Agent 相关页面集中在 `pages/agents/` 目录下，提供从列表浏览、编辑创建、运行查看到记忆管理的完整闭环。

### 0.1.1 Agent 页面文件清单

| 文件 | 职责 |
|------|------|
| `AgentList.tsx` | Agent 列表页，支持搜索、分页、按类型/状态筛选 |
| `AgentEditor.tsx` | Agent 全字段编辑器，覆盖模型、提示词、工具、沙箱、数据集等配置 |
| `AgentMemory.tsx` | Agent 记忆查看与删除 |
| `RunDetail.tsx` | Agent 单次运行详情，展示 transcript 时间线 |

💡 **设计意图**：将「列表 → 编辑 → 运行 → 记忆」拆为独立页面，每个页面聚焦单一职责，便于维护和独立测试。

### 0.1.2 Agent 数据模型核心字段

```typescript
// api/client.ts - AgentDefinition
interface AgentDefinition {
  id: string;
  name: string;
  description: string;
  system_prompt: string;       // 系统提示词内容
  system_prompt_id?: string;   // 关联的系统提示词 ID
  model: string;               // 使用的模型
  multi_modal_model?: string;  // 多模态模型
  temperature: number;         // 温度参数
  thinking_effort?: string;    // 推理力度
  max_turns: number;           // 最大对话轮数
  timeout_seconds: number;     // 超时时间（秒）
  tools: ToolRef[];            // 关联的工具列表
  input_template?: string;     // 输入模板
  variables: Record<string, string>; // 变量
  subagent_ids: string[];      // 子 Agent ID
  skill_ids: string[];         // 技能 ID
  sandbox_config?: SandboxConfig;  // 沙箱配置
  dataset_config?: object;     // 数据集配置
  enable_memory: boolean;      // 是否启用记忆
}
```

💡 **设计意图**：`AgentDefinition` 将「模型选型」「提示词」「工具」「子 Agent」「沙箱」「数据集」等维度集中到单一结构，使得一个 Agent 可以被完整描述、导入导出和版本化。

---

## 0.2 Agent 列表与筛选

`AgentList.tsx` 提供 Agent 的浏览入口。页面支持三大核心能力：搜索、筛选、分页。

### 0.2.1 列表页功能

- **搜索**：根据名称或描述进行模糊搜索
- **筛选**：按 Agent 类型（如主 Agent、子 Agent）和状态（启用/禁用）过滤
- **分页**：服务端分页，避免大数据量下的前端性能问题
- **操作入口**：新建、编辑、复制、删除、查看运行记录

### 0.2.2 列表交互流程

```
用户输入关键词
  → 触发搜索请求（防抖 300ms）
  → API 返回分页数据
  → 渲染列表项
  → 点击行 → 跳转 AgentEditor
  → 点击「运行记录」→ 跳转 RunDetail
```

💡 **设计意图**：列表页采用「服务端分页 + 客户端防抖」的组合，在数据量增长时保持流畅体验。

---

## 0.3 Agent 编辑器详解

`AgentEditor.tsx` 是前端最复杂的表单页面之一，覆盖 Agent 的所有可配置字段。

### 0.3.1 编辑器字段分组

| 分组 | 字段 | 说明 |
|------|------|------|
| **基础信息** | name, description | Agent 名称和描述 |
| **提示词** | system_prompt, system_prompt_id | 提示词内容或关联 ID |
| **模型配置** | model, multi_modal_model, temperature, thinking_effort | 模型与生成参数 |
| **运行控制** | max_turns, timeout_seconds | 最大轮数、超时 |
| **能力配置** | tools, skills, sub_agents | 工具、技能、子 Agent |
| **变量** | variables | 提示词中的变量占位符 |
| **结构化输出** | response_schema | Java-to-Schema 转换 |
| **沙箱** | sandbox_config | 代码执行沙箱 |
| **数据集** | dataset_config | 数据集关联 |
| **记忆** | enable_memory | 是否启用长期记忆 |

### 0.3.2 响应模式 Schema 转换

编辑器内置 **Java-to-Schema** 转换器：用户输入 Java 类的定义，前端自动解析为 JSON Schema，供 Agent 输出结构化结果。

```
输入 Java 类定义
  → 前端解析器提取字段与类型
  → 生成 JSON Schema
  → 写入 response_schema 字段
  → Agent 运行时强制输出符合 schema 的 JSON
```

💡 **设计意图**：让业务用户无需手写 JSON Schema，降低结构化输出的使用门槛。

### 0.3.3 编辑器保存流程

```typescript
// 伪代码 - AgentEditor 保存逻辑
async function saveAgent(agent: AgentDefinition) {
  // 1. 前端校验（必填项、数值范围）
  validate(agent);
  // 2. 调用 API
  if (agent.id) {
    await api.agents.update(agent.id, agent);
  } else {
    const created = await api.agents.create(agent);
    navigate(`/agents/${created.id}`);
  }
  // 3. 提示成功
  toast.success('保存成功');
}
```

---

## 0.4 Agent 记忆管理

`AgentMemory.tsx` 提供对 Agent 长期记忆的查看与清理能力。

### 0.4.1 记忆数据结构

```typescript
interface AgentMemoryView {
  id: string;           // 记忆 ID
  type: string;         // 记忆类型
  layer: string;        // 记忆层级
  content: string;      // 记忆内容
  source_trace_ids: string[];  // 来源 Trace ID
}
```

### 0.4.2 记忆页面功能

- **列表展示**：按时间倒序展示 Agent 的所有记忆
- **详情查看**：点击记忆查看完整内容与来源 Trace
- **删除记忆**：支持单条删除，清理无用或错误记忆
- **来源追溯**：通过 `source_trace_ids` 可以跳转到产生该记忆的对话

💡 **设计意图**：记忆的可观测性是 Agent 可靠性的关键。提供「查看 → 追溯 → 删除」的闭环，让用户能够主动干预 Agent 的长期记忆。

---

## 0.5 Agent 运行详情

`RunDetail.tsx` 展示单次 Agent 运行的完整 transcript（对话记录）。

### 0.5.1 TranscriptEntry 结构

```typescript
interface TranscriptEntry {
  ts: string;      // 时间戳
  role: string;    // 角色（user / assistant / tool）
  content: string; // 文本内容
  name?: string;   // 工具名称（tool 角色）
  args?: object;   // 工具参数
  status?: string; // 工具状态（success / error）
  result?: string; // 工具返回结果
}
```

### 0.5.2 时间线展示

```
[12:00:01] user      : 帮我分析这段代码
[12:00:02] assistant  : 好的，我来调用代码分析工具...
[12:00:02] tool      : code_analysis({file: "main.py"})
                        → status: success
                        → result: "代码结构良好..."
[12:00:05] assistant  : 分析完成，以下是建议...
```

💡 **设计意图**：时间线式的 transcript 让调试过程变得直观，特别是工具调用链路一目了然。

---

## 0.6 工作流概览

工作流（Workflow）是 core-ai 中用于编排复杂多步骤任务的可视化系统。前端采用 **@xyflow/react**（即 React Flow）作为图形编辑器基础。

### 0.6.1 工作流页面文件清单

| 文件 | 职责 |
|------|------|
| `WorkflowList.tsx` | 工作流列表，搜索/分页/筛选 |
| `WorkflowEditor.tsx` | 可视化图编辑器（React Flow） |
| `WorkflowExplore.tsx` | 探索公共/发布的工作流 |
| `WorkflowRuns.tsx` | 工作流运行记录列表 |

### 0.6.2 工作流核心数据模型

```typescript
// 工作流视图
interface WorkflowView {
  id: string;
  name: string;
  mode: string;              // 工作流模式
  status: string;            // 状态
  visibility: string;        // 可见性（private / public）
  published_version?: number; // 已发布版本号
  draft_graph?: object;      // 草稿图结构
  editable: boolean;         // 是否可编辑
}

// 工作流版本
interface WorkflowVersionView {
  version: number;
  graph: object;
  created_at: string;
  changelog?: string;
}

// 工作流运行记录
interface WorkflowRunView {
  id: string;
  status: string;           // 运行状态
  input: object;            // 输入参数
  output?: object;          // 输出结果
  artifacts?: object[];     // 产出物
  error?: string;           // 错误信息
  pending_inputs?: object;  // 待输入（人机交互节点）
}

// 节点运行记录
interface WorkflowNodeRunView {
  node_id: string;
  status: string;
  input?: object;
  output?: object;
  started_at: string;
  completed_at?: string;
}
```

💡 **设计意图**：工作流采用「草稿 / 已发布」双轨模型，草稿可反复修改不影响线上运行；发布后才产生新版本。

---

## 0.7 工作流列表与探索

### 0.7.1 列表页功能

`WorkflowList.tsx` 提供工作流的管理入口：

- **搜索**：按名称或描述搜索
- **筛选**：按状态（草稿/已发布/已归档）、可见性筛选
- **分页**：服务端分页
- **操作**：新建、编辑、克隆、导出、导入、删除

### 0.7.2 探索页

`WorkflowExplore.tsx` 是一个公共工作流市场：

- 浏览其他用户公开的工作流
- 一键克隆到自己的工作流列表
- 按分类、热度排序

💡 **设计意图**：探索页鼓励工作流的复用与共享，降低从零编排的成本。

---

## 0.8 工作流可视化编辑器

`WorkflowEditor.tsx` 是前端最复杂的组件之一，基于 **@xyflow/react** 构建。

### 0.8.1 React Flow 基础概念

```
Graph（图）
├── Node（节点）
│   ├── 类型：start / end / agent / tool / condition / loop / human
│   ├── 数据：节点配置（如 Agent ID、工具参数）
│   └── 端口：输入端口 / 输出端口
└── Edge（边）
    ├── 源节点 ID + 端口
    └── 目标节点 ID + 端口
```

### 0.8.2 编辑器功能清单

| 功能 | 说明 |
|------|------|
| **拖拽添加节点** | 从侧边栏拖拽节点类型到画布 |
| **连线** | 从端口拖拽到另一端口创建边 |
| **节点配置面板** | 点击节点弹出右侧配置面板 |
| **撤销/重做** | Ctrl+Z / Ctrl+Shift+Z |
| **缩放/平移** | 鼠标滚轮缩放，拖拽平移 |
| **小地图** | 右下角小地图辅助导航 |
| **自动布局** | 一键自动排列节点 |
| **校验** | 保存前检查图的连通性、必填项 |

### 0.8.3 编辑器保存与发布流程

```
编辑画布（draft_graph 实时更新）
  → 点击「保存草稿」→ api.workflows.update(id, { draft_graph })
  → 点击「校验」→ api.workflows.validate(id) → 返回错误列表
  → 点击「发布」→ api.workflows.publish(id) → 生成新版本号
  → 运行 → api.workflows.createRun(id) → 返回 run_id
```

💡 **设计意图**：草稿与发布分离，让用户可以放心在草稿中实验，不影响线上已发布版本。

---

## 0.9 工作流版本与发布

工作流具备完整的版本管理能力。

### 0.9.1 版本生命周期

```
[草稿] → 发布 → [版本 1]（线上运行）
         ↓
     继续编辑草稿 → 发布 → [版本 2]
         ↓
     回滚 → restoreVersion(1) → 线上切换回版本 1
```

### 0.9.2 版本相关 API

| API | 说明 |
|-----|------|
| `versions(id)` | 获取所有版本列表 |
| `saveVersion(id, graph)` | 保存指定版本 |
| `publishVersion(id, version)` | 发布指定版本 |
| `restoreVersion(id, version)` | 回滚到指定版本 |
| `unpublish(id)` | 取消发布 |
| `versionGraph(id, version)` | 获取指定版本的图结构 |

### 0.9.3 导入导出与克隆

| API | 说明 |
|-----|------|
| `export(id)` | 导出工作流为 JSON |
| `import(json)` | 从 JSON 导入工作流 |
| `clone(id)` | 克隆工作流（含图结构） |

💡 **设计意图**：版本管理 + 导入导出构成了工作流的「可移植性」，团队间可以轻松共享最佳实践。

---

## 0.10 工作流运行与调试

`WorkflowRuns.tsx` 展示工作流的运行历史。

### 0.10.1 运行记录字段

- **状态**：running / completed / failed / cancelled
- **输入/输出**：工作流的入口参数与最终结果
- **产出物（artifacts）**：运行过程中生成的文件、数据等
- **错误信息**：失败时的详细错误
- **待输入**：人机交互节点等待用户输入

### 0.10.2 节点级调试

```
查看某次运行
  → 点击「查看节点运行详情」
  → 列表展示每个节点的 input / output / 耗时
  → 定位到出错节点
  → 点击「从此节点重跑」→ api.workflows.resumeFromNode(runId, nodeId)
```

### 0.10.3 运行相关 API

| API | 说明 |
|-----|------|
| `createRun(id)` | 创建一次运行 |
| `runSync(id, input)` | 同步运行（等待结果） |
| `previewRun(id)` | 预览运行（dry-run） |
| `runs(id)` | 获取运行列表 |
| `getRun(runId)` | 获取单次运行详情 |
| `nodeRuns(runId)` | 获取节点运行列表 |
| `runGraph(id, graph)` | 直接运行图结构（不保存） |
| `resume(runId)` | 恢复运行（提供待输入） |
| `resumeFromNode(runId, nodeId)` | 从指定节点重跑 |

💡 **设计意图**：节点级重跑是工作流调试的杀手级功能——出错时不必从头重跑整个流程，大幅节省调试时间。

---

## 0.11 系统提示词管理

系统提示词（System Prompt）是 Agent 行为的核心定义。前端提供专门的提示词管理页面 `pages/system-prompts/`。

### 0.11.1 页面文件

| 文件 | 职责 |
|------|------|
| `SystemPromptList.tsx` | 提示词列表 |
| `SystemPromptEditor.tsx` | 提示词编辑器，支持版本历史与模型测试 |

### 0.11.2 数据模型

```typescript
interface SystemPrompt {
  id: string;
  promptId: string;       // 提示词业务 ID
  name: string;           // 名称
  description: string;    // 描述
  content: string;        // 提示词内容
  variables: string[];    // 变量列表
  version: number;        // 当前版本号
  changelog: string;      // 变更日志
  tags: string[];         // 标签
}

interface SystemPromptVersion {
  version: number;
  content: string;
  changelog: string;
  created_at: string;
}

interface SystemPromptTestResult {
  output: string;         // 模型输出
  tokens: number;         // Token 消耗
  duration_ms: number;    // 耗时
}
```

### 0.11.3 编辑器功能

- **富文本编辑**：支持 Markdown 与变量占位符 `{{variable}}`
- **版本历史**：查看历史版本，对比差异，回滚
- **在线测试**：选择模型，填入变量值，直接测试提示词效果
- **标签管理**：按标签分类提示词

💡 **设计意图**：提示词的「编辑 → 测试 → 版本化」闭环，让提示词工程从「黑盒」变为可迭代、可追溯的工程实践。

---

## 0.12 数据类型与 API 总结

### 0.12.1 Agent API 速查

| 操作 | API | 说明 |
|------|-----|------|
| 列表 | `api.agents.list()` | 分页列表 |
| 创建 | `api.agents.create(def)` | 创建 Agent |
| 详情 | `api.agents.get(id)` | 获取详情 |
| 更新 | `api.agents.update(id, def)` | 更新 Agent |
| 删除 | `api.agents.delete(id)` | 删除 Agent |
| 记忆 | `api.agents.memories(id)` | 获取记忆列表 |
| 删除记忆 | `api.agents.deleteMemory(id, mid)` | 删除指定记忆 |
| 运行详情 | `api.agents.runDetail(runId)` | 获取运行 transcript |

### 0.12.2 Workflow API 速查

| 操作 | API |
|-----|-----|
| 列表 | `api.workflows.list()` |
| 创建 | `api.workflows.create(data)` |
| 详情 | `api.workflows.get(id)` |
| 更新 | `api.workflows.update(id, data)` |
| 删除 | `api.workflows.delete(id)` |
| 校验 | `api.workflows.validate(id)` |
| 发布 | `api.workflows.publish(id)` |
| 版本列表 | `api.workflows.versions(id)` |
| 保存版本 | `api.workflows.saveVersion(id, graph)` |
| 发布版本 | `api.workflows.publishVersion(id, v)` |
| 回滚 | `api.workflows.restoreVersion(id, v)` |
| 取消发布 | `api.workflows.unpublish(id)` |
| 创建运行 | `api.workflows.createRun(id)` |
| 同步运行 | `api.workflows.runSync(id, input)` |
| 预览 | `api.workflows.previewRun(id)` |
| 运行列表 | `api.workflows.runs(id)` |
| 运行详情 | `api.workflows.getRun(runId)` |
| 节点运行 | `api.workflows.nodeRuns(runId)` |
| 恢复 | `api.workflows.resume(runId)` |
| 节点重跑 | `api.workflows.resumeFromNode(runId, nodeId)` |
| 导出 | `api.workflows.export(id)` |
| 导入 | `api.workflows.import(json)` |
| 克隆 | `api.workflows.clone(id)` |
| 探索 | `api.workflows.explore()` |
| Agent 选项 | `api.workflows.agentOptions()` |

💡 **设计意图**：Workflow API 覆盖面极广，体现了「工作流即代码」的设计理念——所有操作都可以通过 API 驱动，前端只是其中一种消费方式。

---

### 🔧 动手实践

1. **创建一个 Agent**：在 Agent 列表页点击「新建」，填写名称、选择模型、编写系统提示词，保存后运行一次对话
2. **配置工具**：在 Agent 编辑器中为 Agent 添加一个工具（如 web_search），再次运行观察工具调用过程
3. **查看记忆**：在 Agent 记忆页面查看 Agent 是否产生了长期记忆
4. **创建简单工作流**：新建工作流 → 拖入 start → agent → end 三个节点 → 连线 → 保存草稿 → 发布 → 运行
5. **版本实验**：修改已发布的工作流草稿，发布为新版本，观察版本号递增
6. **测试系统提示词**：在系统提示词编辑器中编写一段提示词，选择模型进行在线测试

### 📝 自测题

1. Agent 的 `system_prompt` 和 `system_prompt_id` 有什么区别？分别适用于什么场景？
2. 工作流的「草稿」与「已发布版本」是什么关系？为什么这样设计？
3. 在工作流运行出错时，如何利用「节点级重跑」功能加速调试？
4. Agent 的 `enable_memory` 开启后，记忆是如何产生和管理的？
5. 系统提示词的「变量」功能是如何工作的？请举一个使用场景。
6. 工作流的导入导出功能在团队协作中有什么作用？

---

## 🎉 本章小结

本章我们深入学习了 core-ai-frontend 的三大核心功能模块：

- **Agent 管理**：从列表浏览、全字段编辑、运行查看到记忆管理，形成完整闭环
- **工作流编排**：基于 React Flow 的可视化图编辑器，配合草稿/发布双轨模型和版本管理，让复杂任务编排变得可控
- **系统提示词**：提示词的编辑、测试、版本化，让提示词工程成为可追溯的工程实践

关键 API 涵盖了 Agent、Workflow、SystemPrompt 三大资源的全生命周期操作，为后续章节的工具、技能、追踪等功能打下基础。

---

## 🚀 下一章

在 [07-Trace 与调试](./07-Trace与调试.md) 中，我们将学习如何使用 Trace 系统观测和调试 Agent 与 LLM 的运行过程，包括 Trace 列表、Span 树分析、Token 成本追踪和管理员仪表盘。

*最后更新: 2026-08-31*
