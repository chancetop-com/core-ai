# Tutorial: Tool Calling

This tutorial covers how to implement and use tool calling in Core-AI to extend agent capabilities.

## Table of Contents

1. [Tool Calling Overview](#tool-calling-overview)
2. [Creating Custom Tools](#creating-custom-tools)
3. [JSON Schema Definition](#json-schema-definition)
4. [MCP Protocol Integration](#mcp-protocol-integration)
5. [Built-in Tools](#built-in-tools)
6. [Tool Composition and Orchestration](#tool-composition-and-orchestration)
7. [Real-World Examples](#real-world-examples)

## Tool Calling Overview

### What is Tool Calling?

Tool calling enables AI agents to:
- Execute specific functions (database queries, API calls)
- Interact with external systems
- Perform computations and data processing
- Access real-time information

### Tool Execution Mechanism

Core-AI's tool execution follows this process:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Tool Execution Flow                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. LLM returns tool_calls                                      │
│     └─ [{id: "call_1", function: {name: "get_weather",          │
│           arguments: "{\"city\":\"Beijing\"}"}}]                │
│                      │                                          │
│                      ▼                                          │
│  2. Agent.handleFunc(funcMsg)                                   │
│     └─ parallelStream: execute multiple tool calls in parallel  │
│                      │                                          │
│                      ▼                                          │
│  3. ToolExecutor.execute(functionCall, context)                 │
│     ├─ beforeTool() lifecycle hook                              │
│     ├─ Tool lookup: match ToolCall by name                      │
│     ├─ Auth check: needAuth && !authenticated?                  │
│     │   └─ Yes: status → WAITING_FOR_USER_INPUT                 │
│     ├─ tool.execute(arguments, context)                         │
│     └─ afterTool() lifecycle hook                               │
│                      │                                          │
│                      ▼                                          │
│  4. Build TOOL message                                          │
│     └─ Message.of(TOOL, result, toolName, toolCallId, ...)      │
│                      │                                          │
│                      ▼                                          │
│  5. Continue conversation loop (if needed)                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Key Design Points**:

1. **Parallel Execution**: Multiple tool calls execute in parallel via `parallelStream` for efficiency
2. **Lifecycle Hooks**: `beforeTool` and `afterTool` allow interception before/after tool execution
3. **Authentication**: Tools with `needAuth=true` pause execution, waiting for user confirmation
4. **Tracing Support**: `AgentTracer` traces each tool call

### Core-AI Tool Architecture

```
┌─────────────────────────────────────┐
│            Agent                    │
│  ┌──────────────────────────────┐  │
│  │     LLM (Decision Engine)    │  │
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

## Creating Custom Tools

### 1. Basic Tool Implementation

```java
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.ToolParameter;
import java.util.Map;

public class BasicToolExample {

    // Simple tool: Weather Query
    public static class WeatherTool extends ToolCall {
        @Override
        public String getName() {
            return "get_weather";
        }

        @Override
        public String getDescription() {
            return "Get weather information for a specified city";
        }

        @Override
        public List<ToolParameter> getParameters() {
            return List.of(
                ToolParameter.builder()
                    .name("city")
                    .type("string")
                    .description("City name")
                    .required(true)
                    .build(),

                ToolParameter.builder()
                    .name("unit")
                    .type("string")
                    .description("Temperature unit")
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
                // Call weather API
                WeatherData weather = fetchWeather(city, unit);

                return ToolCallResult.success(
                    String.format("%s weather: Temperature %d°%s, %s",
                        city,
                        weather.getTemperature(),
                        unit.equals("celsius") ? "C" : "F",
                        weather.getDescription()
                    )
                );
            } catch (Exception e) {
                return ToolCallResult.error(
                    "Failed to get weather: " + e.getMessage()
                );
            }
        }
    }
}
```

### 2. Complex Tool Implementation

```java
public class ComplexToolExample {

    // Database Query Tool
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
            return "Execute SQL query and return results";
        }

        @Override
        public List<ToolParameter> getParameters() {
            return List.of(
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("SQL query (SELECT only)")
                    .required(true)
                    .validation("^SELECT.*", "Only SELECT queries allowed")
                    .build(),

                ToolParameter.builder()
                    .name("limit")
                    .type("integer")
                    .description("Result count limit")
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

            // Security check
            if (!isSelectQuery(query)) {
                return ToolCallResult.error("Only SELECT queries allowed");
            }

            // Add limit
            query = addLimitToQuery(query, limit);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query);
                 ResultSet rs = stmt.executeQuery()) {

                // Convert results to JSON
                List<Map<String, Object>> results = resultSetToList(rs);

                return ToolCallResult.success(
                    Map.of(
                        "rowCount", results.size(),
                        "data", results
                    )
                );

            } catch (SQLException e) {
                return ToolCallResult.error("Query failed: " + e.getMessage());
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

### 3. Async Tools

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
                    ToolCallResult.error("Send timeout or failed")
                );
        }
    }
}
```

## JSON Schema Definition

### JSON Schema Auto-Generation Principles

Core-AI automatically converts `ToolCall` parameter definitions to OpenAI-compliant JSON Schema. This process is transparent to developers:

```
┌─────────────────────────────────────────────────────────────────┐
│           Tool Definition → JSON Schema Conversion              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ToolCall                                                       │
│    ├─ name: "get_weather"                                       │
│    ├─ description: "Get weather information"                    │
│    └─ parameters: List<ToolCallParameter>                       │
│         ├─ city (String, required)                              │
│         └─ unit (String, optional, enum: [celsius, ...])        │
│                        │                                        │
│                        ▼  toTool()                              │
│                                                                 │
│  Tool (OpenAI Format)                                           │
│    ├─ type: "function"                                          │
│    └─ function:                                                 │
│         ├─ name: "get_weather" (max 64 chars, auto-truncated)   │
│         ├─ description: "Get weather information"               │
│         └─ parameters: JsonSchema                               │
│              {                                                  │
│                "type": "object",                                │
│                "properties": {                                  │
│                  "city": {                                      │
│                    "type": "string",                            │
│                    "description": "City name"                   │
│                  },                                             │
│                  "unit": {                                      │
│                    "type": "string",                            │
│                    "enum": ["celsius", "fahrenheit"]            │
│                  }                                              │
│                },                                               │
│                "required": ["city"]                             │
│              }                                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Schema Generation Core Logic**:

```java
public class JsonSchemaUtil {
    public static JsonSchema toJsonSchema(List<ToolCallParameter> parameters) {
        var schema = new JsonSchema();
        schema.type = PropertyType.OBJECT;

        // Extract required fields
        schema.required = parameters.stream()
            .filter(p -> p.isRequired() != null && p.isRequired())
            .map(ToolCallParameter::getName)
            .toList();

        // Generate Schema properties for each parameter
        schema.properties = parameters.stream()
            .filter(p -> p.getName() != null)
            .collect(Collectors.toMap(
                ToolCallParameter::getName,
                JsonSchemaUtil::toSchemaProperty
            ));

        return schema;
    }

    private static JsonSchema toSchemaProperty(ToolCallParameter p) {
        var property = new JsonSchema();
        property.description = p.getDescription();
        property.type = buildJsonSchemaType(p.getClassType());
        property.enums = p.getEnums();

        // Recursively handle nested objects
        if (property.type == PropertyType.OBJECT && isCustomObjectType(p.getClassType())) {
            var nestedSchema = toJsonSchema(p.getClassType());
            property.properties = nestedSchema.properties;
            property.required = nestedSchema.required;
        }

        return property;
    }
}
```

**Supported Parameter Type Mappings**:

| Java Type | JSON Schema Type | Description |
|-----------|------------------|-------------|
| `String` | `string` | String |
| `Integer`, `int`, `Long`, `long` | `integer` | Integer |
| `Double`, `double`, `Float`, `float` | `number` | Floating point |
| `Boolean`, `boolean` | `boolean` | Boolean |
| `List<T>`, `Array` | `array` | Array, supports nesting |
| Custom class | `object` | Object, recursive schema generation |

### 1. Auto Schema Generation

```java
import ai.core.utils.JsonSchemaGenerator;
import ai.core.tool.ToolDefinition;

public class JsonSchemaExample {

    // Define tool parameters using annotations
    @ToolDefinition(
        name = "calculate_loan",
        description = "Calculate monthly loan payment"
    )
    public static class LoanCalculator {

        @ToolParam(description = "Loan principal", required = true)
        @Min(1000)
        @Max(10000000)
        private double principal;

        @ToolParam(description = "Annual interest rate (%)", required = true)
        @DecimalMin("0.1")
        @DecimalMax("20.0")
        private double annualRate;

        @ToolParam(description = "Loan term (months)", required = true)
        @Min(1)
        @Max(360)
        private int months;

        public double calculate() {
            double monthlyRate = annualRate / 100 / 12;
            return principal * monthlyRate * Math.pow(1 + monthlyRate, months) /
                   (Math.pow(1 + monthlyRate, months) - 1);
        }
    }

    // Auto-generate JSON Schema
    public String generateSchema() {
        JsonSchemaGenerator generator = new JsonSchemaGenerator();
        return generator.generate(LoanCalculator.class);
    }

    // Generated Schema example
    /*
    {
        "type": "object",
        "properties": {
            "principal": {
                "type": "number",
                "description": "Loan principal",
                "minimum": 1000,
                "maximum": 10000000
            },
            "annualRate": {
                "type": "number",
                "description": "Annual interest rate (%)",
                "minimum": 0.1,
                "maximum": 20.0
            },
            "months": {
                "type": "integer",
                "description": "Loan term (months)",
                "minimum": 1,
                "maximum": 360
            }
        },
        "required": ["principal", "annualRate", "months"]
    }
    */
}
```

### 2. Complex Schema Definition

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
                                    // Database config
                                    JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of(
                                            "table", JsonSchema.string(),
                                            "columns", JsonSchema.array(JsonSchema.string())
                                        ))
                                        .when("source", "database")
                                        .build(),

                                    // File config
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

## MCP Protocol Integration

### 1. MCP Server

```java
import ai.core.mcp.McpServer;
import ai.core.mcp.McpTool;
import ai.core.mcp.McpProtocol;

public class McpServerExample {

    public class CustomMcpServer extends McpServer {

        @Override
        protected void initialize() {
            // Register MCP tool
            registerTool(new McpTool() {
                @Override
                public String getName() {
                    return "file_search";
                }

                @Override
                public String getDescription() {
                    return "Search file system";
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

            // Register resource provider
            registerResource("database", new DatabaseResourceProvider());
        }

        public void start() {
            // Start MCP server
            McpProtocol protocol = new McpProtocol();
            protocol.serve(this, 8080);
        }
    }
}
```

### 2. MCP Client

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
            // Discover tools on MCP server
            List<McpToolInfo> toolInfos = mcpClient.listTools();

            // Convert to Core-AI tools
            return toolInfos.stream()
                .map(info -> new McpToolAdapter(mcpClient, info))
                .collect(Collectors.toList());
        }

        public Agent createMcpEnabledAgent(LLMProvider llmProvider) {
            // Get MCP tools
            List<ToolCall> mcpTools = getMcpTools();

            // Add local tools
            List<ToolCall> allTools = new ArrayList<>(mcpTools);
            allTools.add(new WeatherTool());
            allTools.add(new DatabaseQueryTool());

            return Agent.builder()
                .name("mcp-agent")
                .llmProvider(llmProvider)
                .tools(allTools)
                .systemPrompt("""
                    You can use both local tools and MCP server tools.
                    MCP tools provide additional capabilities.
                    """)
                .build();
        }
    }
}
```

### 3. MCP Tool Adapter

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
                // Call MCP server
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
                return ToolCallResult.error("MCP call failed: " + e.getMessage());
            }
        }
    }
}
```

