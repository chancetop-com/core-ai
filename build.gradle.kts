apply(plugin = "project")

plugins {
    `java-library`
    `maven-publish`
}


repositories {
    mavenCentral()
}

subprojects {
    group = "com.chancetop"

    repositories {
        maven {
            url = uri("https://neowu.github.io/maven-repo/")
            content {
                includeGroupByRegex("core\\.framework.*")
            }
        }
        maven {
            url = uri("https://chancetop-com.github.io/maven-repo/")
            content {
                includeGroupByRegex("com\\.chancetop.*")
            }
        }
    }

    if (!plugins.hasPlugin("java")) {
        return@subprojects
    }

    if (project.name.endsWith("core-ai") || project.name.endsWith("core-ai-api")) {
        the<JavaPluginExtension>().withSourcesJar()
        apply(plugin = "maven-publish")
        publishing {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                }
            }

            repositories {
                maven {
                    url = uri(System.getenv("MAVEN_REPO") ?: "${System.getProperty("user.home")}/.m2/repository")
                }
            }
        }
    }
}

configure(
    subprojects.filter {
        it.name.endsWith("-service") ||
                it.name.endsWith("-server") ||
                it.name.matches(Regex("(-|\\w)+-service-v\\d+$")) ||
                it.name.endsWith("-site") ||
                it.name.matches(Regex("(-|\\w)+-site-v\\d+$")) ||
                it.name.endsWith("-portal") ||
                it.name.endsWith("-cronjob")
    }
) {
    apply(plugin = "app")
    dependencies {
        implementation("core.framework:core-ng:${Versions.CORE_FRAMEWORK_VERSION}")
        testImplementation("core.framework:core-ng-test:${Versions.CORE_FRAMEWORK_VERSION}")
    }
}


val useLocalProjects = System.getenv("CORE_AI_USE_LOCAL_PROJECTS")?.toBoolean() ?: false
project(":core-ai") {
    apply(plugin = "java-library")
    version = ProjectVersions.CORE_AI_VERSION
    dependencies {
        implementation(project(":core-ai-api"))
        implementation("core.framework:core-ng:${Versions.CORE_FRAMEWORK_VERSION}")
        testImplementation("core.framework:core-ng-test:${Versions.CORE_FRAMEWORK_VERSION}")
        api("com.fasterxml.jackson.core:jackson-core:${Versions.JACKSON_VERSION}")
        implementation("com.github.spullara.mustache.java:compiler:${Versions.MUSTACHE_JAVA_VERSION}")
        implementation("io.milvus:milvus-sdk-java:${Versions.MILVUS_JAVA_VERSION}")
        implementation("com.github.jelmerk:hnswlib-core:${Versions.HNSWLIB_JAVA_VERSION}")
        implementation("com.github.jelmerk:hnswlib-utils:${Versions.HNSWLIB_JAVA_VERSION}")
        implementation("com.knuddels:jtokkit:${Versions.JTOKKIT_VERSION}")
        // tricky part: self-defined sse module
        implementation("com.fasterxml.jackson.core:jackson-databind:${Versions.JACKSON_VERSION}")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${Versions.JACKSON_VERSION}")
        implementation("io.undertow:undertow-core:${Versions.UNDERTOW_CORE_VERSION}")
        // OpenTelemetry for tracing
        implementation("io.opentelemetry:opentelemetry-api:${Versions.OPENTELEMETRY_VERSION}")
        implementation("io.opentelemetry:opentelemetry-sdk:${Versions.OPENTELEMETRY_VERSION}")
        implementation("io.opentelemetry:opentelemetry-exporter-otlp:${Versions.OPENTELEMETRY_VERSION}")
        implementation("com.openai:openai-java:${Versions.OPENAI_JAVA_CLIENT_VERSION}")
        // MCP SDK
        implementation("io.modelcontextprotocol.sdk:mcp:${Versions.MCP_SDK_VERSION}")
        // SQLite JDBC for long-term memory storage
        implementation("org.xerial:sqlite-jdbc:${Versions.SQLITE_JDBC_VERSION}")
        // SnakeYAML for Skills YAML frontmatter parsing
        implementation("org.yaml:snakeyaml:${Versions.SNAKEYAML_VERSION}")
        // Jsoup for HTML parsing (DuckDuckGo search provider)
        implementation("org.jsoup:jsoup:${Versions.JSOUP_VERSION}")
    }
}


project(":core-ai-api") {
    version = ProjectVersions.CORE_AI_API_VERSION
    dependencies {
        implementation("core.framework:core-ng-api:${Versions.CORE_FRAMEWORK_VERSION}")
    }
}


