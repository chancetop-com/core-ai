plugins {
    id("org.graalvm.buildtools.native")
}

graalvmNative {
    toolchainDetection.set(false)
    metadataRepository {
        // the reachability-metadata repo registers jackson's optional DOM/JAXB ext handlers
        // (condition: org.w3c.dom.Node reachable), which drags java.xml/Xerces into the image;
        // the CLI ships its own minimal reflect-config instead (see META-INF/native-image in resources)
        excludedModules.add("com.fasterxml.jackson.core:jackson-databind")
    }
    binaries {
        named("main") {
            javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
            })
            mainClass.set("Main")
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            buildArgs.add("--initialize-at-build-time=core.framework.internal.log.LoggerImpl")
            buildArgs.add("--initialize-at-build-time=core.framework.internal.log.LogLevel")
            buildArgs.add("--initialize-at-build-time=ai.core.cli.log.CliLoggerFactory")
            buildArgs.add("--initialize-at-build-time=ai.core.cli.log.CliLoggerServiceProvider")
            // the cl100k tokenizer model is registered by NativeReflectionFeature via RuntimeResourceAccess;
            // an -H:IncludeResources regex for it silently matched nothing in the full CLI build (see 1.0.48)
            buildArgs.add("-H:IncludeResources=org/jline/.*")
            buildArgs.add("-H:ExcludeResources=org/jline/nativ/.*")
            buildArgs.add("-H:IncludeResources=META-INF/services/org/jline/.*")
            buildArgs.add("-H:IncludeResources=META-INF/services/io.modelcontextprotocol.*")
            buildArgs.add("-H:IncludeResources=META-INF/services/com.agentclientprotocol.*")
            // litellm pricing catalog is only a cost fallback; CLI cost comes from upstream/gateway prices
            buildArgs.add("-H:ExcludeResources=model_prices_and_context_window\\.json")
            buildArgs.add("--initialize-at-run-time=org.slf4j.LoggerFactory")
            buildArgs.add("--features=ai.core.cli.graalvm.NativeReflectionFeature")
        }
    }
}
