# Server 上手

`core-ai-server` 管理 Agent、工具、技能和数据集，为浏览器、CLI 和脚本提供服务。Web 界面来自 `core-ai-frontend`，Agent 执行依赖 `core-ai` 框架。

## 已有团队服务

1. 向管理员取得服务地址、账号、可用模型及资源权限。
2. 在 **Agents** 新建 Agent，填写名称、system prompt 和可用模型。首次练习使用简单问答。
3. 保存 draft，检查工具和最大轮数，在 **Chat** 测试问题并观察流式回复及最终状态。
4. 在 **Traces** 定位调用。需要给其他用户使用时按权限发布版本；保存草稿与发布是两个操作。

预期结果依赖账号、模型、额度和服务配置。本次没有登录或启动 Server。下面是仓库真实历史界面（2026 年 7 月），用于定位入口。

![Agent 管理列表，仓库历史截图](manual/screenshots/server-agent.png)

![Chat 对话与文件预览，仓库历史截图](manual/screenshots/server-chat.png)

## 自行部署前的检查

| 路线 | 环境要求 | 下一步 |
| --- | --- | --- |
| 容器 | Docker / Compose、MongoDB 7、Redis 7 | [Compose 步骤](manual/#_2-使用仓库-compose) |
| 源码 | Java 25、Wrapper 9.3.0，前端构建工具 | [配置与 installDist](manual/#_3-配置项与源码启动) |

`docker-compose.local.yml` 使用 external 数据卷并启用 Docker 沙箱。先复制配置，改成自己的管理员、模型、回连地址和测试端口；基础体验可按手册设置 `SYS_SANDBOX_PROVIDER=none`。镜像版本需另行固定，不能把 `latest` 等同于源码 commit。

## 接入 CLI 或应用

- [CLI 连接 Server](cli.md#连接-server)：登录后先发现可见资源，再按 schema 调用。
- [REST 与 SSE](manual/#_6-使用会话-rest-与-sse)：事件流使用 **PUT** 与 query 参数 **`agent-session-id`**。
- [工具、技能与工作流](manual/#_5-工具-技能-数据集与工作流)：配置最小流程，再增加能力。
- [Server 常见问题](manual/#_8-server-常见问题)：数据卷、Mongo、Redis、模型和沙箱排查。
