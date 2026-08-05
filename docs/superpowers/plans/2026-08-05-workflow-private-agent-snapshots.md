# Workflow Private Agent Snapshots Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Workflow owners select their own draft Agents, freeze the executable configuration into immutable Workflow Versions, and replace the unsearchable published-only dropdown with a secure paginated picker.

**Architecture:** Add a narrow Workflow Agent Options API backed by normalized-name indexes, then centralize Agent executable-config construction so Agent publishing and Workflow snapshot capture use the same field mapping. Workflow Versions retain the existing `agent_snapshots` JSON contract and add optional source metadata; public publishing applies a fail-closed portability validator only to owner-editable snapshots. The frontend consumes the minimal API through a tested My/Shared picker and no longer downloads the full Agent catalog.

**Tech Stack:** Java 21, core-ng WebService/Mongo, MongoDB schema migrations, JUnit 5/Mockito, React 19, TypeScript 5.9, Vite 8, Vitest, Testing Library.

## Global Constraints

- Follow the approved design in `docs/superpowers/specs/2026-08-05-workflow-private-agent-snapshots-design.md`.
- Do not add a `PRIVATE` AgentStatus; an owned non-public Agent remains `DRAFT`.
- Other users' Agents and System Default Agents must have both `status == PUBLISHED` and a non-null `publishedConfig`.
- An owned non-System Agent snapshot always uses its current editable fields, including when its status is `PUBLISHED`.
- Preview, Save Version, and direct Publish freeze snapshots; publishing an already-saved Version never recaptures Agent state.
- Runtime continues reading only `WorkflowPublishedVersion.agentSnapshots` and executes with `WorkflowRun.userId` as caller identity.
- Public APIs, clone payloads, and export files must never return `agentSnapshots` or private executable configuration.
- Public publishing must not delegate owner secrets or owner-only dependencies; rejection is fail-closed and node-specific.
- Keep Java comments in English and follow core-ng DTO/entity constraints from `instructions.md`.
- After integrating current master, the Agent-name migration version is `20260805002` because master already uses `20260805001` for the Dataset session index; the release version is `1.0.133` because master already uses `1.0.132`.
- Preserve unrelated worktree changes and stage only files named by the current task.

---

## File Structure

### New server/API files

- `core-ai-server/src/main/java/ai/core/server/agent/AgentNameKey.java` — one normalization function shared by Agent writes and option queries.
- `core-ai-server/src/main/java/ai/core/server/agent/AgentExecutableConfigFactory.java` — the only editable/published-to-executable config mapping.
- `core-ai-server/src/main/java/ai/core/server/domain/AgentSnapshotSource.java` — source metadata serialized per Workflow node.
- `core-ai-server/src/main/java/ai/core/server/domain/migration/SchemaMigrationVAgentNameSearch.java` — backfill `name_key` and create Agent picker indexes.
- `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentAccessPolicy.java` — shared ownership/published/type access rules.
- `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentOptionService.java` — secure My/Shared filtering, paging, and minimal view mapping.
- `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentSnapshotService.java` — capture immutable configs and provenance for AGENT/LLM nodes.
- `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPrivateAgentSafetyValidator.java` — public portability checks for `OWNED_EDITABLE` snapshots.
- `core-ai-api/src/main/java/ai/core/api/server/workflow/ListWorkflowAgentOptionsRequest.java` — options query parameters.
- `core-ai-api/src/main/java/ai/core/api/server/workflow/ListWorkflowAgentOptionsResponse.java` — page plus selected-option resolution.
- `core-ai-api/src/main/java/ai/core/api/server/workflow/WorkflowAgentOptionView.java` — minimal non-secret Agent option DTO.

### New frontend files

- `core-ai-frontend/src/pages/workflows/AgentPicker.tsx` — searchable, paginated My/Shared Agent selector.
- `core-ai-frontend/src/pages/workflows/AgentPicker.test.tsx` — picker interaction and stale-request tests.
- `core-ai-frontend/src/pages/workflows/validationErrors.test.ts` — node-error grouping tests.
- `core-ai-frontend/src/test/setup.ts` — Testing Library cleanup.
- `core-ai-frontend/vitest.config.ts` — jsdom test configuration.

### Modified integration files

- `core-ai-server/src/main/java/ai/core/server/domain/AgentDefinition.java`
- `core-ai-server/src/main/java/ai/core/server/domain/WorkflowPublishedVersion.java`
- `core-ai-server/src/main/java/ai/core/server/domain/migration/SchemaMigrationManager.java`
- `core-ai-server/src/main/java/ai/core/server/agent/AgentDefinitionService.java`
- `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPublishService.java`
- `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPortService.java`
- `core-ai-server/src/main/java/ai/core/server/web/WorkflowWebServiceImpl.java`
- `core-ai-server/src/main/java/ai/core/server/WorkflowModule.java`
- `core-ai-server/src/test/java/ai/core/server/workflow/WorkflowTestModule.java`
- `core-ai-api/src/main/java/ai/core/api/server/workflow/WorkflowWebService.java`
- `core-ai-frontend/src/api/client.ts`
- `core-ai-frontend/src/pages/workflows/NodeConfigPanel.tsx`
- `core-ai-frontend/src/pages/workflows/WorkflowEditor.tsx`
- `core-ai-frontend/src/pages/workflows/validation.ts`
- `core-ai-frontend/package.json`
- `core-ai-frontend/package-lock.json`
- `core-ai-server/VERSION`

---

### Task 1: Normalized Agent Names and Mongo Indexes

**Files:**
- Create: `core-ai-server/src/main/java/ai/core/server/agent/AgentNameKey.java`
- Create: `core-ai-server/src/main/java/ai/core/server/domain/migration/SchemaMigrationVAgentNameSearch.java`
- Create: `core-ai-server/src/test/java/ai/core/server/agent/AgentNameKeyTest.java`
- Create: `core-ai-server/src/test/java/ai/core/server/domain/migration/SchemaMigrationVAgentNameSearchTest.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/domain/AgentDefinition.java:20-32`
- Modify: `core-ai-server/src/main/java/ai/core/server/agent/AgentDefinitionService.java:51-80,225-275`
- Modify: `core-ai-server/src/main/java/ai/core/server/domain/migration/SchemaMigrationManager.java:103-109`

**Interfaces:**
- Produces: `AgentNameKey.normalize(String): String`.
- Produces: persisted `agents.name_key` maintained on create/update and indexed for subsequent option queries.
- Consumes: no feature-specific earlier task.

- [ ] **Step 1: Write failing normalization and migration tests**

```java
class AgentNameKeyTest {
    @Test
    void normalizesWithRootLocaleAndTrim() {
        assertEquals("review assistant", AgentNameKey.normalize("  Review Assistant  "));
        assertEquals("i", AgentNameKey.normalize("I"));
        assertEquals("", AgentNameKey.normalize(null));
    }
}
```

```java
class SchemaMigrationVAgentNameSearchTest {
    @Test
    void backfillsNameKeyAndCreatesBothPickerIndexes() {
        var mongo = mock(Mongo.class);

        new SchemaMigrationVAgentNameSearch().migrate(mongo);

        verify(mongo).runCommand(argThat(command ->
            "agents".equals(command.getString("update"))
                && command.getList("updates", Document.class).getFirst().getBoolean("multi")));
        verify(mongo, times(2)).createIndex(eq("agents"), any(Bson.class));
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail because the new classes and field do not exist**

Run:

```bash
./gradlew :core-ai-server:test \
  --tests 'ai.core.server.agent.AgentNameKeyTest' \
  --tests 'ai.core.server.domain.migration.SchemaMigrationVAgentNameSearchTest'
```

Expected: compilation fails on `AgentNameKey` and `SchemaMigrationVAgentNameSearch`.

- [ ] **Step 3: Implement normalization, persistence, backfill, and indexes**

```java
public final class AgentNameKey {
    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private AgentNameKey() {
    }
}
```

Add to `AgentDefinition`:

```java
@Field(name = "name_key")
public String nameKey;
```

Set `entity.nameKey = AgentNameKey.normalize(entity.name)` in `create`, and recompute it whenever `request.name != null` in `update`.

Implement migration `20260805002` with a Mongo update pipeline equivalent to:

```javascript
db.agents.updateMany({}, [
  {$set: {name_key: {$toLower: {$trim: {input: {$ifNull: ["$name", ""]}}}}}}
])
```

Use this Java command shape so all rows are updated in one server-side operation:

```java
var normalized = new Document("$toLower", new Document("$trim",
    new Document("input", new Document("$ifNull", List.of("$name", "")))));
var pipeline = List.of(new Document("$set", new Document("name_key", normalized)));
mongo.runCommand(new Document("update", "agents")
    .append("updates", List.of(new Document("q", new Document())
        .append("u", pipeline)
        .append("multi", Boolean.TRUE))));
