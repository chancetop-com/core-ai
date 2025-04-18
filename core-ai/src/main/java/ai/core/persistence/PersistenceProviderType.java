package ai.core.persistence;

/**
 * @author stephen
 */
public enum PersistenceProviderType {
    REDIS("redis"),
    FILE("file"),
    TEMPORARY("temporary");

    private final String name;

    PersistenceProviderType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static PersistenceProviderType fromName(String name) {
        for (var type : PersistenceProviderType.values()) {
            if (type.getName().equalsIgnoreCase(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("No enum constant " + PersistenceProviderType.class.getCanonicalName() + "." + name);
    }
}
