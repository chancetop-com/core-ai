# 09 - Skill 系统

> **学习目标**：深入理解 Skill 系统的定义格式（SKILL.md frontmatter）、加载机制、注册表、以及 Skill 如何转成工具供 Agent 调用。
>
> **预计时间**：1 天
>
> **前置要求**：完成 [05-工具系统](./05-工具系统.md)（ToolCall/ToolProvider）

---

## 📋 本章内容

- [9.1 Skill 系统总览](#91-skill-系统总览)
- [9.2 SKILL.md 定义格式](#92-skillmd-定义格式)
- [9.3 SkillLoader 加载机制](#93-skillloader-加载机制)
- [9.4 SkillMetadata 元数据](#94-skillmetadata-元数据)
- [9.5 SkillRegistry 注册表](#95-skillregistry-注册表)
- [9.6 SkillToolProvider 转工具](#96-skilltoolprovider-转工具)
- [9.7 Skill 命名空间](#97-skill-命名空间)
- [9.8 实战示例](#98-实战示例)
- [9.9 验证学习成果](#99-验证学习成果)

---

## 9.1 Skill 系统总览

### 9.1.1 Skill 的角色

Skill 是 core-ai 的**可复用能力单元**，把一组相关的提示词 + 工具定义封装成一个独立模块：

- **提示词**：定义 agent 的行为和专业知识
- **工具定义**：定义 skill 提供的工具（可选）
- **元数据**：名称、描述、版本、作者等

💡 **设计意图**：Skill 让 agent 能力可以模块化、可复用、可共享。类似于编程中的"库"或"插件"。

### 9.1.2 核心类关系图

```
┌─────────────────────────────────────────────────────────────┐
│                    SKILL.md 文件                              │
│                                                             │
│  ---                                                        │
│  name: my-skill                                             │
│  description: A useful skill                                │
│  version: 1.0.0                                             │
│  author: john                                               │
│  tools:                                                     │
│    - name: my-tool                                          │
│      description: Does something                            │
│      parameters:                                            │
│        - name: input                                        │
│          type: string                                       │
│          required: true                                     │
│  ---                                                        │
│                                                             │
│  # My Skill                                                 │
│                                                             │
│  This is the skill prompt...                                │
└──────────────────────┬──────────────────────────────────────┘
                       │ 加载
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    SkillLoader                               │
│                                                             │
│  + loadAll(sources): List<SkillMetadata>        ← 加载所有  │
│  + loadFromSource(path): List<SkillMetadata>    ← 从目录加载│
│  + loadSkillFile(file, name, namespace): SkillMetadata      │
└──────────────────────┬──────────────────────────────────────┘
                       │ 注册
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    SkillRegistry                             │
│                                                             │
│  - skills: Map<qualifiedName, SkillMetadata>                │
│                                                             │
│  + register(skill)                                          │
│  + get(qualifiedName): SkillMetadata                        │
│  + getAll(): List<SkillMetadata>                            │
└──────────────────────┬──────────────────────────────────────┘
                       │ 转换
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                  SkillToolProvider                           │
│                                                             │
│  + id(): "skill"                                            │
│  + provide(): Map<String, ToolCall>             ← 转成工具  │
│  + priority(): 200                                          │
│  + refreshPolicy(): ONCE                                    │
└─────────────────────────────────────────────────────────────┘
```

### 9.1.3 Skill vs Tool

| 特性 | Skill | Tool |
|---|---|---|
| **粒度** | 能力单元（可能包含多个工具） | 单个工具 |
| **定义方式** | SKILL.md 文件 | Java 类（继承 ToolCall） |
| **加载方式** | 文件扫描 | 代码注册 |
| **适用场景** | 复杂能力（如"代码审查"） | 简单操作（如"执行 bash"） |
| **可复用性** | 高（可共享 SKILL.md） | 中（需要写代码） |

💡 **设计意图**：
- **Skill**：适合复杂能力，用 Markdown 定义，易于非开发人员理解
- **Tool**：适合简单操作，用 Java 定义，性能更好

---

## 9.2 SKILL.md 定义格式

### 9.2.1 文件结构

```markdown
---
name: code-review
description: Code review skill for Java projects
version: 1.0.0
author: john
tools:
  - name: review-code
    description: Review Java code for best practices
    parameters:
      - name: code
        type: string
        required: true
        description: The code to review
      - name: language
        type: string
        required: false
        description: Programming language
        default: java
  - name: suggest-improvements
    description: Suggest code improvements
    parameters:
      - name: code
        type: string
        required: true
---

# Code Review Skill

You are an expert Java code reviewer. When reviewing code, focus on:

1. **Best practices**: Follow Java conventions and design patterns
2. **Performance**: Identify performance bottlenecks
3. **Security**: Check for common security vulnerabilities
4. **Readability**: Ensure code is clean and well-documented

Always provide specific, actionable feedback with examples.
```

💡 **设计意图**：
- **Frontmatter**（`---` 之间）：YAML 格式的元数据
- **正文**：Markdown 格式的提示词（定义 agent 的行为）

### 9.2.2 Frontmatter 字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | ✅ | Skill 名称（唯一标识） |
| `description` | string | ✅ | Skill 描述 |
| `version` | string | ❌ | 版本号（如 "1.0.0"） |
| `author` | string | ❌ | 作者 |
| `tags` | list | ❌ | 标签列表（如 ["java", "review"]） |
| `tools` | list | ❌ | 工具定义列表 |

### 9.2.3 工具定义格式

```yaml
tools:
  - name: review-code                    # 工具名
    description: Review Java code        # 工具描述
    parameters:                          # 参数列表
      - name: code                       # 参数名
        type: string                     # 参数类型
        required: true                   # 是否必填
        description: The code to review  # 参数描述
        default: ""                      # 默认值（可选）
      - name: language
        type: string
        required: false
        description: Programming language
        default: java
```

💡 **设计意图**：工具定义会被转换成 JSON Schema，供 LLM 调用。

### 9.2.4 支持的参数类型

| 类型 | 说明 | 示例 |
|---|---|---|
| `string` | 字符串 | `"hello"` |
| `number` | 数字 | `42`, `3.14` |
| `boolean` | 布尔值 | `true`, `false` |
| `array` | 数组 | `[1, 2, 3]` |
| `object` | 对象 | `{"key": "value"}` |

---

## 9.3 SkillLoader 加载机制

### 9.3.1 文件位置

```
core-ai/src/main/java/ai/core/skill/SkillLoader.java
```

### 9.3.2 核心逻辑

```java
public class SkillLoader {
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final Pattern FRONTMATTER_PATTERN = 
        Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n", Pattern.DOTALL);
    
    private final int maxSkillFileSize;
    
    public SkillLoader(int maxSkillFileSize) {
        this.maxSkillFileSize = maxSkillFileSize;
    }
    
    /**
     * 从多个源目录加载所有 Skill
     */
    public List<SkillMetadata> loadAll(List<SkillSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        
        Map<String, SkillMetadata> skillMap = new LinkedHashMap<>();
        var sorted = new ArrayList<>(sources);
        Collections.sort(sorted);  // 按优先级排序
        
        for (var source : sorted) {
            var skills = loadFromSource(source.path());
            for (var skill : skills) {
                skillMap.put(skill.getQualifiedName(), skill);  // 高优先级覆盖
            }
        }
        
        return new ArrayList<>(skillMap.values());
    }
    
    /**
     * 从单个源目录加载 Skill
     */
    public List<SkillMetadata> loadFromSource(String sourcePath) {
        var dir = Path.of(sourcePath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            LOGGER.debug("Skill source directory does not exist: {}", sourcePath);
            return Collections.emptyList();
        }
        
        Path realDir = resolveRealPath(dir);
        if (realDir == null) return Collections.emptyList();
        
        List<SkillMetadata> skills = new ArrayList<>();
        
        // 1. 检查根目录是否有 SKILL.md（整个目录是一个 skill）
        loadRootSkillIfPresent(realDir, skills);
        
        // 2. 扫描子目录
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(realDir)) {
            for (var entry : stream) {
                if (!Files.isDirectory(entry)) continue;
                
                var skillFile = entry.resolve(SKILL_FILE_NAME);
                if (Files.exists(skillFile)) {
                    // 子目录包含 SKILL.md：这是一个 skill
                    if (!isWithinDirectory(skillFile, realDir)) {
                        LOGGER.warn("Skill file path escapes source directory: {}", skillFile);
                        continue;
                    }
                    var entryName = entry.getFileName();
                    if (entryName == null) continue;
                    var skill = loadSkillFile(skillFile, entryName.toString(), null);
                    if (skill != null) {
                        skills.add(skill);
                    }
                } else {
                    // 子目录不包含 SKILL.md：可能是命名空间目录
                    skills.addAll(loadNamespaceDirectory(entry, realDir));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan skill source directory: {}", sourcePath, e);
        }
        
        return skills;
    }
    
    /**
     * 加载单个 SKILL.md 文件
     */
    private SkillMetadata loadSkillFile(Path skillFile, String dirName, String namespace) {
        try {
            var content = Files.readString(skillFile, StandardCharsets.UTF_8);
            
            // 检查文件大小
            if (content.length() > maxSkillFileSize) {
                LOGGER.warn("Skill file too large: {} ({} chars > {} max)", 
                    skillFile, content.length(), maxSkillFileSize);
                return null;
            }
            
            // 解析 frontmatter
            var matcher = FRONTMATTER_PATTERN.matcher(content);
            if (!matcher.find()) {
                LOGGER.warn("No frontmatter found in skill file: {}", skillFile);
                return null;
            }
            
            var frontmatterYaml = matcher.group(1);
            var prompt = content.substring(matcher.end()).trim();
            
            // 解析 YAML
            var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            var metadata = yaml.loadAs(frontmatterYaml, Map.class);
            
            // 构建 SkillMetadata
            var skill = new SkillMetadata();
            skill.setName((String) metadata.getOrDefault("name", dirName));
            skill.setDescription((String) metadata.get("description"));
            skill.setVersion((String) metadata.get("version"));
            skill.setAuthor((String) metadata.get("author"));
            skill.setTags((List<String>) metadata.get("tags"));
            skill.setPrompt(prompt);
            skill.setNamespace(namespace);
            
            // 解析工具定义
            var toolsList = (List<Map<String, Object>>) metadata.get("tools");
            if (toolsList != null) {
                skill.setTools(parseTools(toolsList));
            }
            
            return skill;
        } catch (Exception e) {
            LOGGER.warn("Failed to load skill file: {}", skillFile, e);
            return null;
        }
    }
    
    /**
     * 加载命名空间目录（包含多个 skill）
     */
    private List<SkillMetadata> loadNamespaceDirectory(Path namespaceDir, Path parentDir) {
        List<SkillMetadata> skills = new ArrayList<>();
        var namespace = namespaceDir.getFileName().toString();
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(namespaceDir)) {
            for (var entry : stream) {
                if (!Files.isDirectory(entry)) continue;
                
                var skillFile = entry.resolve(SKILL_FILE_NAME);
                if (Files.exists(skillFile)) {
                    var skill = loadSkillFile(skillFile, entry.getFileName().toString(), namespace);
                    if (skill != null) {
                        skills.add(skill);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan namespace directory: {}", namespaceDir, e);
        }
        
        return skills;
    }
}
```

💡 **设计意图**：
- **多源加载**：支持从多个目录加载 skill，按优先级排序
- **命名空间**：支持目录嵌套，形成 `namespace.skill-name` 的 qualified name
- **安全检查**：检查文件大小、路径逃逸等安全问题
- **容错处理**：单个 skill 加载失败不影响其他 skill

### 9.3.3 加载流程

```
SkillLoader.loadAll(sources)
  │
  ├─ 1. 按优先级排序 sources
  │
  ├─ 2. 对每个 source：
  │      │
  │      ├─ 2.1 检查根目录是否有 SKILL.md
  │      │      └─ 如果有：整个目录是一个 skill
  │      │
  │      └─ 2.2 扫描子目录
  │             │
  │             ├─ 如果子目录包含 SKILL.md：这是一个 skill
  │             └─ 如果子目录不包含 SKILL.md：可能是命名空间目录
  │                    └─ 递归扫描子目录
  │
  ├─ 3. 用 qualifiedName 去重（高优先级覆盖低优先级）
  │
  └─ 4. 返回所有 skill 列表
```

---

## 9.4 SkillMetadata 元数据

### 9.4.1 类定义

```java
public class SkillMetadata {
    private String name;                    // Skill 名称
    private String description;             // Skill 描述
    private String version;                 // 版本号
    private String author;                  // 作者
    private List<String> tags;              // 标签列表
    private String prompt;                  // 提示词（Markdown 正文）
    private String namespace;               // 命名空间（可选）
    private List<SkillToolDefinition> tools; // 工具定义列表
    
    /**
     * 获取 qualified name（命名空间.名称）
     */
    public String getQualifiedName() {
        if (namespace == null || namespace.isBlank()) {
            return name;
        }
        return namespace + "." + name;
    }
    
    // getters and setters
}
```

💡 **设计意图**：
- **qualifiedName**：唯一标识，格式为 `namespace.name` 或 `name`
- **prompt**：Markdown 格式的提示词，定义 agent 的行为
- **tools**：skill 提供的工具定义列表

### 9.4.2 SkillToolDefinition 工具定义

```java
public class SkillToolDefinition {
    private String name;                    // 工具名
    private String description;             // 工具描述
    private List<SkillToolParameter> parameters;  // 参数列表
    
    // getters and setters
}

public class SkillToolParameter {
    private String name;                    // 参数名
    private String type;                    // 参数类型（string/number/boolean/array/object）
    private boolean required;               // 是否必填
    private String description;             // 参数描述
    private Object defaultValue;            // 默认值（可选）
    
    // getters and setters
}
```

---

## 9.5 SkillRegistry 注册表

### 9.5.1 文件位置

```
core-ai/src/main/java/ai/core/skill/SkillRegistry.java
```

### 9.5.2 类定义

```java
public class SkillRegistry {
    private final Map<String, SkillMetadata> skills = new ConcurrentHashMap<>();
    
    /**
     * 注册 skill
     */
    public void register(SkillMetadata skill) {
        var qualifiedName = skill.getQualifiedName();
        var previous = skills.put(qualifiedName, skill);
        if (previous != null) {
            LOGGER.info("Replaced skill: {}", qualifiedName);
        } else {
            LOGGER.info("Registered skill: {}", qualifiedName);
        }
    }
    
    /**
     * 批量注册
     */
    public void registerAll(List<SkillMetadata> skills) {
        for (var skill : skills) {
            register(skill);
        }
    }
    
    /**
     * 按 qualified name 获取 skill
     */
    public SkillMetadata get(String qualifiedName) {
        return skills.get(qualifiedName);
    }
    
    /**
     * 获取所有 skill
     */
    public List<SkillMetadata> getAll() {
        return new ArrayList<>(skills.values());
    }
    
    /**
     * 按名称获取（忽略命名空间）
     */
    public Optional<SkillMetadata> findByName(String name) {
        return skills.values().stream()
            .filter(s -> s.getName().equals(name))
            .findFirst();
    }
    
    /**
     * 按标签过滤
     */
    public List<SkillMetadata> findByTag(String tag) {
        return skills.values().stream()
            .filter(s -> s.getTags() != null && s.getTags().contains(tag))
            .toList();
    }
    
    /**
     * 检查是否存在
     */
    public boolean contains(String qualifiedName) {
        return skills.containsKey(qualifiedName);
    }
    
    /**
     * 取消注册
     */
    public void unregister(String qualifiedName) {
        skills.remove(qualifiedName);
        LOGGER.info("Unregistered skill: {}", qualifiedName);
    }
}
```

💡 **设计意图**：
- **按 qualifiedName 索引**：快速查找
- **支持命名空间**：`namespace.skill-name` 避免名称冲突
- **按标签过滤**：方便按功能分类查找

---

## 9.6 SkillToolProvider 转工具

### 9.6.1 文件位置

```
core-ai/src/main/java/ai/core/skill/SkillToolProvider.java
```

### 9.6.2 类定义

```java
public class SkillToolProvider implements ToolProvider {
    private final SkillRegistry skillRegistry;
    
    public SkillToolProvider(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }
    
    @Override
    public String id() {
        return "skill";
    }
    
    @Override
    public Map<String, ToolCall> provide() {
        var result = new LinkedHashMap<String, ToolCall>();
        
        for (var skill : skillRegistry.getAll()) {
            if (skill.getTools() != null) {
                for (var toolDef : skill.getTools()) {
                    var toolCall = convertToToolCall(skill, toolDef);
                    result.put(toolCall.getName(), toolCall);
                }
            }
        }
        
        return result;
    }
    
    @Override
    public int priority() {
        return 200;  // 中等优先级
    }
    
    @Override
    public RefreshPolicy refreshPolicy() {
        return RefreshPolicy.ONCE;  // skill 定义不变
    }
    
    /**
     * 把 SkillToolDefinition 转换成 ToolCall
     */
    private ToolCall convertToToolCall(SkillMetadata skill, SkillToolDefinition toolDef) {
        return new SkillToolCall(skill, toolDef);
    }
}
```

### 9.6.3 SkillToolCall 实现

```java
public class SkillToolCall extends ToolCall {
    private final SkillMetadata skill;
    private final SkillToolDefinition toolDef;
    
    public SkillToolCall(SkillMetadata skill, SkillToolDefinition toolDef) {
        this.skill = skill;
        this.toolDef = toolDef;
        
        // 设置工具属性
        this.name = toolDef.getName();
        this.description = toolDef.getDescription();
        this.parameters = convertParameters(toolDef.getParameters());
        this.sourceType = "skill";
    }
    
    @Override
    public ToolCallResult execute(String arguments) {
        // Skill 工具的执行逻辑：
        // 1. 把 skill 的 prompt 和工具参数组合
        // 2. 调 LLM 生成回答
        // 3. 返回结果
        
        var args = parseArguments(arguments);
        
        // 构建 prompt
        var prompt = skill.getPrompt() + "\n\n";
        prompt += "Tool: " + toolDef.getName() + "\n";
        prompt += "Arguments: " + JsonUtil.toJson(args) + "\n";
        
        // 调 LLM（需要通过 ExecutionContext 获取 LLMProvider）
        // 这里简化处理
        return ToolCallResult.success("Skill tool executed: " + toolDef.getName());
    }
    
    private List<ToolCallParameter> convertParameters(List<SkillToolParameter> skillParams) {
        if (skillParams == null) return List.of();
        
        return skillParams.stream()
            .map(sp -> {
                var param = new ToolCallParameter();
                param.setName(sp.getName());
                param.setType(ToolCallParameterType.fromString(sp.getType()));
                param.setRequired(sp.isRequired());
                param.setDescription(sp.getDescription());
                param.setDefaultValue(sp.getDefaultValue());
                return param;
            })
            .toList();
    }
}
```

💡 **设计意图**：
- **SkillToolProvider**：把 SkillRegistry 中的 skill 转成 ToolCall，注册到 ToolRegistry
- **SkillToolCall**：具体的工具实现，执行时调 LLM 处理 skill 的 prompt
- **优先级 200**：中等优先级，高于内置工具（1000），低于用户自定义（100）

---

## 9.7 Skill 命名空间

### 9.7.1 目录结构示例

```
skills/
├── code-review/                    ← 单个 skill（根目录有 SKILL.md）
│   └── SKILL.md
├── java/                           ← 命名空间目录
│   ├── code-style/                 ← skill: java.code-style
│   │   └── SKILL.md
│   └── testing/                    ← skill: java.testing
│       └── SKILL.md
└── python/                         ← 命名空间目录
    ├── linting/                    ← skill: python.linting
    │   └── SKILL.md
    └── testing/                    ← skill: python.testing
        └── SKILL.md
```

💡 **设计意图**：
- **根目录 skill**：`code-review/` 的 qualified name 是 `code-review`
- **命名空间 skill**：`java/code-style/` 的 qualified name 是 `java.code-style`
- **避免冲突**：不同命名空间可以有同名 skill（如 `java.testing` 和 `python.testing`）

### 9.7.2 Qualified Name 解析

```java
// 获取 skill
var skill = registry.get("java.code-style");

// 按名称查找（忽略命名空间）
var skill = registry.findByName("code-style").orElse(null);

// 按标签过滤
var skills = registry.findByTag("java");
```

---

## 9.8 实战示例

### 9.8.1 创建 Skill 目录

```bash
mkdir -p skills/code-review
cat > skills/code-review/SKILL.md << 'EOF'
---
name: code-review
description: Code review skill for Java projects
version: 1.0.0
author: john
tools:
  - name: review-code
    description: Review Java code for best practices
    parameters:
      - name: code
        type: string
        required: true
        description: The code to review
---

# Code Review Skill

You are an expert Java code reviewer. Focus on:
1. Best practices
2. Performance
3. Security
4. Readability
EOF
```

### 9.8.2 加载并使用 Skill

```java
// 1. 创建 SkillLoader
var loader = new SkillLoader(100000);  // 最大文件大小 100KB

// 2. 加载 skill
var sources = List.of(new SkillSource("skills", 100));  // 优先级 100
var skills = loader.loadAll(sources);

// 3. 注册到 SkillRegistry
var registry = new SkillRegistry();
registry.registerAll(skills);

// 4. 创建 SkillToolProvider
var skillToolProvider = new SkillToolProvider(registry);

// 5. 注册到 ToolRegistry
var toolRegistry = new ToolRegistry();
toolRegistry.registerProvider(skillToolProvider);

// 6. 创建 Agent
var agent = Agent.builder()
    .name("skill-agent")
    .systemPrompt("你是一个代码审查助手")
    .llmProvider(llmProvider)
    .toolRegistry(toolRegistry)
    .build();

// 7. 执行
var output = agent.execute("请审查这段代码：public class Test { ... }", null);
System.out.println(output);
```

### 9.8.3 多命名空间 Skill

```bash
# 创建命名空间目录
mkdir -p skills/java/code-style
mkdir -p skills/python/linting

# 创建 Java skill
cat > skills/java/code-style/SKILL.md << 'EOF'
---
name: code-style
description: Java code style checker
---

Check Java code style according to Google Java Style Guide.
EOF

# 创建 Python skill
cat > skills/python/linting/SKILL.md << 'EOF'
---
name: linting
description: Python linting skill
---

Lint Python code using pylint and flake8.
EOF
```

```java
// 加载所有 skill
var skills = loader.loadAll(sources);

// 按 qualified name 获取
var javaSkill = registry.get("java.code-style");
var pythonSkill = registry.get("python.linting");

// 按标签过滤
var javaSkills = registry.findByTag("java");
```

---

## 9.9 验证学习成果

完成本章后，你应该能：

### ✅ 必须掌握

- [ ] 说出 SKILL.md 的格式（frontmatter + prompt）
- [ ] 说出 SkillLoader 的加载流程（多源扫描 + 命名空间）
- [ ] 说出 SkillRegistry 的作用（按 qualifiedName 索引）
- [ ] 说出 SkillToolProvider 如何把 skill 转成工具
- [ ] 能创建一个简单的 skill 并加载使用

### 🔧 动手实践

1. **创建 SKILL.md**：

写一个简单的 skill（如"翻译助手"），包含 frontmatter 和 prompt。

2. **加载 skill**：

用 SkillLoader 加载 skill 目录，注册到 SkillRegistry。

3. **使用 skill**：

创建 Agent，注册 SkillToolProvider，调用 skill 工具。

### 📝 自测题

1. Skill 的定义文件格式是什么？
   - A. JSON
   - B. YAML
   - C. Markdown with frontmatter
   
   **答案**：C（Markdown with frontmatter）

2. SkillLoader 如何避免名称冲突？
   - A. 用 UUID
   - B. 用命名空间（qualifiedName）
   - C. 用版本号
   
   **答案**：B（用命名空间，如 `java.code-style`）

3. SkillToolProvider 的优先级是多少？
   - A. 100（高）
   - B. 200（中）
   - C. 1000（低）
   
   **答案**：B（200，中等优先级）

---

## 🎉 本章小结

本章你学会了：

- ✅ Skill 的定义格式（SKILL.md frontmatter + prompt）
- ✅ SkillLoader 的加载机制（多源扫描 + 命名空间 + 安全检查）
- ✅ SkillMetadata 元数据（name/description/tools/prompt）
- ✅ SkillRegistry 注册表（按 qualifiedName 索引）
- ✅ SkillToolProvider 转工具（把 skill 转成 ToolCall）
- ✅ 命名空间机制（避免名称冲突）
- ✅ 实战示例（创建/加载/使用 skill）

---

## 🚀 下一章

准备好进入 **[10-MCP协议集成](./10-MCP协议集成.md)** 了吗？

下一章你会学到：
- MCP 协议简介
- McpClientManager（MCP 客户端）
- McpServerService（MCP 服务端）
- 如何接入外部 MCP server
- 如何暴露工具为 MCP server

---

*最后更新：2026-08-31*