```

Then create these indexes:

```java
mongo.createIndex("agents", Indexes.compoundIndex(
    Indexes.ascending("user_id"), Indexes.ascending("type"),
    Indexes.ascending("name_key"), Indexes.ascending("_id")));
mongo.createIndex("agents", Indexes.compoundIndex(
    Indexes.ascending("status"), Indexes.ascending("type"),
    Indexes.ascending("name_key"), Indexes.ascending("_id")));
```

Register the migration at the end of `operationalMigrations()` so it runs after all historical default-Agent upserts.

- [ ] **Step 4: Run focused tests and server compilation**

Run:

```bash
./gradlew :core-ai-server:test \
  --tests 'ai.core.server.agent.AgentNameKeyTest' \
  --tests 'ai.core.server.domain.migration.SchemaMigrationVAgentNameSearchTest' \
  --tests 'ai.core.server.agent.AgentDefinitionServiceTest'
```

Expected: all selected tests pass; `AgentDefinitionServiceTest` confirms existing Agent mappings still compile.

- [ ] **Step 5: Commit the search foundation**

```bash
git add core-ai-server/src/main/java/ai/core/server/agent/AgentNameKey.java \
  core-ai-server/src/main/java/ai/core/server/domain/AgentDefinition.java \
  core-ai-server/src/main/java/ai/core/server/agent/AgentDefinitionService.java \
  core-ai-server/src/main/java/ai/core/server/domain/migration/SchemaMigrationVAgentNameSearch.java \
  core-ai-server/src/main/java/ai/core/server/domain/migration/SchemaMigrationManager.java \
  core-ai-server/src/test/java/ai/core/server/agent/AgentNameKeyTest.java \
  core-ai-server/src/test/java/ai/core/server/domain/migration/SchemaMigrationVAgentNameSearchTest.java
git commit -m "feat: index workflow agent names"
```

---

### Task 2: Shared Executable Agent Config Factory

**Files:**
- Create: `core-ai-server/src/main/java/ai/core/server/agent/AgentExecutableConfigFactory.java`
- Create: `core-ai-server/src/test/java/ai/core/server/agent/AgentExecutableConfigFactoryTest.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/agent/AgentDefinitionService.java:273-310`

**Interfaces:**
- Produces: `AgentExecutableConfigFactory.fromEditableDefinition(AgentDefinition): AgentPublishedConfig`.
- Produces: `AgentExecutableConfigFactory.fromPublishedConfig(AgentPublishedConfig): AgentPublishedConfig`.
- Consumes: existing `AgentDefinition`, `AgentPublishedConfig`, and `IdLists.cleanOrNull` semantics.

- [ ] **Step 1: Write a failing field-parity and deep-copy test**

```java
@Test
void editableFactoryCopiesEveryExecutableFieldWithoutAliasing() {
    var sandbox = new AgentSandboxConfig();
    sandbox.environmentVariables = new HashMap<>(Map.of("TOKEN", "original"));
    var dataset = new AgentDatasetConfig();
    dataset.datasetId = "dataset-1";
    var tool = ToolRef.of("builtin-web", ToolSourceType.BUILTIN);
    var source = new AgentDefinition();
    source.systemPrompt = "review changes";
    source.systemPromptId = "prompt-1";
    source.model = "model-1";
    source.multiModalModel = "vision-1";
    source.preferCaptionPath = Boolean.TRUE;
    source.temperature = 0.2;
    source.thinkingEffort = "high";
    source.maxTurns = 12;
    source.timeoutSeconds = 300;
    source.tools = new ArrayList<>(List.of(tool));
    source.skillIds = new ArrayList<>(List.of("skill-1"));
    source.subAgentIds = new ArrayList<>(List.of("sub-1"));
    source.inputTemplate = "{{ input }}";
    source.variables = new HashMap<>(Map.of("locale", "en"));
    source.responseSchema = "{\"type\":\"object\"}";
    source.enableMemory = Boolean.FALSE;
    source.sandboxConfig = sandbox;
    source.datasetConfig = new ArrayList<>(List.of(dataset));

    AgentPublishedConfig config = AgentExecutableConfigFactory.fromEditableDefinition(source);
    source.variables.put("locale", "changed");
    source.sandboxConfig.environmentVariables.put("TOKEN", "changed");

    assertEquals("en", config.variables.get("locale"));
    assertEquals("original", config.sandboxConfig.environmentVariables.get("TOKEN"));
    assertEquals(source.systemPrompt, config.systemPrompt);
    assertEquals(source.tools, config.tools);
    assertEquals(source.skillIds, config.skillIds);
    assertEquals(source.subAgentIds, config.subAgentIds);
    assertNotSame(source.datasetConfig, config.datasetConfig);
    assertEquals("dataset-1", config.datasetConfig.getFirst().datasetId);
    assertEquals(source.responseSchema, config.responseSchema);
    assertEquals(source.enableMemory, config.enableMemory);
}

@Test
void publishedFactoryReturnsADeepCopy() {
    AgentPublishedConfig source = AgentExecutableConfigFactory.fromEditableDefinition(new AgentDefinition());
    AgentPublishedConfig copy = AgentExecutableConfigFactory.fromPublishedConfig(source);
    assertNotSame(source, copy);
    assertEquals(JSON.toJSON(source), JSON.toJSON(copy));
}
```

- [ ] **Step 2: Run the factory test and verify it fails**

Run:

```bash
./gradlew :core-ai-server:test --tests 'ai.core.server.agent.AgentExecutableConfigFactoryTest'
```

Expected: compilation fails because `AgentExecutableConfigFactory` is absent.

- [ ] **Step 3: Implement the factory and route Agent publish through it**

The editable builder must map exactly these fields before deep-copying through `JSON`:

```java
config.systemPrompt = definition.systemPrompt;
config.systemPromptId = definition.systemPromptId;
config.model = definition.model;
config.multiModalModel = definition.multiModalModel;
config.preferCaptionPath = definition.preferCaptionPath;
config.temperature = definition.temperature;
config.thinkingEffort = definition.thinkingEffort;
config.maxTurns = definition.maxTurns;
config.timeoutSeconds = definition.timeoutSeconds;
config.tools = definition.tools;
config.skillIds = IdLists.cleanOrNull(definition.skillIds);
config.subAgentIds = IdLists.cleanOrNull(definition.subAgentIds);
config.inputTemplate = definition.inputTemplate;
config.variables = definition.variables;
config.responseSchema = definition.responseSchema;
config.enableMemory = definition.enableMemory;
config.sandboxConfig = definition.sandboxConfig;
config.datasetConfig = definition.datasetConfig;
```

Both public methods return `JSON.fromJSON(AgentPublishedConfig.class, JSON.toJSON(config))`; `fromPublishedConfig(null)` throws `IllegalArgumentException("published agent config is missing")`. Replace the manual mapping in `AgentDefinitionService.publish` with `AgentExecutableConfigFactory.fromEditableDefinition(entity)`.

- [ ] **Step 4: Run factory, publish, and snapshot round-trip tests**

Run:

```bash
./gradlew :core-ai-server:test \
  --tests 'ai.core.server.agent.AgentExecutableConfigFactoryTest' \
  --tests 'ai.core.server.agent.AgentDefinitionServiceTest' \
  --tests 'ai.core.server.workflow.AgentSnapshotRoundTripTest'
```

Expected: all selected tests pass and the existing nested snapshot round-trip remains intact.

- [ ] **Step 5: Commit the shared mapping**

```bash
git add core-ai-server/src/main/java/ai/core/server/agent/AgentExecutableConfigFactory.java \
  core-ai-server/src/main/java/ai/core/server/agent/AgentDefinitionService.java \
  core-ai-server/src/test/java/ai/core/server/agent/AgentExecutableConfigFactoryTest.java
git commit -m "refactor: centralize executable agent config"
```

---

### Task 3: Secure Workflow Agent Options API

**Files:**
- Create: `core-ai-api/src/main/java/ai/core/api/server/workflow/ListWorkflowAgentOptionsRequest.java`
- Create: `core-ai-api/src/main/java/ai/core/api/server/workflow/ListWorkflowAgentOptionsResponse.java`
- Create: `core-ai-api/src/main/java/ai/core/api/server/workflow/WorkflowAgentOptionView.java`
- Create: `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentAccessPolicy.java`
- Create: `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentOptionService.java`
- Create: `core-ai-server/src/test/java/ai/core/server/workflow/WorkflowAgentOptionServiceTest.java`
- Modify: `core-ai-api/src/main/java/ai/core/api/server/workflow/WorkflowWebService.java:15-25`
- Modify: `core-ai-server/src/main/java/ai/core/server/web/WorkflowWebServiceImpl.java:58-90`
- Modify: `core-ai-server/src/main/java/ai/core/server/WorkflowModule.java:31-78`
- Modify: `core-ai-server/src/test/java/ai/core/server/workflow/WorkflowTestModule.java:34-88`

**Interfaces:**
- Produces: `GET /api/workflows/agent-options?scope=mine|shared&type=AGENT|LLM_CALL&query=&page=1&limit=20&selected_id=`.
- Produces: `WorkflowAgentAccessPolicy.canReference(AgentDefinition, String): boolean` and `WorkflowAgentAccessPolicy.isOwnedEditable(AgentDefinition, String): boolean`.
- Consumes: `AgentNameKey.normalize` and the two Task 1 indexes.

- [ ] **Step 1: Write failing filter, sort, paging, and selected-option tests**

Test the package-visible builders and minimal DTO mapping:

```java
@Test
void mineFilterAllowsOwnedDraftsAndExcludesSystemDefaults() {
    BsonDocument filter = WorkflowAgentOptionService
        .filter("mine", DefinitionType.AGENT, "rev", "user-1")
        .toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    String json = filter.toJson();
    assertTrue(json.contains("user_id"));
    assertTrue(json.contains("user-1"));
    assertTrue(json.contains("system_default"));
    assertTrue(json.contains("name_key"));
}

