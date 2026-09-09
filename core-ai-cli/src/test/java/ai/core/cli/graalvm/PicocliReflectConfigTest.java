package ai.core.cli.graalvm;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.AnnotatedElement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * picocli instantiates every command, mixin and annotated base class reflectively; in the native image that only
 * works for classes listed in META-INF/native-image/picocli-generated/reflect-config.json, so a new subcommand that
 * is not registered crashes the whole CLI at startup ("Cannot instantiate ...: the class has no constructor").
 */
class PicocliReflectConfigTest {
    private static final Path REFLECT_CONFIG = Path.of("src/main/resources/META-INF/native-image/picocli-generated/reflect-config.json");

    @Test
    void everyCommandClassIsRegisteredForReflection() throws ClassNotFoundException {
        var registered = registeredClassNames();
        var required = new TreeSet<String>();
        collect(new CommandLine(Class.forName("Main")), required);

        var missing = required.stream().filter(name -> !registered.contains(name)).toList();
        assertTrue(missing.isEmpty(), "add these picocli classes to " + REFLECT_CONFIG + ": " + missing);
    }

    private Set<String> registeredClassNames() {
        try {
            List<Map<String, Object>> entries = JsonUtil.fromJson(new TypeReference<>() {
            }, Files.readString(REFLECT_CONFIG, StandardCharsets.UTF_8));
            return entries.stream().map(entry -> (String) entry.get("name")).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void collect(CommandLine command, Set<String> names) {
        var spec = command.getCommandSpec();
        collectHierarchy(spec.userObject().getClass(), names);
        for (var mixin : spec.mixins().values()) {
            collectHierarchy(mixin.userObject().getClass(), names);
        }
        for (var sub : new LinkedHashSet<>(command.getSubcommands().values())) {
            collect(sub, names);
        }
    }

    // picocli reads @Option/@Mixin fields declared on superclasses too, so those must be registered as well
    private void collectHierarchy(Class<?> clazz, Set<String> names) {
        for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
            if (current == clazz || hasPicocliAnnotations(current)) {
                names.add(current.getName());
            }
        }
    }

    private boolean hasPicocliAnnotations(Class<?> clazz) {
        return Stream.concat(Stream.of((AnnotatedElement) clazz), Stream.of(clazz.getDeclaredFields()))
            .flatMap(element -> Stream.of(element.getAnnotations()))
            .anyMatch(annotation -> annotation.annotationType().getName().startsWith("picocli."));
    }
}
