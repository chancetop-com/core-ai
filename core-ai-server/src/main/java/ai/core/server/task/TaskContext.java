package ai.core.server.task;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Execution context passed to {@link AbstractTask#execute(TaskContext)}.
 * Collects log lines and a human-readable status summary that
 * {@link TaskRunner} persists to the {@code background_tasks} document
 * on completion.
 *
 * <p>When the task ID follows the {@code TYPE:YYYY-MM-DD} convention,
 * {@link #date()} returns the parsed date; otherwise it is {@code null}.</p>
 *
 * @author cyril
 */
public class TaskContext {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final List<String> logs = new ArrayList<>();
    private String statusText;
    private LocalDate date;

    /**
     * Append a timestamped log line.
     */
    public void log(String message) {
        logs.add("[" + LocalTime.now().format(TIME_FMT) + "] " + message);
    }

    /**
     * Set a human-readable summary for the final task status.
     */
    public void setStatusText(String text) {
        this.statusText = text;
    }

    /**
     * The date this task is processing, parsed from the task ID when it follows
     * the {@code TYPE:YYYY-MM-DD} convention. Returns {@code null} if the task ID
     * does not contain a date suffix.
     */
    public LocalDate date() {
        return date;
    }

    // -- package-private accessors for TaskRunner --

    void setDate(LocalDate date) {
        this.date = date;
    }

    List<String> logs() {
        return logs;
    }

    String statusText() {
        return statusText;
    }
}
