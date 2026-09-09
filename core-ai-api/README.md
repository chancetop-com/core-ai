# core-ai-api

Shared contract module of Core-AI. It holds only interfaces, DTOs, enums and annotations — no
runtime implementation — so the framework, the server and the CLI (or any external client) can
depend on the same definitions without pulling in the agent runtime.

## Contents

| Package | Purpose |
| --- | --- |
| `ai.core.api.a2a` | A2A protocol types: agent card, message/part, task, JSON-RPC envelopes |
| `ai.core.api.apidefinition` | API definition model used when importing HTTP APIs as tools |
| `ai.core.api.jsonschema` | JSON Schema model for tool and agent definitions |
| `ai.core.api.server` | Web service interfaces exposed by core-ai-server |
| `ai.core.api.tool.function` | `@CoreAiMethod` / `@CoreAiParameter` annotations for exposing POJOs as tools |

## Build

```bash
./gradlew :core-ai-api:build
```

The module version and dependencies are declared in the root `build.gradle.kts`
(`project(":core-ai-api") { ... }`).
