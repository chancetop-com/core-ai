# 11 - API 设计

> 🎯 **学习目标**: 掌握 core-ai-server 的 RESTful API 设计原则、版本管理、错误处理和文档生成
> 
> ⏱️ **预计时间**: 1 天
> 
> 📋 **前置要求**: 完成 [10-数据模型](./10-数据模型.md)

---

## 📚 本章内容

- [11.1 API 设计概述](#111-api-设计概述)
- [11.2 RESTful 设计原则](#112-restful-设计原则)
- [11.3 API 版本管理](#113-api-版本管理)
- [11.4 请求和响应设计](#114-请求和响应设计)
- [11.5 错误处理](#115-错误处理)
- [11.6 API 文档](#116-api-文档)
- [11.7 API 测试](#117-api-测试)
- [11.8 实战: 设计完整 API](#118-实战-设计完整-api)
- [11.9 最佳实践](#119-最佳实践)
- [11.10 常见问题](#1110-常见问题)
- [11.11 验证学习成果](#1111-验证学习成果)

---

## 11.1 API 设计概述

### 11.1.1 API 设计的重要性

良好的 API 设计能够：

| 方面 | 说明 |
|------|------|
| **易用性** | 开发者容易理解和使用 |
| **一致性** | 统一的命名和行为规范 |
| **可维护性** | 易于演进和扩展 |
| **可测试性** | 便于自动化测试 |
| **文档化** | 自动生成文档 |

### 11.1.2 core-ai-server 的 API 架构

```
┌─────────────────────────────────────────┐
│          API 层次结构                    │
├─────────────────────────────────────────┤
│                                         │
│  Controller 层                          │
│  ├─ UserController                     │
│  ├─ AgentController                    │
│  ├─ SessionController                  │
│  └─ WorkflowController                 │
│                                         │
│  Service 层                             │
│  ├─ UserService                        │
│  ├─ AgentService                       │
│  ├─ SessionService                     │
│  └─ WorkflowService                    │
│                                         │
│  Repository 层                          │
│  ├─ UserRepository                     │
│  ├─ AgentRepository                    │
│  ├─ SessionRepository                  │
│  └─ WorkflowRepository                 │
│                                         │
└─────────────────────────────────────────┘
```

### 11.1.3 API 端点概览

```yaml
# 用户 API
POST   /api/auth/login              # 登录
POST   /api/auth/logout             # 登出
GET    /api/users/me                # 获取当前用户
PUT    /api/users/me                # 更新当前用户

# Agent API
GET    /api/agents                  # 列出 Agent
POST   /api/agents                  # 创建 Agent
GET    /api/agents/{id}             # 获取 Agent
PUT    /api/agents/{id}             # 更新 Agent
DELETE /api/agents/{id}             # 删除 Agent

# Session API
GET    /api/sessions                # 列出会话
POST   /api/sessions                # 创建会话
GET    /api/sessions/{id}           # 获取会话
DELETE /api/sessions/{id}           # 删除会话
POST   /api/sessions/{id}/messages  # 发送消息

# Workflow API
GET    /api/workflows               # 列出工作流
POST   /api/workflows               # 创建工作流
GET    /api/workflows/{id}          # 获取工作流
PUT    /api/workflows/{id}          # 更新工作流
DELETE /api/workflows/{id}          # 删除工作流
POST   /api/workflows/{id}/execute  # 执行工作流
```

---

## 11.2 RESTful 设计原则

### 11.2.1 资源命名

```java
// ✅ 好的实践：使用名词复数
@RestController
public class AgentController {
    
    @GET("/api/agents")
    public List<Agent> listAgents() { ... }
    
    @GET("/api/agents/{id}")
    public Agent getAgent(@Path("id") String id) { ... }
    
    @POST("/api/agents")
    public Agent createAgent(@Body Agent agent) { ... }
}

// ❌ 避免：使用动词
@RestController
public class AgentController {
    
    @GET("/api/getAgents")
    public List<Agent> getAgents() { ... }
    
    @POST("/api/createAgent")
    public Agent createAgent(@Body Agent agent) { ... }
}
```

### 11.2.2 HTTP 方法

| 方法 | 用途 | 幂等性 | 示例 |
|------|------|--------|------|
| **GET** | 查询资源 | 是 | `GET /api/agents` |
| **POST** | 创建资源 | 否 | `POST /api/agents` |
| **PUT** | 更新资源（全量） | 是 | `PUT /api/agents/123` |
| **PATCH** | 更新资源（部分） | 否 | `PATCH /api/agents/123` |
| **DELETE** | 删除资源 | 是 | `DELETE /api/agents/123` |

```java
@RestController
public class AgentController {
    
    // GET - 查询（幂等）
    @GET("/api/agents/{id}")
    public Agent getAgent(@Path("id") String id) {
        return agentService.findById(id);
    }
    
    // POST - 创建（非幂等）
    @POST("/api/agents")
    public Agent createAgent(@Body CreateAgentRequest request) {
        return agentService.create(request);
    }
    
    // PUT - 全量更新（幂等）
    @PUT("/api/agents/{id}")
    public Agent updateAgent(@Path("id") String id, 
                            @Body UpdateAgentRequest request) {
        return agentService.update(id, request);
    }
    
    // PATCH - 部分更新（非幂等）
    @PATCH("/api/agents/{id}")
    public Agent patchAgent(@Path("id") String id, 
                           @Body Map<String, Object> updates) {
        return agentService.patch(id, updates);
    }
    
    // DELETE - 删除（幂等）
    @DELETE("/api/agents/{id}")
    public void deleteAgent(@Path("id") String id) {
        agentService.delete(id);
    }
}
```

### 11.2.3 状态码

| 状态码 | 含义 | 使用场景 |
|--------|------|---------|
| **200 OK** | 成功 | GET、PUT、PATCH、DELETE |
| **201 Created** | 创建成功 | POST |
| **204 No Content** | 成功但无内容 | DELETE |
| **400 Bad Request** | 请求错误 | 参数验证失败 |
| **401 Unauthorized** | 未认证 | Token 无效或过期 |
| **403 Forbidden** | 无权限 | 权限不足 |
| **404 Not Found** | 资源不存在 | 资源 ID 无效 |
| **409 Conflict** | 冲突 | 资源已存在 |
| **500 Internal Server Error** | 服务器错误 | 未知异常 |

```java
@RestController
public class AgentController {
    
    @POST("/api/agents")
    public Response createAgent(@Body CreateAgentRequest request) {
        var agent = agentService.create(request);
        
        // 201 Created
        return Response.status(201)
            .header("Location", "/api/agents/" + agent.getId())
            .entity(agent)
            .build();
    }
    
    @DELETE("/api/agents/{id}")
    public Response deleteAgent(@Path("id") String id) {
        agentService.delete(id);
        
        // 204 No Content
        return Response.noContent().build();
    }
}
```

### 11.2.4 超媒体（HATEOAS）

```java
public class AgentDTO {
    private String id;
    private String name;
    private List<Link> links;
    
    public static AgentDTO from(Agent agent) {
        var dto = new AgentDTO();
        dto.id = agent.getId();
        dto.name = agent.getName();
        
        // 添加超链接
        dto.links = List.of(
            new Link("self", "/api/agents/" + agent.getId()),
            new Link("sessions", "/api/agents/" + agent.getId() + "/sessions"),
            new Link("messages", "/api/agents/" + agent.getId() + "/messages")
        );
        
        return dto;
    }
}

public class Link {
    private String rel;
    private String href;
    
    public Link(String rel, String href) {
        this.rel = rel;
        this.href = href;
    }
}
```

💡 **RESTful 原则**:
- 使用名词命名资源
- 使用正确的 HTTP 方法
- 返回合适的状态码
- 支持 HATEOAS（可选）
- 保持接口一致性

---

## 11.3 API 版本管理

### 11.3.1 版本策略

**1. URL 路径版本**
```
/api/v1/agents
/api/v2/agents
```

**2. Header 版本**
```
GET /api/agents
Accept: application/vnd.coreai.v1+json
```

**3. 查询参数版本**
```
/api/agents?version=1
```

💡 **推荐**: URL 路径版本（最直观、最常用）

### 11.3.2 版本实现

```java
@RestController
public class AgentV1Controller {
    
    @GET("/api/v1/agents")
    public List<AgentV1DTO> listAgents() {
        return agentService.findAll().stream()
            .map(AgentV1DTO::from)
            .toList();
    }
}

@RestController
public class AgentV2Controller {
    
    @GET("/api/v2/agents")
    public List<AgentV2DTO> listAgents() {
        return agentService.findAll().stream()
            .map(AgentV2DTO::from)
            .toList();
    }
}

// V1 DTO
public class AgentV1DTO {
    private String id;
    private String name;
    
    public static AgentV1DTO from(Agent agent) {
        var dto = new AgentV1DTO();
        dto.id = agent.getId();
        dto.name = agent.getName();
        return dto;
    }
}

// V2 DTO（新增字段）
public class AgentV2DTO {
    private String id;
    private String name;
    private String description;  // 新增
    private String status;       // 新增
    
    public static AgentV2DTO from(Agent agent) {
        var dto = new AgentV2DTO();
        dto.id = agent.getId();
        dto.name = agent.getName();
        dto.description = agent.getDescription();
        dto.status = agent.getStatus().name();
        return dto;
    }
}
```

### 11.3.3 版本路由

```java
@Configuration
public class ApiVersionConfig {
    
    @Bean
    public RouterFunction<ServerResponse> agentRoutes() {
        return route()
            .GET("/api/v1/agents", agentV1Handler::listAgents)
            .GET("/api/v2/agents", agentV2Handler::listAgents)
            .build();
    }
}
```

💡 **版本管理原则**:
- 保持向后兼容
- 提供迁移指南
- 标记废弃版本
- 设置版本生命周期

---

## 11.4 请求和响应设计

### 11.4.1 请求参数

**1. 路径参数**
```java
@GET("/api/agents/{id}")
public Agent getAgent(@Path("id") String id) {
    return agentService.findById(id);
}
```

**2. 查询参数**
```java
@GET("/api/agents")
public List<Agent> listAgents(
    @Param("page") int page,
    @Param("size") int size,
    @Param("status") String status,
    @Param("sort") String sort
) {
    return agentService.findAgents(page, size, status, sort);
}
```

**3. 请求体**
```java
@POST("/api/agents")
public Agent createAgent(@Body CreateAgentRequest request) {
    return agentService.create(request);
}
```

### 11.4.2 分页设计

```java
public class PageRequest {
    private int page = 1;       // 页码（从 1 开始）
    private int size = 20;      // 每页数量
    private String sort;        // 排序字段
    private String order;       // 排序方向（asc/desc）
}

public class PageResponse<T> {
    private List<T> items;          // 数据列表
    private long total;             // 总数
    private int page;               // 当前页
    private int size;               // 每页数量
    private int totalPages;         // 总页数
    private boolean hasNext;        // 是否有下一页
    private boolean hasPrevious;    // 是否有上一页
}

@RestController
public class AgentController {
    
    @GET("/api/agents")
    public PageResponse<AgentDTO> listAgents(
        @Param("page") @DefaultValue("1") int page,
        @Param("size") @DefaultValue("20") int size,
        @Param("sort") @DefaultValue("created_at") String sort,
        @Param("order") @DefaultValue("desc") String order
    ) {
        var agents = agentService.findAgents(page, size, sort, order);
        var total = agentService.countAgents();
        
        return PageResponse.<AgentDTO>builder()
            .items(agents.stream().map(AgentDTO::from).toList())
            .total(total)
            .page(page)
            .size(size)
            .totalPages((int) Math.ceil((double) total / size))
            .hasNext(page * size < total)
            .hasPrevious(page > 1)
            .build();
    }
}
```

### 11.4.3 过滤和排序

```java
@GET("/api/agents")
public List<AgentDTO> listAgents(
    @Param("status") String status,
    @Param("owner_id") String ownerId,
    @Param("created_after") ZonedDateTime createdAfter,
    @Param("created_before") ZonedDateTime createdBefore,
    @Param("sort") @DefaultValue("created_at") String sort,
    @Param("order") @DefaultValue("desc") String order
) {
    var filter = AgentFilter.builder()
        .status(status)
        .ownerId(ownerId)
        .createdAfter(createdAfter)
        .createdBefore(createdBefore)
        .sort(sort)
        .order(order)
        .build();
    
    return agentService.findAgents(filter).stream()
        .map(AgentDTO::from)
        .toList();
}
```

### 11.4.4 响应格式

**成功响应**
```json
{
  "id": "123",
  "name": "My Agent",
  "status": "ACTIVE",
  "created_at": "2026-08-31T10:00:00Z"
}
```

**列表响应**
```json
{
  "items": [
    {
      "id": "123",
      "name": "My Agent"
    }
  ],
  "total": 100,
  "page": 1,
  "size": 20,
  "total_pages": 5
}
```

**错误响应**
```json
{
  "error": {
    "code": "AGENT_NOT_FOUND",
    "message": "Agent not found",
    "details": {
      "agent_id": "123"
    }
  }
}
```

💡 **请求/响应设计原则**:
- 使用清晰的参数命名
- 提供合理的默认值
- 支持分页、过滤、排序
- 统一的响应格式
- 详细的错误信息

---

## 11.5 错误处理

### 11.5.1 异常分类

```java
// 业务异常
public class BusinessException extends RuntimeException {
    private final String code;
    private final int status;
    
    public BusinessException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}

// 资源不存在
public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resource, String id) {
        super(
            resource.toUpperCase() + "_NOT_FOUND",
            resource + " not found: " + id,
            404
        );
    }
}

// 参数验证失败
public class ValidationException extends BusinessException {
    public ValidationException(String message) {
        super("VALIDATION_ERROR", message, 400);
    }
}

// 权限不足
public class ForbiddenException extends BusinessException {
    public ForbiddenException(String message) {
        super("FORBIDDEN", message, 403);
    }
}
```

### 11.5.2 全局异常处理

```java
@ControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(ResourceNotFoundException.class)
    public Response handleNotFound(ResourceNotFoundException e) {
        return Response.status(e.getStatus())
            .entity(ErrorResponse.from(e))
            .build();
    }
    
    @ExceptionHandler(ValidationException.class)
    public Response handleValidation(ValidationException e) {
        return Response.status(e.getStatus())
            .entity(ErrorResponse.from(e))
            .build();
    }
    
    @ExceptionHandler(ForbiddenException.class)
    public Response handleForbidden(ForbiddenException e) {
        return Response.status(e.getStatus())
            .entity(ErrorResponse.from(e))
            .build();
    }
    
    @ExceptionHandler(Exception.class)
    public Response handleGeneric(Exception e) {
        LOGGER.error("Unexpected error", e);
        
        return Response.status(500)
            .entity(new ErrorResponse(
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                null
            ))
            .build();
    }
}

public class ErrorResponse {
    private ErrorDetail error;
    
    public ErrorResponse(String code, String message, Object details) {
        this.error = new ErrorDetail(code, message, details);
    }
    
    public static ErrorResponse from(BusinessException e) {
        return new ErrorResponse(e.getCode(), e.getMessage(), null);
    }
    
    public class ErrorDetail {
        private String code;
        private String message;
        private Object details;
        
        public ErrorDetail(String code, String message, Object details) {
            this.code = code;
            this.message = message;
            this.details = details;
        }
    }
}
```

### 11.5.3 参数验证

```java
public class CreateAgentRequest {
    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be less than 100 characters")
    private String name;
    
    @Size(max = 1000, message = "Description must be less than 1000 characters")
    private String description;
    
    @NotNull(message = "Config is required")
    private AgentConfig config;
}

@RestController
public class AgentController {
    
    @POST("/api/agents")
    public Agent createAgent(@Valid @Body CreateAgentRequest request) {
        return agentService.create(request);
    }
}

// 验证失败时返回
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Validation failed",
    "details": {
      "field_errors": [
        {
          "field": "name",
          "message": "Name is required"
        }
      ]
    }
  }
}
```

💡 **错误处理原则**:
- 使用统一的错误格式
- 提供有意义的错误码
- 包含详细的错误信息
- 隐藏内部实现细节
- 记录错误日志

---

## 11.6 API 文档

### 11.6.1 OpenAPI/Swagger

```java
@Configuration
public class SwaggerConfig {
    
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Core AI Server API")
                .version("1.0.0")
                .description("Core AI Server REST API Documentation")
                .contact(new Contact()
                    .name("Core AI Team")
                    .email("support@core-ai.com")
                )
            )
            .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
            .components(new Components()
                .addSecuritySchemes("BearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                )
            );
    }
}
```

### 11.6.2 接口注解

```java
@RestController
public class AgentController {
    
    @Operation(
        summary = "List agents",
        description = "Retrieve a list of all agents"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Successful operation",
            content = @Content(schema = @Schema(implementation = Agent.class))
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized"
        )
    })
    @GET("/api/agents")
    public List<Agent> listAgents() {
        return agentService.findAll();
    }
    
    @Operation(summary = "Get agent by ID")
    @Parameters({
        @Parameter(
            name = "id",
            description = "Agent ID",
            required = true,
            example = "123"
        )
    })
    @GET("/api/agents/{id}")
    public Agent getAgent(@Path("id") String id) {
        return agentService.findById(id);
    }
    
    @Operation(summary = "Create a new agent")
    @RequestBody(
        description = "Agent to create",
        required = true,
        content = @Content(schema = @Schema(implementation = CreateAgentRequest.class))
    )
    @POST("/api/agents")
    @ResponseStatus(201)
    public Agent createAgent(@Body CreateAgentRequest request) {
        return agentService.create(request);
    }
}
```

### 11.6.3 文档访问

```yaml
# application.yml
springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
    enabled: true
```

访问地址：
- API 文档：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/api-docs`

💡 **文档原则**:
- 使用 OpenAPI 标准
- 提供详细的接口说明
- 包含示例请求和响应
- 保持文档与代码同步

---

## 11.7 API 测试

### 11.7.1 单元测试

```java
@Test
public class AgentControllerTest {
    
    @InjectMocks
    private AgentController agentController;
    
    @Mock
    private AgentService agentService;
    
    @Test
    public void testGetAgent() {
        // Given
        var agent = new Agent();
        agent.setId("123");
        agent.setName("Test Agent");
        
        when(agentService.findById("123")).thenReturn(agent);
        
        // When
        var result = agentController.getAgent("123");
        
        // Then
        assertNotNull(result);
        assertEquals("123", result.getId());
        assertEquals("Test Agent", result.getName());
        
        verify(agentService).findById("123");
    }
    
    @Test
    public void testCreateAgent() {
        // Given
        var request = new CreateAgentRequest();
        request.setName("New Agent");
        
        var agent = new Agent();
        agent.setId("123");
        agent.setName("New Agent");
        
        when(agentService.create(any())).thenReturn(agent);
        
        // When
        var result = agentController.createAgent(request);
        
        // Then
        assertNotNull(result);
        assertEquals("New Agent", result.getName());
        
        verify(agentService).create(any());
    }
}
```

### 11.7.2 集成测试

```java
@Test
public class AgentControllerIntegrationTest extends BaseIntegrationTest {
    
    @Test
    public void testCreateAndGetAgent() {
        // Create agent
        var request = new CreateAgentRequest();
        request.setName("Test Agent");
        request.setDescription("Test Description");
        
        var createResponse = given()
            .contentType(ContentType.JSON)
            .body(request)
            .when()
            .post("/api/agents")
            .then()
            .statusCode(201)
            .extract()
            .as(Agent.class);
        
        assertNotNull(createResponse.getId());
        
        // Get agent
        given()
            .when()
            .get("/api/agents/" + createResponse.getId())
            .then()
            .statusCode(200)
            .body("name", equalTo("Test Agent"))
            .body("description", equalTo("Test Description"));
    }
    
    @Test
    public void testListAgents() {
        // Create multiple agents
        for (int i = 0; i < 5; i++) {
            var request = new CreateAgentRequest();
            request.setName("Agent " + i);
            
            given()
                .contentType(ContentType.JSON)
                .body(request)
                .post("/api/agents");
        }
        
        // List agents
        given()
            .when()
            .get("/api/agents")
            .then()
            .statusCode(200)
            .body("size()", equalTo(5));
    }
}
```

### 11.7.3 性能测试

```java
@Test
public class AgentPerformanceTest {
    
    @Test
    public void testGetAgentPerformance() {
        var settings = new Settings();
        settings.setDuration(60);  // 60 秒
        settings.setThreads(10);   // 10 个并发线程
        
        var result = HttpPerfTest.test(settings, () -> {
            given()
                .when()
                .get("/api/agents/123")
                .then()
                .statusCode(200);
        });
        
        // 验证性能指标
        assertTrue(result.getAverageResponseTime() < 100);  // 平均响应时间 < 100ms
        assertTrue(result.getPercentile95() < 200);         // 95% 请求 < 200ms
        assertTrue(result.getErrorRate() < 0.01);           // 错误率 < 1%
    }
}
```

💡 **测试原则**:
- 覆盖所有接口
- 测试正常和异常场景
- 进行性能测试
- 自动化测试流程

---

## 11.8 实战: 设计完整 API

### 11.8.1 需求

设计 `Dataset` 的完整 API

### 11.8.2 API 设计

```yaml
# Dataset API
GET    /api/datasets                    # 列出数据集
POST   /api/datasets                    # 创建数据集
GET    /api/datasets/{id}               # 获取数据集
PUT    /api/datasets/{id}               # 更新数据集
DELETE /api/datasets/{id}               # 删除数据集

# Dataset Records API
GET    /api/datasets/{id}/records       # 列出记录
POST   /api/datasets/{id}/records       # 添加记录
GET    /api/datasets/{id}/records/{rid} # 获取记录
PUT    /api/datasets/{id}/records/{rid} # 更新记录
DELETE /api/datasets/{id}/records/{rid} # 删除记录
```

### 11.8.3 实现

```java
@RestController
public class DatasetController {
    
    @Inject
    private DatasetService datasetService;
    
    // ===== Dataset APIs =====
    
    @Operation(summary = "List datasets")
    @GET("/api/datasets")
    public PageResponse<DatasetDTO> listDatasets(
        @Param("page") @DefaultValue("1") int page,
        @Param("size") @DefaultValue("20") int size,
        @Param("status") String status
    ) {
        var datasets = datasetService.findDatasets(page, size, status);
        var total = datasetService.countDatasets(status);
        
        return PageResponse.<DatasetDTO>builder()
            .items(datasets.stream().map(DatasetDTO::from).toList())
            .total(total)
            .page(page)
            .size(size)
            .build();
    }
    
    @Operation(summary = "Create dataset")
    @POST("/api/datasets")
    @ResponseStatus(201)
    public DatasetDTO createDataset(@Valid @Body CreateDatasetRequest request) {
        var userId = getCurrentUserId();
        var dataset = datasetService.createDataset(request, userId);
        return DatasetDTO.from(dataset);
    }
    
    @Operation(summary = "Get dataset")
    @GET("/api/datasets/{id}")
    public DatasetDTO getDataset(@Path("id") String id) {
        var dataset = datasetService.getDataset(id);
        return DatasetDTO.from(dataset);
    }
    
    @Operation(summary = "Update dataset")
    @PUT("/api/datasets/{id}")
    public DatasetDTO updateDataset(
        @Path("id") String id,
        @Valid @Body UpdateDatasetRequest request
    ) {
        var dataset = datasetService.updateDataset(id, request);
        return DatasetDTO.from(dataset);
    }
    
    @Operation(summary = "Delete dataset")
    @DELETE("/api/datasets/{id}")
    @ResponseStatus(204)
    public void deleteDataset(@Path("id") String id) {
        datasetService.deleteDataset(id);
    }
    
    // ===== Dataset Records APIs =====
    
    @Operation(summary = "List dataset records")
    @GET("/api/datasets/{id}/records")
    public PageResponse<DatasetRecordDTO> listRecords(
        @Path("id") String datasetId,
        @Param("page") @DefaultValue("1") int page,
        @Param("size") @DefaultValue("20") int size
    ) {
        var records = datasetService.findRecords(datasetId, page, size);
        var total = datasetService.countRecords(datasetId);
        
        return PageResponse.<DatasetRecordDTO>builder()
            .items(records.stream().map(DatasetRecordDTO::from).toList())
            .total(total)
            .page(page)
            .size(size)
            .build();
    }
    
    @Operation(summary = "Add dataset record")
    @POST("/api/datasets/{id}/records")
    @ResponseStatus(201)
    public DatasetRecordDTO addRecord(
        @Path("id") String datasetId,
        @Valid @Body CreateDatasetRecordRequest request
    ) {
        var record = datasetService.addRecord(datasetId, request);
        return DatasetRecordDTO.from(record);
    }
}
```

---

## 11.9 最佳实践

### 11.9.1 API 设计

```java
// ✅ 好的实践
@RestController
public class AgentController {
    
    // 使用名词复数
    @GET("/api/agents")
    
    // 使用正确的 HTTP 方法
    @POST("/api/agents")
    
    // 返回合适的状态码
    @ResponseStatus(201)
    
    // 使用清晰的参数命名
    @Param("page") int page
    
    // 提供默认值
    @DefaultValue("20")
}

// ❌ 避免
@RestController
public class AgentController {
    
    @GET("/api/getAgents")      // 使用动词
    @POST("/api/createAgent")   // 使用动词
    
    @GET("/api/agents")
    public List<Agent> get() { ... }  // 方法名不清晰
}
```

### 11.9.2 错误处理

```java
// ✅ 好的实践
@ExceptionHandler(ResourceNotFoundException.class)
public Response handleNotFound(ResourceNotFoundException e) {
    return Response.status(404)
        .entity(new ErrorResponse(
            "AGENT_NOT_FOUND",
            e.getMessage(),
            Map.of("agent_id", e.getResourceId())
        ))
        .build();
}

// ❌ 避免
@ExceptionHandler(Exception.class)
public Response handleAll(Exception e) {
    return Response.status(500)
        .entity(Map.of("error", e.getMessage()))  // 暴露内部细节
        .build();
}
```

### 11.9.3 文档

```java
// ✅ 好的实践
@Operation(
    summary = "List agents",
    description = "Retrieve a paginated list of agents with optional filtering"
)
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "Success"),
    @ApiResponse(responseCode = "401", description = "Unauthorized")
})
@Parameters({
    @Parameter(name = "page", description = "Page number", example = "1"),
    @Parameter(name = "size", description = "Page size", example = "20")
})

// ❌ 避免
@GET("/api/agents")
public List<Agent> list() { ... }  // 缺少文档
```

---

## 11.10 常见问题

### 11.10.1 API 版本冲突

**问题**: 新旧版本 API 不兼容

**解决方案**:
```java
// 保持向后兼容
public class AgentV2DTO {
    // 新增字段使用默认值
    private String status = "ACTIVE";
    
    // 废弃字段标记 @Deprecated
    @Deprecated
    private String oldField;
}
```

### 11.10.2 性能问题

**问题**: API 响应慢

**解决方案**:
```java
// 1. 使用缓存
@Cacheable("agents")
public Agent getAgent(String id) {
    return agentRepository.findById(id);
}

// 2. 使用分页
public PageResponse<Agent> listAgents(int page, int size) {
    return agentRepository.findAgents(page, size);
}

// 3. 使用异步
@Async
public CompletableFuture<Agent> getAgentAsync(String id) {
    return CompletableFuture.completedFuture(
        agentRepository.findById(id)
    );
}
```

### 11.10.3 安全问题

**问题**: API 被恶意调用

**解决方案**:
```java
// 1. 限流
@RateLimit(value = 100, period = 60)  // 每分钟 100 次
public List<Agent> listAgents() { ... }

// 2. 权限检查
@RequiresPermission(Permission.AGENT_READ)
public List<Agent> listAgents() { ... }

// 3. 输入验证
public Agent createAgent(@Valid CreateAgentRequest request) { ... }
```

---

## 11.11 验证学习成果

### 11.11.1 自测题

1. **RESTful API 中，创建资源应该使用哪个 HTTP 方法？**
   - A. GET
   - B. POST
   - C. PUT
   - D. DELETE
   
   **答案**: B

2. **API 版本管理的推荐方式是什么？**
   - A. URL 路径版本
   - B. Header 版本
   - C. 查询参数版本
   
   **答案**: A

3. **分页响应应该包含哪些信息？**
   - A. 数据列表
   - B. 总数、页码、每页数量
   - C. 是否有下一页
   - D. 以上都是
   
   **答案**: D

### 11.11.2 动手实践

1. **设计 RESTful API**
   - 定义资源名称
   - 选择合适的 HTTP 方法
   - 设计请求和响应格式

2. **实现错误处理**
   - 定义异常类
   - 实现全局异常处理器
   - 返回统一错误格式

3. **生成 API 文档**
   - 配置 OpenAPI
   - 添加接口注解
   - 访问 Swagger UI

### 11.11.3 思考题

1. **如何设计向后兼容的 API 演进？**

2. **如何处理大规模数据查询的性能问题？**

3. **如何确保 API 的安全性？**

---

## 🎉 本章小结

本章你学会了:

✅ RESTful API 设计原则  
✅ API 版本管理策略  
✅ 请求和响应设计  
✅ 错误处理机制  
✅ API 文档生成  
✅ API 测试方法  
✅ 完整 API 设计实战  

---

## 🚀 下一步

准备好进入 **[12-测试策略](./12-测试策略.md)** 了吗？

下一章你将学习:
- 单元测试
- 集成测试
- 性能测试
- 测试最佳实践

---

## 📚 参考资料

- [RESTful API Design Guide](https://restfulapi.net/)
- [OpenAPI Specification](https://swagger.io/specification/)
- [HTTP Status Codes](https://developer.mozilla.org/en-US/docs/Web/HTTP/Status)

---

*最后更新: 2026-08-31*
