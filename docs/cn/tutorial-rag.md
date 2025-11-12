# 教程：RAG（检索增强生成）集成

本教程将介绍如何在 Core-AI 中实现和使用 RAG 来增强代理的知识和能力。

## 目录

1. [RAG 概述](#rag-概述)
2. [向量存储配置](#向量存储配置)
3. [文档处理](#文档处理)
4. [嵌入和检索](#嵌入和检索)
5. [查询优化](#查询优化)
6. [实战案例](#实战案例)

## RAG 概述

### 什么是 RAG？

RAG（Retrieval-Augmented Generation）通过检索相关文档来增强 LLM 的生成能力：

1. **检索**：从知识库中找到相关文档
2. **增强**：将检索到的信息注入提示
3. **生成**：基于增强的上下文生成响应

### Core-AI 的 RAG 架构

```
┌──────────────────────────────────────┐
│           用户查询                    │
└──────────────┬───────────────────────┘
               │
               ▼
┌──────────────────────────────────────┐
│         查询重写/优化                 │
└──────────────┬───────────────────────┘
               │
               ▼
┌──────────────────────────────────────┐
│          向量化嵌入                   │
└──────────────┬───────────────────────┘
               │
               ▼
┌──────────────────────────────────────┐
│       向量存储检索                    │
│   (Milvus/HNSWLib)                   │
└──────────────┬───────────────────────┘
               │
               ▼
┌──────────────────────────────────────┐
│         重排序                        │
└──────────────┬───────────────────────┘
               │
               ▼
┌──────────────────────────────────────┐
│      上下文注入 + LLM生成             │
└──────────────────────────────────────┘
```

## 向量存储配置

### 1. Milvus 向量存储

```java
import ai.core.vectorstore.MilvusVectorStore;
import ai.core.vectorstore.VectorStoreConfig;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;

public class MilvusConfigExample {

    public MilvusVectorStore createMilvusStore() {
        // Milvus 连接配置
        ConnectParam connectParam = ConnectParam.newBuilder()
            .withHost("localhost")
            .withPort(19530)
            .withDatabaseName("default")
            .build();

        MilvusServiceClient client = new MilvusServiceClient(connectParam);

        // 创建向量存储配置
        VectorStoreConfig config = VectorStoreConfig.builder()
            .collectionName("knowledge_base")
            .embeddingDimension(1536)  // OpenAI ada-002 维度
            .metricType("COSINE")       // 相似度度量
            .indexType("IVF_FLAT")      // 索引类型
            .nlist(1024)                // 索引参数
            .build();

        // 创建 Milvus 向量存储
        return new MilvusVectorStore(client, config);
    }

    public void createCollection() {
        MilvusVectorStore store = createMilvusStore();

        // 创建集合架构
        store.createCollection(
            "documents",
            List.of(
                Field.builder()
                    .name("doc_id")
                    .dataType(DataType.VarChar)
                    .maxLength(65535)
                    .isPrimaryKey(true)
                    .build(),

                Field.builder()
                    .name("content")
                    .dataType(DataType.VarChar)
                    .maxLength(65535)
                    .build(),

                Field.builder()
                    .name("embedding")
                    .dataType(DataType.FloatVector)
                    .dimension(1536)
                    .build(),

                Field.builder()
                    .name("metadata")
                    .dataType(DataType.JSON)
                    .build()
            )
        );

        // 创建索引
        store.createIndex("embedding", IndexType.IVF_FLAT, Map.of("nlist", 1024));
    }
}
```

### 2. HNSWLib 向量存储（轻量级）

```java
import ai.core.vectorstore.HNSWLibVectorStore;
import com.github.jelmerk.knn.hnswlib.HnswIndex;
import com.github.jelmerk.knn.hnswlib.DistanceFunctions;

public class HNSWLibConfigExample {

    public HNSWLibVectorStore createHNSWStore() {
        // HNSWLib 配置
        HnswIndex<String, float[], Item<float[], String>, Float> index =
            HnswIndex.newBuilder(
                DistanceFunctions.FLOAT_COSINE_DISTANCE,
                1536  // 维度
            )
            .withM(16)                // 连接数
            .withEf(200)              // 搜索参数
            .withEfConstruction(200)  // 构建参数
            .withMaxItemCount(1000000) // 最大项数
            .build();

        return new HNSWLibVectorStore(index);
    }

    // 持久化支持
    public void saveAndLoadIndex() throws IOException {
        HNSWLibVectorStore store = createHNSWStore();

        // 添加一些向量...
        store.add(vectors);

        // 保存索引到文件
        store.saveIndex(Paths.get("index.hnsw"));

        // 加载索引
        HNSWLibVectorStore loadedStore = HNSWLibVectorStore.loadIndex(
            Paths.get("index.hnsw")
        );
    }
}
```

## 文档处理

### 1. 文档加载和分割

```java
import ai.core.document.Document;
import ai.core.document.DocumentLoader;
import ai.core.document.TextSplitter;
import ai.core.document.RecursiveCharacterTextSplitter;

public class DocumentProcessingExample {

    public List<Document> processDocuments(String directoryPath) {
        // 1. 加载文档
        DocumentLoader loader = new DocumentLoader();

        List<Document> documents = loader
            .loadDirectory(directoryPath)
            .withExtensions(".txt", ".pdf", ".md", ".html")
            .withRecursive(true)
            .load();

        // 2. 分割文档
        TextSplitter splitter = new RecursiveCharacterTextSplitter()
            .withChunkSize(1000)        // 块大小
            .withChunkOverlap(200)      // 重叠
            .withSeparators(List.of(
                "\n\n",  // 段落
                "\n",    // 行
                ". ",    // 句子
                " ",     // 单词
                ""       // 字符
            ));

        List<Document> chunks = new ArrayList<>();
        for (Document doc : documents) {
            List<String> textChunks = splitter.splitText(doc.getContent());

            for (int i = 0; i < textChunks.size(); i++) {
                Document chunk = Document.builder()
                    .id(doc.getId() + "_chunk_" + i)
                    .content(textChunks.get(i))
                    .metadata(Map.of(
                        "source", doc.getSource(),
                        "chunk_index", i,
                        "total_chunks", textChunks.size(),
                        "original_doc_id", doc.getId()
                    ))
                    .build();

                chunks.add(chunk);
            }
        }

        return chunks;
    }
}
```

### 2. 智能文档分割

```java
import ai.core.document.SemanticTextSplitter;

public class SemanticSplittingExample {

    public List<Document> semanticSplit(String content, LLMProvider llmProvider) {
        // 语义分割：基于内容含义分割
        SemanticTextSplitter splitter = new SemanticTextSplitter()
            .withEmbeddingModel(llmProvider.getEmbeddingModel())
            .withMaxChunkSize(1000)
            .withSimilarityThreshold(0.8)  // 语义相似度阈值
            .withBreakpointThreshold(0.4); // 断点阈值

        return splitter.split(content);
    }

    // 基于结构的分割
    public List<Document> structuredSplit(String markdownContent) {
        MarkdownTextSplitter splitter = new MarkdownTextSplitter()
            .withHeadersToSplitOn(List.of(
                new Header("##", "section"),
                new Header("###", "subsection")
            ))
            .withReturnEachLine(false)
            .withStripHeaders(false);

        return splitter.split(markdownContent);
    }
}
```

## 嵌入和检索

### 1. 生成嵌入向量

```java
import ai.core.llm.EmbeddingModel;
import ai.core.llm.providers.AzureOpenAIEmbeddingModel;

public class EmbeddingExample {

    public void generateEmbeddings(List<Document> documents) {
        // 创建嵌入模型
        EmbeddingModel embeddingModel = new AzureOpenAIEmbeddingModel(
            AzureOpenAIConfig.builder()
                .endpoint(endpoint)
                .apiKey(apiKey)
                .deploymentName("text-embedding-ada-002")
                .build()
        );

        // 批量生成嵌入
        int batchSize = 100;
        for (int i = 0; i < documents.size(); i += batchSize) {
            List<Document> batch = documents.subList(
                i,
                Math.min(i + batchSize, documents.size())
            );

            List<String> texts = batch.stream()
                .map(Document::getContent)
                .collect(Collectors.toList());

            // 生成嵌入向量
            List<float[]> embeddings = embeddingModel.embed(texts);

            // 关联嵌入到文档
            for (int j = 0; j < batch.size(); j++) {
                batch.get(j).setEmbedding(embeddings.get(j));
            }
        }
    }

    // 使用缓存优化嵌入生成
    public class CachedEmbeddingModel {
        private final EmbeddingModel model;
        private final Cache<String, float[]> cache;

        public CachedEmbeddingModel(EmbeddingModel model) {
            this.model = model;
            this.cache = CacheBuilder.newBuilder()
                .maximumSize(10000)
                .expireAfterAccess(1, TimeUnit.HOURS)
                .build();
        }

        public float[] embed(String text) {
            try {
                return cache.get(text, () -> model.embed(text));
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
```

### 2. 相似度检索

```java
import ai.core.rag.SimilaritySearch;
import ai.core.rag.SearchResult;

public class RetrievalExample {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    public List<SearchResult> retrieve(String query, int topK) {
        // 1. 查询向量化
        float[] queryEmbedding = embeddingModel.embed(query);

        // 2. 相似度搜索
        List<SearchResult> results = vectorStore.search(
            queryEmbedding,
            topK,
            Map.of(
                "min_score", 0.7,  // 最小相似度
                "filter", Map.of(   // 元数据过滤
                    "type", "technical",
                    "date_after", "2023-01-01"
                )
            )
        );

        return results;
    }

    // 混合检索：结合向量和关键词搜索
    public List<SearchResult> hybridRetrieve(String query) {
        // 向量搜索
        List<SearchResult> vectorResults = vectorStore.search(
            embeddingModel.embed(query),
            20
        );

        // 关键词搜索（BM25）
        List<SearchResult> keywordResults = keywordSearch(query, 20);

        // 结果融合（Reciprocal Rank Fusion）
        return fuseResults(vectorResults, keywordResults);
    }

    private List<SearchResult> fuseResults(
            List<SearchResult> vectorResults,
            List<SearchResult> keywordResults) {

        Map<String, Double> scores = new HashMap<>();
        int k = 60;  // RRF 参数

        // 计算向量搜索得分
        for (int i = 0; i < vectorResults.size(); i++) {
            SearchResult result = vectorResults.get(i);
            double score = 1.0 / (k + i + 1);
            scores.put(result.getId(), score);
        }

        // 累加关键词搜索得分
        for (int i = 0; i < keywordResults.size(); i++) {
            SearchResult result = keywordResults.get(i);
            double score = 1.0 / (k + i + 1);
            scores.merge(result.getId(), score, Double::sum);
        }

        // 排序并返回
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(10)
            .map(entry -> getResultById(entry.getKey()))
            .collect(Collectors.toList());
    }
}
```

### 3. 重排序

```java
import ai.core.rag.Reranker;
import ai.core.llm.providers.CohereReranker;

public class RerankingExample {

    public List<SearchResult> rerankResults(
            String query,
            List<SearchResult> initialResults) {

        // 使用 Cohere 重排序模型
        Reranker reranker = new CohereReranker(
            CohereConfig.builder()
                .apiKey(apiKey)
                .model("rerank-english-v2.0")
                .build()
        );

        // 重排序
        List<RerankResult> reranked = reranker.rerank(
            query,
            initialResults.stream()
                .map(SearchResult::getContent)
                .collect(Collectors.toList())
        );

        // 按新分数排序
        return reranked.stream()
            .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
            .map(r -> initialResults.get(r.getIndex()))
            .collect(Collectors.toList());
    }

    // 自定义 LLM 重排序
    public List<SearchResult> llmRerank(
            String query,
            List<SearchResult> results,
            LLMProvider llmProvider) {

        String prompt = """
            查询：{{query}}

            文档列表：
            {{#documents}}
            文档{{index}}：
            {{content}}
            ---
            {{/documents}}

            请根据与查询的相关性对文档重新排序。
            返回格式：[最相关文档索引, 次相关文档索引, ...]
            """;

        Map<String, Object> data = Map.of(
            "query", query,
            "documents", results
        );

        String response = llmProvider.complete(
            PromptTemplate.render(prompt, data)
        );

        // 解析排序结果
        List<Integer> order = parseRankingOrder(response);

        return order.stream()
            .map(results::get)
            .collect(Collectors.toList());
    }
}
```

## 查询优化

### 1. 查询重写

```java
import ai.core.rag.QueryRewriter;

public class QueryOptimizationExample {

    public class SmartQueryRewriter implements QueryRewriter {
        private final LLMProvider llmProvider;

        @Override
        public List<String> rewrite(String originalQuery) {
            String prompt = """
                原始查询：{{query}}

                请生成3个改进的搜索查询：
                1. 更具体的版本
                2. 更通用的版本
                3. 相关概念的版本

                每个查询一行。
                """;

            String response = llmProvider.complete(
                PromptTemplate.render(prompt, Map.of("query", originalQuery))
            );

            return Arrays.asList(response.split("\n"));
        }
    }

    // HyDE（假设文档嵌入）
    public String hypotheticalDocumentEmbedding(String query) {
        String prompt = """
            问题：{{query}}

            请写一个理想的段落来回答这个问题。
            这个段落应该包含所有相关信息。
            """;

        return llmProvider.complete(
            PromptTemplate.render(prompt, Map.of("query", query))
        );
    }

    // 查询扩展
    public List<String> expandQuery(String query) {
        // 同义词扩展
        List<String> synonyms = getSynonyms(query);

        // 上下位词扩展
        List<String> hypernyms = getHypernyms(query);
        List<String> hyponyms = getHyponyms(query);

        // 组合扩展查询
        List<String> expanded = new ArrayList<>();
        expanded.add(query);
        expanded.addAll(synonyms);
        expanded.addAll(hypernyms);
        expanded.addAll(hyponyms);

        return expanded;
    }
}
```

### 2. 自适应检索

```java
public class AdaptiveRetrievalExample {

    public class AdaptiveRAG {
        private final VectorStore vectorStore;
        private final LLMProvider llmProvider;

        public List<SearchResult> adaptiveRetrieve(String query) {
            // 第一轮检索
            List<SearchResult> initialResults = vectorStore.search(query, 5);

            // 评估结果质量
            double quality = evaluateResultQuality(initialResults, query);

            if (quality < 0.7) {
                // 质量不足，尝试查询重写
                List<String> rewrittenQueries = rewriteQuery(query);
                List<SearchResult> additionalResults = new ArrayList<>();

                for (String rewritten : rewrittenQueries) {
                    additionalResults.addAll(
                        vectorStore.search(rewritten, 3)
                    );
                }

                initialResults.addAll(additionalResults);
            }

            // 动态调整检索数量
            if (isComplexQuery(query)) {
                // 复杂查询需要更多上下文
                initialResults.addAll(
                    vectorStore.search(query, 10, Map.of("offset", 5))
                );
            }

            // 去重和重排序
            return deduplicateAndRerank(initialResults);
        }

        private double evaluateResultQuality(
                List<SearchResult> results,
                String query) {

            String prompt = """
                查询：{{query}}
                检索结果：{{results}}

                评估这些结果与查询的相关性（0-1分）：
                """;

            String score = llmProvider.complete(
                PromptTemplate.render(prompt, Map.of(
                    "query", query,
                    "results", results
                ))
            );

            return Double.parseDouble(score);
        }
    }
}
```

## RAG 配置和集成

### 1. 代理 RAG 配置

```java
import ai.core.rag.RAGConfig;
import ai.core.agent.Agent;

public class AgentRAGIntegrationExample {

    public Agent createRAGEnabledAgent(
            LLMProvider llmProvider,
            VectorStore vectorStore) {

        // 配置 RAG
        RAGConfig ragConfig = RAGConfig.builder()
            // 向量存储
            .vectorStore(vectorStore)

            // 嵌入模型
            .embeddingModel("text-embedding-ada-002")

            // 检索参数
            .topK(5)                      // 检索数量
            .similarityThreshold(0.75)    // 相似度阈值
            .maxTokensPerChunk(500)       // 每块最大 token

            // 查询处理
            .enableQueryRewriting(true)   // 启用查询重写
            .queryRewriter(new SmartQueryRewriter())

            // 重排序
            .enableReranking(true)        // 启用重排序
            .reranker(new CohereReranker())

            // 上下文注入
            .contextTemplate("""
                相关信息：
                {{#documents}}
                ---
                来源：{{metadata.source}}
                内容：{{content}}
                ---
                {{/documents}}

                基于以上信息回答问题。
                """)

            .build();

        // 创建启用 RAG 的代理
        return Agent.builder()
            .name("rag-agent")
            .description("具有知识检索能力的代理")
            .llmProvider(llmProvider)
            .systemPrompt("""
                你是一个知识助手。
                使用检索到的信息准确回答问题。
                如果信息不足，诚实说明。
                """)
            .enableRAG(true)
            .ragConfig(ragConfig)
            .build();
    }
}
```

### 2. 动态知识更新

```java
public class DynamicKnowledgeUpdateExample {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    // 实时知识更新
    public void updateKnowledge(Document newDocument) {
        // 1. 生成嵌入
        float[] embedding = embeddingModel.embed(newDocument.getContent());
        newDocument.setEmbedding(embedding);

        // 2. 检查重复
        List<SearchResult> similar = vectorStore.search(embedding, 1);
        if (!similar.isEmpty() && similar.get(0).getScore() > 0.95) {
            // 更新现有文档
            vectorStore.update(similar.get(0).getId(), newDocument);
        } else {
            // 添加新文档
            vectorStore.add(newDocument);
        }

        // 3. 更新索引
        vectorStore.refreshIndex();
    }

    // 批量更新优化
    public void batchUpdate(List<Document> documents) {
        // 使用事务批量更新
        vectorStore.beginTransaction();
        try {
            for (Document doc : documents) {
                updateKnowledge(doc);
            }
            vectorStore.commit();
        } catch (Exception e) {
            vectorStore.rollback();
            throw e;
        }
    }

    // 知识版本控制
    public void versionedUpdate(Document document) {
        // 保存历史版本
        Document currentVersion = vectorStore.get(document.getId());
        if (currentVersion != null) {
            currentVersion.getMetadata().put("version", "archived");
            currentVersion.getMetadata().put("archived_at", Instant.now());
            vectorStore.add(currentVersion);  // 保存为新记录
        }

        // 添加新版本
        document.getMetadata().put("version", "current");
        document.getMetadata().put("updated_at", Instant.now());
        vectorStore.update(document.getId(), document);
    }
}
```

## 实战案例

### 案例1：技术文档助手

```java
public class TechnicalDocumentationAssistant {

    private final Agent ragAgent;
    private final VectorStore vectorStore;
    private final DocumentProcessor processor;

    public void initialize() {
        // 1. 加载技术文档
        List<Document> docs = processor.loadDocuments(
            List.of(
                "docs/api-reference/",
                "docs/tutorials/",
                "docs/guides/"
            )
        );

        // 2. 处理和索引文档
        for (Document doc : docs) {
            // 智能分割
            List<Document> chunks = processor.smartSplit(doc);

            // 添加元数据
            for (Document chunk : chunks) {
                chunk.addMetadata("doc_type", detectDocType(chunk));
                chunk.addMetadata("tech_stack", extractTechStack(chunk));
                chunk.addMetadata("difficulty", assessDifficulty(chunk));
            }

            // 生成嵌入并存储
            vectorStore.addBatch(chunks);
        }

        // 3. 创建专门的技术助手
        ragAgent = Agent.builder()
            .name("tech-doc-assistant")
            .systemPrompt("""
                你是技术文档助手。
                职责：
                1. 回答技术问题
                2. 提供代码示例
                3. 解释概念
                4. 指向相关文档

                始终引用来源。
                """)
            .enableRAG(true)
            .ragConfig(createTechRAGConfig())
            .tools(List.of(
                new CodeFormatterTool(),
                new DiagramGeneratorTool()
            ))
            .build();
    }

    public String answerQuestion(String question) {
        // 增强查询
        String enrichedQuery = enrichTechnicalQuery(question);

        // 执行 RAG 查询
        AgentOutput output = ragAgent.execute(enrichedQuery);

        // 后处理
        return formatTechnicalResponse(output.getOutput());
    }

    private String enrichTechnicalQuery(String query) {
        // 检测编程语言
        String language = detectProgrammingLanguage(query);

        // 检测技术领域
        String domain = detectTechnicalDomain(query);

        // 增强查询
        return String.format(
            "%s [语言: %s] [领域: %s]",
            query, language, domain
        );
    }
}
```

### 案例2：客户支持知识库

```java
public class CustomerSupportKnowledgeBase {

    public class SupportRAGSystem {
        private final VectorStore ticketStore;
        private final VectorStore faqStore;
        private final VectorStore productDocsStore;

        public Agent createSupportAgent() {
            // 多源 RAG 配置
            MultiSourceRAGConfig config = MultiSourceRAGConfig.builder()
                .addSource("tickets", ticketStore, 0.3)      // 历史工单
                .addSource("faq", faqStore, 0.4)             // 常见问题
                .addSource("docs", productDocsStore, 0.3)    // 产品文档
                .fusionStrategy(FusionStrategy.WEIGHTED)
                .build();

            return Agent.builder()
                .name("support-agent")
                .systemPrompt("""
                    你是客户支持专家。
                    使用知识库回答客户问题。

                    优先级：
                    1. 相似问题的历史解决方案
                    2. FAQ 中的标准答案
                    3. 产品文档中的详细说明

                    保持专业、友好、准确。
                    """)
                .enableRAG(true)
                .multiSourceRAGConfig(config)
                .build();
        }

        public void indexSupportTicket(SupportTicket ticket) {
            // 处理工单
            Document doc = Document.builder()
                .id("ticket_" + ticket.getId())
                .content(formatTicket(ticket))
                .metadata(Map.of(
                    "status", ticket.getStatus(),
                    "category", ticket.getCategory(),
                    "priority", ticket.getPriority(),
                    "resolution", ticket.getResolution(),
                    "created_at", ticket.getCreatedAt(),
                    "resolved_at", ticket.getResolvedAt()
                ))
                .build();

            // 生成嵌入
            doc.setEmbedding(embeddingModel.embed(doc.getContent()));

            // 存储
            ticketStore.add(doc);

            // 如果是成功解决的工单，提取为 FAQ
            if ("resolved".equals(ticket.getStatus()) &&
                ticket.getSatisfactionScore() >= 4) {
                extractToFAQ(ticket);
            }
        }

        private void extractToFAQ(SupportTicket ticket) {
            String faqEntry = generateFAQEntry(ticket);

            Document faq = Document.builder()
                .id("faq_auto_" + ticket.getId())
                .content(faqEntry)
                .metadata(Map.of(
                    "source", "auto_extracted",
                    "original_ticket", ticket.getId(),
                    "confidence", calculateConfidence(ticket)
                ))
                .build();

            faqStore.add(faq);
        }
    }
}
```

### 案例3：研究论文助手

```java
public class ResearchPaperAssistant {

    public class AcademicRAGSystem {
        private final VectorStore paperStore;
        private final CitationGraph citationGraph;
        private final Agent researchAgent;

        public void indexPaper(ResearchPaper paper) {
            // 1. 分节处理
            Map<String, List<Document>> sections = new HashMap<>();

            sections.put("abstract", processAbstract(paper.getAbstract()));
            sections.put("introduction", processSection(paper.getIntroduction()));
            sections.put("methodology", processSection(paper.getMethodology()));
            sections.put("results", processSection(paper.getResults()));
            sections.put("conclusion", processSection(paper.getConclusion()));

            // 2. 提取关键信息
            Map<String, Object> metadata = Map.of(
                "title", paper.getTitle(),
                "authors", paper.getAuthors(),
                "year", paper.getYear(),
                "venue", paper.getVenue(),
                "citations", paper.getCitations(),
                "keywords", extractKeywords(paper),
                "contributions", extractContributions(paper)
            );

            // 3. 构建引用图
            for (String citation : paper.getCitations()) {
                citationGraph.addEdge(paper.getId(), citation);
            }

            // 4. 存储所有部分
            for (Map.Entry<String, List<Document>> entry : sections.entrySet()) {
                for (Document doc : entry.getValue()) {
                    doc.getMetadata().putAll(metadata);
                    doc.getMetadata().put("section", entry.getKey());
                    paperStore.add(doc);
                }
            }
        }

        public List<SearchResult> semanticScholarSearch(String query) {
            // 1. 基础语义搜索
            List<SearchResult> semanticResults = paperStore.search(
                embeddingModel.embed(query),
                20
            );

            // 2. 引用增强
            Set<String> relevantPapers = new HashSet<>();
            for (SearchResult result : semanticResults) {
                String paperId = (String) result.getMetadata().get("paper_id");

                // 添加高引用的引用文献
                List<String> citations = citationGraph.getCitations(paperId);
                relevantPapers.addAll(
                    citations.stream()
                        .filter(c -> citationGraph.getCitationCount(c) > 50)
                        .collect(Collectors.toList())
                );

                // 添加被引用的文献
                List<String> citedBy = citationGraph.getCitedBy(paperId);
                relevantPapers.addAll(citedBy.subList(0, Math.min(5, citedBy.size())));
            }

            // 3. 扩展搜索
            List<SearchResult> expandedResults = new ArrayList<>(semanticResults);
            for (String paperId : relevantPapers) {
                expandedResults.addAll(paperStore.getByPaperId(paperId));
            }

            // 4. 学术重排序
            return academicRerank(query, expandedResults);
        }

        private List<SearchResult> academicRerank(
                String query,
                List<SearchResult> results) {

            // 计算综合分数
            for (SearchResult result : results) {
                double score = 0;

                // 语义相关性
                score += result.getScore() * 0.4;

                // 引用影响力
                int citations = (int) result.getMetadata().get("citation_count");
                score += Math.log(citations + 1) / 10 * 0.3;

                // 时效性
                int year = (int) result.getMetadata().get("year");
                int yearsSince = LocalDate.now().getYear() - year;
                score += Math.max(0, 1 - yearsSince / 20.0) * 0.2;

                // 场地声望
                String venue = (String) result.getMetadata().get("venue");
                score += getVenueImpactFactor(venue) * 0.1;

                result.setCompositeScore(score);
            }

            return results.stream()
                .sorted((a, b) -> Double.compare(
                    b.getCompositeScore(),
                    a.getCompositeScore()
                ))
                .collect(Collectors.toList());
        }
    }
}
```

## 性能优化

### 1. 索引优化

```java
public class IndexOptimizationExample {

    public void optimizeVectorIndex() {
        MilvusVectorStore store = createMilvusStore();

        // 1. 选择合适的索引类型
        store.createIndex("embedding", IndexType.HNSW, Map.of(
            "M", 16,           // 连接数
            "efConstruction", 200  // 构建质量
        ));

        // 2. 分区策略
        store.createPartition("recent", "date >= '2024-01-01'");
        store.createPartition("archive", "date < '2024-01-01'");

        // 3. 预加载热数据
        store.loadPartition("recent");

        // 4. 定期优化
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            store.compact();
            store.rebuildIndex();
        }, 0, 24, TimeUnit.HOURS);
    }
}
```

### 2. 查询优化

```java
public class QueryOptimizationExample {

    public class OptimizedRAGQuery {
        private final Cache<String, List<SearchResult>> queryCache;
        private final BatchProcessor batchProcessor;

        // 查询缓存
        public List<SearchResult> cachedSearch(String query) {
            String cacheKey = generateCacheKey(query);

            return queryCache.get(cacheKey, () -> {
                return performSearch(query);
            });
        }

        // 批量查询
        public Map<String, List<SearchResult>> batchSearch(
                List<String> queries) {

            return batchProcessor.process(queries, batch -> {
                // 批量生成嵌入
                List<float[]> embeddings = embeddingModel.embedBatch(batch);

                // 并行搜索
                return IntStream.range(0, batch.size())
                    .parallel()
                    .boxed()
                    .collect(Collectors.toMap(
                        i -> batch.get(i),
                        i -> vectorStore.search(embeddings.get(i), 5)
                    ));
            });
        }

        // 预取策略
        public void prefetchRelated(String currentQuery) {
            CompletableFuture.runAsync(() -> {
                List<String> relatedQueries = generateRelatedQueries(currentQuery);
                for (String query : relatedQueries) {
                    cachedSearch(query);  // 预加载到缓存
                }
            });
        }
    }
}
```

## 监控和调试

### 1. RAG 性能监控

```java
public class RAGMonitoringExample {

    @Component
    public class RAGMetricsCollector {
        private final MeterRegistry metrics;

        public void recordRetrieval(String query, List<SearchResult> results, long duration) {
            // 记录检索延迟
            metrics.timer("rag.retrieval.duration").record(duration, TimeUnit.MILLISECONDS);

            // 记录结果数量
            metrics.gauge("rag.retrieval.results", results.size());

            // 记录平均相似度分数
            double avgScore = results.stream()
                .mapToDouble(SearchResult::getScore)
                .average()
                .orElse(0);
            metrics.gauge("rag.retrieval.avg_score", avgScore);

            // 记录查询复杂度
            metrics.counter("rag.query.complexity",
                "type", classifyQueryComplexity(query)
            ).increment();
        }

        public void recordRAGQuality(String query, String response, String feedback) {
            // 记录响应质量
            metrics.counter("rag.response.quality",
                "rating", feedback
            ).increment();

            // 追踪失败案例
            if ("poor".equals(feedback)) {
                logFailureCase(query, response);
            }
        }
    }
}
```

## 最佳实践

1. **文档处理**
   - 使用适当的分块策略（语义 vs 固定大小）
   - 保留文档结构和元数据
   - 处理多种文档格式

2. **嵌入优化**
   - 选择合适的嵌入模型
   - 实施嵌入缓存
   - 批量处理提高效率

3. **检索策略**
   - 结合多种检索方法（混合检索）
   - 实施查询优化和重写
   - 使用重排序提高精度

4. **质量保证**
   - 监控检索质量指标
   - 定期评估和优化
   - 收集用户反馈改进

5. **扩展性**
   - 使用分区和索引优化
   - 实施缓存策略
   - 考虑分布式部署

## 总结

通过本教程，您学习了：

1. ✅ RAG 的核心概念和架构
2. ✅ 如何配置和使用向量存储
3. ✅ 文档处理和嵌入生成
4. ✅ 检索和重排序策略
5. ✅ 查询优化技术
6. ✅ 实际应用案例

下一步，您可以：
- 学习[工具调用](tutorial-tool-calling.md)扩展代理能力
- 探索[流程编排](tutorial-flow.md)构建复杂工作流
- 阅读[性能优化指南](performance-guide.md)提升系统性能