package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.StreamingMarkdownRenderer;
import ai.core.cli.ui.TerminalUI;
import ai.core.llm.domain.RoleType;
import ai.core.session.transcript.TranscriptKinds;
import ai.core.session.transcript.TranscriptReader;
import ai.core.session.transcript.TranscriptRecord;
import ai.core.session.transcript.TranscriptRecorder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Renders a resumed session from its transcript records so thinking, tool calls and notices survive
 * a restart. Falls back to the agent history, which only keeps text, when no record exists.
 */
final class TranscriptHistoryPrinter {
    private final TerminalUI ui;

    TranscriptHistoryPrinter(TerminalUI ui) {
        this.ui = ui;
    }

    boolean print(TranscriptRecorder recorder, String sessionId) {
        if (recorder == null) return false;
        var loaded = load(recorder);
        if (loaded.records().isEmpty()) return false;
        var renderer = new StreamingMarkdownRenderer(ui.getWriter(), ui.isAnsiSupported(), ui::getTerminalWidth);
        var cancelled = cancelledSteerSeqs(loaded.records());
        boolean printed = false;
        for (var record : loaded.records()) {
            if (record.kind == null) continue;
            printed |= render(record, cancelled, renderer);
        }
        if (loaded.truncated()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  … earlier turns are in the transcript" + AnsiTheme.RESET + "\n");
        }
        if (printed) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  ↑ restored " + sessionId + AnsiTheme.RESET + "\n");
        }
        return printed;
    }

    void printAgentHistory(Agent agent, String sessionId) {
        var messages = agent.getHistory();
        boolean hasHistory = false;
        var renderer = new StreamingMarkdownRenderer(ui.getWriter(), ui.isAnsiSupported(), ui::getTerminalWidth);
        for (var msg : messages) {
            String text = msg.getTextContent();
            if (text == null || text.isBlank()) continue;
            if (msg.role == RoleType.USER) {
                hasHistory = true;
                ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET + text.strip() + "\n");
            } else if (msg.role == RoleType.ASSISTANT) {
                hasHistory = true;
                printAssistantText(renderer, text);
            }
        }
        if (hasHistory) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  ↑ restored " + sessionId + AnsiTheme.RESET + "\n");
        }
    }

    private Loaded load(TranscriptRecorder recorder) {
        var reader = new TranscriptReader(recorder.store());
        var records = new ArrayList<TranscriptRecord>();
        long before = recorder.store().maxSeq() + 1;
        boolean truncated = false;
        for (int pages = 0; pages < 100; pages++) {
            var page = reader.page(before, 100, 2000);
            if (page.records().isEmpty()) break;
            records.addAll(0, page.records());
            if (page.nextBeforeSeq() == null) break;
            before = page.nextBeforeSeq();
            if (pages == 99) truncated = true;
        }
        return new Loaded(records, truncated);
    }

    private boolean render(TranscriptRecord record, Set<Long> cancelled, StreamingMarkdownRenderer renderer) {
        return switch (record.kind) {
            case TranscriptKinds.TURN_START -> printTurnInputs(record);
            case TranscriptKinds.STEER -> renderSteer(record, cancelled);
            case TranscriptKinds.TOOL_START -> renderToolStart(record);
            case TranscriptKinds.TOOL_RESULT -> renderToolResult(record);
            case TranscriptKinds.ASSISTANT_TEXT, TranscriptKinds.REASONING, TranscriptKinds.NOTICE,
                 TranscriptKinds.COMPRESSION, TranscriptKinds.SANDBOX, TranscriptKinds.ERROR -> renderContent(record, renderer);
            case TranscriptKinds.TURN_COMPLETE -> {
                printTurnSummary(record);
                yield false;
            }
            default -> false;
        };
    }

    private boolean renderContent(TranscriptRecord record, StreamingMarkdownRenderer renderer) {
        return switch (record.kind) {
            case TranscriptKinds.ASSISTANT_TEXT -> renderAssistantText(record, renderer);
            case TranscriptKinds.REASONING -> renderReasoning(record);
            case TranscriptKinds.NOTICE, TranscriptKinds.COMPRESSION, TranscriptKinds.SANDBOX -> renderNotice(record);
            case TranscriptKinds.ERROR -> renderError(record);
            default -> false;
        };
    }

    private boolean renderSteer(TranscriptRecord record, Set<Long> cancelled) {
        if (!TranscriptKinds.STEER_PENDING.equals(record.text("state")) || cancelled.contains(record.seq)) return false;
        return printPrompt(recordText(record, "text"));
    }

    private boolean renderAssistantText(TranscriptRecord record, StreamingMarkdownRenderer renderer) {
        var text = recordText(record, "text");
        if (text == null || text.isBlank()) return false;
        printAssistantText(renderer, text);
        return true;
    }

    private boolean renderReasoning(TranscriptRecord record) {
        var text = recordText(record, "reasoning");
        if (text == null || text.isBlank()) return false;
        printReasoning(text);
        return true;
    }

    private boolean renderToolStart(TranscriptRecord record) {
        ui.printStreamingChunk("\n  " + AnsiTheme.SEPARATOR + "●" + AnsiTheme.RESET + ' '
                + record.text("toolName") + "\n");
        printPreview(record, "arguments");
        return true;
    }

    private boolean renderToolResult(TranscriptRecord record) {
        var status = record.text("status");
        var color = "success".equals(status) ? AnsiTheme.SEPARATOR : AnsiTheme.ERROR;
        ui.printStreamingChunk("  " + color + (status == null ? "done" : status) + AnsiTheme.RESET + "\n");
        printPreview(record, "result");
        return true;
    }

    private boolean renderNotice(TranscriptRecord record) {
        var text = firstText(record, "text", "message");
        if (text == null || text.isBlank()) return false;
        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + text.strip() + AnsiTheme.RESET + "\n");
        return true;
    }

    private boolean renderError(TranscriptRecord record) {
        var text = recordText(record, "message");
        if (text == null || text.isBlank()) return false;
        ui.printStreamingChunk("\n  " + AnsiTheme.ERROR + text.strip() + AnsiTheme.RESET + "\n");
        return true;
    }

    private Set<Long> cancelledSteerSeqs(List<TranscriptRecord> records) {
        var cancelled = new HashSet<Long>();
        for (var record : records) {
            if (!TranscriptKinds.STEER.equals(record.kind)) continue;
            if (!TranscriptKinds.STEER_CANCELLED.equals(record.text("state"))) continue;
            var ref = record.number("ref_seq");
            if (ref != null) cancelled.add(ref);
        }
        return cancelled;
    }

    private boolean printTurnInputs(TranscriptRecord record) {
        var inputs = record.objects("inputs");
        if (inputs == null) return false;
        var source = record.text("source");
        boolean printed = false;
        for (var input : inputs) {
            var value = input.get("text");
            if (value == null) value = input.get("text_preview");
            if (value == null) continue;
            var prefix = TranscriptKinds.SOURCE_TASK_NOTE.equals(source)
                    ? AnsiTheme.MUTED + "[task] " + AnsiTheme.RESET : "";
            printed |= printPrompt(prefix + value);
        }
        return printed;
    }

    private boolean printPrompt(String text) {
        if (text == null || text.isBlank()) return false;
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET + text.strip() + "\n");
        return true;
    }

    private void printAssistantText(StreamingMarkdownRenderer renderer, String text) {
        var writer = ui.getWriter();
        writer.print("\n" + AnsiTheme.SEPARATOR + "●" + AnsiTheme.RESET + " ");
        writer.flush();
        renderer.setFirstLineOffset(AnsiTheme.displayWidth("● "));
        renderer.processChunk(text.replace("\n", "\n  "));
        renderer.flush();
        renderer.reset();
        writer.println();
    }

    private void printReasoning(String text) {
        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "◌ thinking" + AnsiTheme.RESET + "\n");
        var body = text.strip();
        if (body.length() > 4000) body = body.substring(0, 4000) + "…";
        for (var line : body.split("\n")) {
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + line + AnsiTheme.RESET + "\n");
        }
    }

    private void printPreview(TranscriptRecord record, String field) {
        var text = recordText(record, field);
        if (text == null || text.isBlank()) return;
        var line = text.strip().split("\n", 2)[0];
        if (line.length() > 160) line = line.substring(0, 160) + "…";
        ui.printStreamingChunk("    " + AnsiTheme.MUTED + line + AnsiTheme.RESET + "\n");
    }

    private void printTurnSummary(TranscriptRecord record) {
        var summary = turnSummary(record);
        if (summary != null) {
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + summary + AnsiTheme.RESET + "\n");
        }
    }

    private String turnSummary(TranscriptRecord record) {
        var parts = new ArrayList<String>();
        var duration = record.number("duration_ms");
        if (duration != null) parts.add(String.format(Locale.ROOT, "%.1fs", duration / 1000.0));
        var input = record.number("input_tokens");
        var output = record.number("output_tokens");
        if (input != null || output != null) {
            parts.add((input == null ? 0 : input) + " in / " + (output == null ? 0 : output) + " out");
        }
        var cost = record.decimal("cost_usd");
        if (cost != null && cost > 0) parts.add(String.format(Locale.ROOT, "$%.4f", cost));
        return parts.isEmpty() ? null : "✦ " + String.join(" · ", parts);
    }

    private String recordText(TranscriptRecord record, String field) {
        var text = record.text(field);
        return text != null ? text : record.text(field + "_preview");
    }

    private String firstText(TranscriptRecord record, String first, String second) {
        var text = recordText(record, first);
        return text != null ? text : recordText(record, second);
    }

    private record Loaded(List<TranscriptRecord> records, boolean truncated) {
    }
}