## Built-in Tools

### 1. Using Built-in Tools

```java
import ai.core.tool.tools.*;

public class BuiltinToolsExample {

    public Agent createAgentWithBuiltinTools() {
        return Agent.builder()
            .name("utility-agent")
            .llmProvider(llmProvider)
            .tools(List.of(
                // File system tools
                new FileReadTool(),
                new FileWriteTool(),
                new FileSearchTool(),

                // Network tools
                new HttpRequestTool(),
                new WebScrapeTool(),

                // Data processing tools
                new JsonParseTool(),
                new CsvProcessTool(),

                // System tools
                new ShellCommandTool(),
                new EnvironmentVariableTool()
            ))
            .build();
    }
}
```

### 2. Extending Built-in Tools

```java
public class ExtendedToolExample {

    // Extended file read tool
    public static class EnhancedFileReadTool extends FileReadTool {

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            // Add permission check
            String path = (String) arguments.get("path");
            if (!isAllowedPath(path)) {
                return ToolCallResult.error("Access denied");
            }

            // Call parent implementation
            ToolCallResult result = super.call(arguments);

            // Add post-processing
            if (result.isSuccess()) {
                String content = result.getResult().toString();

                // Add metadata
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
            // Implement path security check
            return !path.contains("..") &&
                   !path.startsWith("/etc") &&
                   !path.startsWith("/sys");
        }
    }
}
```

