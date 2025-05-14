package ai.core.task;

import ai.core.persistence.Persistence;
import ai.core.task.parts.DataPart;
import ai.core.task.parts.FilePart;
import ai.core.task.parts.TextPart;
import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class TaskPersistence implements Persistence<Task> {
    @Override
    public String serialization(Task task) {
        return JSON.toJSON(TaskPersistenceDomain.of(task));
    }

    @Override
    public void deserialization(Task task, String c) {
        var domain = JSON.fromJSON(TaskPersistenceDomain.class, c);
        task.setId(domain.id);
        task.setMetadata(domain.metadata);
        task.setHistory(domain.histories.stream().map(v -> {
            var message = new TaskMessage();
            message.setRole(v.role);
            message.setMetadata(v.metadata);
            message.setParts(v.parts.stream().<Part<?>>map(TaskPersistenceDomain::fromPart).toList());
            return message;
        }).collect(Collectors.toList()));
        task.setArtifacts(domain.artifacts.stream().map(v -> new TaskArtifact(
                v.name,
                v.description,
                v.parts.stream().<Part<?>>map(TaskPersistenceDomain::fromPart).toList(),
                v.metadata,
                v.index,
                v.append,
                v.lastChunk)).collect(Collectors.toList()));
    }

    public static class TaskPersistenceDomain {
        public static TaskPersistenceDomain of(Task task) {
            var domain = new TaskPersistenceDomain();
            domain.id = task.getId();
            domain.metadata = task.getMetadata();
            domain.histories = task.getHistory().stream().map(v -> {
                var message = new TaskMessagePersistenceDomain();
                message.metadata = v.getMetadata();
                message.role = v.getRole();
                message.parts = v.getParts().stream().map(TaskPersistenceDomain::toPartDomain).toList();
                return message;
            }).toList();
            domain.artifacts = task.getArtifacts().stream().map(v -> {
                var artifact = new TaskArtifactPersistenceDomain();
                artifact.name = v.name();
                artifact.description = v.description();
                artifact.metadata = v.metadata();
                artifact.index = v.index();
                artifact.append = v.append();
                artifact.lastChunk = v.lastChunk();
                artifact.parts = v.parts().stream().map(TaskPersistenceDomain::toPartDomain).toList();
                return artifact;
            }).toList();
            return domain;
        }

        public static Part<?> fromPart(PartPersistenceDomain part) {
            if (part.textPart != null) {
                return new TextPart(part.textPart.text);
            }
            if (part.filePart != null) {
                return new FilePart(new FilePart.File(part.filePart.name, part.filePart.mimeType, part.filePart.bytes, part.filePart.uri));
            }
            if (part.dataPart != null) {
                return new DataPart(part.dataPart.metadata);
            }
            throw new IllegalArgumentException("Unsupported part type: " + part.getClass());
        }

        public static PartPersistenceDomain toPartDomain(Part<?> part) {
            var partDomain = new PartPersistenceDomain();
            if (part instanceof TextPart textPart) {
                partDomain.textPart = new TextParPersistenceDomain();
                partDomain.textPart.text = textPart.getText();
            }
            if (part instanceof FilePart filePart) {
                partDomain.filePart = new FilePartPersistenceDomain();
                partDomain.filePart.name = filePart.getFile().name();
                partDomain.filePart.mimeType = filePart.getFile().mimeType();
                partDomain.filePart.bytes = filePart.getFile().bytes();
                partDomain.filePart.uri = filePart.getFile().uri();
            }
            if (part instanceof DataPart dataPart) {
                partDomain.dataPart = new DataPartPersistenceDomain();
                partDomain.dataPart.metadata = dataPart.getMetadata();
            }
            return partDomain;
        }

        @Property(name = "id")
        public String id;
        @Property(name = "histories")
        public List<TaskMessagePersistenceDomain> histories;
        @Property(name = "artifacts")
        public List<TaskArtifactPersistenceDomain> artifacts;
        @Property(name = "metadata")
        public Map<String, String> metadata;
    }

    public static class TaskMessagePersistenceDomain {
        @Property(name = "role")
        public TaskRoleType role;
        @Property(name = "parts")
        public List<PartPersistenceDomain> parts;
        @Property(name = "metadata")
        public Map<String, String> metadata;
    }

    public static class TaskArtifactPersistenceDomain {
        @Property(name = "name")
        public String name;
        @Property(name = "description")
        public String description;
        @Property(name = "parts")
        public List<PartPersistenceDomain> parts;
        @Property(name = "metadata")
        public Map<String, String> metadata;
        @Property(name = "index")
        public Integer index;
        @Property(name = "append")
        public Boolean append;
        @Property(name = "lastChunk")
        public Boolean lastChunk;
    }

    public static class PartPersistenceDomain {
        @Property(name = "text_part")
        public TextParPersistenceDomain textPart;
        @Property(name = "file_part")
        public FilePartPersistenceDomain filePart;
        @Property(name = "data_part")
        public DataPartPersistenceDomain dataPart;
    }

    public static class DataPartPersistenceDomain {
        @Property(name = "metadata")
        public Map<String, String> metadata;
    }

    public static class FilePartPersistenceDomain {
        @Property(name = "name")
        public String name;
        @Property(name = "mime_type")
        public String mimeType;
        @Property(name = "bytes")
        public String bytes;
        @Property(name = "uri")
        public String uri;
    }

    public static class TextParPersistenceDomain {
        @Property(name = "text")
        public String text;
    }
}
