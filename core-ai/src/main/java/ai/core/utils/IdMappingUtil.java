package ai.core.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * @author stephen
 */
public final class IdMappingUtil<K> {
    public static <K> IdMappingUtil<K> fromIds(Collection<K> ids) {
        return fromIds(ids, String::valueOf);
    }

    public static <K> IdMappingUtil<K> fromIds(Collection<K> ids, Function<Integer, String> idGenerator) {
        var originalToSimple = new HashMap<K, String>();
        var simpleToOriginal = new HashMap<String, K>();
        int counter = 1;
        for (K id : ids) {
            if (id == null || originalToSimple.containsKey(id)) continue;
            var simpleId = idGenerator.apply(counter++);
            originalToSimple.put(id, simpleId);
            simpleToOriginal.put(simpleId, id);
        }
        return new IdMappingUtil<>(Map.copyOf(originalToSimple), Map.copyOf(simpleToOriginal));
    }

    public static <T, K> IdMappingUtil<K> from(Iterable<T> items, Function<T, K> getId) {
        return from(items, getId, String::valueOf);
    }

    public static <T, K> IdMappingUtil<K> from(Iterable<T> items, Function<T, K> getId, Function<Integer, String> idGenerator) {
        var originalToSimple = new HashMap<K, String>();
        var simpleToOriginal = new HashMap<String, K>();
        int counter = 1;
        for (var item : items) {
            K originalId = getId.apply(item);
            if (originalId == null || originalToSimple.containsKey(originalId)) continue;
            var simpleId = idGenerator.apply(counter++);
            originalToSimple.put(originalId, simpleId);
            simpleToOriginal.put(simpleId, originalId);
        }
        return new IdMappingUtil<>(Map.copyOf(originalToSimple), Map.copyOf(simpleToOriginal));
    }

    private final Map<K, String> originalToSimple;
    private final Map<String, K> simpleToOriginal;

    private IdMappingUtil(Map<K, String> originalToSimple, Map<String, K> simpleToOriginal) {
        this.originalToSimple = originalToSimple;
        this.simpleToOriginal = simpleToOriginal;
    }

    public String toSimple(K originalId) {
        var simpleId = originalToSimple.get(originalId);
        return simpleId != null ? simpleId : String.valueOf(originalId);
    }

    public K toOriginal(String simpleId) {
        return simpleToOriginal.get(simpleId);
    }

    public <T> void simplifyIds(Iterable<T> items, Function<T, K> getId, BiConsumer<T, String> setId) {
        if (items == null) return;
        for (var item : items) {
            var simpleId = originalToSimple.get(getId.apply(item));
            if (simpleId != null) {
                setId.accept(item, simpleId);
            }
        }
    }

    public <T> void restoreIds(Iterable<T> items, Function<T, String> getId, BiConsumer<T, K> setId) {
        if (items == null) return;
        for (var item : items) {
            var originalId = simpleToOriginal.get(getId.apply(item));
            if (originalId != null) {
                setId.accept(item, originalId);
            }
        }
    }

    public <T> List<T> simplifyAll(List<T> items, Function<T, K> getId, BiFunction<T, String, T> withId) {
        if (items == null) return List.of();
        var result = new ArrayList<T>(items.size());
        for (var item : items) {
            var simpleId = originalToSimple.get(getId.apply(item));
            result.add(simpleId != null ? withId.apply(item, simpleId) : item);
        }
        return result;
    }

    public <T> List<T> restoreAll(List<T> items, Function<T, String> getId, BiFunction<T, K, T> withId) {
        if (items == null) return List.of();
        var result = new ArrayList<T>(items.size());
        for (var item : items) {
            var originalId = simpleToOriginal.get(getId.apply(item));
            result.add(originalId != null ? withId.apply(item, originalId) : item);
        }
        return result;
    }
}