## Tool Composition and Orchestration

### 1. Tool Chains

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
                    return result;  // Stop chain on failure
                }

                // Pass result as next tool's input
                currentInput = Map.of(
                    "previousResult", result.getResult(),
                    "originalInput", initialInput
                );
            }

            return ToolCallResult.success(currentInput);
        }
    }

    // Usage example
    public void demonstrateToolChain() {
        ToolChain analysisChain = new ToolChain(
            new DataFetchTool(),       // Fetch data
            new DataCleanTool(),       // Clean data
            new DataAnalysisTool(),    // Analyze data
            new ReportGenerationTool() // Generate report
        );

        ToolCallResult result = analysisChain.execute(Map.of(
            "dataSource", "sales_database",
            "timeRange", "last_quarter"
        ));
    }
}
```

### 2. Conditional Tool Execution

```java
public class ConditionalToolExample {

    public static class ConditionalToolExecutor {
        private final LLMProvider llmProvider;

        public ToolCallResult executeConditionally(
                String condition,
                Map<String, ToolCall> tools,
                Map<String, Object> input) {

            // Let LLM decide which tool to use
            String prompt = String.format(
                "Condition: %s\n" +
                "Available tools: %s\n" +
                "Select the most appropriate tool.",
                condition,
                tools.keySet()
            );

            String selectedTool = llmProvider.complete(prompt);

            if (tools.containsKey(selectedTool)) {
                return tools.get(selectedTool).call(input);
            }

            return ToolCallResult.error("No suitable tool found");
        }
    }
}
```

### 3. Parallel Tool Execution

```java
public class ParallelToolExample {