@Test
void sharedFilterRequiresPublishedConfigAndExcludesOwnedNonSystemAgents() {
    BsonDocument filter = WorkflowAgentOptionService
        .filter("shared", DefinitionType.LLM_CALL, "", "user-1")
        .toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    String json = filter.toJson();
    assertTrue(json.contains("PUBLISHED"));
    assertTrue(json.contains("published_config"));
    assertTrue(json.contains("user-1"));
}

@Test
void selectedResolutionDoesNotReturnInaccessibleDraft() {
    var collection = mock(MongoCollection.class);
    var service = new WorkflowAgentOptionService();
    service.agentDefinitionCollection = collection;
    var privateAgent = new AgentDefinition();
    privateAgent.id = "private-agent";
    privateAgent.userId = "other";
    privateAgent.type = DefinitionType.AGENT;
    privateAgent.status = AgentStatus.DRAFT;
    when(collection.get("private-agent")).thenReturn(Optional.of(privateAgent));
    when(collection.find(any(Query.class))).thenReturn(List.of());
    when(collection.count(any(Bson.class))).thenReturn(0L);
    var request = new ListWorkflowAgentOptionsRequest();
    request.scope = "mine";
    request.type = "AGENT";
    request.selectedId = "private-agent";

    var response = service.list("caller", request);

    assertNull(response.selected);
}

