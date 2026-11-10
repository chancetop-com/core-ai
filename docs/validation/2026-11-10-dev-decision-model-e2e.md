# dev 决策模型接入 E2E 验收

- 日期：2026-11-10
- 环境：`FBRDevAKSCluster / dev-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:g2-small-collections-20261110-02`
- 上游：Azure AI Foundry `fbruatai.services.ai.azure.com`，deployment `Decision-1`
- 结论：**PASS**

## 配置

| 对象 | 值 |
|---|---|
| provider id | `c135ac82-77b9-4613-a861-e46ae5760a05` |
| provider name | `azure-decision` |
| provider type | `azure` |
| baseUrl | `https://fbruatai.services.ai.azure.com` |
| decisionProtocol | `AZURE_FOUNDRY_DECISION` |
| defaultDecisionModel | `Decision-1` |
| model id | `83e5ac68-4cc2-4d5f-82c3-966c09e9f56e` |
| modelId | `decision-1` |
| upstreamModel | `Decision-1` |
| endpointTypes | `["decision"]` |
| 价格 | 输入 `$0.042/M`，输出 `0` |

模型按设计**手工创建**，未使用 “Import models”（导入会把 `Decision-1` 判成 chat 模型）。

## E2E 结果

| 操作 | 结果 |
|---|---|
| `POST /api/gateway/providers/{id}/test` | `ok=true`，`Connected; decision answered, model=microsoft-decision-1`，250ms |
| `GET /api/hub/decision-models` | 返回 `decision-1`，`displayName=Microsoft Decision 1`，`providerName=azure-decision`，`upstreamModel=Decision-1` |
| `GET /api/gateway/models/available` | 包含 `decision-1`，`endpointTypes=["decision"]` |
| `GET /api/hub/models` | 不包含 `decision-1`（decision 模型不会出现在 chat/desktop 模型选择器） |
| `POST /api/gateway/v1/decisions`（noul + choice + score 一次请求） | 200，1.47s；`team` 选中 `engineering`（confidence 0.9974），`repeat_contact` 0.9997，`severity` 2.518（Major/Critical 区间） |
| 省略 `model` | 200，按默认路由落到 `decision-1` → `microsoft-decision-1` |
| 非法 question type / 空 questions / 未注册模型 | 400 |
| CLI `tool list` | 可见 `decide` |
| CLI `tool describe decide` | 描述包含 `Available decision models`、`decision-1 (Microsoft Decision 1) — azure-decision` |
| CLI `tool call decide --args-file ... --server https://core-ai-server.connexup-dev.net` | `completed`，1.31s，返回 choice + noul 结构化判定 |

CLI 调用结果（节选）：

```json
{"model":"microsoft-decision-1","answers":{
  "team":{"type":"choice","choice":"engineering","confidence":0.99945},
  "urgent":{"type":"noul","noul":0.8176}},
 "usage":{"input_tokens":76,"output_tokens":2}}
```

## 说明

- 本次验证覆盖 hub 发现、gateway 调用、默认路由、错误透传、CLI/desktop 的 hub provider 与 `decide` 工具；未覆盖 trace/计价、前端 gateway 表单字段、旁路命令安全检测（均为 P1）。
- `decision` 模型只出现在 decision 专属目录与 `endpointTypes` 中，不污染 chat 模型列表。
- 回滚：先删模型 `83e5ac68-4cc2-4d5f-82c3-966c09e9f56e`，再删 provider `c135ac82-77b9-4613-a861-e46ae5760a05`。
