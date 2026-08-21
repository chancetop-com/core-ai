package ai.core.cli.schedule;

import ai.core.schedule.CronExpression;
import ai.core.schedule.ScheduledTask;
import ai.core.schedule.ScheduledTaskStore;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link ScheduledTaskStore} for the CLI. All tasks live in a single
 * JSON file ({@code ~/.core-ai/schedules.json} by default); sessions are restored
 * on resume because the store is keyed by session id, not by process lifetime.
 *
 * @author stephen
 */
public class FileScheduledTaskStore implements ScheduledTaskStore {

    private static ScheduledTask copyOf(ScheduledTask task) {
        var copy = new ScheduledTask();
        copy.id = task.id;
        copy.sessionId = task.sessionId;
        copy.userId = task.userId;
        copy.name = task.name;
        copy.cronExpression = task.cronExpression;
        copy.timezone = task.timezone;
        copy.input = task.input;
        copy.enabled = task.enabled;
        copy.nextRunAt = task.nextRunAt;
        copy.createdAt = task.createdAt;
        copy.updatedAt = task.updatedAt;
        return copy;
    }

    private final Path file;
    private final Map<String, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public FileScheduledTaskStore(Path file) {
        this.file = file;
        load();
    }

    @Override
    public ScheduledTask create(ScheduledTask task) {
        synchronized (lock) {
            var copy = copyOf(task);
            var now = ZonedDateTime.now();
            var zone = ZoneId.of(copy.timezone);
            copy.nextRunAt = new CronExpression(copy.cronExpression).nextAfter(now, zone);
            copy.createdAt = now;
            copy.updatedAt = now;
            tasks.put(copy.id, copy);
            persist();
            return copy;
        }
    }

    @Override
    public List<ScheduledTask> list(String sessionId) {
        return tasks.values().stream()
                .filter(task -> task.sessionId.equals(sessionId))
                .toList();
    }

    @Override
    public ScheduledTask get(String id) {
        return tasks.get(id);
    }

    @Override
    public boolean delete(String id) {
        synchronized (lock) {
            var removed = tasks.remove(id);
            if (removed != null) persist();
            return removed != null;
        }
    }

    @Override
    public List<ScheduledTask> findDue(ZonedDateTime now) {
        return tasks.values().stream()
                .filter(task -> task.nextRunAt != null
                        && !task.nextRunAt.isAfter(now)
                        && Boolean.TRUE.equals(task.enabled))
                .toList();
    }

    @Override
    public boolean claim(String id, ZonedDateTime expectedNextRunAt, ZonedDateTime newNextRunAt) {
        synchronized (lock) {
            var task = tasks.get(id);
            if (task == null || task.nextRunAt == null || !task.nextRunAt.isEqual(expectedNextRunAt)) return false;
            task.nextRunAt = newNextRunAt;
            task.updatedAt = ZonedDateTime.now();
            persist();
            return true;
        }
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            var content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) return;
            var loaded = JsonUtil.fromJson(new TypeReference<List<ScheduledTask>>() {
            }, content);
            if (loaded != null) {
                for (var task : loaded) {
                    if (task.id != null) tasks.put(task.id, task);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load scheduled tasks, file=" + file, e);
        }
    }

    private void persist() {
        try {
            var parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JsonUtil.toJson(new ArrayList<>(tasks.values())), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist scheduled tasks, file=" + file, e);
        }
    }
}
