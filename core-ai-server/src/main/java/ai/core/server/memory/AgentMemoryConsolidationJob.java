package ai.core.server.memory;

import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ChatMessage;
import ai.core.server.memory.experiment.MemoryLayer;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * V2 memory consolidation: append-only extraction, no REFINE/MERGE.
 * <p>
 * Key changes from V1:
 * <ul>
 *   <li>Layer 2 (methods): extracted patterns are appended, never merged/refined.</li>
 *   <li>Layer 3 (trajectories): per-trace session summaries, append-only.</li>
 *   <li>Layer 1 (knowledge): NOT auto-generated — only via manual/semi-auto confirmation.</li>
 *   <li>FIFO eviction for Layer 2 (max 50) handled by AgentMemoryService.</li>
 * </ul>
 *
 * @author stephen
 */
public class AgentMemoryConsolidationJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMemoryConsolidationJob.class);
    private static final int MAX_TRACES_PER_AGENT = 50;
    private static final int MIN_TRACES_FOR_EXTRACTION = 1;
    private static final int IDLE_THRESHOLD_HOURS = 1;
    private static final int MIN_MEANINGFUL_USER_TURNS = 3;
    private static final int MIN_TOOL_CALLS_FOR_SHORT_SESSION = 5;
    private static final int MESSAGE_LOOKBACK_SECONDS = 5;
    private static final int MESSAGE_LOOKAHEAD_SECONDS = 30;
    private static final int TRAJECTORY_MAX_CHARS = 500;
    private static final int PROCESSING_TIMEOUT_SECONDS = 55;
    public static final String DEFAULT_EXTRACTION_MODEL = "deepseek/deepseek-v4-flash";
    private static final String EXCLUDED_AGENT_NAME = "assistant";

    @Inject
    LLMProviders llmProviders;

    @Inject
    MongoCollection<Trace> traceCollection;

    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;

    @Inject
    MongoCollection<Span> spanCollection;

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @Inject
    AgentMemoryService agentMemoryService;

    @Inject
    SystemSettingsService systemSettingsService;

    public String extractionModel = DEFAULT_EXTRACTION_MODEL;

    @Override
    public void execute(JobContext context) {
        var agentIds = collectAgentIds();
        if (agentIds.isEmpty()) {
            LOGGER.debug("no agents with traces found");
            return;
        }
        LOGGER.info("memory consolidation starting for {} agents", agentIds.size());
        var deadline = ZonedDateTime.now().plusSeconds(PROCESSING_TIMEOUT_SECONDS);
        var processed = new AtomicInteger(0);
        var failed = new AtomicInteger(0);
        var futures = new ArrayList<Future<?>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var agentId : agentIds) {
                if (ZonedDateTime.now().isAfter(deadline)) {
                    LOGGER.warn("memory consolidation timeout reached, skipping remaining {} agents",
                            agentIds.size() - processed.get() - failed.get());
                    break;
                }
                futures.add(executor.submit(() -> processAgentAsync(agentId, processed, failed)));
            }
        }
        for (var future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                LOGGER.warn("memory consolidation interrupted", e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.warn("memory consolidation task failed unexpectedly", e);
            }
        }
        LOGGER.info("memory consolidation completed: processed={}, failed={}, total={}",
                processed.get(), failed.get(), agentIds.size());
    }

    private Set<String> collectAgentIds() {
        var cutoff = ZonedDateTime.now().minusHours(IDLE_THRESHOLD_HOURS);
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("status", TraceStatus.COMPLETED),
                Filters.exists("agent_id", true),
                Filters.ne("agent_id", null),
                Filters.ne("agent_id", ""),
                Filters.lte("started_at", cutoff)
        );
        query.sort = Sorts.descending("started_at");
        query.limit = 500;
        var traces = traceCollection.find(query);
        var ids = new HashSet<String>();
        for (var trace : traces) {
            if (trace.agentId != null && !trace.agentId.isBlank()
                    && !EXCLUDED_AGENT_NAME.equalsIgnoreCase(trace.agentName)) {
                ids.add(trace.agentId);
            }
        }
        return ids;
    }

    private void processAgentAsync(String agentId, AtomicInteger processed, AtomicInteger failed) {
        try {
            processAgent(agentId);
            processed.incrementAndGet();
        } catch (Exception e) {
            LOGGER.error("failed to consolidate memory for agent={}", agentId, e);
            failed.incrementAndGet();
        }
    }

    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private void processAgent(String agentId) {
        var definition = agentDefinitionCollection.get(agentId).orElse(null);
        if (definition == null) {
            LOGGER.debug("agent={} definition not found, skipping extraction", agentId);
            return;
        }
        var enableMemory = definition.publishedConfig != null ? definition.publishedConfig.enableMemory : definition.enableMemory;
        if (!AgentMemoryService.memoryEnabled(enableMemory)) {
            LOGGER.debug("agent={} memory disabled, skipping extraction", agentId);
            return;
        }
        var cursor = agentMemoryService.getCursor(agentId);
        var since = cursor != null ? cursor.lastProcessedAt : ZonedDateTime.now().minusYears(10);
        var cutoff = ZonedDateTime.now().minusHours(IDLE_THRESHOLD_HOURS);
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("agent_id", agentId),
                Filters.eq("status", TraceStatus.COMPLETED),
                Filters.gt("started_at", since),
                Filters.lte("started_at", cutoff)
        );
        query.sort = Sorts.ascending("started_at");
        query.limit = MAX_TRACES_PER_AGENT;
        var traces = traceCollection.find(query);
        if (traces.size() < MIN_TRACES_FOR_EXTRACTION) {
            LOGGER.debug("agent={} has {} new traces, skipping (min={})", agentId, traces.size(), MIN_TRACES_FOR_EXTRACTION);
            return;
        }
        if (!hasMeaningfulInteractions(traces)) {
            LOGGER.debug("agent={} has no meaningful interactions, advancing cursor without extraction", agentId);
            advanceCursor(agentId, traces);
            return;
        }
        var tracesText = buildSessionLog(traces);
        var prompt = MemoryConsolidationPrompt.EXTRACTION_PROMPT.formatted(TRAJECTORY_MAX_CHARS, tracesText, TRAJECTORY_MAX_CHARS);
        var response = callLLM(prompt);
        var result = parseV2Response(response, agentId, traces);
        if (!result.isEmpty()) {
            agentMemoryService.appendMemories(agentId, result);
        }
        advanceCursor(agentId, traces);
        LOGGER.info("memory V2 appended for agent={}, total={}, traces={}", agentId, result.size(), traces.size());
    }

    private void advanceCursor(String agentId, List<Trace> traces) {
        var newCursor = new AgentMemoryExtractionCursor();
        newCursor.agentId = agentId;
        newCursor.lastProcessedAt = traces.getLast().startedAt;
        newCursor.lastTraceIds = traces.stream().map(t -> t.traceId).toList();
        agentMemoryService.upsertCursor(newCursor);
    }

    private String buildSessionLog(List<Trace> traces) {
        var allMessages = queryChatMessages(traces);
        if (allMessages.isEmpty()) return "(no session data)";
        var systemPrompt = extractSystemPrompt(traces);
        var bySession = allMessages.stream()
                .collect(Collectors.groupingBy(m -> m.sessionId, LinkedHashMap::new, Collectors.toList()));
        var sb = new StringBuilder(256);
        if (systemPrompt != null) {
            sb.append("## System Prompt\n").append(truncate(systemPrompt, 2000)).append("\n\n");
        }
        int totalChars = sb.length();
        for (var entry : bySession.entrySet()) {
            if (totalChars > 50000) break;
            var section = formatSessionLog(entry.getKey(), entry.getValue());
            totalChars += section.length();
            sb.append(section);
        }
        return sb.toString();
    }

    @SuppressFBWarnings("PCAIL_POSSIBLE_CONSTANT_ALLOCATION_IN_LOOP")
    private String extractSystemPrompt(List<Trace> traces) {
        var traceIds = traces.stream().map(t -> t.traceId).toList();
        var query = new Query();
        query.filter = Filters.and(
                Filters.in("trace_id", traceIds),
                Filters.eq("type", SpanType.LLM.name())
        );
        query.sort = Sorts.ascending("started_at");
        query.limit = 5;
        var spans = spanCollection.find(query);
        for (var span : spans) {
            if (span.input == null || span.input.isBlank()) continue;
            JsonNode node;
            try {
                node = new ObjectMapper().readTree(span.input);
            } catch (Exception e) {
                LOGGER.warn("failed to parse span input JSON: {}", e.getMessage());
                continue;
            }
            if (node == null) continue;
            var messages = node.get("messages");
            if (messages == null || !messages.isArray()) continue;
            for (var msg : messages) {
                var role = msg.get("role");
                if (role != null && "system".equals(role.asText())) {
                    return extractTextContent(msg);
                }
            }
        }
        return null;
    }

    private String extractTextContent(JsonNode message) {
        var content = message.get("content");
        if (content == null) return null;
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            for (var block : content) {
                if ("text".equals(block.get("type").asText())) {
                    var text = block.get("text");
                    return text != null ? text.asText() : null;
                }
            }
        }
        return null;
    }

    private List<ChatMessage> queryChatMessages(List<Trace> traces) {
        var filters = new ArrayList<Bson>();
        var traceIds = traces.stream()
                .map(t -> t.traceId)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        if (!traceIds.isEmpty()) {
            filters.add(Filters.in("trace_id", traceIds));
        }
        for (var trace : traces) {
            if (trace.sessionId == null || trace.sessionId.isBlank() || trace.startedAt == null) continue;
            var end = trace.completedAt != null ? trace.completedAt : trace.startedAt;
            filters.add(Filters.and(
                    Filters.eq("session_id", trace.sessionId),
                    Filters.gte("created_at", trace.startedAt.minusSeconds(MESSAGE_LOOKBACK_SECONDS)),
                    Filters.lte("created_at", end.plusSeconds(MESSAGE_LOOKAHEAD_SECONDS))
            ));
        }
        return queryChatMessagesByFilters(filters);
    }

    private List<ChatMessage> queryChatMessagesByFilters(List<Bson> filters) {
        if (filters.isEmpty()) return List.of();
        var query = new Query();
        query.filter = filters.size() == 1 ? filters.getFirst() : Filters.or(filters);
        query.sort = Sorts.ascending("created_at");
        query.limit = 500;
        return chatMessageCollection.find(query);
    }

    private boolean hasMeaningfulInteractions(List<Trace> traces) {
        var messages = queryChatMessages(traces);
        var userTurns = messages.stream()
                .filter(m -> "user".equals(m.role))
                .count();
        var toolCalls = messages.stream()
                .filter(m -> m.tools != null && "agent".equals(m.role))
                .mapToLong(m -> m.tools.size())
                .sum();
        if (userTurns >= MIN_MEANINGFUL_USER_TURNS) return true;
        return toolCalls >= MIN_TOOL_CALLS_FOR_SHORT_SESSION;
    }

    private String formatSessionLog(String sessionId, List<ChatMessage> messages) {
        var sb = new StringBuilder(256);
        sb.append("## Session ").append(sessionId).append('\n');
        int turnNum = 0;
        for (var msg : messages) {
            if ("user".equals(msg.role)) {
                turnNum++;
                sb.append("\n### Turn ").append(turnNum).append("\nUser: ").append(truncate(msg.content, 3000)).append('\n');
            } else if ("agent".equals(msg.role)) {
                sb.append("Agent:");
                if (msg.thinking != null && !msg.thinking.isBlank()) {
                    sb.append(" [thinking] ").append(truncate(msg.thinking, 1000));
                }
                if (msg.tools != null && !msg.tools.isEmpty()) {
                    sb.append("\n  Tools used:");
                    for (var tool : msg.tools) {
                        sb.append("\n    -> ").append(tool.name)
                                .append('(').append(truncate(tool.arguments, 500)).append(')');
                        appendToolResult(sb, tool);
                    }
                }
                sb.append('\n');
                if (msg.content != null && !msg.content.isBlank()) {
                    sb.append(truncate(msg.content, 3000)).append('\n');
                }
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private void appendToolResult(StringBuilder sb, ChatMessage.ToolCallRecord tool) {
        if (tool.result != null && !tool.result.isBlank()) {
            sb.append(" = ").append(truncate(tool.result, 500));
        }
        if (tool.status != null && !"success".equalsIgnoreCase(tool.status)) {
            sb.append(" [").append(tool.status).append(']');
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(truncated)";
    }

    private String callLLM(String prompt) {
        var provider = llmProviders.getProvider();
        var msgs = List.of(Message.of(RoleType.USER, prompt));
        var model = systemSettingsService.memoryExtractionModel();
        var request = CompletionRequest.of(msgs, null, provider.config.getTemperature(), model, "memory-consolidator");
        request.setTimeoutSeconds(120);
        var response = provider.completion(request);
        if (response != null && response.choices != null && !response.choices.isEmpty()) {
            return response.choices.getFirst().message.content;
        }
        return "";
    }

    @SuppressFBWarnings({"VA_FORMAT_STRING_USES_NEWLINE", "REC_CATCH_EXCEPTION"})
    private List<AgentMemory> parseV2Response(String response, String agentId, List<Trace> traces) {
        var memories = new ArrayList<AgentMemory>();
        try {
            var json = extractJson(response);
            var om = new ObjectMapper();
            var node = om.readTree(json);
            var now = ZonedDateTime.now();
            var trajectories = node.get("trajectories");
            if (trajectories != null && trajectories.isArray()) {
                for (var item : trajectories) {
                    var memory = new AgentMemory();
                    memory.id = UUID.randomUUID().toString();
                    memory.agentId = agentId;
                    memory.type = "TRAJECTORY";
                    memory.layer = MemoryLayer.TRAJECTORIES;
                    memory.content = formatTrajectoryContent(item);
                    memory.createdAt = now;
                    memory.updatedAt = now;
                    memory.sourceTraceIds = traces.stream().map(t -> t.traceId).toList();
                    memories.add(memory);
                }
            }
            var patterns = node.get("patterns");
            if (patterns != null && patterns.isArray()) {
                for (var item : patterns) {
                    var memory = new AgentMemory();
                    memory.id = UUID.randomUUID().toString();
                    memory.agentId = agentId;
                    memory.type = item.has("type") ? item.get("type").asText() : null;
                    memory.layer = MemoryLayer.METHODS;
                    memory.content = item.get("content").asText();
                    memory.createdAt = now;
                    memory.updatedAt = now;
                    memory.sourceTraceIds = traces.stream().map(t -> t.traceId).toList();
                    memories.add(memory);
                }
            }
        } catch (Exception e) {
            LOGGER.error("failed to parse V2 extraction response", e);
        }
        return memories;
    }

    private String formatTrajectoryContent(JsonNode item) {
        var sessionId = item.has("session_id") ? item.get("session_id").asText() : "unknown";
        var summary = item.has("summary") ? item.get("summary").asText() : "";
        if (summary.length() > TRAJECTORY_MAX_CHARS) {
            summary = summary.substring(0, TRAJECTORY_MAX_CHARS);
        }
        return "[session=" + sessionId + "] " + summary;
    }

    private String extractJson(String response) {
        if (response == null) return "{}";
        var trimmed = response.trim();
        var start = trimmed.indexOf('{');
        var end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return "{}";
    }
}