@Test
void responseContainsOnlyPickerFields() {
    var entity = new AgentDefinition();
    entity.id = "a1";
    entity.userId = "caller";
    entity.name = "Safe Name";
    entity.type = DefinitionType.AGENT;
    entity.status = AgentStatus.DRAFT;
    entity.systemPrompt = "must not leave the server";
    WorkflowAgentOptionView view = WorkflowAgentOptionService.toView(entity, "caller");
    String json = JSON.toJSON(view);
    assertTrue(json.contains("Safe Name"));
    assertFalse(json.contains("system_prompt"));
    assertFalse(json.contains("must not leave the server"));
    assertFalse(json.contains("published_config"));
}
```

Capture the page-2 `Query` with this test:

```java
@Test
void pageTwoUsesStableNameOrdering() {
    @SuppressWarnings("unchecked")
    MongoCollection<AgentDefinition> collection = mock(MongoCollection.class);
    var service = new WorkflowAgentOptionService();
    service.agentDefinitionCollection = collection;
    when(collection.find(any(Query.class))).thenReturn(List.of());
    when(collection.count(any(Bson.class))).thenReturn(0L);
    var request = new ListWorkflowAgentOptionsRequest();
    request.scope = "mine";
    request.type = "AGENT";
    request.page = 2;
    request.limit = 20;

    service.list("user-1", request);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(collection).find(query.capture());
    assertEquals(20, query.getValue().skip);
    assertEquals(20, query.getValue().limit);
    String sort = query.getValue().sort.toBsonDocument(
        BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
    assertTrue(sort.indexOf("name_key") < sort.indexOf("_id"));
}
```

- [ ] **Step 2: Run the service test and verify it fails**

Run:

```bash
./gradlew :core-ai-server:test --tests 'ai.core.server.workflow.WorkflowAgentOptionServiceTest'
```

Expected: compilation fails on the missing request/view/service classes.

- [ ] **Step 3: Add API DTOs and the access policy**

Use Strings for view state so Mongo enums are never reused in API DTOs:

```java
public class WorkflowAgentOptionView {
    @Property(name = "id") public String id;
    @Property(name = "name") public String name;
    @Property(name = "type") public String type;
    @Property(name = "status") public String status;
    @Property(name = "ownership") public String ownership;
    @Property(name = "updated_at") public ZonedDateTime updatedAt;
}
```

```java
public class ListWorkflowAgentOptionsRequest {
    @QueryParam(name = "scope") public String scope;
    @QueryParam(name = "type") public String type;
    @QueryParam(name = "query") public String query;
    @QueryParam(name = "page") public Integer page;
    @QueryParam(name = "limit") public Integer limit;
    @QueryParam(name = "selected_id") public String selectedId;
}
```

`ListWorkflowAgentOptionsResponse` contains `List<WorkflowAgentOptionView> items`, optional `WorkflowAgentOptionView selected`, and non-null `Long total`, `Integer page`, `Integer limit`.

Access policy rules:

```java
static boolean isOwnedEditable(AgentDefinition agent, String userId) {
    return !Boolean.TRUE.equals(agent.systemDefault) && userId.equals(agent.userId);
}

static boolean hasUsablePublishedConfig(AgentDefinition agent) {
    return agent.status == AgentStatus.PUBLISHED && agent.publishedConfig != null;
}

static boolean canReference(AgentDefinition agent, String userId) {
    return isOwnedEditable(agent, userId) || hasUsablePublishedConfig(agent);
}
```

- [ ] **Step 4: Implement the service and WebService route**

Validate scope and type with 400 responses, clamp limit to `1..50`, and construct queries as follows:

```java
mine = and(eq("user_id", userId), ne("system_default", true), eq("type", type));
shared = and(eq("status", PUBLISHED), exists("published_config", true), ne("published_config", null),
    eq("type", type), or(eq("system_default", true), ne("user_id", userId)));
prefix = AgentNameKey.normalize(request.query);
filter = prefix.isEmpty() ? scopeFilter : and(scopeFilter,
    regex("name_key", Pattern.compile("^" + Pattern.quote(prefix))));
query.sort = Sorts.ascending("name_key", "_id");
```

Resolve `selected_id` independently of the current tab, but return it only when `canReference` is true and its type matches. This lets the client distinguish an off-page selection from an unavailable one without revealing inaccessible config.

Map ownership exactly as follows:

```java
view.ownership = Boolean.TRUE.equals(agent.systemDefault)
    ? "SYSTEM"
    : userId.equals(agent.userId) ? "MINE" : "SHARED";
view.status = agent.status.name();
view.type = agent.type.name();
```

Add to `WorkflowWebService`:

```java
@GET
@Path("/api/workflows/agent-options")
ListWorkflowAgentOptionsResponse agentOptions(ListWorkflowAgentOptionsRequest request);
```

`WorkflowWebServiceImpl.agentOptions` obtains `AuthContext.userId(webContext)` and delegates. Bind `WorkflowAgentOptionService` before binding the WebService in production and test modules. Add Agent picker indexes to `WorkflowTestModule` startup hooks for notablescan-enabled integration tests.

- [ ] **Step 5: Run API-focused tests and compilation**

Run:

```bash
./gradlew :core-ai-server:test --tests 'ai.core.server.workflow.WorkflowAgentOptionServiceTest'
./gradlew :core-ai-api:compileJava :core-ai-server:compileJava
```

Expected: tests pass; API and server compile with the new route.

- [ ] **Step 6: Commit the options API**

```bash
git add core-ai-api/src/main/java/ai/core/api/server/workflow/ListWorkflowAgentOptionsRequest.java \
  core-ai-api/src/main/java/ai/core/api/server/workflow/ListWorkflowAgentOptionsResponse.java \
  core-ai-api/src/main/java/ai/core/api/server/workflow/WorkflowAgentOptionView.java \
  core-ai-api/src/main/java/ai/core/api/server/workflow/WorkflowWebService.java \
  core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentAccessPolicy.java \
  core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentOptionService.java \
  core-ai-server/src/main/java/ai/core/server/web/WorkflowWebServiceImpl.java \
  core-ai-server/src/main/java/ai/core/server/WorkflowModule.java \
  core-ai-server/src/test/java/ai/core/server/workflow/WorkflowAgentOptionServiceTest.java \
  core-ai-server/src/test/java/ai/core/server/workflow/WorkflowTestModule.java
git commit -m "feat: add workflow agent options api"
```

---

### Task 4: Capture Owned Draft Agent Snapshots and Provenance

**Files:**
- Create: `core-ai-server/src/main/java/ai/core/server/domain/AgentSnapshotSource.java`
- Create: `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentSnapshotService.java`
- Create: `core-ai-server/src/test/java/ai/core/server/workflow/WorkflowAgentSnapshotServiceTest.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/domain/WorkflowPublishedVersion.java:47-54`
- Modify: `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPublishService.java:35-360`
- Modify: `core-ai-server/src/main/java/ai/core/server/WorkflowModule.java:55-78`
- Modify: `core-ai-server/src/test/java/ai/core/server/workflow/WorkflowTestModule.java:50-70`
- Modify: `core-ai-server/src/test/java/ai/core/server/workflow/WorkflowDiscoverCloneTest.java`

**Interfaces:**
- Produces: `WorkflowAgentSnapshotService.capture(WorkflowGraph, String, List<String>, Map<String,String>, Map<String,String>)`.
- Produces: optional `WorkflowPublishedVersion.agentSnapshotSources: Map<String,String>` where each value is serialized `AgentSnapshotSource`.
- Consumes: `AgentExecutableConfigFactory` and `WorkflowAgentAccessPolicy`.

- [ ] **Step 1: Write failing capture tests for each permission/config source**

Use mocked `MongoCollection<AgentDefinition>` for focused cases:

```java
@SuppressWarnings("unchecked")
private final MongoCollection<AgentDefinition> collection = mock(MongoCollection.class);
private final WorkflowAgentSnapshotService service = new WorkflowAgentSnapshotService();

@BeforeEach
void setUp() {
    service.agentDefinitionCollection = collection;
}

@Test
void ownedDraftCapturesCurrentEditableConfig() {
    var agent = new AgentDefinition();
    agent.id = "a1";
    agent.userId = "owner";
    agent.status = AgentStatus.DRAFT;
    agent.type = DefinitionType.AGENT;
    agent.systemPrompt = "current editable";
    when(collection.get("a1")).thenReturn(Optional.of(agent));
    WorkflowGraph graph = WorkflowGraphParser.parse("""
        {"nodes":[{"id":"n1","type":"AGENT","config":{"agent_id":"a1"}}],"edges":[]}
        """);
    var errors = new ArrayList<String>();
    var snapshots = new LinkedHashMap<String, String>();
    var sources = new LinkedHashMap<String, String>();

    service.capture(graph, "owner", errors, snapshots, sources);

    assertTrue(errors.isEmpty());
    assertEquals("current editable", JSON.fromJSON(AgentPublishedConfig.class, snapshots.get("n1")).systemPrompt);
    assertEquals("OWNED_EDITABLE", JSON.fromJSON(AgentSnapshotSource.class, sources.get("n1")).sourceKind);
}

@Test
void otherUsersPublishedAgentCapturesPublishedConfig() {
    var agent = new AgentDefinition();
    agent.id = "a2";
    agent.userId = "other";
    agent.status = AgentStatus.PUBLISHED;
    agent.type = DefinitionType.LLM_CALL;
    agent.systemPrompt = "unpublished edit";
    agent.publishedConfig = new AgentPublishedConfig();
    agent.publishedConfig.systemPrompt = "published value";
    when(collection.get("a2")).thenReturn(Optional.of(agent));
    WorkflowGraph graph = WorkflowGraphParser.parse("""
        {"nodes":[{"id":"n2","type":"LLM","config":{"agent_id":"a2"}}],"edges":[]}
        """);
    var errors = new ArrayList<String>();
    var snapshots = new LinkedHashMap<String, String>();
    var sources = new LinkedHashMap<String, String>();

    service.capture(graph, "owner", errors, snapshots, sources);

    assertEquals("published value", JSON.fromJSON(AgentPublishedConfig.class, snapshots.get("n2")).systemPrompt);
    assertEquals("PUBLISHED", JSON.fromJSON(AgentSnapshotSource.class, sources.get("n2")).sourceKind);
}

@Test
void ownedPublishedAgentStillCapturesCurrentEditableConfig() {
    var agent = new AgentDefinition();
    agent.id = "a3";
    agent.userId = "owner";
    agent.status = AgentStatus.PUBLISHED;
    agent.type = DefinitionType.AGENT;
    agent.systemPrompt = "new editable value";
    agent.publishedConfig = new AgentPublishedConfig();
    agent.publishedConfig.systemPrompt = "old public value";
    when(collection.get("a3")).thenReturn(Optional.of(agent));
    WorkflowGraph graph = WorkflowGraphParser.parse("""
        {"nodes":[{"id":"n3","type":"AGENT","config":{"agent_id":"a3"}}],"edges":[]}
        """);
    var errors = new ArrayList<String>();
    var snapshots = new LinkedHashMap<String, String>();
    var sources = new LinkedHashMap<String, String>();

    service.capture(graph, "owner", errors, snapshots, sources);

    assertEquals("new editable value", JSON.fromJSON(AgentPublishedConfig.class, snapshots.get("n3")).systemPrompt);
    assertEquals("OWNED_EDITABLE", JSON.fromJSON(AgentSnapshotSource.class, sources.get("n3")).sourceKind);
}
```

Use the same local fixture for the rejection cases:

```java
@Test
void rejectsOtherDraftMissingAgentAndTypeMismatch() {
    assertEquals("node n1 references an agent/LLM definition you cannot access: other-draft",
        captureError(agent("other-draft", "other", AgentStatus.DRAFT, DefinitionType.AGENT), "AGENT"));
    assertEquals("node n1 references an unknown agent/LLM definition: missing",
        captureMissingError("missing", "AGENT"));
    assertEquals("node n1 references an agent with the wrong type: wrong-type",
        captureError(agent("wrong-type", "owner", AgentStatus.DRAFT, DefinitionType.LLM_CALL), "AGENT"));
}

@Test
void ownedEditableAgentRejectsUnpublishedSubAgentDependency() {
    var top = agent("top", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
    top.subAgentIds = List.of("private-sub");
    var sub = agent("private-sub", "owner", AgentStatus.DRAFT, DefinitionType.AGENT);
    when(collection.get("top")).thenReturn(Optional.of(top));
    when(collection.get("private-sub")).thenReturn(Optional.of(sub));

    String error = captureMissingError("top", "AGENT");

    assertEquals("node n1 references sub-agent without a usable published config: private-sub", error);
}

private AgentDefinition agent(String id, String userId, AgentStatus status, DefinitionType type) {
    var agent = new AgentDefinition();
    agent.id = id;
    agent.userId = userId;
    agent.status = status;
    agent.type = type;
    return agent;
}

private String captureError(AgentDefinition agent, String nodeType) {
    when(collection.get(agent.id)).thenReturn(Optional.of(agent));
    return captureMissingError(agent.id, nodeType);
}

private String captureMissingError(String agentId, String nodeType) {
    WorkflowGraph graph = WorkflowGraphParser.parse("{\"nodes\":[{\"id\":\"n1\",\"type\":\""
        + nodeType + "\",\"config\":{\"agent_id\":\"" + agentId + "\"}}],\"edges\":[]}");
    var errors = new ArrayList<String>();
    service.capture(graph, "owner", errors, new LinkedHashMap<>(), new LinkedHashMap<>());
    return errors.getFirst();
}
```

- [ ] **Step 2: Run the snapshot service test and verify it fails**

Run:

```bash
./gradlew :core-ai-server:test --tests 'ai.core.server.workflow.WorkflowAgentSnapshotServiceTest'
```

Expected: compilation fails because the capture service and metadata model are absent.

- [ ] **Step 3: Implement capture and metadata serialization**

`AgentSnapshotSource` contains:

```java
@Field(name = "agent_id") public String agentId;
@Field(name = "source_kind") public String sourceKind;
@Field(name = "source_updated_at") public ZonedDateTime sourceUpdatedAt;
@Field(name = "captured_at") public ZonedDateTime capturedAt;
```

Store it safely under core-ng's concrete map constraint:

```java
@Field(name = "agent_snapshot_sources")
public Map<String, String> agentSnapshotSources;
```

Capture logic:

```java
DefinitionType expectedType = "LLM".equals(node.type()) ? DefinitionType.LLM_CALL : DefinitionType.AGENT;
if (agent.type != expectedType) {
    errors.add("node " + node.id() + " references an agent with the wrong type: " + agent.id);
}
else if (WorkflowAgentAccessPolicy.isOwnedEditable(agent, ownerUserId)) {
    config = AgentExecutableConfigFactory.fromEditableDefinition(agent);
    kind = "OWNED_EDITABLE";
} else if (WorkflowAgentAccessPolicy.hasUsablePublishedConfig(agent)) {
    config = AgentExecutableConfigFactory.fromPublishedConfig(agent.publishedConfig);
    kind = "PUBLISHED";
} else {
    errors.add("node " + node.id() + " references an agent/LLM definition you cannot access: " + agent.id);
}
```

Before storing an `OWNED_EDITABLE` config, resolve every `subAgentIds` entry and require `WorkflowAgentAccessPolicy.hasUsablePublishedConfig(subAgent)`. Add `node <id> references sub-agent without a usable published config: <subAgentId>` and skip snapshot storage when any entry fails. This preserves the existing published Sub-agent runtime contract without recursively embedding private dependencies.

For a valid node, serialize both maps without mutating the source Agent:

```java
snapshots.put(node.id(), JSON.toJSON(config));
var source = new AgentSnapshotSource();
source.agentId = agent.id;
source.sourceKind = kind;
source.sourceUpdatedAt = agent.updatedAt;
source.capturedAt = ZonedDateTime.now();
sources.put(node.id(), JSON.toJSON(source));
```

- [ ] **Step 4: Integrate capture into Workflow Version creation**

Replace `WorkflowPublishService.captureAgentSnapshots` and its local access helper with the new service. `createVersion` owns two `LinkedHashMap`s, passes both through validation/capture, and persists both. `validate` still performs the same capture validation but discards the generated maps.

Keep the no-recapture invariant explicit in the integration test:

```java
var agent = new AgentDefinition();
agent.id = "private-agent-" + UUID.randomUUID();
agent.userId = "owner";
agent.name = "Private Reviewer";
agent.nameKey = AgentNameKey.normalize(agent.name);
agent.type = DefinitionType.AGENT;
agent.status = AgentStatus.DRAFT;
agent.systemPrompt = "captured before change";
agent.createdAt = ZonedDateTime.now();
agent.updatedAt = agent.createdAt;
agentCollection.insert(agent);
String graph = """
    {"nodes":[
      {"id":"start","type":"START"},
      {"id":"n1","type":"AGENT","config":{"agent_id":"AGENT_ID"}},
      {"id":"end","type":"END"}],
     "edges":[
      {"id":"e1","source":"start","target":"n1"},
      {"id":"e2","source":"n1","target":"end"}]}
    """.replace("AGENT_ID", agent.id);
WorkflowDefinition workflow = definitionService.create("private-agent-workflow", "WORKFLOW", graph, "owner");
WorkflowPublishedVersion saved = publishService.saveVersion(workflow.id, "owner");
String frozen = saved.agentSnapshots.get("n1");
agent.systemPrompt = "changed after save";
agentCollection.replace(agent);
publishService.publishVersion(workflow.id, saved.id, "owner");
assertEquals("captured before change",
    JSON.fromJSON(AgentPublishedConfig.class, saved.agentSnapshots.get("n1")).systemPrompt);
agentCollection.delete(agent.id);
publishService.publishVersion(workflow.id, saved.id, "owner");
WorkflowPublishedVersion reloaded = versionCollection.get(saved.id).orElseThrow();
assertEquals(frozen, reloaded.agentSnapshots.get("n1"));
```

- [ ] **Step 5: Run snapshot and Workflow regression tests**

Run:

```bash
./gradlew :core-ai-server:test \
  --tests 'ai.core.server.workflow.WorkflowAgentSnapshotServiceTest' \
  --tests 'ai.core.server.workflow.AgentSnapshotRoundTripTest' \
  --tests 'ai.core.server.workflow.MongoAgentRunGatewayTest' \
  --tests 'ai.core.server.workflow.WorkflowDiscoverCloneTest'
```

Expected: all available tests pass; the Mongo-gated test either passes against local Mongo or is explicitly skipped by its existing `@EnabledIf` guard.

- [ ] **Step 6: Commit immutable owned-Agent snapshots**

```bash
git add core-ai-server/src/main/java/ai/core/server/domain/AgentSnapshotSource.java \
  core-ai-server/src/main/java/ai/core/server/domain/WorkflowPublishedVersion.java \
  core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentSnapshotService.java \
  core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPublishService.java \
  core-ai-server/src/main/java/ai/core/server/WorkflowModule.java \
  core-ai-server/src/test/java/ai/core/server/workflow/WorkflowAgentSnapshotServiceTest.java \
  core-ai-server/src/test/java/ai/core/server/workflow/WorkflowDiscoverCloneTest.java \
  core-ai-server/src/test/java/ai/core/server/workflow/WorkflowTestModule.java
git commit -m "feat: snapshot owned agents in workflow versions"
```

---

### Task 5: Public Snapshot Safety and Clone/Import Isolation

**Files:**
- Create: `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPrivateAgentSafetyValidator.java`
- Create: `core-ai-server/src/test/java/ai/core/server/workflow/WorkflowPrivateAgentSafetyValidatorTest.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPublishService.java:337-355`
- Modify: `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPortService.java:40-135`
- Modify: `core-ai-server/src/main/java/ai/core/server/WorkflowModule.java`
- Modify: `core-ai-server/src/test/java/ai/core/server/workflow/WorkflowTestModule.java`
- Modify: `core-ai-server/src/test/java/ai/core/server/workflow/WorkflowDiscoverCloneTest.java`
- Modify: `core-ai-server/src/test/java/ai/core/server/workflow/WorkflowPortServiceTest.java`
- Modify: `core-ai-server/src/test/java/ai/core/server/web/WorkflowViewMapperTest.java`

**Interfaces:**
- Produces: `WorkflowPrivateAgentSafetyValidator.validate(WorkflowPublishedVersion): List<String>`.
- Consumes: `agentSnapshots`, optional `agentSnapshotSources`, and `WorkflowAgentAccessPolicy.hasUsablePublishedConfig` for published Sub-agent checks.

- [ ] **Step 1: Write failing fail-closed safety tests**

Build versions with one `OWNED_EDITABLE` source and assert exact node-scoped failures:

```java
private final WorkflowPrivateAgentSafetyValidator validator = new WorkflowPrivateAgentSafetyValidator();

@Test
void rejectsOwnerBoundResourcesFromPublicWorkflow() {
    AgentPublishedConfig config = new AgentPublishedConfig();
    config.systemPrompt = "classify the input";
    config.model = "model-1";
    config.systemPromptId = "private-system-prompt";
    config.skillIds = List.of("private-skill");
    var dataset = new AgentDatasetConfig();
    dataset.datasetId = "owner-dataset";
    config.datasetConfig = List.of(dataset);
    config.enableMemory = Boolean.TRUE;
    config.tools = List.of(ToolRef.of("private-mcp", ToolSourceType.MCP));
    config.sandboxConfig = new AgentSandboxConfig();
    config.sandboxConfig.environmentVariables = Map.of("TOKEN", "secret");
    config.sandboxConfig.gitRepoUrl = "https://private/repo.git";
    WorkflowPublishedVersion version = ownedVersion("n1", config);

    List<String> errors = validator.validate(version);

    assertTrue(errors.stream().allMatch(error -> error.startsWith("node n1")));
    assertTrue(errors.stream().anyMatch(error -> error.contains("environment variables")));
    assertTrue(errors.stream().anyMatch(error -> error.contains("dataset")));
}

@Test
void acceptsPromptModelAndBuiltinTools() {
    var config = new AgentPublishedConfig();
    config.systemPrompt = "summarize the input";
    config.model = "model-1";
    config.tools = List.of(ToolRef.of("builtin-web", ToolSourceType.BUILTIN));
    assertTrue(validator.validate(ownedVersion("n1", config)).isEmpty());
}

private WorkflowPublishedVersion ownedVersion(String nodeId, AgentPublishedConfig config) {
    var source = new AgentSnapshotSource();
    source.agentId = "private-agent";
    source.sourceKind = "OWNED_EDITABLE";
    source.capturedAt = ZonedDateTime.now();
    var version = new WorkflowPublishedVersion();
    version.agentSnapshots = Map.of(nodeId, JSON.toJSON(config));
    version.agentSnapshotSources = Map.of(nodeId, JSON.toJSON(source));
    return version;
}
```

Add the compatibility and Sub-agent tests with explicit fixtures:

```java
@Test
void skipsPublishedAndLegacySnapshots() {
    var config = new AgentPublishedConfig();
    config.skillIds = List.of("historical-skill");
    var published = ownedVersion("n1", config);
    var source = JSON.fromJSON(AgentSnapshotSource.class, published.agentSnapshotSources.get("n1"));
    source.sourceKind = "PUBLISHED";
    published.agentSnapshotSources = Map.of("n1", JSON.toJSON(source));
    assertTrue(validator.validate(published).isEmpty());

    var legacy = new WorkflowPublishedVersion();
    legacy.agentSnapshots = Map.of("n1", JSON.toJSON(config));
    assertTrue(validator.validate(legacy).isEmpty());
}

@Test
void ownedSnapshotRequiresSubAgentToBePublished() {
    @SuppressWarnings("unchecked")
    MongoCollection<AgentDefinition> collection = mock(MongoCollection.class);
    validator.agentDefinitionCollection = collection;
    var sub = new AgentDefinition();
    sub.id = "sub-1";
    sub.status = AgentStatus.DRAFT;
    when(collection.get("sub-1")).thenReturn(Optional.of(sub));
    var config = new AgentPublishedConfig();
    config.subAgentIds = List.of("sub-1");
    assertTrue(validator.validate(ownedVersion("n1", config)).getFirst().contains("sub-agent"));

    sub.status = AgentStatus.PUBLISHED;
    sub.publishedConfig = new AgentPublishedConfig();
    assertTrue(validator.validate(ownedVersion("n1", config)).isEmpty());
}
```

- [ ] **Step 2: Run validator tests and verify they fail**

Run:

```bash
./gradlew :core-ai-server:test --tests 'ai.core.server.workflow.WorkflowPrivateAgentSafetyValidatorTest'
```

Expected: compilation fails because the validator is absent.

- [ ] **Step 3: Implement the public portability rules**

For `OWNED_EDITABLE` snapshots, accept prompt/model/temperature/input template/variables/response schema and `BUILTIN` or `API` tools. Reject:

```text
systemPromptId != null/blank
skillIds not empty
datasetConfig not empty
enableMemory == true
subAgentIds whose definitions lack a usable publishedConfig
tools of type MCP or AGENT
sandboxConfig.environmentVariables not empty
sandboxConfig.gitRepoUrl not blank
```

Return one node-scoped message per category and never include stored values. Any nonblank source entry that cannot be parsed, and any `OWNED_EDITABLE` snapshot JSON that cannot be parsed, is a publish-blocking node error. A completely missing metadata map is treated as a legacy published snapshot for backward compatibility.

Implement the top-level control flow explicitly:

```java
public List<String> validate(WorkflowPublishedVersion version) {
    if (version.agentSnapshotSources == null) return List.of();
    var errors = new ArrayList<String>();
    for (var entry : version.agentSnapshotSources.entrySet()) {
        String nodeId = entry.getKey();
        AgentSnapshotSource source;
        try {
            source = JSON.fromJSON(AgentSnapshotSource.class, entry.getValue());
        } catch (RuntimeException e) {
            errors.add("node " + nodeId + " has malformed agent snapshot source metadata");
            continue;
        }
        if (source == null) {
            errors.add("node " + nodeId + " has malformed agent snapshot source metadata");
            continue;
        }
        if (!"OWNED_EDITABLE".equals(source.sourceKind)) continue;
        String snapshotJson = version.agentSnapshots == null ? null : version.agentSnapshots.get(nodeId);
        try {
            validateConfig(nodeId, JSON.fromJSON(AgentPublishedConfig.class, snapshotJson), errors);
        } catch (RuntimeException e) {
            errors.add("node " + nodeId + " has a malformed private agent snapshot");
        }
    }
    return errors;
}
```

`validateConfig` performs each category check once, rejects every ToolRef whose type is neither `BUILTIN` nor `API` (including null), and looks up every Sub-agent ID in `agentDefinitionCollection` before calling `hasUsablePublishedConfig`.

- [ ] **Step 4: Apply safety checks only at public publish time**

In `WorkflowPublishService.requireVersionReferencesPublishable`, append:

```java
errors.addAll(privateAgentSafetyValidator.validate(version));
```

Bind `WorkflowPrivateAgentSafetyValidator` before `WorkflowPublishService` in both `WorkflowModule` and `WorkflowTestModule`. Do not call this validator from `validate`, `createPreviewVersion`, or `saveVersion`; private testing and saved private Versions continue to use the owner's own authorization. Direct Publish still reaches the gate because it saves a Version and then calls `publishVersion`.

- [ ] **Step 5: Align clone/import reference checks without copying snapshots**

Replace `WorkflowPortService`'s owner-only helper with `WorkflowAgentAccessPolicy.canReference`. An own DRAFT and another user's PUBLISHED Agent resolve; another user's DRAFT produces:

```text
Private embedded agent is not available — choose a replacement
```

Keep export limited to graph/metadata. Use these integration assertions after publishing a safe Workflow whose source Agent remains DRAFT and cloning it as another user:

```java
var privateAgent = new AgentDefinition();
privateAgent.id = "clone-private-agent-" + UUID.randomUUID();
privateAgent.userId = "owner";
privateAgent.name = "Clone Private Agent";
privateAgent.nameKey = AgentNameKey.normalize(privateAgent.name);
privateAgent.type = DefinitionType.AGENT;
privateAgent.status = AgentStatus.DRAFT;
privateAgent.systemPrompt = "private prompt value";
privateAgent.createdAt = ZonedDateTime.now();
privateAgent.updatedAt = privateAgent.createdAt;
agentCollection.insert(privateAgent);
String graph = """
    {"nodes":[
      {"id":"start","type":"START"},
      {"id":"n1","type":"AGENT","config":{"agent_id":"AGENT_ID"}},
      {"id":"end","type":"END"}],
     "edges":[
      {"id":"e1","source":"start","target":"n1"},
      {"id":"e2","source":"n1","target":"end"}]}
    """.replace("AGENT_ID", privateAgent.id);
WorkflowDefinition source = definitionService.create("clone-private-source", "WORKFLOW", graph, "owner");
publishService.publish(source.id, "owner");

assertEquals(AgentStatus.DRAFT, agentCollection.get(privateAgent.id).orElseThrow().status);
WorkflowDefinition copy = definitionService.clone(source.id, "viewer");
List<String> warnings = publishService.validate(copy);
assertTrue(warnings.stream().anyMatch(message -> message.contains("node n1")));
assertTrue(warnings.stream().anyMatch(message -> message.contains("choose a replacement")));
assertTrue(warnings.stream().noneMatch(message -> message.contains("private prompt value")));
String exported = JSON.toJSON(portService.export(source.id, "owner"));
assertFalse(exported.contains("agent_snapshots"));
assertFalse(exported.contains("private prompt value"));
```

In `WorkflowViewMapperTest`, verify Version views remain metadata-only:

```java
@Test
void versionViewDoesNotExposeAgentSnapshots() {
    var version = new WorkflowPublishedVersion();
    version.id = "wf:v1";
    version.agentSnapshots = Map.of("n1", "private prompt value");
    version.agentSnapshotSources = Map.of("n1", "private source metadata");

    String json = JSON.toJSON(WorkflowViewMapper.toVersionView(version, null, false));

    assertFalse(json.contains("agent_snapshots"));
    assertFalse(json.contains("private prompt value"));
    assertFalse(json.contains("private source metadata"));
}
```

- [ ] **Step 6: Run safety, clone, port, and compatibility tests**

Run:

```bash
./gradlew :core-ai-server:test \
  --tests 'ai.core.server.workflow.WorkflowPrivateAgentSafetyValidatorTest' \
  --tests 'ai.core.server.workflow.WorkflowDiscoverCloneTest' \
  --tests 'ai.core.server.workflow.WorkflowPortServiceTest' \
  --tests 'ai.core.server.web.WorkflowViewMapperTest'
```

Expected: all available tests pass; private config values never appear in response-oriented assertions.

- [ ] **Step 7: Commit the public safety boundary**

```bash
git add core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPrivateAgentSafetyValidator.java \
  core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPublishService.java \
  core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPortService.java \
  core-ai-server/src/main/java/ai/core/server/WorkflowModule.java \
  core-ai-server/src/test/java/ai/core/server/workflow/WorkflowTestModule.java \
  core-ai-server/src/test/java/ai/core/server/workflow/WorkflowPrivateAgentSafetyValidatorTest.java \
  core-ai-server/src/test/java/ai/core/server/workflow/WorkflowDiscoverCloneTest.java \
  core-ai-server/src/test/java/ai/core/server/workflow/WorkflowPortServiceTest.java \
  core-ai-server/src/test/java/ai/core/server/web/WorkflowViewMapperTest.java
git commit -m "feat: protect private agents in public workflows"
```

---

### Task 6: Tested Searchable Agent Picker

**Files:**
- Create: `core-ai-frontend/src/pages/workflows/AgentPicker.tsx`
- Create: `core-ai-frontend/src/pages/workflows/AgentPicker.test.tsx`
- Create: `core-ai-frontend/src/test/setup.ts`
- Create: `core-ai-frontend/vitest.config.ts`
- Modify: `core-ai-frontend/src/api/client.ts:316-390,1333-1400`
- Modify: `core-ai-frontend/package.json:6-40`
- Modify: `core-ai-frontend/package-lock.json`

**Interfaces:**
- Produces: frontend `WorkflowAgentOption` and `ListWorkflowAgentOptionsResponse` types.
- Produces: `api.workflows.agentOptions(scope, type, query, page, limit, selectedId)`.
- Produces: `<AgentPicker value selectedName type onChange />`.
- Consumes: Task 3's API contract.

- [ ] **Step 1: Add the frontend test harness**

Run:

```bash
cd core-ai-frontend
npm install --save-dev vitest jsdom @testing-library/react @testing-library/user-event
```

Add script:

```json
"test": "vitest run"
```

Create `vitest.config.ts`:

```ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: { environment: 'jsdom', setupFiles: './src/test/setup.ts' },
});
```

Create setup:

```ts
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});
```

- [ ] **Step 2: Write failing picker behavior tests**

Mock `api.workflows.agentOptions` and cover the required behavior:

```tsx
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { vi } from 'vitest';
import { api, type ListWorkflowAgentOptionsResponse, type WorkflowAgentOption } from '../../api/client';
import AgentPicker from './AgentPicker';

