---
layout: home
hero:
  name: core-ai
  text: 从第一个 Agent 到团队协作
  tagline: 用 Server 管理资源，用 CLI 完成任务，用 Java 框架构建应用。
  image:
    src: /core-ai-logo-v5-symbol-c-icon.svg
    alt: core-ai
  actions:
    - theme: brand
      text: 选择上手路径
      link: /cn/quickstart
    - theme: alt
      text: 阅读完整手册
      link: /cn/manual/
    - theme: alt
      text: GitHub
      link: https://github.com/chancetop-com/core-ai
features:
  - title: Server · 团队服务
    details: 管理 Agent、工具、技能与数据集，从 Web、REST 和 SSE 接入。
    link: /cn/server
    linkText: 打开 Server 指南
  - title: CLI · 终端工作
    details: 安装、登录、恢复会话和调用资源，附 Windows 与 macOS 排障。
    link: /cn/cli
    linkText: 打开 CLI 指南
  - title: 框架 · Java 开发
    details: 从离线 Mock Agent 开始，逐步接入模型、函数工具与 Streaming。
    link: /cn/framework
    linkText: 打开框架指南
---

<div class="site-start">

## 第一次使用，从这里开始

<div class="start-links">
  <a href="/core-ai/cn/quickstart"><strong>01 · 确认环境</strong><span>选择角色、版本和安装路线</span></a>
  <a href="/core-ai/cn/framework#离线-agent"><strong>02 · 跑通离线演示</strong><span>无需真实凭据，先理解输入和结果</span></a>
  <a href="/core-ai/cn/manual/#_28-三部分协作实例"><strong>03 · 接入测试服务</strong><span>把 Server、CLI 和脚本连起来</span></a>
</div>

## 操作示例，带着预期结果阅读

<div class="demo-grid">
  <a href="/core-ai/cn/server"><img src="./manual/screenshots/server-chat.png" alt="Server Chat 历史真实界面" loading="lazy"><strong>Server：创建 Agent 并对话</strong><span>仓库历史界面 · 按步骤核对账号、模型与状态</span></a>
  <a href="/core-ai/cn/framework#离线-agent"><img src="./manual/screenshots/offline-demos.jpg" alt="Java 与 Python 离线演示真实 stdout" loading="lazy"><strong>框架：不调用模型的本地演示</strong><span>本次 Mac 实测 · Mock Agent 与 FakeSession</span></a>
</div>

## 遇到问题？从现象定位

[Windows / macOS CLI 排障](/cn/cli-troubleshooting) · [Server 常见问题](/cn/manual/#_8-server-常见问题) · [框架 API 与构建问题](/cn/manual/#_30-框架常见问题)

<div class="site-evidence">文档以 CLI 2.0.21 源码为基线。Mac help 与离线演示已实测；Server 启动、发布包安装、Windows 实机及线上模型调用未验证。完整手册保留每一步的验证范围。</div>

</div>
