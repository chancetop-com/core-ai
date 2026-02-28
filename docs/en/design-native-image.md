# GraalVM Native Image for core-ai-cli

## Overview

`core-ai-cli` is distributed as a GraalVM native binary for fast startup and zero JVM dependency. Native image imposes restrictions on reflection, dynamic class loading, and certain I/O patterns. This document records the issues encountered and the solutions applied.

## Build Configuration

**Plugin**: `org.graalvm.buildtools:native-gradle-plugin:0.10.5`

**Build args** (`native-app.gradle.kts`):

| Arg | Purpose |
|---|---|
| `--no-fallback` | Pure native image, no JVM fallback |
| `--enable-native-access=ALL-UNNAMED` | Allow Foreign Function & Memory (FFM) API |
| `--initialize-at-build-time=core.framework.internal.log.LoggerImpl` | core-ng SLF4J binding uses static fields |
| `--initialize-at-build-time=core.framework.internal.log.LogLevel` | Same as above |
| `-H:IncludeResources=com/knuddels/jtokkit/.*` | Tokenizer data files |
| `-H:IncludeResources=org/jline/.*` | JLine terminal capability databases |
| `--features=ai.core.cli.graalvm.NativeReflectionFeature` | Auto-register reflection for domain classes |

## Issue 1: JLine Terminal Falls Back to Dumb Terminal

### Symptom

```
WARNING: Unable to create a system terminal, creating a dumb terminal
```

In JVM mode (IDE), the dumb terminal still works. In native image on Windows, JLine's `NonBlockingReader` (which uses a background pump thread) reads stdin unreliably, causing:

- `readLine()` intermittently throws `EndOfFileException` (premature EOF)
- Some REPL commands work, others trigger immediate exit ("Goodbye!")
- Regular chat messages hang forever (unrelated agent execution issue, see Issue 2)

### Root Cause

JLine requires a platform-specific **TerminalProvider** (`jline-terminal-ffm`, `jline-terminal-jni`, or `jline-terminal-jansi`) to create a real system terminal. Without one, it falls back to `DumbTerminal`, which wraps `System.in` in a `NonBlockingInputStream` with a background thread. This pump thread is unreliable in GraalVM native image on Windows.

### Solution

In `TerminalUI`, detect dumb terminal and bypass JLine's `LineReader` entirely. Use a plain `BufferedReader(new InputStreamReader(System.in))` for input:

```java
boolean isDumb = Terminal.TYPE_DUMB.equals(terminal.getType())
        || Terminal.TYPE_DUMB_COLOR.equals(terminal.getType());

if (isDumb) {
    // bypass JLine's NonBlockingReader, read stdin directly
    this.jlineReader = null;
    this.simpleReader = new BufferedReader(new InputStreamReader(System.in));
} else {
    this.jlineReader = LineReaderBuilder.builder()
            .terminal(terminal).appName("core-ai").build();
    this.simpleReader = null;
}
```

**Trade-off**: dumb terminal mode loses JLine features (line editing, history, tab completion). Output still goes through `terminal.writer()` which works fine.

## Issue 2: Jackson Serialization Fails — Missing Reflection Metadata

### Symptom

```
No serializer found for class ai.core.llm.domain.CompletionRequest
and no properties discovered to create BeanSerializer.
This appears to be a native image, in which case you may need to
configure reflection for the class that is to be serialized
```

### Root Cause

Jackson discovers bean properties (fields, getters) via reflection. The project uses a custom `CoreAiAnnotationIntrospector` that reads `@Property` annotations from public fields. In native image, reflection is blocked unless explicitly registered.

### Solution: `NativeReflectionFeature`

Instead of maintaining a large `reflect-config.json` (which does NOT support wildcards), a GraalVM `Feature` class scans packages at build time and registers all classes for reflection automatically.

```java
public class NativeReflectionFeature implements Feature {
    private static final String[] PACKAGES = {
            "ai/core/llm/domain",
            "ai/core/api/jsonschema",
    };

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        for (String pkg : PACKAGES) {
            scanPackage(access, pkg);  // finds all .class files in directory/JAR
        }
    }

    private void registerClass(BeforeAnalysisAccess access, String className) {
        var clazz = access.findClassByName(className);
        if (clazz == null) return;

        RuntimeReflection.register(clazz);
        // register individual members for INVOCATION (not just query)
        for (var c : clazz.getDeclaredConstructors()) RuntimeReflection.register(c);
        for (var m : clazz.getDeclaredMethods()) RuntimeReflection.register(m);
        for (var f : clazz.getDeclaredFields()) RuntimeReflection.register(f);
    }
}
```

**Key detail for GraalVM 25+**: `registerAllDeclaredConstructors(clazz)` only enables **querying** (e.g. `clazz.getDeclaredConstructors()`). To enable **invocation** (e.g. `constructor.newInstance()`), each member must be registered individually via `RuntimeReflection.register(constructor)`.

**Adding new packages**: add the package path to `PACKAGES` array. All classes (including nested classes like `Content$ImageUrl`) are automatically discovered and registered.

**Dependency**: `compileOnly("org.graalvm.sdk:nativeimage:${Versions.GRAALVM_SDK_VERSION}")` — only needed at compile time, not at runtime.

## Dependency Summary

| Dependency | Scope | Purpose |
|---|---|---|
| `org.graalvm.sdk:nativeimage` | compileOnly | Feature API for reflection registration |
| `org.jline:jline-terminal` | implementation | Terminal abstraction |
| `org.jline:jline-reader` | implementation | Line reader (used when real terminal available) |
| `info.picocli:picocli` | implementation | CLI argument parsing (GraalVM-friendly) |
| `info.picocli:picocli-codegen` | annotationProcessor | Generates reflect-config.json for picocli |

## Adding Reflection for New Packages

When agent execution hits a new `MissingReflectionRegistrationError`, the fix is to add the package to `NativeReflectionFeature.PACKAGES`:

```java
private static final String[] PACKAGES = {
        "ai/core/llm/domain",
        "ai/core/api/jsonschema",
        "ai/core/api/mcp/schema/tool",  // add new package here
};
```

For individual classes outside the scanned packages, add to `EXTRA_CLASSES`:

```java
private static final String[] EXTRA_CLASSES = {
        "core.framework.api.json.Property",
        "ai.core.api.tool.function.CoreAiParameter",
};
```

Rebuild and the new classes are automatically registered.