it('loads mine first, shows status, and selects a draft agent', async () => {
  vi.spyOn(api.workflows, 'agentOptions').mockResolvedValue({
    items: [{
      id: 'draft-1', name: 'Alpha Reviewer', type: 'AGENT',
      status: 'DRAFT', ownership: 'MINE',
    }],
    selected: undefined, total: 1, page: 1, limit: 20,
  });
  const onChange = vi.fn();
  render(<AgentPicker value="" type="AGENT" onChange={onChange} />);
  await userEvent.click(screen.getByRole('button', { name: /select agent/i }));
  await userEvent.click(await screen.findByRole('button', { name: /alpha reviewer.*draft/i }));
  expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ id: 'draft-1' }));
});
```

The following tests cover Shared requests, 250ms debounce, stale-response isolation, page-2 append, inaccessible selected IDs, and retry behavior.

Use deferred promises for the stale-response case so ordering is deterministic:

```tsx
it('ignores an older search response and keeps the newest results', async () => {
  vi.useFakeTimers();
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  const oldRequest = deferred<ListWorkflowAgentOptionsResponse>();
  const newRequest = deferred<ListWorkflowAgentOptionsResponse>();
  vi.spyOn(api.workflows, 'agentOptions')
    .mockResolvedValueOnce(emptyPage())
    .mockReturnValueOnce(oldRequest.promise)
    .mockReturnValueOnce(newRequest.promise);
  render(<AgentPicker value="" type="AGENT" onChange={vi.fn()} />);
  await user.click(screen.getByRole('button', { name: /select agent/i }));
  const search = screen.getByRole('textbox', { name: /search agents/i });
  await user.type(search, 'old');
  await vi.advanceTimersByTimeAsync(250);
  await user.clear(search);
  await user.type(search, 'new');
  await vi.advanceTimersByTimeAsync(250);
  await act(async () => {
    newRequest.resolve(page(option('new-id', 'New Result')));
    oldRequest.resolve(page(option('old-id', 'Old Result')));
    await Promise.resolve();
  });
  expect(screen.getByText('New Result')).toBeTruthy();
  expect(screen.queryByText('Old Result')).toBeNull();
  vi.useRealTimers();
});

