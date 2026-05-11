package ai.core.cli.task;

import ai.core.tool.tools.TodoStore;
import ai.core.tool.tools.WriteTodoTaskTool;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * File-based implementation of {@link TodoStore}.
 * Each task is stored as a JSON file under {@code <baseDir>/<sessionId>/<id>.json}.
 *
 * @author lim chen
 */
public class FileTodoStore implements TodoStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileTodoStore.class);

    private final Path taskListDir;
    private final AtomicInteger idSeq;

    private static String sanitize(String sessionId) {
        return sessionId.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    public FileTodoStore(Path baseDir, String sessionId) {
        this.taskListDir = baseDir.resolve(sanitize(sessionId));
        this.idSeq = new AtomicInteger(scanMaxId() + 1);
    }

    private int scanMaxId() {
        if (!java.nio.file.Files.exists(taskListDir)) return 0;
        File[] files = taskListDir.toFile().listFiles((_, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) return 0;
        int max = 0;
        for (File file : files) {
            try {
                int id = Integer.parseInt(file.getName().replace(".json", ""));
                if (id > max) max = id;
            } catch (NumberFormatException ignored) {
            }
        }
        return max;
    }

    @Override
    public WriteTodoTaskTool.TaskEntity create(WriteTodoTaskTool.TaskEntity task) {
        ensureDir();
        task.id = String.valueOf(idSeq.getAndIncrement());
        writeFile(Integer.parseInt(task.id), task);
        return task;
    }

    @Override
    public WriteTodoTaskTool.TaskEntity read(String taskId) {
        Path file = taskPath(taskId);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return JsonUtil.fromJson(WriteTodoTaskTool.TaskEntity.class, content);
        } catch (IOException e) {
            LOGGER.warn("failed to read task file: {}", file, e);
            return null;
        }
    }

    @Override
    public void write(String taskId, WriteTodoTaskTool.TaskEntity task) {
        ensureDir();
        writeFile(Integer.parseInt(taskId), task);
    }

    @Override
    public void delete(String taskId) {
        Path file = taskPath(taskId);
        if (!Files.exists(file)) {
            return;
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            LOGGER.warn("failed to delete task file: {}", file, e);
        }
        cleanReferences(taskId);
    }

    @Override
    public List<WriteTodoTaskTool.TaskEntity> listAll() {
        ensureDir();
        List<WriteTodoTaskTool.TaskEntity> tasks = new ArrayList<>();
        File[] files = taskListDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return tasks;
        }
        for (File file : files) {
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                WriteTodoTaskTool.TaskEntity task = JsonUtil.fromJson(WriteTodoTaskTool.TaskEntity.class, content);
                tasks.add(task);
            } catch (IOException e) {
                LOGGER.warn("failed to read task file: {}", file, e);
            }
        }
        tasks.sort(Comparator.comparingInt(t -> Integer.parseInt(t.id)));
        return tasks;
    }

    @Override
    public void cleanup() {
        if (!Files.exists(taskListDir)) {
            return;
        }
        File[] files = taskListDir.toFile().listFiles();
        if (files != null) {
            for (File file : files) {
                try {
                    Files.delete(file.toPath());
                } catch (IOException e) {
                    LOGGER.warn("failed to delete file during cleanup: {}", file, e);
                }
            }
        }
        try {
            Files.delete(taskListDir);
        } catch (IOException e) {
            LOGGER.warn("failed to delete task directory: {}", taskListDir, e);
        }
    }

    private void cleanReferences(String deletedTaskId) {
        List<WriteTodoTaskTool.TaskEntity> all = listAll();
        for (WriteTodoTaskTool.TaskEntity task : all) {
            boolean changed = false;
            if (task.blocks != null && task.blocks.remove(deletedTaskId)) {
                changed = true;
            }
            if (task.blockedBy != null && task.blockedBy.remove(deletedTaskId)) {
                changed = true;
            }
            if (changed) {
                write(task.id, task);
            }
        }
    }

    private void ensureDir() {
        try {
            Files.createDirectories(taskListDir);
        } catch (IOException e) {
            throw new RuntimeException("failed to create task directory: " + taskListDir, e);
        }
    }

    private void writeFile(int id, WriteTodoTaskTool.TaskEntity task) {
        Path file = taskPath(String.valueOf(id));
        try {
            String json = JsonUtil.toJson(task);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("failed to write task file: " + file, e);
        }
    }

    private Path taskPath(String taskId) {
        return taskListDir.resolve(taskId + ".json");
    }
}
