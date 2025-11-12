# 教程：工具调用（Tool Calling）

本教程将介绍如何在 Core-AI 中实现和使用工具调用，扩展代理的能力。

## 目录

1. [工具调用概述](#工具调用概述)
2. [创建自定义工具](#创建自定义工具)
3. [JSON Schema 定义](#json-schema-定义)
4. [MCP 协议集成](#mcp-协议集成)
5. [内置工具](#内置工具)
6. [工具组合和编排](#工具组合和编排)
7. [实战案例](#实战案例)

## 工具调用概述

### 什么是工具调用？

工具调用允许 AI 代理：
- 执行特定功能（如查询数据库、调用 API）
- 与外部系统交互
- 执行计算和数据处理
- 访问实时信息

### Core-AI 工具架构

```
┌─────────────────────────────────────┐
│            Agent                    │
│  ┌──────────────────────────────┐  │
│  │     LLM (决策引擎)            │  │
│  └──────────┬───────────────────┘  │
│             │                       │
│     ┌───────▼────────┐             │
│     │  Tool Selector │             │
│     └───────┬────────┘             │
│             │                       │
│  ┌──────────▼───────────────────┐  │
│  │       Tool Executor          │  │
│  └──────────────────────────────┘  │
└─────────────────────────────────────┘
                 │
    ┌────────────┼────────────┐
    ▼            ▼            ▼
┌────────┐  ┌────────┐  ┌────────┐
│ Tool A │  │ Tool B │  │ Tool C │
└────────┘  └────────┘  └────────┘
```

## 创建自定义工具

### 1. 基础工具实现

```java
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.ToolParameter;
import java.util.Map;

public class BasicToolExample {

    // 简单工具：天气查询
    public static class WeatherTool extends ToolCall {
        @Override
        public String getName() {
            return "get_weather";
        }

        @Override
        public String getDescription() {
            return "获取指定城市的天气信息";
        }

        @Override
        public List<ToolParameter> getParameters() {
            return List.of(
                ToolParameter.builder()
                    .name("city")
                    .type("string")
                    .description("城市名称")
                    .required(true)
                    .build(),

                ToolParameter.builder()
                    .name("unit")
                    .type("string")
                    .description("温度单位")
                    .enum_(List.of("celsius", "fahrenheit"))
                    .required(false)
                    .defaultValue("celsius")
                    .build()
            );
        }

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            String city = (String) arguments.get("city");
            String unit = (String) arguments.getOrDefault("unit", "celsius");

            try {
                // 调用天气 API
                WeatherData weather = fetchWeather(city, unit);

                return ToolCallResult.success(
                    String.format("%s 的天气：温度 %d°%s，%s",
                        city,
                        weather.getTemperature(),
                        unit.equals("celsius") ? "C" : "F",
                        weather.getDescription()
                    )
                );
            } catch (Exception e) {
                return ToolCallResult.error(
                    "获取天气失败：" + e.getMessage()
                );
            }
        }
    }
}
```

### 2. 复杂工具实现

```java
public class ComplexToolExample {

    // 数据库查询工具
    public static class DatabaseQueryTool extends ToolCall {
        private final DataSource dataSource;

        public DatabaseQueryTool(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public String getName() {
            return "query_database";
        }

        @Override
        public String getDescription() {
            return "执行 SQL 查询并返回结果";
        }

        @Override
        public List<ToolParameter> getParameters() {
            return List.of(
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("SQL 查询语句（只允许 SELECT）")
                    .required(true)
                    .validation("^SELECT.*", "只允许 SELECT 查询")
                    .build(),

                ToolParameter.builder()
                    .name("limit")
                    .type("integer")
                    .description("返回结果数量限制")
                    .minimum(1)
                    .maximum(1000)
                    .defaultValue(100)
                    .build()
            );
        }

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            String query = (String) arguments.get("query");
            int limit = (int) arguments.getOrDefault("limit", 100);

            // 安全检查
            if (!isSelectQuery(query)) {
                return ToolCallResult.error("只允许 SELECT 查询");
            }

            // 添加限制
            query = addLimitToQuery(query, limit);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query);
                 ResultSet rs = stmt.executeQuery()) {

                // 转换结果为 JSON
                List<Map<String, Object>> results = resultSetToList(rs);

                return ToolCallResult.success(
                    Map.of(
                        "rowCount", results.size(),
                        "data", results
                    )
                );

            } catch (SQLException e) {
                return ToolCallResult.error("查询失败：" + e.getMessage());
            }
        }

        private boolean isSelectQuery(String query) {
            String normalized = query.trim().toUpperCase();
            return normalized.startsWith("SELECT") &&
                   !normalized.contains("INSERT") &&
                   !normalized.contains("UPDATE") &&
                   !normalized.contains("DELETE") &&
                   !normalized.contains("DROP");
        }
    }
}
```

### 3. 异步工具

```java
import java.util.concurrent.CompletableFuture;

public class AsyncToolExample {

    public static class AsyncEmailTool extends ToolCall {
        private final EmailService emailService;

        @Override
        public String getName() {
            return "send_email_async";
        }

        @Override
        public CompletableFuture<ToolCallResult> callAsync(
                Map<String, Object> arguments) {

            String to = (String) arguments.get("to");
            String subject = (String) arguments.get("subject");
            String body = (String) arguments.get("body");

            return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        String messageId = emailService.send(to, subject, body);
                        return ToolCallResult.success(
                            Map.of("messageId", messageId, "status", "sent")
                        );
                    } catch (Exception e) {
                        return ToolCallResult.error(e.getMessage());
                    }
                })
                .orTimeout(30, TimeUnit.SECONDS)
                .exceptionally(throwable ->
                    ToolCallResult.error("发送超时或失败")
                );
        }
    }
}
```

## JSON Schema 定义

### 1. 自动 Schema 生成

```java
import ai.core.utils.JsonSchemaGenerator;
import ai.core.tool.ToolDefinition;

public class JsonSchemaExample {

    // 使用注解定义工具参数
    @ToolDefinition(
        name = "calculate_loan",
        description = "计算贷款月供"
    )
    public static class LoanCalculator {

        @ToolParam(description = "贷款本金", required = true)
        @Min(1000)
        @Max(10000000)
        private double principal;

        @ToolParam(description = "年利率（百分比）", required = true)
        @DecimalMin("0.1")
        @DecimalMax("20.0")
        private double annualRate;

        @ToolParam(description = "贷款期限（月）", required = true)
        @Min(1)
        @Max(360)
        private int months;

        public double calculate() {
            double monthlyRate = annualRate / 100 / 12;
            return principal * monthlyRate * Math.pow(1 + monthlyRate, months) /
                   (Math.pow(1 + monthlyRate, months) - 1);
        }
    }

    // 自动生成 JSON Schema
    public String generateSchema() {
        JsonSchemaGenerator generator = new JsonSchemaGenerator();
        return generator.generate(LoanCalculator.class);
    }

    // 生成的 Schema 示例
    /*
    {
        "type": "object",
        "properties": {
            "principal": {
                "type": "number",
                "description": "贷款本金",
                "minimum": 1000,
                "maximum": 10000000
            },
            "annualRate": {
                "type": "number",
                "description": "年利率（百分比）",
                "minimum": 0.1,
                "maximum": 20.0
            },
            "months": {
                "type": "integer",
                "description": "贷款期限（月）",
                "minimum": 1,
                "maximum": 360
            }
        },
        "required": ["principal", "annualRate", "months"]
    }
    */
}
```

### 2. 复杂 Schema 定义

```java
public class ComplexSchemaExample {

    public static class DataAnalysisTool extends ToolCall {

        @Override
        public JsonSchema getParameterSchema() {
            return JsonSchema.builder()
                .type("object")
                .properties(Map.of(
                    "dataset", JsonSchema.builder()
                        .type("object")
                        .properties(Map.of(
                            "source", JsonSchema.builder()
                                .type("string")
                                .enum_(List.of("database", "file", "api"))
                                .build(),

                            "config", JsonSchema.builder()
                                .oneOf(List.of(
                                    // 数据库配置
                                    JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of(
                                            "table", JsonSchema.string(),
                                            "columns", JsonSchema.array(JsonSchema.string())
                                        ))
                                        .when("source", "database")
                                        .build(),

                                    // 文件配置
                                    JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of(
                                            "path", JsonSchema.string(),
                                            "format", JsonSchema.enum_("csv", "json", "parquet")
                                        ))
                                        .when("source", "file")
                                        .build()
                                ))
                                .build()
                        ))
                        .required(List.of("source", "config"))
                        .build(),

                    "operations", JsonSchema.builder()
                        .type("array")
                        .items(JsonSchema.builder()
                            .type("object")
                            .properties(Map.of(
                                "type", JsonSchema.enum_("filter", "aggregate", "transform"),
                                "params", JsonSchema.object()
                            ))
                            .build())
                        .minItems(1)
                        .maxItems(10)
                        .build()
                ))
                .required(List.of("dataset", "operations"))
                .additionalProperties(false)
                .build();
        }
    }
}
```

## MCP 协议集成

### 1. MCP 服务器

```java
import ai.core.mcp.McpServer;
import ai.core.mcp.McpTool;
import ai.core.mcp.McpProtocol;

public class McpServerExample {

    public class CustomMcpServer extends McpServer {

        @Override
        protected void initialize() {
            // 注册 MCP 工具
            registerTool(new McpTool() {
                @Override
                public String getName() {
                    return "file_search";
                }

                @Override
                public String getDescription() {
                    return "搜索文件系统";
                }

                @Override
                public McpResponse execute(McpRequest request) {
                    String pattern = request.getParam("pattern", String.class);
                    String directory = request.getParam("directory", String.class);

                    List<String> files = searchFiles(directory, pattern);

                    return McpResponse.success(Map.of(
                        "files", files,
                        "count", files.size()
                    ));
                }
            });

            // 注册资源提供者
            registerResource("database", new DatabaseResourceProvider());
        }

        public void start() {
            // 启动 MCP 服务器
            McpProtocol protocol = new McpProtocol();
            protocol.serve(this, 8080);
        }
    }
}
```

### 2. MCP 客户端

```java
import ai.core.mcp.McpClient;
import ai.core.mcp.McpToolAdapter;

public class McpClientExample {

    public class McpIntegration {
        private final McpClient mcpClient;

        public McpIntegration(String serverUrl) {
            this.mcpClient = new McpClient(serverUrl);
        }

        public List<ToolCall> getMcpTools() {
            // 发现 MCP 服务器上的工具
            List<McpToolInfo> toolInfos = mcpClient.listTools();

            // 转换为 Core-AI 工具
            return toolInfos.stream()
                .map(info -> new McpToolAdapter(mcpClient, info))
                .collect(Collectors.toList());
        }

        public Agent createMcpEnabledAgent(LLMProvider llmProvider) {
            // 获取 MCP 工具
            List<ToolCall> mcpTools = getMcpTools();

            // 添加本地工具
            List<ToolCall> allTools = new ArrayList<>(mcpTools);
            allTools.add(new WeatherTool());
            allTools.add(new DatabaseQueryTool());

            return Agent.builder()
                .name("mcp-agent")
                .llmProvider(llmProvider)
                .tools(allTools)
                .systemPrompt("""
                    你可以使用本地工具和 MCP 服务器工具。
                    MCP 工具提供额外的能力。
                    """)
                .build();
        }
    }
}
```

### 3. MCP 工具适配器

```java
public class McpToolAdapterExample {

    public static class McpToolAdapter extends ToolCall {
        private final McpClient client;
        private final McpToolInfo toolInfo;

        public McpToolAdapter(McpClient client, McpToolInfo toolInfo) {
            this.client = client;
            this.toolInfo = toolInfo;
        }

        @Override
        public String getName() {
            return toolInfo.getName();
        }

        @Override
        public String getDescription() {
            return toolInfo.getDescription();
        }

        @Override
        public JsonSchema getParameterSchema() {
            return toolInfo.getInputSchema();
        }

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            try {
                // 调用 MCP 服务器
                McpRequest request = McpRequest.builder()
                    .tool(getName())
                    .params(arguments)
                    .build();

                McpResponse response = client.call(request);

                if (response.isSuccess()) {
                    return ToolCallResult.success(response.getResult());
                } else {
                    return ToolCallResult.error(response.getError());
                }
            } catch (Exception e) {
                return ToolCallResult.error("MCP 调用失败：" + e.getMessage());
            }
        }
    }
}
```

## 内置工具

### 1. 使用内置工具

```java
import ai.core.tool.tools.*;

public class BuiltinToolsExample {

    public Agent createAgentWithBuiltinTools() {
        return Agent.builder()
            .name("utility-agent")
            .llmProvider(llmProvider)
            .tools(List.of(
                // 文件系统工具
                new FileReadTool(),
                new FileWriteTool(),
                new FileSearchTool(),

                // 网络工具
                new HttpRequestTool(),
                new WebScrapeTool(),

                // 数据处理工具
                new JsonParseTool(),
                new CsvProcessTool(),

                // 系统工具
                new ShellCommandTool(),
                new EnvironmentVariableTool()
            ))
            .build();
    }
}
```

### 2. 扩展内置工具

```java
public class ExtendedToolExample {

    // 扩展文件读取工具
    public static class EnhancedFileReadTool extends FileReadTool {

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            // 添加权限检查
            String path = (String) arguments.get("path");
            if (!isAllowedPath(path)) {
                return ToolCallResult.error("访问被拒绝");
            }

            // 调用父类实现
            ToolCallResult result = super.call(arguments);

            // 添加后处理
            if (result.isSuccess()) {
                String content = result.getResult().toString();

                // 添加元数据
                Map<String, Object> enhanced = new HashMap<>();
                enhanced.put("content", content);
                enhanced.put("size", content.length());
                enhanced.put("lines", content.split("\n").length);
                enhanced.put("readTime", Instant.now());

                return ToolCallResult.success(enhanced);
            }

            return result;
        }

        private boolean isAllowedPath(String path) {
            // 实现路径安全检查
            return !path.contains("..") &&
                   !path.startsWith("/etc") &&
                   !path.startsWith("/sys");
        }
    }
}
```

## 工具组合和编排

### 1. 工具链

```java
public class ToolChainExample {

    public static class ToolChain {
        private final List<ToolCall> tools;

        public ToolChain(ToolCall... tools) {
            this.tools = Arrays.asList(tools);
        }

        public ToolCallResult execute(Map<String, Object> initialInput) {
            Map<String, Object> currentInput = initialInput;

            for (ToolCall tool : tools) {
                ToolCallResult result = tool.call(currentInput);

                if (!result.isSuccess()) {
                    return result;  // 失败则停止链
                }

                // 将结果作为下一个工具的输入
                currentInput = Map.of(
                    "previousResult", result.getResult(),
                    "originalInput", initialInput
                );
            }

            return ToolCallResult.success(currentInput);
        }
    }

    // 使用示例
    public void demonstrateToolChain() {
        ToolChain analysisChain = new ToolChain(
            new DataFetchTool(),      // 获取数据
            new DataCleanTool(),       // 清洗数据
            new DataAnalysisTool(),    // 分析数据
            new ReportGenerationTool() // 生成报告
        );

        ToolCallResult result = analysisChain.execute(Map.of(
            "dataSource", "sales_database",
            "timeRange", "last_quarter"
        ));
    }
}
```

### 2. 条件工具执行

```java
public class ConditionalToolExample {

    public static class ConditionalToolExecutor {
        private final LLMProvider llmProvider;

        public ToolCallResult executeConditionally(
                String condition,
                Map<String, ToolCall> tools,
                Map<String, Object> input) {

            // 让 LLM 决定使用哪个工具
            String prompt = String.format(
                "条件：%s\n" +
                "可用工具：%s\n" +
                "选择最合适的工具。",
                condition,
                tools.keySet()
            );

            String selectedTool = llmProvider.complete(prompt);

            if (tools.containsKey(selectedTool)) {
                return tools.get(selectedTool).call(input);
            }

            return ToolCallResult.error("未找到合适的工具");
        }
    }
}
```

### 3. 并行工具执行

```java
public class ParallelToolExample {

    public static class ParallelToolExecutor {
        private final ExecutorService executor = Executors.newFixedThreadPool(10);

        public Map<String, ToolCallResult> executeParallel(
                Map<String, ToolCall> tools,
                Map<String, Object> input) {

            Map<String, CompletableFuture<ToolCallResult>> futures = new HashMap<>();

            // 并行启动所有工具
            for (Map.Entry<String, ToolCall> entry : tools.entrySet()) {
                CompletableFuture<ToolCallResult> future = CompletableFuture
                    .supplyAsync(() -> entry.getValue().call(input), executor);

                futures.put(entry.getKey(), future);
            }

            // 收集结果
            Map<String, ToolCallResult> results = new HashMap<>();
            for (Map.Entry<String, CompletableFuture<ToolCallResult>> entry :
                 futures.entrySet()) {
                try {
                    results.put(entry.getKey(), entry.getValue().get());
                } catch (Exception e) {
                    results.put(entry.getKey(),
                        ToolCallResult.error("执行失败：" + e.getMessage()));
                }
            }

            return results;
        }
    }
}
```

## 实战案例

### 案例1：数据分析助手

```java
public class DataAnalysisAssistant {

    public Agent createDataAnalyst() {
        return Agent.builder()
            .name("data-analyst")
            .systemPrompt("""
                你是数据分析专家。
                使用工具来：
                1. 连接数据源
                2. 执行数据查询
                3. 进行统计分析
                4. 生成可视化
                """)
            .tools(List.of(
                new DatabaseConnectorTool(),
                new SqlQueryTool(),
                new DataVisualizationTool(),
                new StatisticalAnalysisTool()
            ))
            .build();
    }

    // 数据库连接工具
    public static class DatabaseConnectorTool extends ToolCall {
        private final Map<String, DataSource> dataSources = new HashMap<>();

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            String dbType = (String) arguments.get("type");
            String connectionString = (String) arguments.get("connection");

            try {
                DataSource ds = createDataSource(dbType, connectionString);
                String id = UUID.randomUUID().toString();
                dataSources.put(id, ds);

                return ToolCallResult.success(Map.of(
                    "connectionId", id,
                    "status", "connected"
                ));
            } catch (Exception e) {
                return ToolCallResult.error(e.getMessage());
            }
        }
    }

    // 数据可视化工具
    public static class DataVisualizationTool extends ToolCall {
        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            String chartType = (String) arguments.get("type");
            List<Map<String, Object>> data =
                (List<Map<String, Object>>) arguments.get("data");

            try {
                String chartUrl = generateChart(chartType, data);
                return ToolCallResult.success(Map.of(
                    "chartUrl", chartUrl,
                    "type", chartType,
                    "dataPoints", data.size()
                ));
            } catch (Exception e) {
                return ToolCallResult.error(e.getMessage());
            }
        }

        private String generateChart(String type, List<Map<String, Object>> data) {
            // 使用图表库生成图表
            ChartBuilder builder = new ChartBuilder();

            switch (type) {
                case "bar":
                    return builder.bar(data).build();
                case "line":
                    return builder.line(data).build();
                case "pie":
                    return builder.pie(data).build();
                default:
                    throw new IllegalArgumentException("Unknown chart type: " + type);
            }
        }
    }
}
```

### 案例2：自动化运维助手

```java
public class DevOpsAssistant {

    public Agent createDevOpsAgent() {
        return Agent.builder()
            .name("devops-assistant")
            .systemPrompt("""
                你是 DevOps 自动化专家。
                帮助管理：
                - 容器和编排
                - CI/CD 流程
                - 监控和告警
                - 基础设施
                """)
            .tools(createDevOpsTools())
            .build();
    }

    private List<ToolCall> createDevOpsTools() {
        return List.of(
            new DockerTool(),
            new KubernetesTool(),
            new TerraformTool(),
            new MonitoringTool()
        );
    }

    // Docker 工具
    public static class DockerTool extends ToolCall {
        private final DockerClient docker = DockerClientBuilder.getInstance().build();

        @Override
        public String getName() {
            return "docker_operations";
        }

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            String operation = (String) arguments.get("operation");

            try {
                switch (operation) {
                    case "list_containers":
                        return listContainers();

                    case "start_container":
                        String containerId = (String) arguments.get("container_id");
                        return startContainer(containerId);

                    case "deploy_image":
                        String image = (String) arguments.get("image");
                        Map<String, String> env = (Map<String, String>) arguments.get("env");
                        return deployImage(image, env);

                    case "get_logs":
                        String container = (String) arguments.get("container");
                        return getContainerLogs(container);

                    default:
                        return ToolCallResult.error("Unknown operation: " + operation);
                }
            } catch (Exception e) {
                return ToolCallResult.error(e.getMessage());
            }
        }

        private ToolCallResult deployImage(String image, Map<String, String> env) {
            CreateContainerResponse container = docker.createContainerCmd(image)
                .withEnv(env.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.toList()))
                .exec();

            docker.startContainerCmd(container.getId()).exec();

            return ToolCallResult.success(Map.of(
                "containerId", container.getId(),
                "status", "running"
            ));
        }
    }

    // Kubernetes 工具
    public static class KubernetesTool extends ToolCall {
        private final KubernetesClient k8s = new DefaultKubernetesClient();

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            String operation = (String) arguments.get("operation");

            switch (operation) {
                case "scale_deployment":
                    return scaleDeployment(arguments);

                case "rolling_update":
                    return rollingUpdate(arguments);

                case "check_pod_status":
                    return checkPodStatus(arguments);

                case "apply_manifest":
                    return applyManifest(arguments);

                default:
                    return ToolCallResult.error("Unknown operation");
            }
        }

        private ToolCallResult scaleDeployment(Map<String, Object> args) {
            String namespace = (String) args.get("namespace");
            String deployment = (String) args.get("deployment");
            int replicas = (int) args.get("replicas");

            k8s.apps().deployments()
                .inNamespace(namespace)
                .withName(deployment)
                .scale(replicas);

            return ToolCallResult.success(Map.of(
                "deployment", deployment,
                "replicas", replicas,
                "status", "scaled"
            ));
        }
    }
}
```

### 案例3：API 集成助手

```java
public class ApiIntegrationAssistant {

    public Agent createApiAgent() {
        return Agent.builder()
            .name("api-integration-agent")
            .systemPrompt("""
                你是 API 集成专家。
                可以：
                - 调用 REST API
                - 处理认证
                - 转换数据格式
                - 处理错误和重试
                """)
            .tools(List.of(
                new RestApiTool(),
                new GraphQLTool(),
                new WebhookTool(),
                new DataTransformTool()
            ))
            .build();
    }

    // REST API 工具
    public static class RestApiTool extends ToolCall {
        private final HttpClient httpClient = HttpClient.newHttpClient();

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            String method = (String) arguments.get("method");
            String url = (String) arguments.get("url");
            Map<String, String> headers =
                (Map<String, String>) arguments.getOrDefault("headers", Map.of());
            String body = (String) arguments.get("body");

            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

                // 添加 headers
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }

                // 设置方法和 body
                HttpRequest request = switch (method.toUpperCase()) {
                    case "GET" -> requestBuilder.GET().build();
                    case "POST" -> requestBuilder.POST(
                        HttpRequest.BodyPublishers.ofString(body)).build();
                    case "PUT" -> requestBuilder.PUT(
                        HttpRequest.BodyPublishers.ofString(body)).build();
                    case "DELETE" -> requestBuilder.DELETE().build();
                    default -> throw new IllegalArgumentException("Unsupported method: " + method);
                };

                HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                );

                return ToolCallResult.success(Map.of(
                    "status", response.statusCode(),
                    "body", response.body(),
                    "headers", response.headers().map()
                ));

            } catch (Exception e) {
                return ToolCallResult.error("API 调用失败：" + e.getMessage());
            }
        }
    }

    // GraphQL 工具
    public static class GraphQLTool extends ToolCall {
        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            String endpoint = (String) arguments.get("endpoint");
            String query = (String) arguments.get("query");
            Map<String, Object> variables =
                (Map<String, Object>) arguments.getOrDefault("variables", Map.of());

            try {
                GraphQLClient client = GraphQLClient.newClient(endpoint).build();

                GraphQLRequest request = GraphQLRequest.builder()
                    .query(query)
                    .variables(variables)
                    .build();

                GraphQLResponse response = client.execute(request);

                if (response.hasErrors()) {
                    return ToolCallResult.error(
                        "GraphQL errors: " + response.getErrors()
                    );
                }

                return ToolCallResult.success(response.getData());

            } catch (Exception e) {
                return ToolCallResult.error(e.getMessage());
            }
        }
    }

    // 数据转换工具
    public static class DataTransformTool extends ToolCall {
        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            Object data = arguments.get("data");
            String fromFormat = (String) arguments.get("from");
            String toFormat = (String) arguments.get("to");
            String schema = (String) arguments.get("schema");

            try {
                Object transformed = transform(data, fromFormat, toFormat, schema);
                return ToolCallResult.success(transformed);
            } catch (Exception e) {
                return ToolCallResult.error(e.getMessage());
            }
        }

        private Object transform(Object data, String from, String to, String schema) {
            // 实现不同格式之间的转换
            if ("json".equals(from) && "xml".equals(to)) {
                return jsonToXml(data);
            } else if ("xml".equals(from) && "json".equals(to)) {
                return xmlToJson(data);
            } else if ("csv".equals(from) && "json".equals(to)) {
                return csvToJson(data);
            } else {
                throw new UnsupportedOperationException(
                    "Unsupported transformation: " + from + " to " + to
                );
            }
        }
    }
}
```

## 错误处理和重试

### 1. 工具错误处理

```java
public class ToolErrorHandlingExample {

    public abstract static class RobustTool extends ToolCall {
        private final int maxRetries;
        private final long retryDelay;

        public RobustTool(int maxRetries, long retryDelay) {
            this.maxRetries = maxRetries;
            this.retryDelay = retryDelay;
        }

        @Override
        public final ToolCallResult call(Map<String, Object> arguments) {
            int attempt = 0;
            Exception lastException = null;

            while (attempt <= maxRetries) {
                try {
                    // 验证输入
                    ValidationResult validation = validateInput(arguments);
                    if (!validation.isValid()) {
                        return ToolCallResult.error(validation.getError());
                    }

                    // 执行工具逻辑
                    return executeWithTimeout(arguments, 30, TimeUnit.SECONDS);

                } catch (RetryableException e) {
                    lastException = e;
                    attempt++;

                    if (attempt <= maxRetries) {
                        sleep(retryDelay * attempt);  // 指数退避
                    }

                } catch (Exception e) {
                    // 不可重试的错误
                    return ToolCallResult.error(e.getMessage());
                }
            }

            return ToolCallResult.error(
                "Max retries exceeded: " + lastException.getMessage()
            );
        }

        protected abstract ToolCallResult doExecute(Map<String, Object> arguments)
            throws Exception;

        protected abstract ValidationResult validateInput(Map<String, Object> arguments);
    }
}
```

### 2. 工具回退策略

```java
public class ToolFallbackExample {

    public static class FallbackTool extends ToolCall {
        private final List<ToolCall> tools;  // 优先级顺序

        public FallbackTool(ToolCall primary, ToolCall... fallbacks) {
            this.tools = new ArrayList<>();
            this.tools.add(primary);
            this.tools.addAll(Arrays.asList(fallbacks));
        }

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            for (int i = 0; i < tools.size(); i++) {
                ToolCall tool = tools.get(i);

                try {
                    ToolCallResult result = tool.call(arguments);

                    if (result.isSuccess()) {
                        return result;
                    }

                    // 记录失败但继续尝试
                    logger.warn("Tool {} failed: {}", tool.getName(), result.getError());

                } catch (Exception e) {
                    logger.error("Tool {} threw exception", tool.getName(), e);
                }
            }

            return ToolCallResult.error("All tools failed");
        }
    }
}
```

## 工具监控

### 1. 性能监控

```java
public class ToolMonitoringExample {

    public static class MonitoredTool extends ToolCall {
        private final ToolCall delegate;
        private final MeterRegistry metrics;

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            Timer.Sample sample = Timer.start(metrics);

            try {
                ToolCallResult result = delegate.call(arguments);

                // 记录成功/失败
                metrics.counter("tool.calls",
                    "name", getName(),
                    "status", result.isSuccess() ? "success" : "failure"
                ).increment();

                return result;

            } catch (Exception e) {
                metrics.counter("tool.exceptions", "name", getName()).increment();
                throw e;

            } finally {
                sample.stop(metrics.timer("tool.duration", "name", getName()));
            }
        }
    }
}
```

## 最佳实践

1. **安全性**
   - 验证所有输入参数
   - 实施权限控制
   - 避免执行危险操作
   - 使用沙盒环境

2. **可靠性**
   - 实现重试机制
   - 提供回退策略
   - 处理超时情况
   - 记录详细日志

3. **性能**
   - 使用异步执行
   - 实施结果缓存
   - 批量处理优化
   - 连接池管理

4. **可维护性**
   - 清晰的工具描述
   - 完整的参数 schema
   - 版本控制
   - 文档化

5. **测试**
   - 单元测试每个工具
   - 集成测试工具组合
   - 模拟失败场景
   - 性能测试

## 总结

通过本教程，您学习了：

1. ✅ 如何创建自定义工具
2. ✅ JSON Schema 定义和验证
3. ✅ MCP 协议集成方法
4. ✅ 内置工具的使用和扩展
5. ✅ 工具组合和编排策略
6. ✅ 实际应用案例

下一步，您可以：
- 学习[流程编排](tutorial-flow.md)构建复杂工作流
- 阅读[性能优化指南](performance-guide.md)提升系统性能
- 探索[部署指南](deployment-guide.md)了解生产部署