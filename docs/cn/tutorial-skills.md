# Skills 技能包

<span class="legacy-anchor" id="教程-skills-系统"></span>
<span class="legacy-anchor" id="目录"></span>
<span class="legacy-anchor" id="概述"></span>
<span class="legacy-anchor" id="为什么需要-skills"></span>
<span class="legacy-anchor" id="核心概念"></span>
<span class="legacy-anchor" id="什么是-skill"></span>
<span class="legacy-anchor" id="skill-md-格式"></span>
<span class="legacy-anchor" id="skill-命名规则"></span>
<span class="legacy-anchor" id="渐进式披露流程"></span>
<span class="legacy-anchor" id="创建-skill"></span>
<span class="legacy-anchor" id="步骤-1-创建目录结构"></span>
<span class="legacy-anchor" id="步骤-2-编写-skill-md"></span>
<span class="legacy-anchor" id="与-agent-集成"></span>
<span class="legacy-anchor" id="基础用法"></span>
<span class="legacy-anchor" id="使用-skillconfig-完整配置"></span>
<span class="legacy-anchor" id="禁用-skills"></span>
<span class="legacy-anchor" id="多来源优先级"></span>
<span class="legacy-anchor" id="架构设计"></span>
<span class="legacy-anchor" id="lifecycle-执行顺序"></span>
<span class="legacy-anchor" id="核心类"></span>
<span class="legacy-anchor" id="最佳实践"></span>
<span class="legacy-anchor" id="skill-设计原则"></span>
<span class="legacy-anchor" id="目录组织"></span>
<span class="legacy-anchor" id="安全考虑"></span>
<span class="legacy-anchor" id="api-参考"></span>
<span class="legacy-anchor" id="skillconfig-builder"></span>
<span class="legacy-anchor" id="skillconfig-of"></span>
<span class="legacy-anchor" id="agentbuilder-skills"></span>

Skill 通常包含 `SKILL.md`、脚本和资源，为 Agent 提供可复用操作知识。文件存在不代表模型会自动使用，也不会给脚本授予外部账号权限。

::: info 示例范围
以下片段来自[完整编译样例](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java)，以当前源码做类型检查；未执行联网模型或外部存储。`provider`、`model` 等参数由调用者传入。运行证据见[离线 Agent](framework.md#离线-agent)。
:::
## 准备目录

```text
.core-ai/skills/
  echo-guide/
    SKILL.md
    scripts/
    resources/
```

`SKILL.md` 用 frontmatter 声明名称和描述，正文写清适用任务、输入、步骤与预期结果。脚本先在自己的测试环境独立验证，资源路径按包内相对路径组织。

## 接入 Registry

当前 Agent builder 使用 `.skillRegistry(registry)`。配置文件系统 provider：

```java
var registry = new SkillRegistry();
registry.addProvider(new FilesystemSkillProvider(
    "project", "./.core-ai/skills", 0));
registry.addProvider(new FilesystemSkillProvider(
    "user", userSkillsPath, 1));
var agent = Agent.builder()
    .name("skill-agent")
    .llmProvider(provider)
    .model(model)
    .skillRegistry(registry)
    .build();
```
`userSkillsPath` 是调用者提供的用户 skills 目录。当前优先级为**数值越小越优先**，Registry 按 qualified name 合并。不要依赖名称相同就一定覆盖所有 namespace；实际匹配看 metadata。

`SkillConfig` 可描述多个来源，但它不是当前 `AgentBuilder.skills(...)` 方法；后者不存在。加载来源和构造 Registry 应按实际调用链处理。

## 检查加载结果

用 `registry.listAll()` 核对 metadata，再用 `find(...)` 和 `readContent(...)` 检查内容。`invalidateCache()` 清除 Registry 缓存；文件系统 provider 自己也有缓存，更新资源后的重新加载行为需按实例生命周期确认。

本例只编译，未安装或执行外部 skill。CLI 工作区与全局来源见[资源路径](cli.md#独立模型与工作区)。

[函数工具](tutorial-tool-calling.md) · [SkillRegistry 源码](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/skill/SkillRegistry.java)

## 历史长篇材料

此前的场景分析和长篇示例保存在[固定版本记录](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/cn/tutorial-skills.md)中，可能使用旧 API。当前开发请以本页源码核对示例为准。
