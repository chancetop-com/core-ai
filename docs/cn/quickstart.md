# 快速开始

先选择要完成的任务。Server、CLI、Java 框架在同一仓库中，安装要求和操作入口各不相同。

| 你要做什么 | 从这里开始 | 准备什么 |
| --- | --- | --- |
| 使用团队 Agent、工具与 Web 界面 | [Server 上手](server.md) | 管理员提供的服务地址、账号和资源权限 |
| 在终端工作或调用 Server 资源 | [CLI 上手](cli.md) | 对应系统的发布包；源码运行需 Java 25 |
| 在 Java 应用中开发 Agent | [框架上手](framework.md) | Java 25、仓库 Wrapper、依赖缓存或下载权限 |

::: tip 先确认版本
本页与[完整使用手册](manual/)以 `e1afa9fa` 源码为基线：CLI 2.0.21，框架 1.4.0-SNAPSHOT，公共 API 1.3.0-SNAPSHOT。其他版本先查看实际 `--help`。版本常量不能证明 SNAPSHOT 制品已经发布到 Maven。
:::

## 1. 检查本地入口

安装 CLI 后，先运行不调用模型或 Server 的命令：

```sh
core-ai-cli --version
core-ai-cli --help
core-ai-cli mcp call --help
```

预期显示版本和参数，根命令包含 `mcp`、`skill`、`api-tool`、`agent`、`report`、`dataset`、`catalog`。本次在 Mac 上通过离线编译后的 Java `Main` 验证了 8 组 help/version；发布包安装与原生执行未验证。找不到命令时，按[跨平台排障](cli-troubleshooting.md)检查 PATH、文件名和架构。

## 2. 运行离线演示

Java Mock Agent 和 Python FakeSession 都不发送模型请求，不需要真实凭据。示例在 `docs/cn/manual/examples/`：

- [Java Mock Agent](framework.md#离线-agent)：检查预设回复、Agent 状态和返回值。
- [Python FakeSession](manual/#_29-python-离线演示与截图)：检查资源发现、调用参数与返回数据。
- [验证记录](https://github.com/chancetop-com/core-ai/tree/master/docs/cn/manual/evidence)：区分实测、源码核对和未验证操作。

Java 脚本要求 checkout 已有 Gradle/SpotBugs classpath 和本地依赖缓存，缺失时停止，不自动下载依赖；不适用于刚克隆且没有缓存的仓库。

## 3. 接入自己的测试环境

已有团队 Server 时，用管理员提供的地址[登录 CLI](cli.md#连接-server)。独立模型需要自己的兼容 API base 和可用模型。自行部署前，检查 MongoDB、Redis、数据卷和沙箱配置，按[Server 手册](manual/#第一部分-core-ai-server)逐步准备。

联网登录、Server 启动、真实模型请求及 Windows 实机操作均未在本次验证。参数以代码为依据，预期结果与实测记录分开说明。

## 下一步

- [完整手册](manual/)：安装配置、五张截图、核心操作、协作示例和常见问题。
- [CLI 跨平台排障](cli-troubleshooting.md)：PATH、权限、shell、配置路径和 hooks。
- [三部分协作实例](manual/#_28-三部分协作实例)：Server 注册资源，CLI 发现，脚本复用。
