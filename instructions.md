# CLAUDE.md

This file provides guidance to codeAgent when working with code in this repository.

## Build & Test Commands

```bash
./gradlew build                                          # full project build
./gradlew test                                           # run all tests
./gradlew :core-ai:test                                  # run tests for a specific module
./gradlew :core-ai:test --tests "ai.core.agent.AgentTest"  # run a single test class
./gradlew :core-ai-server:run                            # run server from gradle
./gradlew :core-ai-cli:nativeCompile                     # build CLI native binary (needs GraalVM JDK 21+)
./gradlew :core-ai-cli:run                               # run CLI from gradle

# Frontend
cd core-ai-frontend && npm install --legacy-peer-deps && npm run dev   # start Vite dev server

# Docker (requires Docker Buildx multi-platform builder)
make server                                              # build and push server Docker image
make cli                                                 # build CLI native binary
make release VERSION=1.0.0                               # build CLI and upload to GitHub release
make update-model-context                                # update model_prices_and_context_window.json from litellm
```

## Project Structure

Multi-module Gradle (Java 21+, Gradle 8.0+) with Kotlin DSL buildSrc:

| Module | Purpose |
|--------|---------|
| `core-ai` | Core framework — agents, tools, LLM providers, RAG, flow, context management |
| `core-ai-api` | Public API types (JSON schemas, tool interfaces) — minimal dependencies |
| `core-ai-cli` | Terminal CLI app (picocli + JLine + GraalVM native-image) |
| `core-ai-server` | Backend server (Undertow + core.framework, MongoDB, Redis, Sandbox orchestration) |
| `core-ai-benchmark` | Benchmarks |
| `core-ai-frontend` | React 19 + Vite + Tailwind CSS SPA |

Versions are managed in `buildSrc/src/main/kotlin/Versions.kt`. The project depends on `core.framework:core-ng` (version 9.4.2) via custom Maven repos:
- `https://neowu.github.io/maven-repo/` (core.framework)
- `https://chancetop-com.github.io/maven-repo/` (com.chancetop)

## Architecture: Core-ai
references [README.md](docs/en/README.md)

## Code Style

- Comments: **English only**, class-level javadoc only — no inline/method comments unless explicitly requested
- Code self-description is preferred over comments
- Always import classes, never use fully-qualified names inline
- Newly added files（*.java） need to be subject to Git version control
- The author of a newly created file must be the same person (git author or computer user).