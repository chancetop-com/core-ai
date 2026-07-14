package ai.core.cli.memory;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Tool for extraction agents to read and advance the extraction cursor.
 * The cursor tracks which conversation messages have been processed,
 * so the next extraction only processes new messages.
 *
 * Uses in-memory cursor (no file I/O) — per-process, resets on restart.
 *
 * Two named instances are created via Builder: one for read, one for advance.
 */
public class ExtractionCursorTool extends ToolCall {
    public static final String ADVANCE_TOOL_NAME = "advance_extraction_cursor";
    public static final String READ_TOOL_NAME = "read_extraction_cursor";

    public static Builder advanceBuilder() {
        return new Builder()
                .name(ADVANCE_TOOL_NAME)
                .description("Advance the extraction cursor to the given position. "
                        + "Pass the cursor value that the next extraction should start from "
                        + "(i.e., the index of the last processed message + 1). "
                        + "Always call this after extraction — whether you extracted knowledge or found nothing worth extracting.")
                .parameters(List.of(ToolCallParameter.builder()
                        .name("cursor")
                        .type(ToolCallParameterType.INTEGER)
                        .description("The position to advance the cursor to. "
                                + "Set to the index of the last processed message + 1. "
                                + "If omitted, defaults to total message count - 1.")
                        .required(Boolean.FALSE)
                        .build()));
    }

    public static Builder readBuilder() {
        return new Builder()
                .name(READ_TOOL_NAME)
                .description("Read the current extraction cursor position. "
                        + "Returns the message index up to which knowledge has been extracted. "
                        + "Messages after this index have not yet been processed for extraction.");
    }

    private final IntSupplier cursorReader;
    private final IntConsumer cursorWriter;
    private final IntSupplier totalMessagesSnapshot;

    public ExtractionCursorTool(IntSupplier cursorReader, IntConsumer cursorWriter,
                                IntSupplier totalMessagesSnapshot) {
        this.cursorReader = cursorReader;
        this.cursorWriter = cursorWriter;
        this.totalMessagesSnapshot = totalMessagesSnapshot;
    }

    @Override
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        String toolName = getName();
        try {
            if (ADVANCE_TOOL_NAME.equals(toolName)) {
                return executeAdvance(text, startTime);
            } else if (READ_TOOL_NAME.equals(toolName)) {
                return executeRead(startTime);
            }
            return ToolCallResult.failed("Unknown tool: " + toolName)
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Failed to execute " + toolName + ": " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult executeAdvance(String arguments, long startTime) {
        int maxCursor = totalMessagesSnapshot.getAsInt();
        int newCursor;
        try {
            var args = parseArguments(arguments);
            Object cursorVal = args.get("cursor");
            if (cursorVal instanceof Number n) {
                newCursor = Math.min(n.intValue(), maxCursor);
            } else {
                newCursor = Math.max(0, maxCursor - 1);
            }
        } catch (RuntimeException e) {
            newCursor = Math.max(0, maxCursor - 1);
        }
        cursorWriter.accept(newCursor);
        return ToolCallResult.completed("Cursor advanced to " + newCursor)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("cursor", newCursor);
    }

    private ToolCallResult executeRead(long startTime) {
        int cursor = cursorReader.getAsInt();
        if (cursor >= 0) {
            return ToolCallResult.completed("Extraction cursor is at " + cursor
                    + " (total messages: " + totalMessagesSnapshot.getAsInt() + ")")
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("cursor", cursor);
        }
        return ToolCallResult.completed("No extraction cursor yet (nothing has been extracted)")
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("cursor", -1);
    }

    /**
     * Unified builder for both read and advance cursor tools.
     * Configure name/description/parameters as needed, then call {@link #build()}.
     */
    public static class Builder extends ToolCall.Builder<Builder, ExtractionCursorTool> {
        private IntSupplier cursorReader;
        private IntConsumer cursorWriter;
        private IntSupplier totalMessagesSnapshotSupplier;

        public Builder cursorReader(IntSupplier reader) {
            this.cursorReader = reader;
            return this;
        }

        public Builder cursorWriter(IntConsumer writer) {
            this.cursorWriter = writer;
            return this;
        }

        public Builder totalMessagesSnapshot(IntSupplier supplier) {
            this.totalMessagesSnapshotSupplier = supplier;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ExtractionCursorTool build() {
            var tool = new ExtractionCursorTool(cursorReader, cursorWriter, totalMessagesSnapshotSupplier);
            build(tool);
            return tool;
        }
    }
}