    public static class ParallelToolExecutor {
        private final ExecutorService executor = Executors.newFixedThreadPool(10);

        public Map<String, ToolCallResult> executeParallel(
                Map<String, ToolCall> tools,
                Map<String, Object> input) {

            Map<String, CompletableFuture<ToolCallResult>> futures = new HashMap<>();

            // Launch all tools in parallel
            for (Map.Entry<String, ToolCall> entry : tools.entrySet()) {
                CompletableFuture<ToolCallResult> future = CompletableFuture
                    .supplyAsync(() -> entry.getValue().call(input), executor);

                futures.put(entry.getKey(), future);
            }

            // Collect results
            Map<String, ToolCallResult> results = new HashMap<>();
            for (Map.Entry<String, CompletableFuture<ToolCallResult>> entry :
                 futures.entrySet()) {
                try {
                    results.put(entry.getKey(), entry.getValue().get());
                } catch (Exception e) {
                    results.put(entry.getKey(),
                        ToolCallResult.error("Execution failed: " + e.getMessage()));
                }
            }

            return results;
        }
    }
}
```

## Real-World Examples

### Example 1: Data Analysis Assistant

```java
public class DataAnalysisAssistant {

    public Agent createDataAnalyst() {
        return Agent.builder()
            .name("data-analyst")
            .systemPrompt("""
                You are a data analysis expert.
                Use tools to:
                1. Connect to data sources
                2. Execute data queries
                3. Perform statistical analysis
                4. Generate visualizations
                """)
            .tools(List.of(
                new DatabaseConnectorTool(),
                new SqlQueryTool(),
                new DataVisualizationTool(),
                new StatisticalAnalysisTool()
            ))
            .build();
    }

