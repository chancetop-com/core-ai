# 会话 / 运行时内存治理 — 文档索引

| 项目 | 内容 |
|---|---|
| 状态 | DRAFT；2026-11-24 用户授权开始实现（A/B1/C 采用提案默认值）；设计与修复并行推进 |
| 日期 | 2026-11-24 |
| 作者 | core-ai |
| 目的 | 把 Q1–Q8 的问题拆分、tech design、简单修复轨道放在一个入口，避免文档之间口径漂移 |
| 约束 | 设计文档只出设计；2026-11-24 用户授权按提案默认值实施 A/B1/C，偏离记录在各文档附录 C |

## 1. 文档清单

| 文档 | 覆盖问题 | 类型 | 状态 |
|---|---|---|---|
| [`design-session-media-model.md`](design-session-media-model.md) | Q1 会话状态表示 + Q2 媒体生命周期；接口影响 Q3/Q4 | Tech design（完整） | DRAFT v0.1 |
| [`design-trace-payload-boundary.md`](design-trace-payload-boundary.md) | Q4 Trace 载荷边界；依赖 Q1/Q5 | Tech design（小） | DRAFT v0.1 |
| [`design-runtime-pressure-admission-metrics.md`](design-runtime-pressure-admission-metrics.md) | Q6 压力信号 + Q7 准入 + Q8b 度量语义/footprint | Tech design（完整） | DRAFT v0.1 |
| [`note-trace-ingest-byte-budget.md`](note-trace-ingest-byte-budget.md) | Q5 Span ingest 字节预算 | Decision note（简单修复） | DRAFT v0.1 |
| 本文 | Q1–Q8 索引、简单修复轨道、顺序 | 索引 | DRAFT v0.1 |

## 2. 问题 → 文档 / 修复

| # | 问题 | 结论 | 落点 |
|---|---|---|---|
| Q1 | 会话状态的权威副本是什么 | Tech design | Design A §4–§5 |
| Q2 | 媒体字节的生命周期由谁负责 | Tech design（并入 A） | Design A §4.5、§5.4 |
| Q3 | 一次上游请求如何组装 | 简单修复 + A 的接口 | Design A §5.2–§5.3；B0-2/3 |
| Q4 | Trace 记录什么、何时记录 | Tech design | Design B |
| Q5 | Span ingest 队列的容量边界 | 简单修复 + 一页 note | [`note-trace-ingest-byte-budget.md`](note-trace-ingest-byte-budget.md) |
| Q6 | 内存压力用哪个物理信号 | Tech design | Design C §4.1、§5.1 |
| Q7 | 准入在哪些入口、用什么状态机 | Tech design（并入 C） | Design C §4.2、§5.2 |
| Q8 | 度量采集/聚合/呈现 | 混合：C0 简单修复 + C2 语义 | Design C §5.3–§5.5、§9 |

## 3. 简单修复轨道（不阻塞设计 Review）

| 项 | 内容 | 依赖 | 验收 |
|---|---|---|---|
| B0-1 | `NodeMessages.history` 文本快照；undo 改 role/text 匹配；旧文件清洗 | 无 | history 无 `data:`；导出/恢复/undo 测试通过 |
| B0-2 | 会话级 retention prune：`ModelGateway.handLLM` 前 + turn 结束原地回写 | 无 | 单会话 `inlineImageBytes` ≤ 硬上限 |
| B0-3 | retention 硬上限（最新一组也不能无限豁免） | B0-2 | 超限降级；413 路径不受影响 |
| Trace B0 | `LLMTracer` 输入大小上限 + 超限 data URI 占位/截断 | 无 | span input ≤ 256 KB；LLM 调用不受影响 |
| Q5 | `LocalSpanProcessor` 字节预算 + 丢弃计数 | 无 | 队列内存有界；丢 span 可见 |
| C0-1 | `PgRuntimeSampleStore.range()` 加 `ORDER BY collected_at, pod` | 无 | 乱序测试通过 |
| C0-2 | 跨桶 counter delta + pod 重启处理 | C0-1 | 桶间增量不丢；reset 计数 |
| C0-3 | `sse_buffer_bytes/events/dropped` 写入 `RuntimeSample` | 无 | 面板可见 |
| C0-4 | PodCard 补 after-GC / container / est-img / span depth | 无 | 前端显示 |
| C0-5 | 补 `POST /api/sessions/{id}/messages` 准入；路由覆盖测试 | 无 | 无未分类 work 路由 |
| C0-6 | `truncated` 标记 + 前端警告 | C0-1 | 7 天查询可见 |
| P5-语义 | `(all replicas)` 改为 `max pod` / `fleet sum` 明确文案 | 无 | 图例/tooltip 一致 |
| P8-落库 | footprint 刷新时机/高水位字段（C2 的先行部分） | C2 设计 | 长 turn 可见；peak 不丢 |

