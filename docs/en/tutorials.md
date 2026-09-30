# Tutorials and Learning Path

<span class="legacy-anchor" id="core-ai-tutorials"></span>
<span class="legacy-anchor" id="📚-tutorial-index"></span>
<span class="legacy-anchor" id="🎯-getting-started"></span>
<span class="legacy-anchor" id="🔬-deep-dive"></span>
<span class="legacy-anchor" id="🚀-core-tutorials"></span>
<span class="legacy-anchor" id="_1-building-intelligent-agents"></span>
<span class="legacy-anchor" id="_2-memory-systems"></span>
<span class="legacy-anchor" id="_3-compression-mechanism"></span>
<span class="legacy-anchor" id="_4-rag-integration"></span>
<span class="legacy-anchor" id="_5-tool-calling"></span>
<span class="legacy-anchor" id="_6-flow-orchestration"></span>
<span class="legacy-anchor" id="🎓-learning-path"></span>
<span class="legacy-anchor" id="beginner-path"></span>
<span class="legacy-anchor" id="intermediate-path"></span>
<span class="legacy-anchor" id="advanced-path"></span>
<span class="legacy-anchor" id="💡-tutorial-features"></span>
<span class="legacy-anchor" id="🛠️-prerequisites"></span>
<span class="legacy-anchor" id="📖-additional-resources"></span>
<span class="legacy-anchor" id="examples"></span>
<span class="legacy-anchor" id="documentation"></span>
<span class="legacy-anchor" id="community"></span>
<span class="legacy-anchor" id="🚦-ready-to-start"></span>
<span class="legacy-anchor" id="💬-need-help"></span>

Start with [Quick Start](quickstart.md) to choose Server, CLI or framework, then add capabilities one at a time. Source builds require Java 25 and the repository Wrapper. Offline examples do not require a model key.

| Tutorial | What it covers | Additional prerequisites |
| --- | --- | --- |
| [Basic Agent](tutorial-basic-agent.md) | Builder, run, status and streaming contracts | A provider for live requests |
| [Function Tools](tutorial-tool-calling.md) | Annotations, schema and conversion | Authorization for external side effects |
| [Memory](tutorial-memory.md) | User context, MemoryStore and recall | Storage, embeddings and extraction model |
| [Compression](tutorial-compression.md) | Switch and configuration | Summary model, context limits and cost |
| [RAG](tutorial-rag.md) | VectorStore and RagConfig | Index, embeddings and backend dependencies |
| [Skills](tutorial-skills.md) | SKILL.md, registry and precedence | Reviewed directories and script dependencies |
| [Flow](tutorial-flow.md) | Nodes, edges, validation and run | Providers and data required by each node |
| [Architecture](tutorial-architecture.md) | Server / CLI / framework boundaries | A working minimal Agent |

## First verifiable result {#第一个可核对的成功结果}

The [offline Agent](framework.md#离线-agent) returns the fixture text, `COMPLETED` and one provider call. Its script requires existing dependencies and does not guarantee an offline build from a fresh clone. The [FakeSession demo (Chinese)](/cn/manual/#_29-python-离线演示与截图) checks discovery and arguments.

## Example validation {#示例的验证范围}

Current API samples were compile-checked during this update. Live model, memory extraction and vector database calls were not executed. Offline runtime evidence is retained in the manual. Historical designs can contain earlier interfaces or proposals; consult current source.

[API Reference](/en/api) · [CLI Troubleshooting](cli-troubleshooting.md) · [Full manual (Chinese)](/cn/manual/) · [Report an issue](https://github.com/chancetop-com/core-ai/issues)