    // Database connection tool
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

    // Data visualization tool
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
            // Generate chart using chart library
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

### Example 2: DevOps Automation Assistant

```java
public class DevOpsAssistant {

    public Agent createDevOpsAgent() {
        return Agent.builder()
            .name("devops-assistant")
            .systemPrompt("""
                You are a DevOps automation expert.
                Help manage:
                - Containers and orchestration
                - CI/CD pipelines
                - Monitoring and alerting
                - Infrastructure
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

    // Docker tool
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

    // Kubernetes tool
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

### Example 3: API Integration Assistant

```java
public class ApiIntegrationAssistant {

    public Agent createApiAgent() {
        return Agent.builder()
            .name("api-integration-agent")
            .systemPrompt("""
                You are an API integration expert.
                You can:
                - Call REST APIs
                - Handle authentication
                - Transform data formats
                - Handle errors and retries
                """)
            .tools(List.of(
                new RestApiTool(),
                new GraphQLTool(),
                new WebhookTool(),
                new DataTransformTool()
            ))
            .build();
    }

    // REST API tool
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

                // Add headers
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }

                // Set method and body
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
                return ToolCallResult.error("API call failed: " + e.getMessage());
            }
        }
    }

    // GraphQL tool
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

    // Data transformation tool
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
            // Implement transformation between different formats
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

## Error Handling and Retries

### 1. Tool Error Handling

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
                    // Validate input
                    ValidationResult validation = validateInput(arguments);
                    if (!validation.isValid()) {
                        return ToolCallResult.error(validation.getError());
                    }

                    // Execute tool logic
                    return executeWithTimeout(arguments, 30, TimeUnit.SECONDS);

                } catch (RetryableException e) {
                    lastException = e;
                    attempt++;

                    if (attempt <= maxRetries) {
                        sleep(retryDelay * attempt);  // Exponential backoff
                    }

                } catch (Exception e) {
                    // Non-retryable error
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

### 2. Tool Fallback Strategy

```java
public class ToolFallbackExample {

    public static class FallbackTool extends ToolCall {
        private final List<ToolCall> tools;  // Priority order

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

                    // Log failure but continue trying
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

## Tool Monitoring

### 1. Performance Monitoring

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

                // Record success/failure
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

## Best Practices

1. **Security**
   - Validate all input parameters
   - Implement permission controls
   - Avoid dangerous operations
   - Use sandbox environments

2. **Reliability**
   - Implement retry mechanisms
   - Provide fallback strategies
   - Handle timeouts
   - Log detailed information

3. **Performance**
   - Use async execution
   - Implement result caching
   - Batch processing optimization
   - Connection pool management

4. **Maintainability**
   - Clear tool descriptions
   - Complete parameter schemas
   - Version control
   - Documentation

5. **Testing**
   - Unit test each tool
   - Integration test tool combinations
   - Simulate failure scenarios
   - Performance testing

## Summary

Through this tutorial, you learned:

1. How to create custom tools
2. JSON Schema definition and validation
3. MCP protocol integration methods
4. Using and extending built-in tools
5. Tool composition and orchestration strategies
6. Real-world application examples

Next steps:
- Learn [Flow Orchestration](tutorial-flow.md) for complex workflows
- Explore [Memory Systems](tutorial-memory.md) for context management
- Read [Multi-Agent Systems](tutorial-multi-agent.md) for agent coordination
