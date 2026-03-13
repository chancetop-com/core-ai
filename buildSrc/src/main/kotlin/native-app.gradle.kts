plugins {
    id("org.graalvm.buildtools.native")
}

graalvmNative {
    toolchainDetection.set(false)
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
            buildArgs.add("--initialize-at-build-time=ai.core.cli.log.CliLogger")
            buildArgs.add("--initialize-at-build-time=ai.core.cli.log.CliLoggerFactory")
            buildArgs.add("--initialize-at-build-time=ai.core.cli.log.CliLoggerServiceProvider")
            buildArgs.add("-H:IncludeResources=com/knuddels/jtokkit/.*")
            buildArgs.add("-H:IncludeResources=org/jline/.*")
            buildArgs.add("-H:IncludeResources=META-INF/services/org/jline/.*")
            buildArgs.add("-H:IncludeResources=META-INF/services/io.modelcontextprotocol.*")
            buildArgs.add("-Dslf4j.provider=ai.core.cli.log.CliLoggerServiceProvider")
            buildArgs.add("--features=ai.core.cli.graalvm.NativeReflectionFeature")
        }
    }
}
