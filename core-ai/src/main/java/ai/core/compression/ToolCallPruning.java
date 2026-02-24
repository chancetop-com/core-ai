package ai.core.compression;

import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * @author stephen
 */
public class ToolCallPruning {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolCallPruning.class);

    private final int keepRecentSegments;
    private final Set<String> excludeToolNames;

    public ToolCallPruning(int keepRecentSegments, Set<String> excludeToolNames) {
        this.keepRecentSegments = keepRecentSegments;
        this.excludeToolNames = excludeToolNames != null ? excludeToolNames : Set.of();
    }

    public List<Message> prune(List<Message> messages) {
        if (messages == null || messages.size() < 3) {
            return messages;
        }

        try {
            return doPrune(messages);
        } catch (Exception e) {
            LOGGER.error("Pruning failed, returning original", e);
            return messages;
        }
    }

    private List<Message> doPrune(List<Message> messages) {
        var segments = identifySegments(messages);
        if (segments.isEmpty()) {
            return messages;
        }

        // Only consider segments that are followed by an assistant message with non-blank text as digested and eligible for pruning.
        var digestedSegments = segments.stream().filter(seg -> isDigested(seg, messages)).toList();

        var prunableSegments = new ArrayList<ToolSegment>();
        for (var i = 0; i < digestedSegments.size(); i++) {
            var positionFromEnd = digestedSegments.size() - 1 - i;
            // We keep the most recent N segments regardless of their content to preserve recent context, and only consider older segments for pruning.
            if (positionFromEnd >= keepRecentSegments && !containsExcludedTool(digestedSegments.get(i), messages)) {
                prunableSegments.add(digestedSegments.get(i));
            }
        }

        if (prunableSegments.isEmpty()) {
            return messages;
        }

        var toRemove = new TreeSet<Integer>();
        for (var seg : prunableSegments) {
            for (var i = seg.startIndex(); i < seg.endIndex(); i++) {
                toRemove.add(i);
            }
        }

        var result = new ArrayList<Message>(messages.size() - toRemove.size());
        for (var i = 0; i < messages.size(); i++) {
            if (!toRemove.contains(i)) {
                result.add(messages.get(i));
            }
        }

        if (!validateMessageStructure(result)) {
            LOGGER.warn("Structure validation failed after pruning, returning original");
            return messages;
        }

        LOGGER.info("Pruned {} segments ({} messages removed), {} -> {} messages", prunableSegments.size(), toRemove.size(), messages.size(), result.size());

        return result;
    }

    List<ToolSegment> identifySegments(List<Message> messages) {
        var segments = new ArrayList<ToolSegment>();
        var i = 0;
        while (i < messages.size()) {
            if (isToolSegmentMessage(messages.get(i))) {
                int segStart = i;
                while (i < messages.size() && isToolSegmentMessage(messages.get(i))) {
                    i++;
                }
                segments.add(new ToolSegment(segStart, i));
            } else {
                i++;
            }
        }
        return segments;
    }

    boolean isToolSegmentMessage(Message msg) {
        if (RoleType.TOOL == msg.role) {
            return true;
        }
        if (RoleType.ASSISTANT == msg.role && msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
            String text = msg.getTextContent();
            return text == null || text.isBlank();
        }
        return false;
    }

    // A segment is considered digested if it's immediately followed by an assistant message that has non-blank text content.
    boolean isDigested(ToolSegment segment, List<Message> messages) {
        if (segment.endIndex() >= messages.size()) {
            return false;
        }
        var nextMsg = messages.get(segment.endIndex());
        if (RoleType.ASSISTANT != nextMsg.role) {
            return false;
        }
        var text = nextMsg.getTextContent();
        return text != null && !text.isBlank();
    }

    boolean containsExcludedTool(ToolSegment segment, List<Message> messages) {
        for (var i = segment.startIndex(); i < segment.endIndex(); i++) {
            var msg = messages.get(i);
            if (RoleType.ASSISTANT == msg.role && msg.toolCalls != null) {
                for (var call : msg.toolCalls) {
                    var name = call.function != null ? call.function.name : null;
                    if (Compression.COMPRESSION_TOOL_NAME.equals(name) || excludeToolNames.contains(name)) {
                        return true;
                    }
                }
            }
            if (RoleType.TOOL == msg.role && msg.name != null && (Compression.COMPRESSION_TOOL_NAME.equals(msg.name) || excludeToolNames.contains(msg.name))) {
                return true;
            }

        }
        return false;
    }

    boolean validateMessageStructure(List<Message> messages) {
        var pendingToolCallIds = new LinkedHashSet<String>();
        for (var msg : messages) {
            if (RoleType.ASSISTANT == msg.role && msg.toolCalls != null) {
                for (var call : msg.toolCalls) {
                    if (call.id != null) {
                        pendingToolCallIds.add(call.id);
                    }
                }
            }
            if (RoleType.TOOL == msg.role && msg.toolCallId != null && !pendingToolCallIds.remove(msg.toolCallId)) {
                return false;
            }
        }
        return true;
    }

    public record Config(int keepRecentSegments, Set<String> excludeToolNames) {
        public static Config defaultConfig() {
            return new Config(2, Set.of());
        }
    }

    record ToolSegment(int startIndex, int endIndex) {
    }
}
