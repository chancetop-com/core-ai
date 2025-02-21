apply(plugin = "project")

plugins {
    `java-library`
    `maven-publish`
    kotlin("jvm") version "1.9.20"
}

repositories {
    mavenCentral()
}

subprojects {
    group = "com.chancetop"
    version = "1.0.0"

    repositories {
        maven {
            url = uri("https://maven.codelibs.org/")
            content {
                includeGroup("org.codelibs.elasticsearch.module")
            }
        }
        maven {
            url = uri("https://neowu.github.io/maven-repo/")
            content {
                includeGroupByRegex("core\\.framework.*")
            }
        }
    }

    configure(subprojects.filter { (it.name.endsWith("-interface") || it.name.endsWith("-interface-v2")) }) {
        java {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    if (!plugins.hasPlugin("java")) {
        return@subprojects
    }

    configurations.all {
        resolutionStrategy {
            force("com.squareup.okio:okio:3.2.0")
        }
    }

    if (project.name.startsWith("core-ai") || project.name.endsWith("library")) {
        apply(plugin = "maven-publish")
        val sourceJar by tasks.registering(Jar::class) {
            archiveClassifier.set("sources")
            from(sourceSets.main.get().allSource)
        }
        publishing {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    artifact(sourceJar.get())
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

configure(subprojects.filter { it.name.endsWith("-interface") || it.name.matches(Regex("(-|\\w)+-interface-v\\d+$")) }) {
    apply(plugin = "lib")
    dependencies {
        implementation("core.framework:core-ng-api:${Versions.CORE_FRAMEWORK_VERSION}")
    }
}

project(":litellm-library") {
    dependencies {
        implementation("core.framework:core-ng:${Versions.CORE_FRAMEWORK_VERSION}")
        testImplementation("core.framework:core-ng-test:${Versions.CORE_FRAMEWORK_VERSION}")
        implementation("com.fasterxml.jackson.core:jackson-core:${Versions.JACKSON_VERSION}")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${Versions.JACKSON_VERSION}")
    }
}

project(":huggingface-library") {
    dependencies {
        implementation("core.framework:core-ng:${Versions.CORE_FRAMEWORK_VERSION}")
        testImplementation("core.framework:core-ng-test:${Versions.CORE_FRAMEWORK_VERSION}")
        implementation("com.fasterxml.jackson.core:jackson-core:${Versions.JACKSON_VERSION}")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${Versions.JACKSON_VERSION}")
    }
}

project(":language-server-library") {
    dependencies {
        implementation("core.framework:core-ng:${Versions.CORE_FRAMEWORK_VERSION}")
        testImplementation("core.framework:core-ng-test:${Versions.CORE_FRAMEWORK_VERSION}")
        implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:${Versions.ECLIPSE_LSP4J_VERSION}")
    }
}


project(":core-ai") {
    apply(plugin = "kotlin")
    dependencies {
        implementation(project(":litellm-library"))
        implementation("core.framework:core-ng:${Versions.CORE_FRAMEWORK_VERSION}")
        testImplementation("core.framework:core-ng-test:${Versions.CORE_FRAMEWORK_VERSION}")
        implementation("com.google.guava:guava:${Versions.GOOGLE_GUAVA_JRE_VERSION}")
        implementation("com.theokanning.openai-gpt3-java:client:${Versions.OPENAI_JAVA_VERSION}")
        implementation("com.azure:azure-ai-openai:${Versions.AZURE_OPENAI_JAVA_VERSION}")
        implementation("com.github.spullara.mustache.java:compiler:${Versions.MUSTACHE_JAVA_VERSION}")
        implementation("io.milvus:milvus-sdk-java:${Versions.MILVUS_JAVA_VERSION}")
        implementation("io.modelcontextprotocol:kotlin-sdk:${Versions.MCP_KOTLIN_SDK_VERSION}")
    }
}


project(":example-service") {
    dependencies {
        implementation(project(":core-ai"))
        implementation(project(":example-service-interface"))
        implementation(project(":huggingface-library"))
        implementation(project(":language-server-library"))
        implementation("com.microsoft.azure.cognitiveservices:azure-cognitiveservices-imagesearch:${Versions.AZURE_COGNITIVESERVICES_WEBSEARCH_VERSION}")
    }
}