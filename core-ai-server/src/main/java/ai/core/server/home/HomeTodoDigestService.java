package ai.core.server.home;

import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.HomeTodoDigestRun;
import ai.core.server.domain.UserTodo;
import ai.core.server.session.ChatMessageQuery;
import ai.core.server.session.ChatMessageStore;
import ai.core.server.session.ChatSessionQuery;
import ai.core.server.session.ChatSessionStore;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.web.foryou.UserTodoStore;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Summarizes yesterday's chat sessions into todos once a day. Idempotency has three layers:
 * a per-user/day run record claimed with insertIfAbsent, deterministic todo ids, and
 * insert-only writes into user_todos.
 *
 * @author core-ai
 */
public class HomeTodoDigestService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalTime RUN_AFTER = LocalTime.of(7, 30);
    public static final String PROMPT_VERSION = "v1";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_SKIPPED = "SKIPPED";

    private static final Logger LOGGER = LoggerFactory.getLogger(HomeTodoDigestService.class);
    private static final Duration STALE_RUNNING = Duration.ofMinutes(30);
    private static final int MAX_USERS = 200;
    private static final int MAX_SESSIONS = 500;
    private static final int MAX_SESSIONS_PER_USER = 20;
    private static final int MAX_MESSAGES_PER_SESSION = 40;
    private static final int MAX_TRANSCRIPT_CHARS = 12_000;
    private static final int MAX_TODOS = 5;
    private static final String PROMPT = """
            You summarize a user's work sessions from yesterday into actionable todos for today.
            Rules:
            - Only output actions that are still open and worth doing today.
            - Do not repeat work the sessions show as finished.
            - Use the language the user used in the sessions.
            - Keep each title concrete and under 80 characters.
            - At most 5 todos; return an empty array when nothing is actionable.
            - Attribute every todo to the session it came from (S1, S2, ...).
            Return strict JSON only, no markdown:
            {"todos":[{"session":"S1","title":"...","description":"...","priority":"high|normal|low"}]}

            Sessions of %s:
            %s
            """;

    @Inject
    ChatSessionStore chatSessionStore;
    @Inject
    ChatMessageStore chatMessageStore;
    @Inject
    UserTodoStore todoStore;
    @Inject
    HomeTodoDigestStore digestStore;
    @Inject
    LLMProviders llmProviders;
    @Inject
    SystemSettingsService systemSettingsService;

    /** Runs every tick; the calendar guard makes the first tick after 07:30 do the work. */
    public void run() {
        var now = ZonedDateTime.now(ZONE);
        if (now.toLocalTime().isBefore(RUN_AFTER)) return;
        ActionLogContext.maxProcessTime(Duration.ofMinutes(5));

        var runDate = now.toLocalDate().minusDays(1);
        var from = runDate.atStartOfDay(ZONE);
        var to = runDate.plusDays(1).atStartOfDay(ZONE);
        var sessions = chatSessionStore.find(query(from, to));
        var byUser = new LinkedHashMap<String, List<ChatSession>>();
        for (var session : sessions) {
            if (session.userId == null || session.userId.isBlank() || session.id == null) continue;
            byUser.computeIfAbsent(session.userId, ignored -> new ArrayList<>()).add(session);
        }
        if (byUser.isEmpty()) {
            LOGGER.debug("home todo digest has no sessions to summarize, date={}", runDate);
            return;
        }

        var model = systemSettingsService.memoryExtractionModel();
        var processed = new AtomicInteger();
        var failed = new AtomicInteger();
        var entries = byUser.entrySet().stream().limit(MAX_USERS).toList();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            var batch = new DigestBatch(runDate, from, to, model);
            for (var entry : entries) {
                futures.add(executor.submit(() ->
                        processUserSafely(entry.getKey(), entry.getValue(), batch, processed, failed)));
            }
            awaitAll(futures);
        }
        LOGGER.info("home todo digest completed, date={}, users={}, processed={}, failed={}, skippedUsers={}",
                runDate, byUser.size(), processed.get(), failed.get(), Math.max(0, byUser.size() - entries.size()));
    }

    private void processUserSafely(String userId, List<ChatSession> sessions, DigestBatch batch,
                                   AtomicInteger processed, AtomicInteger failed) {
        try {
            processUser(userId, sessions, batch);
            processed.incrementAndGet();
        } catch (Exception e) {
            failed.incrementAndGet();
            LOGGER.warn("home todo digest failed, user={}, date={}, error={}", userId, batch.runDate(), e.getMessage());
        }
    }

    private void awaitAll(List<Future<?>> futures) {
        for (var future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.warn("home todo digest task ended unexpectedly", e);
            }
        }
    }

    /** Null when this user has no run record for the date yet. */
    public HomeTodoDigestRun runRecord(String userId, String date) {
        return digestStore.get(userId + ":" + date).orElse(null);
    }

    private ChatSessionQuery query(ZonedDateTime from, ZonedDateTime to) {
        var query = new ChatSessionQuery();
        query.timeField = "last_message_at";
        query.from = from;
        query.to = to;
        query.sources = List.of("chat", "scheduled");
        query.excludeComparisonVariantB = true;
        query.sortField = "last_message_at";
        query.ascending = false;
        query.limit = MAX_SESSIONS;
        return query;
    }

    // package-private for tests: the run loop only adds the time guard and per-user fan-out
    void processUser(String userId, List<ChatSession> sessions, DigestBatch batch) {
        var runDate = batch.runDate();
        var model = batch.model();
        var run = claim(userId, runDate, model);
        if (run == null) return;
        try {
            var transcript = transcript(sessions, batch.from(), batch.to());
            if (transcript.sessions().isEmpty()) {
                finish(run, STATUS_SKIPPED, transcript, 0, 0);
                return;
            }
            var prompt = PROMPT.formatted(runDate, transcript.text());
            var output = complete(prompt, model);
            var suggestions = parse(output);
            var result = writeTodos(userId, runDate, transcript.sessions(), suggestions, model);
            finish(run, STATUS_COMPLETED, transcript, result.created(), result.skipped());
            LOGGER.info("home todo digest user completed, user={}, date={}, sessions={}, created={}, skipped={}, model={}",
                    userId, runDate, transcript.sessions().size(), result.created(), result.skipped(), model);
        } catch (Exception e) {
            run.status = STATUS_FAILED;
            run.error = truncate(messageOf(e), 500);
            run.completedAt = ZonedDateTime.now(ZONE);
            digestStore.replace(run);
            LOGGER.warn("home todo digest user failed, user={}, date={}, error={}", userId, runDate, messageOf(e));
        }
    }

    /**
     * Returns the claimed RUNNING record, or null when another replica just completed/claimed it.
     * A FAILED or stale RUNNING record is retried in place.
     */
    private HomeTodoDigestRun claim(String userId, LocalDate runDate, String model) {
        var now = ZonedDateTime.now(ZONE);
        var id = userId + ":" + runDate;
        var existing = digestStore.get(id).orElse(null);
        if (existing != null) {
            if (STATUS_COMPLETED.equals(existing.status)) return null;
            if (STATUS_RUNNING.equals(existing.status)
                    && existing.startedAt != null
                    && existing.startedAt.plus(STALE_RUNNING).isAfter(now)) {
                return null;
            }
            existing.status = STATUS_RUNNING;
            existing.model = model;
            existing.promptVersion = PROMPT_VERSION;
            existing.startedAt = now;
            existing.completedAt = null;
            existing.error = null;
            existing.todoCount = null;
            existing.skippedCount = null;
            digestStore.replace(existing);
            return existing;
        }
        var run = new HomeTodoDigestRun();
        run.id = id;
        run.userId = userId;
        run.runDate = runDate.toString();
        run.status = STATUS_RUNNING;
        run.model = model;
        run.promptVersion = PROMPT_VERSION;
        run.startedAt = now;
        return digestStore.insertIfAbsent(run) ? run : null;
    }

    private void finish(HomeTodoDigestRun run, String status, Transcript transcript, int created, int skipped) {
        run.status = status;
        run.sessionCount = transcript.sessions().size();
        run.messageCount = transcript.messages();
        run.todoCount = created;
        run.skippedCount = skipped;
        run.completedAt = ZonedDateTime.now(ZONE);
        digestStore.replace(run);
    }

    private Transcript transcript(List<ChatSession> sessions, ZonedDateTime from, ZonedDateTime to) {
        var builder = new StringBuilder(1024);
        var used = new ArrayList<String>();
        var total = 0;
        for (var session : sessions) {
            if (used.size() >= MAX_SESSIONS_PER_USER || builder.length() >= MAX_TRANSCRIPT_CHARS) break;
            var query = new ChatMessageQuery();
            query.sessionId = session.id;
            query.from = from;
            query.to = to;
            query.sortField = "created_at";
            query.ascending = true;
            query.limit = MAX_MESSAGES_PER_SESSION;
            var messages = chatMessageStore.find(query);
            var userTurns = messages.stream().filter(message -> "user".equals(message.role)).count();
            if (userTurns == 0) continue;

            var index = used.size() + 1;
            var section = new StringBuilder(512);
            section.append("\n[Session S").append(index).append("] ")
                    .append(truncate(session.title == null ? "(untitled)" : session.title, 120)).append('\n');
            var sectionMessages = 0;
            for (var message : messages) {
                appendMessage(section, message);
                sectionMessages++;
            }
            if (builder.length() + section.length() > MAX_TRANSCRIPT_CHARS) break;
            builder.append(section);
            used.add(session.id);
            total += sectionMessages;
        }
        return new Transcript(builder.toString(), used, total);
    }

    private void appendMessage(StringBuilder builder, ChatMessage message) {
        if ("user".equals(message.role)) {
            builder.append("User: ").append(truncate(message.content, 1200)).append('\n');
        } else if ("agent".equals(message.role)) {
            builder.append("Agent: ").append(truncate(message.content, 1200));
            appendTools(builder, message);
            builder.append('\n');
        }
    }

    private void appendTools(StringBuilder builder, ChatMessage message) {
        if (message.tools == null || message.tools.isEmpty()) return;
        builder.append(" [tools: ");
        for (int i = 0; i < Math.min(message.tools.size(), 8); i++) {
            if (i > 0) builder.append(", ");
            builder.append(truncate(message.tools.get(i).name, 60));
        }
        builder.append(']');
    }

    /** Seam for tests; production goes through the configured chat provider. */
    protected String complete(String prompt, String model) {
        var provider = llmProviders.getProvider();
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, prompt)), null,
                provider.config.getTemperature(), model, "home-todo-digest");
        request.setTimeoutSeconds(90);
        var response = provider.completion(request);
        if (response != null && response.choices != null && !response.choices.isEmpty()) {
            return response.choices.getFirst().message.content;
        }
        throw new IllegalStateException("model returned no completion");
    }

    private List<HomeTodoSuggestion> parse(String output) {
        var start = output == null ? -1 : output.indexOf('{');
        var end = output == null ? -1 : output.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("model output is not JSON");
        var result = JsonUtil.fromJson(DigestResult.class, output.substring(start, end + 1));
        return result.todos == null ? List.of() : result.todos;
    }

    private WriteResult writeTodos(String userId, LocalDate runDate, List<String> sessionIds,
                                   List<HomeTodoSuggestion> suggestions, String model) {
        var now = ZonedDateTime.now(ZONE);
        var seenTitles = new HashSet<String>();
        int created = 0;
        int skipped = 0;
        int accepted = 0;
        for (var suggestion : suggestions) {
            if (accepted >= MAX_TODOS) break;
            var title = normalizeTitle(suggestion.title);
            if (title == null) continue;
            var normalized = title.toLowerCase(Locale.ROOT);
            if (!seenTitles.add(normalized)) {
                skipped++;
                continue;
            }
            accepted++;
            var sessionId = sessionFor(suggestion.session, sessionIds);
            var dedupeKey = "auto:" + runDate + ":" + sessionId + ":" + hash("sha-256", normalized, 12);
            var todo = new UserTodo();
            todo.id = "auto-" + runDate.toString().replace("-", "") + "-" + hash("sha-1", userId + ":" + dedupeKey, 16);
            todo.userId = userId;
            todo.title = title;
            todo.description = truncate(suggestion.description, 500);
            todo.priority = priority(suggestion.priority);
            todo.completed = Boolean.FALSE;
            todo.source = "AUTO";
            todo.originDate = runDate.toString();
            todo.originSessionIds = List.of(sessionId);
            todo.dedupeKey = dedupeKey;
            todo.generatedBy = model;
            todo.generatedAt = now;
            todo.createdAt = now;
            todo.updatedAt = now;
            if (todoStore.insertIfAbsent(todo)) {
                created++;
            } else {
                skipped++;
            }
        }
        return new WriteResult(created, skipped);
    }

    private String sessionFor(String label, List<String> sessionIds) {
        if (sessionIds.isEmpty()) throw new IllegalStateException("no session to attribute a todo to");
        if (label != null && label.length() >= 2 && (label.charAt(0) == 'S' || label.charAt(0) == 's')) {
            try {
                var index = Integer.parseInt(label.substring(1).trim());
                if (index >= 1 && index <= sessionIds.size()) return sessionIds.get(index - 1);
            } catch (NumberFormatException ignored) {
                // fall through to the first session
            }
        }
        return sessionIds.getFirst();
    }

    private String normalizeTitle(String value) {
        if (value == null) return null;
        var title = value.trim().replaceAll("\\s+", " ");
        if (title.isEmpty()) return null;
        return truncate(title, 200);
    }

    private String priority(String value) {
        if (value == null) return "normal";
        var priority = value.trim().toLowerCase(Locale.ROOT);
        return "high".equals(priority) || "low".equals(priority) ? priority : "normal";
    }

    private String hash(String algorithm, String value, int length) {
        try {
            var digest = MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (var b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, Math.min(length, hex.length()));
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash todo key", e);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        var text = value.strip();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String messageOf(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    record Transcript(String text, List<String> sessions, int messages) {
    }

    record DigestBatch(LocalDate runDate, ZonedDateTime from, ZonedDateTime to, String model) {
    }

    record WriteResult(int created, int skipped) {
    }

    public static class DigestResult {
        public List<HomeTodoSuggestion> todos;
    }

    public static class HomeTodoSuggestion {
        public String session;
        public String title;
        public String description;
        public String priority;
    }
}
