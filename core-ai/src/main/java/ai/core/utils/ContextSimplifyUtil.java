package ai.core.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public final class ContextSimplifyUtil<T> {
    public static <T> ContextSimplifyUtil<T> of(List<T> items) {
        return new ContextSimplifyUtil<>(items);
    }

    private final List<T> items;
    private final List<Object[]> savedValues = new ArrayList<>();
    @SuppressWarnings("rawtypes")
    private final List<BiConsumer> setters = new ArrayList<>();
    private Map<String, String> originalToSimple;
    private Map<String, String> simpleToOriginal;

    private ContextSimplifyUtil(List<T> items) {
        this.items = items;
    }

    public ContextSimplifyUtil<T> simplifyIds(Function<T, String> getter, BiConsumer<T, String> setter) {
        var o2s = new HashMap<String, String>();
        var s2o = new HashMap<String, String>();
        int counter = 1;
        for (var item : items) {
            var id = getter.apply(item);
            if (id != null && !o2s.containsKey(id)) {
                var simple = String.valueOf(counter++);
                o2s.put(id, simple);
                s2o.put(simple, id);
            }
        }
        this.originalToSimple = Map.copyOf(o2s);
        this.simpleToOriginal = Map.copyOf(s2o);
        return map(getter, setter, id -> originalToSimple.getOrDefault(id, id));
    }

    public <V> ContextSimplifyUtil<T> map(Function<T, V> getter, BiConsumer<T, V> setter, Function<V, V> mapper) {
        var saved = new Object[items.size()];
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var original = getter.apply(item);
            saved[i] = original;
            if (original != null) {
                setter.accept(item, mapper.apply(original));
            }
        }
        savedValues.add(saved);
        setters.add(setter);
        return this;
    }

    public <V> ContextSimplifyUtil<T> nullify(Function<T, V> getter, BiConsumer<T, V> setter) {
        var saved = new Object[items.size()];
        for (int i = 0; i < items.size(); i++) {
            saved[i] = getter.apply(items.get(i));
            setter.accept(items.get(i), null);
        }
        savedValues.add(saved);
        setters.add(setter);
        return this;
    }

    public String toSimple(String originalId) {
        if (originalToSimple == null) return originalId;
        return originalToSimple.getOrDefault(originalId, originalId);
    }

    public String toOriginal(String simpleId) {
        if (simpleToOriginal == null) return null;
        return simpleToOriginal.get(simpleId);
    }

    public <R> R apply(Supplier<R> action) {
        try {
            return action.get();
        } finally {
            restore();
        }
    }

    @SuppressWarnings("unchecked")
    public void restore() {
        for (int f = savedValues.size() - 1; f >= 0; f--) {
            var saved = savedValues.get(f);
            var setter = setters.get(f);
            for (int i = 0; i < items.size(); i++) {
                setter.accept(items.get(i), saved[i]);
            }
        }
    }
}
