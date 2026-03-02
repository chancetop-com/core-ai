plugins {
    id("org.graalvm.buildtools.native")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("cai")
            mainClass.set("Main")
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            buildArgs.add("--initialize-at-build-time=core.framework.internal.log.LoggerImpl")
            buildArgs.add("--initialize-at-build-time=core.framework.internal.log.LogLevel")
            buildArgs.add("-H:IncludeResources=com/knuddels/jtokkit/.*")
            buildArgs.add("-H:IncludeResources=org/jline/.*")
            buildArgs.add("--features=ai.core.cli.graalvm.NativeReflectionFeature")
        }
    }
}
