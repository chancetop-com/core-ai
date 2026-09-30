# Skills

<span class="legacy-anchor" id="tutorial-skills-system"></span>
<span class="legacy-anchor" id="table-of-contents"></span>
<span class="legacy-anchor" id="overview"></span>
<span class="legacy-anchor" id="why-skills"></span>
<span class="legacy-anchor" id="core-concepts"></span>
<span class="legacy-anchor" id="what-is-a-skill"></span>
<span class="legacy-anchor" id="skill-md-format"></span>
<span class="legacy-anchor" id="skill-name-rules"></span>
<span class="legacy-anchor" id="progressive-disclosure-flow"></span>
<span class="legacy-anchor" id="creating-a-skill"></span>
<span class="legacy-anchor" id="step-1-create-directory-structure"></span>
<span class="legacy-anchor" id="step-2-write-skill-md"></span>
<span class="legacy-anchor" id="agent-integration"></span>
<span class="legacy-anchor" id="basic-usage"></span>
<span class="legacy-anchor" id="full-configuration-with-skillconfig"></span>
<span class="legacy-anchor" id="disabling-skills"></span>
<span class="legacy-anchor" id="multi-source-priority"></span>
<span class="legacy-anchor" id="architecture"></span>
<span class="legacy-anchor" id="lifecycle-ordering"></span>
<span class="legacy-anchor" id="key-classes"></span>
<span class="legacy-anchor" id="best-practices"></span>
<span class="legacy-anchor" id="skill-design"></span>
<span class="legacy-anchor" id="directory-organization"></span>
<span class="legacy-anchor" id="security-considerations"></span>
<span class="legacy-anchor" id="api-reference"></span>
<span class="legacy-anchor" id="skillconfig-builder"></span>
<span class="legacy-anchor" id="skillconfig-of"></span>
<span class="legacy-anchor" id="agentbuilder-skills"></span>

A Skill usually contains `SKILL.md`, scripts and resources. It packages reusable operating knowledge. Installation does not guarantee model selection or grant external account permissions.

::: info Validation
These fragments come from the [complete compile-only sample](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java). Types were checked against current source; live models and external stores were not executed. The caller supplies parameters such as `provider` and `model`. Runtime evidence is in the [offline Agent](framework.md#离线-agent).
:::
## Prepare a directory {#准备目录}

```text
.core-ai/skills/
  echo-guide/
    SKILL.md
    scripts/
    resources/
```

Use frontmatter for the name and description. State when to use the skill, its inputs, steps and expected result. Test scripts independently and organize resources with package-relative paths.

## Attach a Registry {#接入-registry}

Current Agent builders use `.skillRegistry(registry)`. Configure filesystem providers:

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
The caller supplies `userSkillsPath`. **Lower numeric priority wins**. Registry merges by qualified name; a matching short name is not a guarantee of replacement across namespaces.

`SkillConfig` describes sources but is not a current `AgentBuilder.skills(...)` method; that method does not exist. Build the Registry through the actual loading path.

## Check loaded metadata {#检查加载结果}

Inspect `registry.listAll()`, then `find(...)` and `readContent(...)`. `invalidateCache()` clears Registry cache; filesystem providers cache independently, so verify instance lifecycle when refreshing files.

This sample was compiled without installing or executing external skills. See [CLI paths](cli.md#独立模型与工作区) for workspace and global resources.

[Function Tools](tutorial-tool-calling.md) · [SkillRegistry source](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/skill/SkillRegistry.java)

## Historical long-form material

Earlier scenario analysis and extended examples are retained in the [pinned version](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/en/tutorial-skills.md). They may use older APIs; use the source-checked examples above for current development.
