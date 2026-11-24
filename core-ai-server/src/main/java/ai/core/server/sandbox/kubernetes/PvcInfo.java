package ai.core.server.sandbox.kubernetes;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

/**
 * Kubernetes PersistentVolumeClaim resource, only the fields the workspace volume lifecycle reads.
 *
 * @author stephen
 */
public class PvcInfo {
    @Property(name = "metadata")
    public PvcMetadata metadata;
    @Property(name = "spec")
    public PvcSpec spec;
    @Property(name = "status")
    public PvcStatus status;

    public String getPhase() {
        return status != null ? status.phase : null;
    }

    public String getStorageClass() {
        return spec != null ? spec.storageClassName : null;
    }

    public String getRequestedStorage() {
        if (spec == null || spec.resources == null || spec.resources.requests == null) return null;
        return spec.resources.requests.get("storage");
    }

    public static class PvcMetadata {
        @Property(name = "name")
        public String name;
        @Property(name = "namespace")
        public String namespace;
        @Property(name = "uid")
        public String uid;
        @Property(name = "labels")
        public Map<String, String> labels;
        @Property(name = "creationTimestamp")
        public String creationTimestamp;
    }

    public static class PvcSpec {
        @Property(name = "accessModes")
        public List<String> accessModes;
        @Property(name = "storageClassName")
        public String storageClassName;
        @Property(name = "resources")
        public Resources resources;

        public static class Resources {
            @Property(name = "requests")
            public Map<String, String> requests;
        }
    }

    public static class PvcStatus {
        @Property(name = "phase")
        public String phase;
        @Property(name = "capacity")
        public Map<String, String> capacity;
        @Property(name = "conditions")
        public PvcCondition[] conditions;
    }

    public static class PvcCondition {
        @Property(name = "type")
        public String type;
        @Property(name = "status")
        public String status;
        @Property(name = "reason")
        public String reason;
        @Property(name = "message")
        public String message;
    }
}
