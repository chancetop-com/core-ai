# Server Guide

`core-ai-server` manages agents, tools, skills and datasets for browser, CLI and script clients. The Web UI comes from `core-ai-frontend`; execution uses the Java framework.

## Use an existing service {#已有团队服务}

1. Obtain your administrator's service URL, account, available models and resource permissions.
2. Create an Agent in **Agents**, with a name, system prompt and available model. Begin with a simple question.
3. Save the draft, check tools and turn limits, then test in **Chat**. Observe streamed output and final status.
4. Inspect the call in **Traces**. Publishing a version for other users is separate from saving a draft.

These steps depend on identity, models, quota and service configuration. Server was not started or accessed in this documentation task. The following are real repository screenshots from July 2026, showing where to find the controls.

![Historical Agent management screen](../cn/manual/screenshots/server-agent.png)

![Historical Chat and file preview](../cn/manual/screenshots/server-chat.png)

## Before deploying {#自行部署前的检查}

| Route | Requirements | Instructions |
| --- | --- | --- |
| Container | Docker / Compose, MongoDB 7, Redis 7 | [Compose steps (Chinese)](/cn/manual/#_2-使用仓库-compose) |
| Source | Java 25, Wrapper 9.3.0, frontend build tooling | [Configuration and installDist (Chinese)](/cn/manual/#_3-配置项与源码启动) |

`docker-compose.local.yml` uses external volumes and enables Docker sandboxing. Copy it and provide your own administrator, models, callback URL and test ports. For a basic service, follow the manual to use `SYS_SANDBOX_PROVIDER=none`. Pin a confirmed image version; `latest` is not equivalent to a source commit.

## Connect CLI or applications {#接入-cli-或应用}

- [CLI login](cli.md#连接-server): discover authorized resources before calling their schemas.
- [REST/SSE (Chinese)](/cn/manual/#_6-使用会话-rest-与-sse): event streaming uses **PUT** with query parameter **`agent-session-id`**.
- [Tools and workflows (Chinese)](/cn/manual/#_5-工具-技能-数据集与工作流): validate a minimal flow before adding capabilities.
- [Server troubleshooting (Chinese)](/cn/manual/#_8-server-常见问题): volumes, Mongo, Redis, models and sandbox checks.
