package ai.core.server.memory;

import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.server.domain.ChatMessage;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class AgentMemoryConsolidationJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMemoryConsolidationJob.class);
    private static final int MAX_TRACES_PER_AGENT = 50;
    // Allow single-run agents with deep interactions (e.g. 100+ turns) to generate memories.
    // Previously required 5+ traces, which excluded agents that complete complex tasks in one run.
    private static final int MIN_TRACES_FOR_EXTRACTION = 1;
    // Reduce idle threshold so agents don't need to wait 24h before memory extraction.
    // 1h is sufficient for UAT; can be increased for production if needed.
    private static final int IDLE_THRESHOLD_HOURS = 1;
    // Filter out trivial interactions (e.g. "test", "hello") by requiring either:
    // - at least 3 user turns (some back-and-forth), or
    // - at least 5 tool calls in a single-turn session (real work was done).
    // This prevents memory bloat from meaningless sessions while preserving
    // deep single-run interactions that are the primary extraction target.
    private static final int MIN_MEANINGFUL_USER_TURNS = 3;
    private static final int MIN_TOOL_CALLS_FOR_SHORT_SESSION = 5;
    private static final String EXTRACTION_MODEL = "deepseek/deepseek-v4-flash";
    private static final String EXTRACTION_PROMPT = """
            You maintain an agent's memory — a concise, stable set of reusable
            experiences that help the same agent perform more efficiently in future runs.

            ## Current memory
            %s

            ## New execution traces
            %s

            ## Task

            Review the current memory against the new traces, then produce the UPDATED
            complete memory set. For each memory:

            - VALIDATE: if a trace reaffirms a memory, keep it unchanged
            - REFINE:   if a trace adds nuance or reveals an edge case, improve it
            - MERGE:    if two memories overlap, combine them into one
            - ADD:      if a trace reveals a genuinely new reusable pattern, add it
            - REMOVE:   if a memory is outdated or no longer applies, drop it

            Rules:
            - Only include patterns reusable across future runs
            - Skip one-off facts tied to specific user input
            - Keep each memory under 200 words
            - Be specific and actionable

            For each memory, also label its type:
            WORKFLOW_PATTERN | GOTCHA | TOOL_USAGE | EFFICIENCY | DOMAIN_KNOWLEDGE

            Output ONLY valid JSON (no markdown fences, no extra text):
            {
              "memories": [
                { "type": "WORKFLOW_PATTERN", "content": "..." },
                { "type": "GOTCHA", "content": "..." }
              ]
            }
            """;

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
    AgentMemoryService agentMemoryService;

    @Override
    public void execute(JobContext context) {
        var agentIds = collectAgentIds();
        if (agentIds.isEmpty()) {
            LOGGER.debug("no agents with traces found");
            return;
        }

        for (var agentId : agentIds) {
            try {
                processAgent(agentId);
            } catch (Exception e) {
                LOGGER.error("failed to consolidate memory for agent={}", agentId, e);
            }
        }
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
                    && !EXCLUDED_AGENT_NAME.equals(trace.agentName)) {
                ids.add(trace.agentId);
            }
        }
        return ids;
    }

    private void processAgent(String agentId) {
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
            LOGGER.debug("agent={} has no meaningful interactions, skipping", agentId);
            return;
        }

        var existingMemories = agentMemoryService.findByAgentId(agentId);
        var prompt = buildPrompt(existingMemories, traces);
        var response = callLLM(prompt);
        var newMemories = parseResponse(response, traces);

        var traceIds = traces.stream().map(t -> t.traceId).toList();
        for (var memory : newMemories) {
            memory.agentId = agentId;
            memory.sourceTraceIds = mergeSourceTraceIds(memory.content, existingMemories, traceIds);
        }

        agentMemoryService.replaceAll(agentId, newMemories);

        var newCursor = new AgentMemoryExtractionCursor();
        newCursor.agentId = agentId;
        newCursor.lastProcessedAt = traces.getLast().startedAt;
        newCursor.lastTraceIds = traceIds;
        agentMemoryService.upsertCursor(newCursor);

        LOGGER.info("memory consolidated for agent={}, old={}, new={}, traces={}",
                agentId, existingMemories.size(), newMemories.size(), traces.size());
    }

    private String buildPrompt(List<AgentMemory> existingMemories, List<Trace> traces) {
        var existingText = existingMemories.isEmpty() ? "(empty)" : formatMemoriesForPrompt(existingMemories);
        var tracesText = buildSessionLog(traces);
        return EXTRACTION_PROMPT.formatted(existingText, tracesText);
    }

    @SuppressWarnings("PMD")
    private String formatMemoriesForPrompt(List<AgentMemory> memories) {
        var sb = new StringBuilder();
        for (int i = 0; i < memories.size(); i++) {
            var m = memories.get(i);
            sb.append((i + 1) + ". [" + (m.type != null ? m.type : "MEMORY") + "] " + m.content + "\n");
        }
        return sb.toString();
    }

    private String buildSessionLog(List<Trace> traces) {
        var sessionIds = traces.stream()
                .map(t -> t.sessionId)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();

        if (sessionIds.isEmpty()) return "(no session data)";

        var systemPrompt = extractSystemPrompt(traces);
        var allMessages = queryChatMessages(sessionIds);
        var bySession = allMessages.stream()
                .collect(Collectors.groupingBy(m -> m.sessionId, LinkedHashMap::new, Collectors.toList()));

        var sb = new StringBuilder();

        if (systemPrompt != null) {
            sb.append("## System Prompt\n");
            sb.append(truncate(systemPrompt, 2000)).append("\n\n");
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
            try {
                if (span.input != null && !span.input.isBlank()) {
                    var node = new ObjectMapper().readTree(span.input);
                    var messages = node.get("messages");
                    if (messages != null && messages.isArray()) {
                        for (var msg : messages) {
                            var role = msg.get("role");
                            if (role != null && "system".equals(role.asText())) {
                                return extractTextContent(msg);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // skip malformed span inputs
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

    private List<ChatMessage> queryChatMessages(List<String> sessionIds) {
        var query = new Query();
        query.filter = Filters.in("session_id", sessionIds);
        query.sort = Sorts.ascending("seq");
        query.limit = 500;
        return chatMessageCollection.find(query);
    }

    private boolean hasMeaningfulInteractions(List<Trace> traces) {
        var sessionIds = traces.stream()
                .map(t -> t.sessionId)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        if (sessionIds.isEmpty()) return false;

        var messages = queryChatMessages(sessionIds);

        // Count user turns (genuine back-and-forth)
        var userTurns = messages.stream()
                .filter(m -> "user".equals(m.role))
                .count();

        // Count tool calls in agent responses (actual work done)
        var toolCalls = messages.stream()
                .filter(m -> "agent".equals(m.role))
                .filter(m -> m.tools != null)
                .mapToLong(m -> m.tools.size())
                .sum();

        // Multiple user turns → meaningful conversation
        if (userTurns >= MIN_MEANINGFUL_USER_TURNS) return true;

        // Single-turn session with deep tool orchestration → real work
        return toolCalls >= MIN_TOOL_CALLS_FOR_SHORT_SESSION;
    }

    private String formatSessionLog(String sessionId, List<ChatMessage> messages) {
        var sb = new StringBuilder();
        sb.append("## Session ").append(sessionId).append("\n");

        int turnNum = 0;
        for (var msg : messages) {
            if ("user".equals(msg.role)) {
                turnNum++;
                sb.append("\n### Turn ").append(turnNum).append("\n");
                sb.append("User: ").append(truncate(msg.content, 3000)).append("\n");
            } else if ("agent".equals(msg.role)) {
                sb.append("Agent:");
                if (msg.thinking != null && !msg.thinking.isBlank()) {
                    sb.append(" [thinking] ").append(truncate(msg.thinking, 1000));
                }
                if (msg.tools != null && !msg.tools.isEmpty()) {
                    sb.append("\n  Tools used:");
                    for (var tool : msg.tools) {
                        sb.append("\n    -> ").append(tool.name).append("(").append(truncate(tool.arguments, 500)).append(")");
                        if (tool.result != null && !tool.result.isBlank()) {
                            sb.append(" = ").append(truncate(tool.result, 500));
                        }
                        if (tool.status != null && !"success".equalsIgnoreCase(tool.status)) {
                            sb.append(" [").append(tool.status).append("]");
                        }
                    }
                }
                sb.append("\n");
                if (msg.content != null && !msg.content.isBlank()) {
                    sb.append(truncate(msg.content, 3000)).append("\n");
                }
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(truncated)";
    }

    private String callLLM(String prompt) {
        var provider = llmProviders.getProvider();
        var msgs = List.of(Message.of(RoleType.USER, prompt));
        var request = CompletionRequest.of(msgs, null, provider.config.getTemperature(), EXTRACTION_MODEL, "memory-consolidator");
        request.setTimeoutSeconds(120);

        var response = provider.completion(request);
        if (response != null && response.choices != null && !response.choices.isEmpty()) {
            return response.choices.getFirst().message.content;
        }
        return "";
    }

    private List<AgentMemory> parseResponse(String response, List<Trace> traces) {
        var memories = new ArrayList<AgentMemory>();
        try {
            var json = extractJson(response);
            var om = new ObjectMapper();
            var node = om.readTree(json);
            var arr = node.get("memories");
            if (arr != null && arr.isArray()) {
                for (var item : arr) {
                    var memory = new AgentMemory();
                    memory.id = UUID.randomUUID().toString();
                    memory.type = item.has("type") ? item.get("type").asText() : null;
                    memory.content = item.get("content").asText();
                    memory.createdAt = ZonedDateTime.now();
                    memory.updatedAt = ZonedDateTime.now();
                    memory.sourceTraceIds = traces.stream().map(t -> t.traceId).toList();
                    memories.add(memory);
                }
            }
        } catch (Exception e) {
            LOGGER.error("failed to parse LLM extraction response", e);
        }
        return memories;
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

    private List<String> mergeSourceTraceIds(String content, List<AgentMemory> existingMemories, List<String> newTraceIds) {
        for (var existing : existingMemories) {
            if (existing.content != null && existing.content.equals(content)) {
                var merged = new ArrayList<>(existing.sourceTraceIds != null ? existing.sourceTraceIds : List.of());
                for (var id : newTraceIds) {
                    if (!merged.contains(id)) {
                        merged.add(id);
                    }
                }
                return merged;
            }
        }
        return new ArrayList<>(newTraceIds);
    }
}
