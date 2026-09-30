# Java 框架上手

`core-ai` 是 Java Agent 框架，公共类型在 `core-ai-api`。Server 与 CLI 都使用这两个模块。先通过离线 Agent 理解运行和状态，再接模型、工具与 Streaming。

## 环境与依赖

源码需要 **Java 25**，使用 **Gradle Wrapper 9.3.0**。框架为 `1.4.0-SNAPSHOT`，公共 API 为 `1.3.0-SNAPSHOT`；这里不假设 SNAPSHOT 已发布。仓库内已有模块可使用：

```kotlin
dependencies {
    implementation(project(":core-ai"))
    implementation(project(":core-ai-api"))
}
```

新增模块需注册到 `settings.gradle.kts` 并遵循构建约定。独立应用使用团队确认的 Maven 制品或 composite build。[完整环境说明](manual/#_22-环境与依赖)。

## 离线 Agent

本次 Mac 已用测试支持类 `MockLLMProvider` 执行 Agent，不访问模型服务。完整代码在 [`OfflineAgentDemo.java`](https://github.com/chancetop-com/core-ai/blob/master/docs/cn/manual/examples/java/OfflineAgentDemo.java)，关键调用为：

```java
var agent = Agent.builder()
    .name("manual-demo")
    .llmProvider(provider)
    .model("mock-model")
    .compression(false)
    .maxTurn(3)
    .build();
String result = agent.run("测试", context);
```

`provider` 和 `context` 由完整样例创建。`run` 返回字符串；当前 API 不使用旧教程的 `Agent.execute` / `AgentOutput`。

从已有依赖缓存的 checkout 根目录复现：

```sh
python3 docs/cn/manual/examples/run_offline_java.py \
  --repo /path/to/core-ai \
  --build-dir /tmp/core-ai-manual-build
```

替换为自己的 checkout，输出目录必须在 checkout 外且为空。脚本需要已有 Gradle/SpotBugs classpath 和缓存，缺失时停止，不下载依赖。Windows 可用 `python` 并调整路径，但未在 Windows 实机验证。

预期输出：

```text
结果: 离线演示成功
状态: COMPLETED
调用次数: 1
模拟 token 数: 15
```

模拟 Usage 不是计费记录。下图为 Java 与 Python 离线演示的真实 stdout 截图。

![Java Mock Agent 与 Python FakeSession 实测输出](manual/screenshots/offline-demos.jpg)

## 实际模型与工具

通用兼容端点 provider 为 `LiteLLMProvider(LLMProviderConfig, url, token)`。base、key 和模型由自己的测试环境提供。函数工具使用 `ai.core.api.tool.function` 注解，通过 `Functions.from(...)` 传入 `.toolCalls(...)`。

Streaming 使用 builder `.streaming(true)`、`.streamingCallback(...)`，回调为 `onChunk(String)` 与无参数 `onComplete()`。API 已做类型核对，真实模型和 Streaming 请求未执行。

[模型配置](manual/#_24-接入真实模型) · [Java 函数工具](manual/#_25-把-java-函数作为工具) · [Streaming 与上下文](manual/#_26-streaming-与执行上下文) · [记忆 / RAG / Flow](manual/#_27-记忆-压缩-rag-与-flow) · [常见问题](manual/#_30-框架常见问题)
