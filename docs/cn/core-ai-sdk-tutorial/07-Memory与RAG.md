# 07 - Memory 与 RAG

> **学习目标**：深入理解 Memory 系统（对话历史、记忆存储、检索）和 RAG 系统（检索增强生成），以及如何配置一个 RAG agent。
>
> **预计时间**：1.5 天
>
> **前置要求**：完成 [06-LLM提供商](./06-LLM提供商.md)

---

## 📋 本章内容

- [7.1 Memory 与 RAG 总览](#71-memory-与-rag-总览)
- [7.2 Memory 系统](#72-memory-系统)
- [7.3 MemoryStore 存储抽象](#73-memorystore-存储抽象)
- [7.4 MemoryRecord 记忆记录](#74-memoryrecord-记忆记录)
- [7.5 RAG 系统](#75-rag-系统)
- [7.6 RagConfig 配置](#76-ragconfig-配置)
- [7.7 VectorStore 向量库](#77-vectorstore-向量库)
- [7.8 Document 文档模型](#78-document-文档模型)
- [7.9 RAG 实战配置](#79-rag-实战配置)
- [7.10 验证学习成果](#710-验证学习成果)

---

## 7.1 Memory 与 RAG 总览

### 7.1.1 Memory 与 RAG 的角色

Memory 和 RAG 是 agent 的"长期记忆"和"知识库"：

- **Memory**：存储用户的偏好、历史对话的关键信息，跨会话保留
- **RAG**：检索外部知识库（文档、网页等），增强 agent 的回答能力

💡 **设计意图**：
- **Memory**：让 agent 记住用户的个性化信息（如"用户喜欢 Python"、"用户是开发者"）
- **RAG**：让 agent 能访问最新的外部知识（如公司文档、产品手册）

### 7.1.2 核心类关系图

```
┌─────────────────────────────────────────────────────────────┐
│                         Memory                               │
│                                                             │
│  - memoryStore (MemoryStore)                    ← 存储抽象  │
│  - llmProvider (LLMProvider)                    ← 用于嵌入  │
│  - defaultTopK (int)                            ← 默认检索数│
│                                                             │
│  + retrieve(userId, query, topK): List<MemoryRecord>        │
│  + formatAsContext(memories): String            ← 格式化成上下文│
│  + hasMemories(userId): boolean                             │
│  + getMemoryCount(userId): int                              │
└──────────────────────┬──────────────────────────────────────┘
                       │ uses
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                      MemoryStore                             │
│                                                             │
│  + add(userId, record)                          ← 添加记忆  │
│  + searchByVector(userId, embedding, topK)      ← 向量检索  │
│    : List<MemoryRecord>                                     │
│  + count(userId): int                           ← 计数      │
│  + delete(userId, recordId)                     ← 删除      │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                       RagConfig                              │
│                                                             │
│  - useRag (boolean)                             ← 是否启用  │
│  - topK (int)                                   ← 检索数量  │
│  - threshold (double)                           ← 相似度阈值│
│  - vectorStore (VectorStore)                    ← 向量库    │
│  - llmProvider (LLMProvider)                    ← 用于嵌入  │
│  - enableQueryRewriting (boolean)               ← 查询重写  │
└──────────────────────┬──────────────────────────────────────┘
                       │ uses
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                      VectorStore                             │
│                                                             │
│  + similaritySearch(request): List<Document>    ← 相似度搜索│
│  + add(documents)                               ← 添加文档  │
│  + delete(texts)                                ← 删除文档  │
│  + get(text): Optional<Document>                ← 获取文档  │
│  + name(): String                               ← 向量库名  │
└─────────────────────────────────────────────────────────────┘
```

### 7.1.3 Memory vs RAG 对比

| 特性 | Memory | RAG |
|---|---|---|
| **存储内容** | 用户偏好、历史摘要 | 外部文档、知识库 |
| **数据来源** | 对话历史（自动提取） | 用户上传的文档 |
| **检索方式** | 向量相似度搜索 | 向量相似度搜索 |
| **使用场景** | 个性化回答 | 知识增强 |
| **存储位置** | MemoryStore（可配置） | VectorStore（Milvus/HNSWlib） |
| **生命周期** | 跨会话保留 | 持久化保留 |

💡 **设计意图**：
- **Memory**：从对话中自动提取关键信息，让 agent 记住用户
- **RAG**：从外部知识库检索，让 agent 能回答最新问题

---

## 7.2 Memory 系统

### 7.2.1 文件位置

```
core-ai/src/main/java/ai/core/memory/Memory.java
```

### 7.2.2 类定义

```java
public class Memory {
    private static final Logger LOGGER = LoggerFactory.getLogger(Memory.class);
    private static final int DEFAULT_TOP_K = 5;

    public static Builder builder() {
        return new Builder();
    }

    private final MemoryStore memoryStore;
    private final LLMProvider llmProvider;
    private final int defaultTopK;

    public Memory(MemoryStore memoryStore, LLMProvider llmProvider) {
        this(memoryStore, llmProvider, DEFAULT_TOP_K);
    }

    public Memory(MemoryStore memoryStore, LLMProvider llmProvider, int defaultTopK) {
        this.memoryStore = memoryStore;
        this.llmProvider = llmProvider;
        this.defaultTopK = defaultTopK;
    }

    /**
     * 检索相关记忆
     * 1. 把 query 转成向量
     * 2. 在 MemoryStore 中搜索相似向量
     * 3. 返回 top-K 条记忆
     */
    public List<MemoryRecord> retrieve(String userId, String query, int topK) {
        List<Double> queryEmbedding = generateEmbedding(query);
        if (queryEmbedding == null) {
            return List.of();
        }
        return memoryStore.searchByVector(userId, queryEmbedding, topK);
    }

    public List<MemoryRecord> retrieve(String userId, String query) {
        return retrieve(userId, query, defaultTopK);
    }

    /**
     * 把记忆格式化成上下文
     * 用于拼接到 system prompt
     */
    public String formatAsContext(List<MemoryRecord> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(128);
        sb.append("[User Memory]\n");
        for (MemoryRecord record : memories) {
            sb.append("- ").append(record.getContent()).append('\n');
        }
        return sb.toString();
    }

    public boolean hasMemories(String userId) {
        return memoryStore.count(userId) > 0;
    }

    public int getMemoryCount(String userId) {
        return memoryStore.count(userId);
    }

    public MemoryStore getStore() {
        return memoryStore;
    }

    /**
     * 生成向量嵌入
     * 用 LLM provider 的 embedding 方法
     */
    private List<Double> generateEmbedding(String text) {
        if (llmProvider == null || text == null || text.isBlank()) {
            return null;
        }

        try {
            EmbeddingResponse response = llmProvider.embeddings(new EmbeddingRequest(List.of(text)));
            if (response != null && response.embeddings != null && !response.embeddings.isEmpty()) {
                return response.embeddings.get(0).vector;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to generate embedding for memory retrieval", e);
        }
        return null;
    }

    public static class Builder {
        private MemoryStore memoryStore;
        private LLMProvider llmProvider;
        private int defaultTopK = DEFAULT_TOP_K;

        public Builder memoryStore(MemoryStore memoryStore) {
            this.memoryStore = memoryStore;
            return this;
        }

        public Builder llmProvider(LLMProvider llmProvider) {
            this.llmProvider = llmProvider;
            return this;
        }

        public Builder defaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
            return this;
        }

        public Memory build() {
            return new Memory(memoryStore, llmProvider, defaultTopK);
        }
    }
}
```

### 7.2.3 检索流程

```
Memory.retrieve(userId, query, topK)
  │
  ├─ 1. 生成 query 的向量嵌入
  │      └─ llmProvider.embeddings(query)
  │
  ├─ 2. 在 MemoryStore 中搜索
  │      └─ memoryStore.searchByVector(userId, embedding, topK)
  │
  └─ 3. 返回 top-K 条 MemoryRecord
```

💡 **设计意图**：
- **向量检索**：用向量相似度搜索，找到最相关的记忆
- **用户隔离**：每个用户的记忆独立存储，互不干扰
- **格式化**：`formatAsContext()` 把记忆格式化成文本，拼接到 system prompt

### 7.2.4 使用示例

```java
// 创建 Memory
var memory = Memory.builder()
    .memoryStore(memoryStore)
    .llmProvider(llmProvider)
    .defaultTopK(5)
    .build();

// 检索记忆
var memories = memory.retrieve("user123", "用户喜欢什么编程语言？", 5);

// 格式化成上下文
var context = memory.formatAsContext(memories);
// 输出：
// [User Memory]
// - 用户喜欢 Python 和 Java
// - 用户是后端开发者
// - 用户偏好简洁的代码风格

// 拼接到 system prompt
var systemPrompt = "你是一个编程助手。\n\n" + context;
```

---

## 7.3 MemoryStore 存储抽象

### 7.3.1 接口定义

```java
public interface MemoryStore {
    /**
     * 添加记忆
     */
    void add(String userId, MemoryRecord record);
    
    /**
     * 向量检索
     * 根据 query 的向量，搜索相似的记忆
     */
    List<MemoryRecord> searchByVector(String userId, List<Double> embedding, int topK);
    
    /**
     * 计数
     */
    int count(String userId);
    
    /**
     * 删除记忆
     */
    void delete(String userId, String recordId);
    
    /**
     * 获取所有记忆
     */
    List<MemoryRecord> getAll(String userId);
}
```

💡 **设计意图**：MemoryStore 是存储抽象，可以有多种实现（内存、文件、数据库、向量库等）。

### 7.3.2 实现示例

**InMemoryMemoryStore**（内存实现）：

```java
public class InMemoryMemoryStore implements MemoryStore {
    private final Map<String, List<MemoryRecord>> store = new ConcurrentHashMap<>();
    
    @Override
    public void add(String userId, MemoryRecord record) {
        store.computeIfAbsent(userId, k -> new ArrayList<>()).add(record);
    }
    
    @Override
    public List<MemoryRecord> searchByVector(String userId, List<Double> embedding, int topK) {
        var records = store.getOrDefault(userId, List.of());
        
        // 计算余弦相似度
        return records.stream()
            .map(r -> Map.entry(r, cosineSimilarity(embedding, r.getEmbedding())))
            .sorted(Map.Entry.<MemoryRecord, Double>comparingByValue().reversed())
            .limit(topK)
            .map(Map.Entry::getKey)
            .toList();
    }
    
    @Override
    public int count(String userId) {
        return store.getOrDefault(userId, List.of()).size();
    }
    
    @Override
    public void delete(String userId, String recordId) {
        var records = store.get(userId);
        if (records != null) {
            records.removeIf(r -> r.getId().equals(recordId));
        }
    }
    
    @Override
    public List<MemoryRecord> getAll(String userId) {
        return store.getOrDefault(userId, List.of());
    }
    
    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.size() != b.size()) return 0;
        
        double dotProduct = 0;
        double normA = 0;
        double normB = 0;
        
        for (int i = 0; i < a.size(); i++) {
            dotProduct += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
```

💡 **设计意图**：
- **内存实现**：简单快速，适合测试和开发
- **向量检索**：用余弦相似度计算
- **用户隔离**：每个用户的记忆独立存储

---

## 7.4 MemoryRecord 记忆记录

### 7.4.1 类定义

```java
public class MemoryRecord {
    private String id;                    // 唯一标识
    private String content;               // 记忆内容（文本）
    private List<Double> embedding;       // 向量嵌入
    private long createdAt;               // 创建时间
    private long updatedAt;               // 更新时间
    private Map<String, Object> metadata; // 元数据（可选）
    
    // getters and setters
}
```

💡 **设计意图**：
- **content**：记忆的文本内容（如"用户喜欢 Python"）
- **embedding**：文本的向量表示，用于相似度搜索
- **metadata**：额外信息（如来源、标签等）

---

## 7.5 RAG 系统

### 7.5.1 RAG 工作流程

```
用户输入 query
  │
  ├─ 1. 查询重写（可选）
  │      └─ 用 LLM 重写 query，提高检索质量
  │
  ├─ 2. 生成 query 的向量嵌入
  │      └─ llmProvider.embedding(query)
  │
  ├─ 3. 在 VectorStore 中检索
  │      └─ vectorStore.similaritySearch(request)
  │
  ├─ 4. 过滤低相似度结果
  │      └─ 相似度 < threshold 的过滤掉
  │
  ├─ 5. 重排序（可选）
  │      └─ llmProvider.reranking(request)
  │
  └─ 6. 拼接到 prompt
         └─ 检索结果作为上下文，帮助 LLM 回答
```

💡 **设计意图**：
- **查询重写**：用 LLM 改写 query，提高检索准确率
- **向量检索**：用向量相似度搜索，找到最相关的文档
- **过滤**：去掉相似度太低的结果，避免噪声
- **重排序**：用 LLM 对检索结果重排序，提高质量
- **上下文拼接**：检索结果作为上下文，帮助 LLM 回答

### 7.5.2 RAG 在 Agent 中的位置

```
Agent.chatLoops(query, variables, skipReflection)
  │
  ├─ 1. 拼接 promptTemplate + query
  │
  ├─ 2. 如果启用 RAG：rag() 检索
  │      │
  │      └─ RagPipeline.execute(ragConfig, query, variables, tokenCostConsumer)
  │             │
  │             ├─ 查询重写
  │             ├─ 向量检索
  │             ├─ 过滤 + 重排序
  │             └─ 把结果存入 variables["rag_context"]
  │
  ├─ 3. Mustache 模板渲染
  │      └─ {{rag_context}} 会被替换为检索结果
  │
  └─ 4. chatTurns() 执行 turns
```

💡 **设计意图**：RAG 检索在 chatLoops 中执行，检索结果存入 `variables`，后续模板渲染时替换 `{{rag_context}}`。

---

## 7.6 RagConfig 配置

### 7.6.1 文件位置

```
core-ai/src/main/java/ai/core/rag/RagConfig.java
```

### 7.6.2 类定义

```java
public class RagConfig {
    public static final String AGENT_RAG_CONTEXT_PLACEHOLDER = "__rag_default_context_placeholder__";
    public static final String AGENT_RAG_CONTEXT_TEMPLATE = Strings.format("\nContext:{{{}}}\n\n", AGENT_RAG_CONTEXT_PLACEHOLDER);

    public static Builder builder() {
        return new Builder();
    }

    boolean useRag = false;                          // 是否启用 RAG
    Integer topK = 5;                                // 检索数量
    Double threshold = 0d;                           // 相似度阈值
    VectorStore vectorStore;                         // 向量库
    LLMProvider llmProvider;                         // LLM provider（用于嵌入和重排序）
    boolean enableQueryRewriting = true;             // 是否启用查询重写

    public boolean useRag() {
        return useRag;
    }

    public Integer topK() {
        return topK;
    }

    public Double threshold() {
        return threshold;
    }

    public VectorStore vectorStore() {
        return vectorStore;
    }

    public LLMProvider llmProvider() {
        return llmProvider;
    }

    public boolean enableQueryRewriting() {
        return enableQueryRewriting;
    }

    public static class Builder {
        private boolean useRag = false;
        private Integer topK = 5;
        private Double threshold = 0d;
        private VectorStore vectorStore;
        private LLMProvider llmProvider;
        private boolean enableQueryRewriting = true;

        public Builder useRag(Boolean useRag) {
            this.useRag = useRag;
            return this;
        }

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        public Builder threshold(Double threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder vectorStore(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        public Builder llmProvider(LLMProvider llmProvider) {
            if (llmProvider == null) {
                throw new IllegalArgumentException("LLMProvider cannot be null");
            }
            this.llmProvider = llmProvider;
            return this;
        }

        public Builder enableQueryRewriting(Boolean enableQueryRewriting) {
            this.enableQueryRewriting = enableQueryRewriting;
            return this;
        }

        public RagConfig build() {
            var config = new RagConfig();
            config.useRag = useRag;
            config.topK = topK;
            config.threshold = threshold;
            config.vectorStore = vectorStore;
            config.llmProvider = llmProvider;
            config.enableQueryRewriting = enableQueryRewriting;
            return config;
        }
    }
}
```

### 7.6.3 配置参数详解

| 参数 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `useRag` | boolean | false | 是否启用 RAG |
| `topK` | Integer | 5 | 检索数量（返回 top-K 条） |
| `threshold` | Double | 0.0 | 相似度阈值（低于此值的过滤掉） |
| `vectorStore` | VectorStore | null | 向量库（存储文档） |
| `llmProvider` | LLMProvider | null | LLM provider（用于嵌入和重排序） |
| `enableQueryRewriting` | boolean | true | 是否启用查询重写 |

💡 **设计意图**：
- **topK**：控制检索数量，太多会引入噪声，太少可能漏掉关键信息
- **threshold**：过滤低相似度结果，提高质量
- **enableQueryRewriting**：用 LLM 改写 query，提高检索准确率

---

## 7.7 VectorStore 向量库

### 7.7.1 文件位置

```
core-ai/src/main/java/ai/core/vectorstore/VectorStore.java
```

### 7.7.2 接口定义

```java
public interface VectorStore {
    /**
     * 相似度搜索
     * 根据 query 的向量，搜索相似的文档
     */
    List<Document> similaritySearch(SimilaritySearchRequest request);

    /**
     * 相似度搜索（返回文本）
     */
    default String similaritySearchText(SimilaritySearchRequest request) {
        return similaritySearch(request).stream()
            .map(v -> v.content)
            .distinct()
            .collect(Collectors.joining("\n"));
    }

    /**
     * 获取单个文档
     */
    Optional<Document> get(String text);

    /**
     * 批量获取文档
     */
    List<Document> getAll(List<String> text);

    /**
     * 添加文档
     */
    void add(List<Document> documents);

    /**
     * 删除文档
     */
    void delete(List<String> texts);

    /**
     * 向量库名称
     */
    String name();
}
```

### 7.7.3 实现

core-ai 提供两种 VectorStore 实现：

**HnswLibVectorStore**（轻量，内存）：

```java
public class HnswLibVectorStore implements VectorStore {
    // 基于 HNSWlib 的轻量实现
    // 适合小规模数据（< 100K 文档）
    // 内存存储，重启丢失
}
```

**MilvusVectorStore**（分布式，大规模）：

```java
public class MilvusVectorStore implements VectorStore {
    // 基于 Milvus 的分布式实现
    // 适合大规模数据（> 100K 文档）
    // 持久化存储，支持集群
}
```

💡 **设计意图**：
- **HnswLib**：轻量、快速、内存存储，适合开发和测试
- **Milvus**：分布式、持久化、支持大规模数据，适合生产环境

### 7.7.4 SimilaritySearchRequest 搜索请求

```java
public class SimilaritySearchRequest {
    private List<Double> queryVector;      // query 的向量
    private int topK;                      // 返回数量
    private double threshold;              // 相似度阈值
    private Map<String, Object> filters;   // 过滤条件（可选）
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private List<Double> queryVector;
        private int topK = 5;
        private double threshold = 0.0;
        private Map<String, Object> filters;
        
        public Builder queryVector(List<Double> queryVector) {
            this.queryVector = queryVector;
            return this;
        }
        
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }
        
        public Builder threshold(double threshold) {
            this.threshold = threshold;
            return this;
        }
        
        public Builder filters(Map<String, Object> filters) {
            this.filters = filters;
            return this;
        }
        
        public SimilaritySearchRequest build() {
            var request = new SimilaritySearchRequest();
            request.queryVector = queryVector;
            request.topK = topK;
            request.threshold = threshold;
            request.filters = filters;
            return request;
        }
    }
}
```

---

## 7.8 Document 文档模型

### 7.8.1 类定义

```java
public class Document {
    public String content;                    // 文档内容（文本）
    public List<Double> embedding;            // 向量嵌入
    public Map<String, Object> metadata;      // 元数据（可选）
    
    public Document() {}
    
    public Document(String content) {
        this.content = content;
    }
    
    public Document(String content, List<Double> embedding) {
        this.content = content;
        this.embedding = embedding;
    }
}
```

💡 **设计意图**：
- **content**：文档的文本内容
- **embedding**：文本的向量表示，用于相似度搜索
- **metadata**：额外信息（如来源、标题、标签等）

### 7.8.2 文档分块

对于长文档，需要先分块（chunking），再把每个 chunk 存入 VectorStore：

```java
// 分块器
public class TextSplitter {
    private int chunkSize = 1000;      // 每个 chunk 的字符数
    private int chunkOverlap = 200;    // chunk 之间的重叠字符数
    
    public List<String> split(String text) {
        var chunks = new ArrayList<String>();
        int start = 0;
        
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            start += chunkSize - chunkOverlap;
        }
        
        return chunks;
    }
}

// 使用
var splitter = new TextSplitter();
var chunks = splitter.split(longDocument);

// 生成嵌入并存储
var documents = chunks.stream()
    .map(chunk -> {
        var doc = new Document(chunk);
        doc.embedding = llmProvider.embedding(chunk);
        return doc;
    })
    .toList();

vectorStore.add(documents);
```

💡 **设计意图**：
- **分块**：长文档切成小块，每块独立存储和检索
- **重叠**：chunk 之间有重叠，避免切断关键信息
- **嵌入**：每个 chunk 生成向量，用于相似度搜索

---

## 7.9 RAG 实战配置

### 7.9.1 基础 RAG Agent

```java
// 1. 创建 VectorStore（用 HnswLib）
var vectorStore = new HnswLibVectorStore();

// 2. 添加文档
var documents = List.of(
    new Document("Python 是一种解释型、高级编程语言"),
    new Document("Java 是一种面向对象的编程语言"),
    new Document("core-ai 是一个 Java AI agent 框架")
);

// 生成嵌入
for (var doc : documents) {
    doc.embedding = llmProvider.embedding(new EmbeddingRequest(List.of(doc.content)))
        .embeddings.get(0).vector;
}

vectorStore.add(documents);

// 3. 配置 RAG
var ragConfig = RagConfig.builder()
    .useRag(true)
    .topK(3)
    .threshold(0.5)
    .vectorStore(vectorStore)
    .llmProvider(llmProvider)
    .enableQueryRewriting(true)
    .build();

// 4. 创建 Agent
var agent = Agent.builder()
    .name("rag-agent")
    .systemPrompt("你是一个编程助手，根据提供的上下文回答问题。\n\nContext:{{rag_context}}")
    .llmProvider(llmProvider)
    .ragConfig(ragConfig)
    .build();

// 5. 执行
var output = agent.execute("core-ai 是什么？", null);
System.out.println(output);
// 输出：core-ai 是一个 Java AI agent 框架
```

💡 **设计意图**：
- **VectorStore**：存储文档和嵌入
- **RagConfig**：配置 RAG 参数
- **system prompt**：用 `{{rag_context}}` 占位符，RAG 检索结果会被替换到这里

### 7.9.2 高级 RAG 配置

```java
var ragConfig = RagConfig.builder()
    .useRag(true)
    .topK(10)                    // 检索 10 条
    .threshold(0.7)              // 相似度阈值 0.7
    .vectorStore(milvusStore)    // 用 Milvus（大规模）
    .llmProvider(llmProvider)
    .enableQueryRewriting(true)  // 启用查询重写
    .build();
```

💡 **设计意图**：
- **topK=10**：检索更多结果，提高召回率
- **threshold=0.7**：过滤低相似度结果，提高精确度
- **Milvus**：适合大规模文档（> 100K）
- **enableQueryRewriting**：用 LLM 改写 query，提高检索质量

### 7.9.3 Memory + RAG 结合

```java
// Memory：记住用户偏好
var memory = Memory.builder()
    .memoryStore(memoryStore)
    .llmProvider(llmProvider)
    .build();

// RAG：检索外部知识
var ragConfig = RagConfig.builder()
    .useRag(true)
    .vectorStore(vectorStore)
    .llmProvider(llmProvider)
    .build();

// Agent：同时使用 Memory 和 RAG
var agent = Agent.builder()
    .name("smart-agent")
    .systemPrompt("你是一个智能助手。\n\n{{memory_context}}\n\n{{rag_context}}")
    .llmProvider(llmProvider)
    .memory(memory)
    .ragConfig(ragConfig)
    .build();

var output = agent.execute("我上次说的 Python 项目怎么样了？", null);
// Memory 提供：用户之前提到过一个 Python 项目
// RAG 提供：Python 项目的最新文档
```

💡 **设计意图**：Memory 和 RAG 可以结合使用，Memory 提供个性化信息，RAG 提供外部知识。

---

## 7.10 验证学习成果

完成本章后，你应该能：

### ✅ 必须掌握

- [ ] 说出 Memory 和 RAG 的区别
- [ ] 说出 Memory 的检索流程（嵌入 → 搜索 → 格式化）
- [ ] 说出 RAG 的工作流程（查询重写 → 检索 → 过滤 → 重排序 → 拼接）
- [ ] 说出 RagConfig 的 6 个参数
- [ ] 说出 VectorStore 的两种实现（HnswLib/Milvus）
- [ ] 能配置一个 RAG agent

### 🔧 动手实践

1. **读源码**：

打开以下文件，逐行读：

```
core-ai/src/main/java/ai/core/memory/Memory.java
core-ai/src/main/java/ai/core/rag/RagConfig.java
core-ai/src/main/java/ai/core/vectorstore/VectorStore.java
```

2. **配置 RAG agent**：

用 HnswLibVectorStore 创建一个 RAG agent，添加几个文档，问一个问题。

3. **结合 Memory + RAG**：

创建一个同时使用 Memory 和 RAG 的 agent。

### 📝 自测题

1. Memory 和 RAG 的主要区别是什么？
   - A. Memory 存用户偏好，RAG 存外部文档
   - B. Memory 用向量检索，RAG 不用
   - C. Memory 跨会话，RAG 不跨会话
   
   **答案**：A（Memory 存用户偏好，RAG 存外部文档）

2. RAG 的 `topK` 参数是什么意思？
   - A. 检索数量
   - B. 相似度阈值
   - C. 查询重写次数
   
   **答案**：A（检索数量）

3. VectorStore 的两种实现是什么？
   - A. HnswLib 和 Milvus
   - B. Redis 和 MongoDB
   - C. File 和 Memory
   
   **答案**：A（HnswLib 和 Milvus）

---

## 🎉 本章小结

本章你学会了：

- ✅ Memory 系统的架构（Memory/MemoryStore/MemoryRecord）
- ✅ Memory 的检索流程（嵌入 → 搜索 → 格式化）
- ✅ RAG 系统的工作流程（查询重写 → 检索 → 过滤 → 重排序 → 拼接）
- ✅ RagConfig 的 6 个参数及其作用
- ✅ VectorStore 的两种实现（HnswLib/Milvus）
- ✅ Document 文档模型和分块策略
- ✅ RAG 实战配置（基础 RAG、高级 RAG、Memory+RAG 结合）

---

## 🎊 核心篇完成！

恭喜你完成了 core-ai 内核 SDK 的核心篇！

你已经掌握了：
- ✅ 内核架构总览（13 个核心子系统）
- ✅ 模块引导机制（MultiAgentModule/AgentBootstrap）
- ✅ Agent 核心循环（完整调用链）
- ✅ 生命周期钩子（12 个钩子方法）
- ✅ 工具系统（ToolCall/ToolExecutor/ToolOrchestration/ToolRegistry/ToolProvider）
- ✅ LLM 提供商（LLMProvider/LLMProviders/LiteLLMProvider）
- ✅ Memory 与 RAG（Memory/RagConfig/VectorStore）

---

## 🚀 下一步

接下来是**进阶篇**（Stage 7），包括：
- 08-Flow编排.md（DAG 编排）
- 09-Skill系统.md（技能定义与加载）
- 10-MCP协议集成.md（MCP client & server）
- 11-Session管理.md（会话、turn 调度、权限）
- 12-遥测与可观测.md（OpenTelemetry 集成）
- 13-持久化机制.md（PersistenceProvider）
- 14-提示工程.md（模板、Langfuse）
- 15-终止条件.md（Termination）
- 16-沙箱执行.md（SandboxProvider）
- 17-A2A协议.md（Agent-to-Agent）

这些章节可以按需选读，不必按顺序。

---

*最后更新：2026-08-31*
