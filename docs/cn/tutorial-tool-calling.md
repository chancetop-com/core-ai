# 函数工具与 MCP

<span class="legacy-anchor" id="教程-工具调用-tool-calling"></span>
<span class="legacy-anchor" id="目录"></span>
<span class="legacy-anchor" id="工具调用概述"></span>
<span class="legacy-anchor" id="什么是工具调用"></span>
<span class="legacy-anchor" id="工具执行机制原理"></span>
<span class="legacy-anchor" id="core-ai-工具架构"></span>
<span class="legacy-anchor" id="创建自定义工具"></span>
<span class="legacy-anchor" id="_1-基础工具实现"></span>
<span class="legacy-anchor" id="_2-复杂工具实现"></span>
<span class="legacy-anchor" id="_3-异步工具"></span>
<span class="legacy-anchor" id="json-schema-定义"></span>
<span class="legacy-anchor" id="json-schema-自动生成原理"></span>
<span class="legacy-anchor" id="_1-自动-schema-生成"></span>
<span class="legacy-anchor" id="_2-复杂-schema-定义"></span>
<span class="legacy-anchor" id="mcp-协议集成"></span>
<span class="legacy-anchor" id="_1-mcp-服务器"></span>
<span class="legacy-anchor" id="_2-mcp-客户端"></span>
<span class="legacy-anchor" id="_3-mcp-工具适配器"></span>
<span class="legacy-anchor" id="内置工具"></span>
<span class="legacy-anchor" id="_1-使用内置工具"></span>
<span class="legacy-anchor" id="_2-扩展内置工具"></span>
<span class="legacy-anchor" id="工具组合和编排"></span>
<span class="legacy-anchor" id="_1-工具链"></span>
<span class="legacy-anchor" id="_2-条件工具执行"></span>
<span class="legacy-anchor" id="_3-并行工具执行"></span>
<span class="legacy-anchor" id="实战案例"></span>
<span class="legacy-anchor" id="案例1-数据分析助手"></span>
<span class="legacy-anchor" id="案例2-自动化运维助手"></span>
<span class="legacy-anchor" id="案例3-api-集成助手"></span>
<span class="legacy-anchor" id="错误处理和重试"></span>
<span class="legacy-anchor" id="_1-工具错误处理"></span>
<span class="legacy-anchor" id="_2-工具回退策略"></span>
<span class="legacy-anchor" id="工具监控"></span>
<span class="legacy-anchor" id="_1-性能监控"></span>
<span class="legacy-anchor" id="最佳实践"></span>
<span class="legacy-anchor" id="总结"></span>

工具让 Agent 调用明确的应用能力。先定义一个无副作用的 Echo 函数，检查 schema，再让模型使用；模型是否调用工具取决于输入、提示与 provider。

::: info 示例范围
以下片段来自[完整编译样例](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java)，以当前源码做类型检查；未执行联网模型或外部存储。`provider`、`model` 等参数由调用者传入。运行证据见[离线 Agent](framework.md#离线-agent)。
:::
## 定义 Java 函数工具

注解位于 `ai.core.api.tool.function`，转换器为 `ai.core.tool.function.Functions`。方法和参数的 `name`、`description` 应填写；参数默认 `required=true`。

```java
public static class EchoTools {
    @CoreAiMethod(name = "echo", description = "Return the input text")
    public String echo(
        @CoreAiParameter(name = "text", description = "Input text")
        String text) {
        return text;
    }
}
```
## 绑定到 Agent

```java
var agent = Agent.builder()
    .name("echo-agent")
    .llmProvider(provider)
    .model(model)
    .toolCalls(Functions.from(new EchoTools()))
    .build();
```
直接单元调用 `new EchoTools().echo("hello")` 应返回 `hello`。这只检验函数逻辑；模型选用工具与工具审批是后续不同环节。

## Schema 与权限

`Functions.from(...)` 读取公开的注解方法并生成工具 schema。输入类型、可空值、默认值和错误结果应明确。`needAuth` 注解默认 false 不能作为外部资源已经授权的证据。涉及写入的工具需要身份检查、审批、幂等性和失败处理。

## MCP 的两个入口

框架的 MCP client 需配置并初始化 manager，builder 可使用 `mcpServers(...)` 等实际接口。CLI 工作区 `.core-ai/MCP.json` 加载本地 stdio 服务；`core-ai-cli mcp ...` 则发现和调用 Server Hub 资源。两者不是同一目录。

[CLI Hub 流程](cli.md#连接-server)中先 search、describe，再用实际返回的 path 与 `--args-file` 调用。[本地 MCP 和 hooks](/cn/manual/#_19-本地-mcp-skills-与-hooks)说明依赖与路径。

## 调试顺序

1. 检查普通 Java 函数返回值。
2. 检查生成 schema 是否符合输入类型。
3. 用测试 provider 和最小提示检查工具选择与结果回传。
4. 接入外部系统后检查身份、失败状态和脱敏日志。

[基础 Agent](tutorial-basic-agent.md) · [Skills](tutorial-skills.md) · [工具源码](https://github.com/chancetop-com/core-ai/tree/master/core-ai/src/main/java/ai/core/tool)

## 历史长篇材料

此前的场景分析和长篇示例保存在[固定版本记录](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/cn/tutorial-tool-calling.md)中，可能使用旧 API。当前开发请以本页源码核对示例为准。
