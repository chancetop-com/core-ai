# Skill 增强实现计划

## 概述

两个维度的增强：
- **维度一**：Skill 调用准确性增强（A、B、C）
- **维度二**：Skill 能力拓展（D、E）

## 实现阶段

### Phase 1：SKILL.md 规范增强与触发描述（A + D 基础）

**目标**：扩展 SKILL.md frontmatter 模式和解析能力，支持 triggers、结构化 references、examples、output-format；同时在 SkillTool 描述中展示触发短语，提升 LLM 匹配准确率。

**需修改文件**：
- `core-ai/src/main/java/ai/core/skill/SkillMetadata.java`
- `core-ai/src/main/java/ai/core/skill/SkillLoader.java`
- `core-ai/src/main/java/ai/core/tool/tools/SkillTool.java`
- `core-ai/src/main/java/ai/core/tool/tools/ManageSkillTool.java`
- `core-ai/src/test/java/ai/core/skill/SkillLoaderTest.java`

**改动内容**：

1. **SkillMetadata** — 变更字段：
   - **移除** `List<String> resources` — 被 `references` 完全替代
   - **新增** `List<String> triggers` — 触发短语列表
   - **新增** `List<ReferenceEntry> references` — 结构化引用（文件 + 描述），替代原 `resources`
   - **新增** `List<String> examples` — 使用示例
   - **新增** `String outputFormat` — 期望输出格式

2. **ReferenceEntry** — SkillMetadata 内新增 record：
   ```java
   public record ReferenceEntry(String file, String description) {}
   ```
   - `file` 为相对于 skill 目录的路径，如 `references/api-patterns.md` 或 `scripts/collect.sh`
   - 实际文件存储位置按类型区分：`{skillDir}/references/` 或 `{skillDir}/scripts/`

3. **SkillLoader** 变更：
   - `parseSkillMd()` — 解析新增 frontmatter 字段：
     - `triggers`：List\<String\>
     - `references`：List\<Map\> → List\<ReferenceEntry\>
     - `examples`：List\<String\>
     - `output-format`：String
   - `scanResources()` — 仍扫描 `scripts/` 和 `references/` 目录，但结果合并到 `references` 字段（未在 frontmatter 中声明的文件自动追加，description 为空）

4. **SkillTool.buildDescription()** — 在每个 skill 后追加触发短语：
   ```
   - code-review: Reviews code quality
     Triggers: "review code", "check quality", "PR review"
   ```

5. **ManageSkillTool** — 更新 create 模板和 TOOL_DESC，包含所有新增 frontmatter 字段

6. **测试**：验证所有新字段的解析

**验证命令**：`./gradlew :core-ai:test --tests "ai.core.skill.*" --tests "ai.core.tool.tools.*"`

---

### Phase 2：Skill Reference 读取工具（B）

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
   - 从 `{skillDir}/{file}` 读取文件（file 为相对路径，如 `references/guide.md` 或 `scripts/run.sh`）
   - 校验文件路径在 skill 目录内（安全性）
   - Builder 接收 `List<SkillMetadata>` 构建动态描述

2. **SkillTool.readSkillContent()** — 输出中包含 reference 描述：
   ```
   References (use read_skill_reference to read):
   - references/api-patterns.md: Common API design patterns
   - scripts/collect.sh: Collect project metrics
   ```

3. **CliAgent** — 在 SkillTool 旁注册 ReadSkillReferenceTool

**验证命令**：`./gradlew :core-ai:test --tests "ai.core.tool.tools.ReadSkillReferenceToolTest"`

---

### Phase 3：Skill 推荐（C）

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

### Phase 4：内置 Skill 包（E）

**目标**：为 CLI 预置高质量 skill。

**需新建文件**：
- `core-ai-cli/src/main/resources/skills/auto-memory/SKILL.md` — 自动记忆管理，跨会话持久化用户偏好和项目约定
- `core-ai-cli/src/main/resources/skills/code-simplify/SKILL.md` — 代码简化，审查并精简代码以提高清晰度和可维护性

**需修改文件**：
- `core-ai-cli/src/main/java/ai/core/cli/agent/CliAgent.java` — 添加内置 skill 源（最低优先级）

**改动内容**：

1. Skill 打包为 classpath 资源
2. CliAgent 添加内置 skill 源，优先级为 0（最低，用户/工作区 skill 可覆盖）
3. SkillLoader 需支持 classpath 资源加载，或解压到临时目录

**验证方式**：CLI 手动测试

---

## 执行顺序

```
Phase 1（基础 + 触发描述）→ Phase 2（B）→ Phase 3（C）→ Phase 4（E）
```

Phase 1 是基础——后续所有阶段都依赖扩展后的 SkillMetadata。
Phase 1-3 为维度一（准确性）。Phase 4 为维度二（拓展）。