project(":core-ai-server") {
    version = ProjectVersions.CORE_AI_SERVER_VERSION
    apply(plugin = "app")
    tasks.register<Exec>("npmInstallServer") {
        group = "build"
        workingDir = rootDir.resolve("core-ai-frontend")
        commandLine(Frontend.commandLine(listOf("npm", "install", "--legacy-peer-deps")))
    }
    tasks.register<Exec>("buildFrontendServer") {
        group = "build"
        dependsOn("npmInstallServer")
        workingDir = rootDir.resolve("core-ai-frontend")
        commandLine(Frontend.commandLine(listOf("npm", "run", "build")))
    }
    tasks.register<Copy>("copyFrontendServer") {
        group = "build"
        dependsOn("buildFrontendServer")
        from(rootDir.resolve("core-ai-frontend/build/dist"))
        into(layout.buildDirectory.dir("install/core-ai-server/web"))
    }
    tasks.named("installDist") {
        finalizedBy("copyFrontendServer")
    }
    dependencies {
        implementation(project(":core-ai"))
        implementation(project(":core-ai-api"))
        implementation("core.framework:core-ng:${Versions.CORE_FRAMEWORK_VERSION}")
        implementation("core.framework:core-ng-mongo:${Versions.CORE_FRAMEWORK_VERSION}")
        // BouncyCastle for password hashing
        implementation("org.bouncycastle:bcprov-jdk18on:1.79")
        // Jackson for trace JSON serialization
        implementation("com.fasterxml.jackson.core:jackson-databind:${Versions.JACKSON_VERSION}")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${Versions.JACKSON_VERSION}")
        // OTLP protobuf for trace ingestion
        implementation("io.opentelemetry.proto:opentelemetry-proto:${Versions.OPENTELEMETRY_PROTO_VERSION}")
        // OpenTelemetry SDK for local SpanProcessor
        implementation("io.opentelemetry:opentelemetry-sdk:${Versions.OPENTELEMETRY_VERSION}")
        implementation("io.opentelemetry:opentelemetry-api:${Versions.OPENTELEMETRY_VERSION}")
        // Redis client for distributed messaging
        implementation("redis.clients:jedis:${Versions.JEDIS_VERSION}")
        testImplementation("core.framework:core-ng-test:${Versions.CORE_FRAMEWORK_VERSION}")
    }
    tasks.withType<Test> {
        setProperty("failOnNoDiscoveredTests", false)
    }
}

project(":core-ai-cli") {
    version = ProjectVersions.CORE_AI_CLI_VERSION
    apply(plugin = "application")
    apply(plugin = "native-app")
    the<JavaApplication>().mainClass.set("Main")
    the<JavaApplication>().applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
    dependencies {
        implementation(project(":core-ai"))
        implementation(project(":core-ai-api"))
        implementation("core.framework:core-ng-api:${Versions.CORE_FRAMEWORK_VERSION}")
        implementation("org.slf4j:slf4j-api:2.0.17")
        implementation("com.fasterxml.jackson.core:jackson-databind:${Versions.JACKSON_VERSION}")
        // JLine 3 for modern CLI experience
        implementation("org.jline:jline-terminal:${Versions.JLINE_VERSION}")
        implementation("org.jline:jline-terminal-ffm:${Versions.JLINE_VERSION}")
        implementation("org.jline:jline-reader:${Versions.JLINE_VERSION}")
        implementation("org.jline:jline-style:${Versions.JLINE_VERSION}")
        // picocli for CLI arg parsing (GraalVM native-image friendly)
        implementation("info.picocli:picocli:${Versions.PICOCLI_VERSION}")
        annotationProcessor("info.picocli:picocli-codegen:${Versions.PICOCLI_VERSION}")
        // Undertow for embedded ACP web server (--serve mode)
        implementation("io.undertow:undertow-core:${Versions.UNDERTOW_CORE_VERSION}")
        // GraalVM SDK for native-image Feature (compile-time only)
        compileOnly("org.graalvm.sdk:nativeimage:${Versions.GRAALVM_SDK_VERSION}")
        testImplementation("core.framework:core-ng-test:${Versions.CORE_FRAMEWORK_VERSION}")
    }
    tasks.withType<JavaExec> {
        standardInput = System.`in`
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        systemProperty("org.jline.terminal.type", "xterm-256color")
        System.getProperty("core.ai.debug")?.let { systemProperty("core.ai.debug", it) }
        outputs.upToDateWhen { false }
    }
    tasks.register<Exec>("npmInstall") {
        group = "build"
        workingDir = rootDir.resolve("core-ai-frontend")
        commandLine(Frontend.commandLine(listOf("npm", "install", "--legacy-peer-deps")))
    }
    tasks.register<Exec>("buildFrontend") {
        group = "build"
        dependsOn("npmInstall")
        workingDir = rootDir.resolve("core-ai-frontend")
        commandLine(Frontend.commandLine(listOf("npm", "run", "build")))
    }
    tasks.register<Copy>("copyFrontend") {
        group = "build"
        dependsOn("buildFrontend")
        from(rootDir.resolve("core-ai-frontend/build/dist"))
        into(layout.buildDirectory.dir("resources/main/web"))
    }
    tasks.named("processResources") {
        dependsOn("copyFrontend")
    }
}

project(":core-ai-benchmark") {
    version = ProjectVersions.CORE_AI_BENCHMARK_VERSION
    apply(plugin = "app")
    dependencies {
        implementation(project(":core-ai"))
        implementation(project(":core-ai-api"))
        implementation("core.framework:core-ng:${Versions.CORE_FRAMEWORK_VERSION}")
        testImplementation("core.framework:core-ng-test:${Versions.CORE_FRAMEWORK_VERSION}")
    }
    tasks.withType<Test> {
        setProperty("failOnNoDiscoveredTests", false)
    }
}
