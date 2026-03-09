# Skill 增强实现计划

## 概述

两个维度的增强：
- **维度一**：Skill 调用准确性增强（A、B、C）
- **维度二**：Skill 能力拓展（D、E、F）

## 实现阶段

### Phase 1：SkillMetadata 与 SkillLoader 增强（D 基础 + A/B 数据层）

**目标**：扩展 SKILL.md frontmatter 模式和解析能力，支持 triggers、结构化 references、examples、output-format。

**需修改文件**：
- `core-ai/src/main/java/ai/core/skill/SkillMetadata.java`
- `core-ai/src/main/java/ai/core/skill/SkillLoader.java`
- `core-ai/src/test/java/ai/core/skill/SkillLoaderTest.java`

**改动内容**：

1. **SkillMetadata** — 新增字段：
   - `List<String> triggers` — 触发短语列表
   - `List<ReferenceEntry> references` — 结构化引用（文件 + 描述）
   - `List<String> examples` — 使用示例
   - `String outputFormat` — 期望输出格式

2. **ReferenceEntry** — SkillMetadata 内新增 record：
   ```java
   public record ReferenceEntry(String file, String description) {}
   ```

3. **SkillLoader.parseSkillMd()** — 解析新增 frontmatter 字段：
   - `triggers`：List\<String\>
   - `references`：List\<Map\> → List\<ReferenceEntry\>
   - `examples`：List\<String\>
   - `output-format`：String

4. **测试**：验证所有新字段的解析

**验证命令**：`./gradlew :core-ai:test --tests "ai.core.skill.SkillLoaderTest"`

---

### Phase 2：触发描述增强（A）

**目标**：SkillTool 描述中包含每个 skill 的触发短语，提升 LLM 匹配准确率。

**需修改文件**：
- `core-ai/src/main/java/ai/core/tool/tools/SkillTool.java`

**改动内容**：

1. **SkillTool.buildDescription()** — 在每个 skill 后追加触发短语：
   ```
   - code-review: Reviews code quality
     Triggers: "review code", "check quality", "PR review"
   ```

**验证命令**：`./gradlew :core-ai:test --tests "ai.core.tool.tools.*"`

---

### Phase 3：Skill Reference 读取工具（B）

**目标**：独立工具，让 LLM 能精确获取 skill 引用文件内容。

**需新建文件**：
- `core-ai/src/main/java/ai/core/tool/tools/ReadSkillReferenceTool.java`
- `core-ai/src/test/java/ai/core/tool/tools/ReadSkillReferenceToolTest.java`

**需修改文件**：
- `core-ai-cli/src/main/java/ai/core/cli/agent/CliAgent.java` — 注册工具
- `core-ai/src/main/java/ai/core/tool/tools/SkillTool.java` — use_skill 输出中包含 reference 描述

**改动内容**：

1. **ReadSkillReferenceTool** — 新建 ToolCall：
   - 名称：`read_skill_reference`
   - 参数：`skill_name`（必填）、`file`（必填）
   - 描述中动态列出每个 skill 的可用引用文件
   - 从 `{skillDir}/references/{file}` 读取文件
   - 校验文件路径在 skill 目录内（安全性）
   - Builder 接收 `List<SkillMetadata>` 构建动态描述

2. **SkillTool.readSkillContent()** — 输出中包含 reference 描述：
   ```
   References (use read_skill_reference to read):
   - api-patterns.md: Common API design patterns
   - error-handling.md: Error handling best practices
   ```

3. **CliAgent** — 在 SkillTool 旁注册 ReadSkillReferenceTool

**验证命令**：`./gradlew :core-ai:test --tests "ai.core.tool.tools.ReadSkillReferenceToolTest"`

---

### Phase 4：Skill 推荐（C）

**目标**：可选的关键词匹配 skill 推荐，在 beforeModel 中执行。

**需修改文件**：
- `core-ai/src/main/java/ai/core/skill/SkillConfig.java`
- `core-ai/src/main/java/ai/core/skill/SkillLifecycle.java`
- `core-ai/src/test/java/ai/core/skill/SkillLifecycleTest.java`
- `core-ai-cli/src/main/java/ai/core/cli/command/SkillCommandHandler.java`

**改动内容**：

1. **SkillConfig** — 新增 `recommendEnabled` 字段（默认：false）
2. **SkillLifecycle.beforeModel()** — 开启时，扫描最后一条用户消息与 skill 的 triggers/name/description 进行关键词匹配，匹配成功则注入提示
3. **SkillCommandHandler** — 新增 `/skill config recommend on|off` 子命令
4. **持久化**：配置存储在 `.core-ai/skill-config.json`

**验证命令**：`./gradlew :core-ai:test --tests "ai.core.skill.SkillLifecycleTest"`

---

### Phase 5：内置 Skill 包（E）

**目标**：为 CLI 预置高质量 skill。

**需新建文件**：
- `core-ai-cli/src/main/resources/skills/commit/SKILL.md`
- `core-ai-cli/src/main/resources/skills/code-review/SKILL.md`
- `core-ai-cli/src/main/resources/skills/debug/SKILL.md`
- `core-ai-cli/src/main/resources/skills/refactor/SKILL.md`

**需修改文件**：
- `core-ai-cli/src/main/java/ai/core/cli/agent/CliAgent.java` — 添加内置 skill 源（最低优先级）

**改动内容**：

1. Skill 打包为 classpath 资源
2. CliAgent 添加内置 skill 源，优先级为 0（最低，用户/工作区 skill 可覆盖）
3. SkillLoader 需支持 classpath 资源加载，或解压到临时目录

**验证方式**：CLI 手动测试

---

### Phase 6：Skill 模板生成（F）

**目标**：ManageSkillTool 的 create 操作生成结构完整的模板。

**需修改文件**：
- `core-ai/src/main/java/ai/core/tool/tools/ManageSkillTool.java`
- `core-ai-cli/src/main/java/ai/core/cli/command/SkillCommandHandler.java`

**改动内容**：

1. **ManageSkillTool** — 更新 TOOL_DESC，说明所有 frontmatter 字段
2. **SkillCommandHandler** — `/skill create <name>` 生成包含所有字段的规范 SKILL.md 模板

**验证方式**：手动测试

---

## 执行顺序

```
Phase 1（基础）→ Phase 2（A）→ Phase 3（B）→ Phase 4（C）→ Phase 5（E）→ Phase 6（F）
```

Phase 1 是基础——后续所有阶段都依赖扩展后的 SkillMetadata。
Phase 2-4 为维度一（准确性）。Phase 5-6 为维度二（拓展）。