it('switches to shared, marks inaccessible selections, and retries failures', async () => {
  const request = vi.spyOn(api.workflows, 'agentOptions')
    .mockRejectedValueOnce(new Error('network down'))
    .mockResolvedValue(emptyPage());
  render(<AgentPicker value="missing-id" selectedName="Old Agent" type="AGENT" onChange={vi.fn()} />);
  await userEvent.click(screen.getByRole('button', { name: /old agent/i }));
  expect(await screen.findByText('network down')).toBeTruthy();
  await userEvent.click(screen.getByRole('button', { name: /retry/i }));
  expect(await screen.findByText(/unavailable.*replace this agent/i)).toBeTruthy();
  await userEvent.click(screen.getByRole('button', { name: /shared agents/i }));
  expect(request).toHaveBeenLastCalledWith('shared', 'AGENT', '', 1, 20, 'missing-id');
});

it('loads page two when the result list reaches its scroll boundary', async () => {
  const request = vi.spyOn(api.workflows, 'agentOptions')
    .mockResolvedValueOnce({ ...page(option('a1', 'Agent 1')), total: 21 })
    .mockResolvedValueOnce({ ...page(option('a2', 'Agent 2')), page: 2, total: 21 });
  render(<AgentPicker value="" type="AGENT" onChange={vi.fn()} />);
  await userEvent.click(screen.getByRole('button', { name: /select agent/i }));
  const list = await screen.findByRole('listbox', { name: /agent results/i });
  Object.defineProperties(list, {
    scrollTop: { value: 180, configurable: true },
    clientHeight: { value: 100, configurable: true },
    scrollHeight: { value: 280, configurable: true },
  });
  fireEvent.scroll(list);
  await waitFor(() => expect(request).toHaveBeenLastCalledWith('mine', 'AGENT', '', 2, 20, undefined));
  expect(await screen.findByText('Agent 2')).toBeTruthy();
});
```

Define the test helpers in the same file:

```ts
function option(id: string, name: string): WorkflowAgentOption {
  return { id, name, type: 'AGENT', status: 'DRAFT', ownership: 'MINE' };
}
function page(...items: WorkflowAgentOption[]): ListWorkflowAgentOptionsResponse {
  return { items, total: items.length, page: 1, limit: 20 };
}
function emptyPage(): ListWorkflowAgentOptionsResponse { return page(); }
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
```

- [ ] **Step 3: Run the picker test and verify it fails**

Run:

```bash
cd core-ai-frontend
npm test -- AgentPicker.test.tsx
```

Expected: compilation fails because `AgentPicker` and `agentOptions` are absent.

- [ ] **Step 4: Add frontend API types and method**

```ts
export interface WorkflowAgentOption {
  id: string;
  name: string;
  type: 'AGENT' | 'LLM_CALL';
  status: 'DRAFT' | 'PUBLISHED';
  ownership: 'MINE' | 'SHARED' | 'SYSTEM';
  updated_at?: string;
}

