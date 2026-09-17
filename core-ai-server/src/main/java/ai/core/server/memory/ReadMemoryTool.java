package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.server.trace.domain.Trace;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads one memory row and, on request, the evidence behind it: the conversation and tool calls of a trace
 * that produced the memory. Ids come from {@link SearchMemoryTool}; the trace is the record, the memory is
 * the conclusion drawn from it.
 *
 * <p>Evidence is only readable while the trace is still retained (traces are archived and deleted after the
 * retention window) and only for the caller's own sessions.
 *
 * @author Xander
 */
public final class ReadMemoryTool extends ToolCall {
    static final String TOOL_NAME = "read_memory";
    private static final int MAX_HINT_IDS = 3;
    private static final String TOOL_DESC = """
            Read one memory in full, together with the sessions it came from.

            Use this when a memory you found through search_memory needs its origin to be useful — why it was
            recorded, what exactly happened, or which run to trust. Pass the `memory_id` from a search result;
            the answer lists the source traces. Ask again with `trace_id` to read the conversation and tool
            calls behind one of them.

            A memory is a conclusion distilled from those sessions, not the sessions themselves: prefer the
            memory unless you need the detail, and treat a trace as evidence rather than as current truth.
            Traces older than the retention window are gone and cannot be read.
            """;

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "memory_id", "Id of the memory to read, taken from a search_memory result").required(),
                ToolCallParameters.ParamSpec.of(String.class, "trace_id", "Optional id of one of the memory's source traces; adds its conversation and tool calls")
                        .optional()
        );
    }

    private final String agentId;
    private final AgentMemoryService agentMemoryService;

    public ReadMemoryTool(String agentId, AgentMemoryService agentMemoryService) {
        this.agentId = agentId;
        this.agentMemoryService = agentMemoryService;
        setName(TOOL_NAME);
        setDescription(TOOL_DESC);
        setParameters(parameters());
        setNeedAuth(Boolean.FALSE);
        setDirectReturn(Boolean.FALSE);
        setLlmVisible(Boolean.TRUE);
        setDiscoverable(Boolean.FALSE);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("read_memory requires ExecutionContext; direct execute is not supported");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
        var memoryId = getStringValue(args, "memory_id");
        if (memoryId == null || memoryId.isBlank()) {
            return ToolCallResult.failed("memory_id is required; take it from a search_memory result");
        }
        var memory = agentMemoryService.find(agentId, memoryId);
        if (memory == null) {
            return ToolCallResult.failed("no memory " + memoryId + " for this agent; use search_memory to find the right id");
        }
        var traces = agentMemoryService.sourceTraces(memory.sourceTraceIds);
        var traceId = getStringValue(args, "trace_id");
        Trace evidenceTrace = null;
        if (traceId != null && !traceId.isBlank()) {
            evidenceTrace = findTrace(traces, traceId);
            if (evidenceTrace == null) {
                return ToolCallResult.failed("trace " + traceId + " is not a source of memory " + memoryId
                        + "; " + availableTraces(traces));
            }
            var callerUserId = context == null ? null : context.getUserId();
            if (evidenceTrace.userId == null || !evidenceTrace.userId.equals(callerUserId)) {
                return ToolCallResult.failed("trace " + traceId
                        + " belongs to another user; only traces of your own sessions can be read");
            }
        }
        return ToolCallResult.completed(render(memory, traces, evidenceTrace));
    }

    private String render(AgentMemory memory, List<Trace> traces, Trace evidenceTrace) {
        var sb = new StringBuilder(512);
        sb.append("Memory ").append(memory.id).append(" (").append(memory.layer).append('/').append(memory.type).append(")\n");
        if (memory.createdAt != null) {
            sb.append("Recorded: ").append(memory.createdAt).append('\n');
        }
        sb.append('\n').append(memory.content).append("\n\nSource traces:\n");
        if (traces.isEmpty()) {
            sb.append("- none retained (traces are archived after the retention window, or the memory was written by hand)\n");
        } else {
            for (var trace : traces) {
                sb.append("- ").append(traceLine(trace)).append('\n');
            }
        }
        if (evidenceTrace != null) {
            sb.append("\nEvidence of ").append(evidenceTrace.traceId)
                    .append(" (conversation and tool calls of that session):\n\n")
                    .append(agentMemoryService.renderEvidence(evidenceTrace));
        } else {
            sb.append("\nPass trace_id to read the conversation behind one of them.\n");
        }
        return sb.toString();
    }

    private String traceLine(Trace trace) {
        return trace.traceId + " | " + text(trace.source) + " | " + text(trace.status) + " | started " + text(trace.startedAt)
                + " | " + tokens(trace) + " | " + text(trace.durationMs) + "ms";
    }

    private String tokens(Trace trace) {
        if (trace.inputTokens == null && trace.outputTokens == null) return "no token usage";
        return text(trace.inputTokens) + " in / " + text(trace.outputTokens) + " out";
    }

    private String text(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private Trace findTrace(List<Trace> traces, String traceId) {
        for (var trace : traces) {
            if (traceId.equals(trace.traceId)) return trace;
        }
        return null;
    }

    private String availableTraces(List<Trace> traces) {
        if (traces.isEmpty()) return "this memory has no retained source traces";
        var ids = traces.stream().limit(MAX_HINT_IDS).map(trace -> trace.traceId).collect(Collectors.joining(", "));
        return traces.size() > MAX_HINT_IDS ? "available: " + ids + ", ..." : "available: " + ids;
    }
}
