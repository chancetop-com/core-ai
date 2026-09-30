# Java Framework Guide

`core-ai` implements agents; public types are in `core-ai-api`. Both Server and CLI use these modules. Begin offline, then add providers, tools and streaming.

## Environment and dependencies {#环境与依赖}

Current source requires **Java 25** and **Gradle Wrapper 9.3.0**. Framework is `1.4.0-SNAPSHOT`, public API `1.3.0-SNAPSHOT`; Maven publication is not assumed. Existing repository modules can use:

```kotlin
dependencies {
    implementation(project(":core-ai"))
    implementation(project(":core-ai-api"))
}
```

Register new modules in `settings.gradle.kts` and follow project conventions. Independent applications need confirmed artifacts or a composite build.

## Offline Agent {#离线-agent}

A Mock Agent was tested on macOS without model requests. [OfflineAgentDemo.java](https://github.com/chancetop-com/core-ai/blob/master/docs/cn/manual/examples/java/OfflineAgentDemo.java) includes the full fixture, provider and context. Its key calls are:

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

`run` returns String. From a checkout with existing dependencies, run:

```sh
python3 docs/cn/manual/examples/run_offline_java.py \
  --repo /path/to/core-ai \
  --build-dir /tmp/core-ai-manual-build
```

Provide your actual checkout and an empty output directory outside it. The script needs cached Gradle/SpotBugs classpaths and dependencies and stops if missing. It does not download dependencies. Windows paths and `python` may differ and were not tested.

Expected fixture output:

```text
结果: 离线演示成功
状态: COMPLETED
调用次数: 1
模拟 token 数: 15
```

The mock Usage is not billing. The screenshot presents real Java and Python demo stdout.

![Verified Java Mock Agent and Python FakeSession output](../cn/manual/screenshots/offline-demos.jpg)

## Models and tools {#实际模型与工具}

The compatible endpoint provider is `LiteLLMProvider(LLMProviderConfig, url, token)`. Your test environment supplies the base, key and model. Function annotations are in `ai.core.api.tool.function` and are converted with `Functions.from(...)`.

Streaming uses `.streaming(true)` and `.streamingCallback(...)`, with `onChunk(String)` and no-argument `onComplete()`. These types were checked; live model calls were not executed.

[Basic Agent](tutorial-basic-agent.md) · [Function Tools](tutorial-tool-calling.md) · [Memory](tutorial-memory.md) · [RAG](tutorial-rag.md) · [Flow](tutorial-flow.md)