export interface ListWorkflowAgentOptionsResponse {
  items: WorkflowAgentOption[];
  selected?: WorkflowAgentOption;
  total: number;
  page: number;
  limit: number;
}
```

Build query parameters with `URLSearchParams`, always sending `scope`, `type`, `page`, and `limit`, and optionally sending trimmed `query` and `selected_id`.

```ts
agentOptions: (scope: 'mine' | 'shared', type: 'AGENT' | 'LLM_CALL', query = '', page = 1, limit = 20, selectedId?: string) => {
  const params = new URLSearchParams({ scope, type, page: String(page), limit: String(limit) });
  if (query.trim()) params.set('query', query.trim());
  if (selectedId) params.set('selected_id', selectedId);
  return request<ListWorkflowAgentOptionsResponse>(`/api/workflows/agent-options?${params}`);
},
```

- [ ] **Step 5: Implement the picker state machine and accessible UI**

Use these stable props:

```ts
interface AgentPickerProps {
  value: string;
  selectedName?: string;
  type: 'AGENT' | 'LLM_CALL';
  onChange: (option: WorkflowAgentOption) => void;
}
```

Implementation requirements:

```text
open false by default; first open loads scope=mine, page=1
scope switch resets query/page/items/error
query change waits 250ms and resets to page 1
effect cleanup marks the prior request cancelled
page > 1 appends de-duplicated IDs; page 1 replaces
scroll within 24px of bottom loads the next page when items.length < total
selected response is pinned above results and removed from duplicate rows
value != empty + selected check completed + selected missing shows Unavailable — replace this agent
network error keeps the current value and renders a Retry button
outside pointer closes the popup without clearing the selected value
```

Use a cancelled-effect guard and ID de-duplication rather than modifying the shared API client to expose `AbortSignal`:

```tsx
const [scope, setScope] = useState<'mine' | 'shared'>('mine');
const [query, setQuery] = useState('');
const [page, setPage] = useState(1);
const [items, setItems] = useState<WorkflowAgentOption[]>([]);
const [total, setTotal] = useState(0);
const [selected, setSelected] = useState<WorkflowAgentOption>();
const [selectedChecked, setSelectedChecked] = useState(false);
const [error, setError] = useState('');
const [retryToken, setRetryToken] = useState(0);

useEffect(() => {
  if (!open) return;
  let cancelled = false;
  const timer = window.setTimeout(() => {
    api.workflows.agentOptions(scope, type, query.trim(), page, 20, value || undefined)
      .then((response) => {
        if (cancelled) return;
        setError('');
        setTotal(response.total);
        setSelected(response.selected);
        setSelectedChecked(true);
        setItems((previous) => {
          const incoming = page === 1 ? response.items : [...previous, ...response.items];
          return [...new Map(incoming.map((item) => [item.id, item])).values()];
        });
      })
      .catch((cause: Error) => { if (!cancelled) setError(cause.message); });
  }, query.trim() ? 250 : 0);
  return () => { cancelled = true; window.clearTimeout(timer); };
}, [open, page, query, retryToken, scope, type, value]);