## 4. 依赖与顺序

```
Design A (Q1/Q2) ──> A1 引用 + store + 水合
        │                    │
        │                    └──> Design B B1 (trace 看到 ref)
        │
        └──> B0-1/B0-2/B0-3（可独立先发）

Design C (Q6/Q7/Q8b) ──> C1 信号/准入
        │
        └──> C0 简单修复（可独立先发）

Q5 / Trace B0 ── 独立，可并行
```

- **可以并行**：Design A 起草 + Design C 起草 + C0/ Trace B0/Q5 简单修复。
- **B1 等 A1**：trace 的 ref 格式依赖 `MediaRef` / `Content.IMAGE_REF`。
- **C2 等 C1**：footprint 的字节字段依赖 A1 的 store/ref 口径；C0 的 bug 修复不等。

## 5. 设计中的关键决策（待 Review）

### Design A

- D1：新增 `Content.IMAGE_REF` vs 复用 `IMAGE_URL` + 私有 scheme。
- D3：水合位置（`invokeCompletionStream` 的 lambda，tracer 之后）。
- D4：Server store 复用 `SessionAttachmentRef` vs 新建 `session_media`。
- D6：预算默认值（请求 16/12 MB；store 256 MB/24h；单图 16 MB；硬上限 24 MB）。
- D8：trace 是否需要显示历史图片。
- D9：Server 重建是否恢复 ref（提案：不恢复）。

### Design B

- T1：trace 是否必须显示原图（决定 B0 能否接受丢图）。
- T2/T3：单 span 256 KB / 单 trace 8 MB 上限。
- T5：CLI 跨端 U1（上传 payloads） vs U2（只标记 unresolvable）。
- T7：详情接口继续 hydrate（限 2 MB） vs 只返回 ref。

### Design C

- D-C1：主准入信号 `oldGenPercent` + after-GC 辅助。
- D-C2：85% 进入 / 75% 退出 / 2×5s / 3×5s / 60s 冷却。
- D-C3：`_peak` = max pod；`_sum_peak` = fleet sum。
- D-C4：footprint scope 是否包含 subagent / turn buffer / SSE。
- D-C7：信号过期 fail-open + 告警；container >95% 仍拒绝。
- D-C8：429 + Retry-After。

## 6. 当前状态与下一步

- 2026-11-24：用户授权按提案默认值实施。
  - Design A（A1）已实现并合入（`897522fe6`，2.0.365）：`Content.IMAGE_REF`、`MediaRef`、两端 store、请求边界水合、Server `kind=MEDIA` + media endpoint + TTL/预算。
  - Design B1 已实现并合入（`5c7845662`，2.0.366）：span 不再持有大图、8 MB/trace + 256 KB/span、详情 2 MB hydrate 上限、trace payload endpoint、CLI 上传本地图片。Trace B0 由简单修复轨道先行合入。
  - Design C1/C2 已实现（C0-1/C0-2 已在简单修复轨道合入）：Old Gen 主信号 + 滞回准入、路由注册表、`_peak`/`_sum_peak` 语义、footprint v2（peaks + media refs）、Dashboard 文案/字段。
- 剩余：
  1. A2：session close 清理 store、跨 pod 对账、URL 直传。
  2. B2：前端按需加载 trace 图片、payload TTL 对账；`TracePayloadSink` SPI 等到真实消费者再加。
  3. C2 后续：subagent / turnBuffer / SSE 字节接入 footprint；`expected_pods`；阈值校准数据。
- 评审记录写在各自附录 C；本文只做索引，不重复设计细节。