const onResultsScroll = (event: React.UIEvent<HTMLDivElement>) => {
  const element = event.currentTarget;
  if (element.scrollHeight - element.scrollTop - element.clientHeight <= 24 && items.length < total) {
    setPage((current) => current + 1);
  }
};
```

`switchScope` and `changeQuery` must synchronously set page to 1, clear items/total/error, and reset `selectedChecked`; Retry increments `retryToken`. Render the pinned selected row before `items.filter(item => item.id !== selected?.id)`.

Use buttons and ARIA labels for tabs, options, retry, and the trigger so tests do not depend on CSS class names.

- [ ] **Step 6: Run picker tests, TypeScript build, and lint**

Run:

```bash
cd core-ai-frontend
npm test -- AgentPicker.test.tsx
npm run build
npm run lint
```

Expected: picker tests pass; TypeScript, Vite, and ESLint report no errors.

- [ ] **Step 7: Commit the reusable picker**

```bash
git add core-ai-frontend/package.json core-ai-frontend/package-lock.json \
  core-ai-frontend/vitest.config.ts core-ai-frontend/src/test/setup.ts \
  core-ai-frontend/src/api/client.ts \
  core-ai-frontend/src/pages/workflows/AgentPicker.tsx \
  core-ai-frontend/src/pages/workflows/AgentPicker.test.tsx
git commit -m "feat: add searchable workflow agent picker"
```

---

### Task 7: Wire the Picker and Surface Node-Scoped Validation

**Files:**
- Create: `core-ai-frontend/src/pages/workflows/validationErrors.test.ts`
- Modify: `core-ai-frontend/src/pages/workflows/validation.ts`
- Modify: `core-ai-frontend/src/pages/workflows/NodeConfigPanel.tsx:1-160`
- Modify: `core-ai-frontend/src/pages/workflows/WorkflowEditor.tsx:50-155,385-530,688-702`

**Interfaces:**
- Produces: `groupNodeErrors(errors: string[]): Record<string,string[]>` and `firstNodeErrorId(errors): string | undefined`.
- Consumes: Task 6 `<AgentPicker>` and existing `nodeIssues` validation.

- [ ] **Step 1: Write failing node-error grouping tests**

```ts
it('groups node-prefixed server errors and returns the first node', () => {
  const errors = [
    'node agent_1 references an unavailable agent',
    'node agent_1 contains private environment variables',
    'workflow must have an END node',
  ];
  expect(firstNodeErrorId(errors)).toBe('agent_1');
  expect(groupNodeErrors(errors)).toEqual({
    agent_1: [errors[0], errors[1]],
  });
});
```

- [ ] **Step 2: Run the helper test and verify it fails**

Run:

```bash
cd core-ai-frontend
npm test -- validationErrors.test.ts
```

Expected: imports fail because the helper functions do not exist.

- [ ] **Step 3: Implement error grouping and replace the old select**

In `validation.ts` use `^node\s+([^\s]+)\b` and ignore non-node errors.

In `NodeConfigPanel`:

```tsx
<AgentPicker
  value={String(config.agent_id ?? '')}
  selectedName={String(config.agent_name ?? '') || undefined}
  type={node.data.nodeType === 'LLM' ? 'LLM_CALL' : 'AGENT'}
  onChange={(agent) => onConfig({ agent_id: agent.id, agent_name: agent.name })}
/>
```

Remove `AgentOption`, the `agents` prop, `visibleAgents`, and the native `<select>`. Add optional `externalIssues?: string[]` and merge it with `nodeIssues` for the existing issue box.

- [ ] **Step 4: Remove full-catalog loading from WorkflowEditor**

Delete the `agents` state declaration and the complete mount effect that calls `Promise.all([api.agents.list(true), api.agents.list(false)])`. Remove `agents={agents}` from `NodeConfigPanel`. This is the acceptance guard that ensures the editor no longer downloads or client-filters the global Agent list.

- [ ] **Step 5: Focus failed nodes on Save, Test, Publish, clone warnings, and import warnings**

Maintain:

```ts
const [serverNodeErrors, setServerNodeErrors] = useState<Record<string, string[]>>({});

const applyNodeErrors = (errors: string[]) => {
  setServerNodeErrors(groupNodeErrors(errors));
  const first = firstNodeErrorId(errors);
  if (first && nodes.some((node) => node.id === first)) setSelectedId(first);
};
```

Call it for `validate` responses before Save/Test and for publish errors by removing the existing `workflow validation failed: ` prefix and splitting the remaining semicolon-delimited messages. Convert router warnings before applying them:

```ts
if (importNotice?.length) {
  applyNodeErrors(importNotice.map((ref) => `node ${ref.node_id} ${ref.message}`));
} else if (cloneWarnings?.length) {
  applyNodeErrors(cloneWarnings);
}
```

Clear a node's external errors when its config changes, and clear all after successful validation/save/publish. Pass `serverNodeErrors[selectedNode.id]` to `NodeConfigPanel`.

- [ ] **Step 6: Run UI tests and static verification**

Run:

```bash
cd core-ai-frontend
npm test
npm run lint
npm run build
```

Expected: all frontend tests pass, and there are no remaining `api.agents.list` calls or `select a published` text under `src/pages/workflows`:

```bash
if rg -n "api\.agents\.list|select a published" src/pages/workflows; then
  exit 1
else
  echo "workflow agent picker no longer uses the legacy published-only list"
fi
```

Expected: the command prints the confirmation line and exits 0.

- [ ] **Step 7: Commit editor integration**

```bash
git add core-ai-frontend/src/pages/workflows/validation.ts \
  core-ai-frontend/src/pages/workflows/validationErrors.test.ts \
  core-ai-frontend/src/pages/workflows/NodeConfigPanel.tsx \
  core-ai-frontend/src/pages/workflows/WorkflowEditor.tsx
git commit -m "feat: use private agents in workflow editor"
```

---

### Task 8: Full Verification, Server Version, and Push

**Files:**
- Modify: `core-ai-server/VERSION:1`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: release version `1.0.133` and a pushed `master` branch that triggers the configured automatic build.

- [ ] **Step 1: Run the complete server test and quality gate**

Run:

```bash
./gradlew :core-ai-server:test \
  :core-ai-server:checkstyleMain \
  :core-ai-server:checkstyleTest \
  :core-ai-server:pmdMain \
  :core-ai-server:pmdTest \
  :core-ai-server:spotbugsMain
```

Expected: Gradle exits 0 with no failed tests or quality violations.

- [ ] **Step 2: Run the complete frontend gate**

Run:

```bash
cd core-ai-frontend
npm test
npm run lint
npm run build
```

Expected: Vitest exits with zero failures, ESLint exits 0, and Vite creates `build/dist`.

- [ ] **Step 3: Re-run the feature acceptance searches and snapshot tests**

Run:

```bash
if rg -n "api\.agents\.list|select a published" core-ai-frontend/src/pages/workflows; then
  exit 1
else
  echo "legacy workflow agent selector absent"
fi
./gradlew :core-ai-server:test \
  --tests 'ai.core.server.workflow.WorkflowAgentOptionServiceTest' \
  --tests 'ai.core.server.workflow.WorkflowAgentSnapshotServiceTest' \
  --tests 'ai.core.server.workflow.WorkflowPrivateAgentSafetyValidatorTest' \
  --tests 'ai.core.server.workflow.AgentSnapshotRoundTripTest'
```

Expected: search output is empty and all four test classes pass.

- [ ] **Step 4: Bump the server release version**

Change exactly:

```text
1.0.131
```

to:

```text
1.0.133
```

Then verify:

```bash
test "$(tr -d '\n' < core-ai-server/VERSION)" = "1.0.133"
git diff --check
```

Expected: both commands exit 0.

- [ ] **Step 5: Commit the version bump after all gates pass**

```bash
git add core-ai-server/VERSION
git commit -m "chore: bump server version to 1.0.133"
```

- [ ] **Step 6: Verify commit scope and clean worktree**

Run:

```bash
git status -sb
git log --oneline --decorate -12
git diff origin/master...HEAD --check
```

Expected: no unstaged/staged paths, all feature commits are present, and the branch is ahead of `origin/master` only by the approved design, plan, implementation, tests, and version commits.

- [ ] **Step 7: Push to trigger the automatic build**

```bash
git push origin master
```

Expected: push succeeds and reports the new `master` tip; record the pushed commit hash in the handoff.

---

## Spec Coverage Checklist

- My DRAFT/PUBLISHED vs Shared PUBLISHED access matrix: Tasks 3 and 4.
- Minimal searchable, paginated, type-filtered, name-sorted picker: Tasks 1, 3, 6, and 7.
- Immutable Preview/Save/Publish snapshots and no recapture: Tasks 2 and 4.
- Optional provenance with old-Version compatibility: Task 4.
- Public Workflow without publishing the source Agent: Tasks 4 and 5.
- Caller-scoped authority and fail-closed owner-resource handling: Task 5.
- Clone/export/import non-leak and replacement warnings: Task 5.
- Loading, empty, stale request, selected-off-page, and unavailable UI states: Task 6.
- Node-scoped validation focus: Task 7.
- Automated backend/frontend coverage, version bump, and push: Task 8.
