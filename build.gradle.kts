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
        maven {
            url = uri("https://chancetop-com.github.io/maven-repo/")
            content {
                includeGroupByRegex("com\\.chancetop.*")
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

    if (project.name.startsWith("core-ai") || project.name.endsWith("library") || project.name.endsWith("api")) {
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

project(":language-server-library") {
    version = "1.1.0"
    dependencies {
        implementation("core.framework:core-ng:${Versions.CORE_FRAMEWORK_VERSION}")
        testImplementation("core.framework:core-ng-test:${Versions.CORE_FRAMEWORK_VERSION}")
        implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:${Versions.ECLIPSE_LSP4J_VERSION}")
    }
}


val useLocalProjects = project.hasProperty("useLocalProjects")
project(":core-ai") {
    version = "1.1.53"
    dependencies {
        if (useLocalProjects) {
            implementation(project(":core-ai-api"))
        } else {
            implementation("com.chancetop:core-ai-api:1.1.6")
        }
        implementation("core.framework:core-ng:${Versions.CORE_FRAMEWORK_VERSION}")
        testImplementation("core.framework:core-ng-test:${Versions.CORE_FRAMEWORK_VERSION}")
        implementation("com.fasterxml.jackson.core:jackson-core:${Versions.JACKSON_VERSION}")
        implementation("com.azure:azure-ai-openai:${Versions.AZURE_OPENAI_JAVA_VERSION}")
        implementation("com.azure:azure-ai-inference:${Versions.AZURE_INFERENCE_JAVA_VERSION}")
        implementation("com.github.spullara.mustache.java:compiler:${Versions.MUSTACHE_JAVA_VERSION}")
        implementation("io.milvus:milvus-sdk-java:${Versions.MILVUS_JAVA_VERSION}")
        implementation("com.github.jelmerk:hnswlib-core:${Versions.HNSWLIB_JAVA_VERSION}")
        implementation("com.github.jelmerk:hnswlib-utils:${Versions.HNSWLIB_JAVA_VERSION}")
        implementation("com.knuddels:jtokkit:${Versions.JTOKKIT_VERSION}")
    }
}


project(":core-ai-api") {
    version = "1.1.6"
    dependencies {
        implementation("core.framework:core-ng-api:${Versions.CORE_FRAMEWORK_VERSION}")
    }
}


project(":example-service") {
    dependencies {
        implementation(project(":core-ai"))
        implementation(project(":core-ai-api"))
        implementation(project(":example-service-interface"))
        implementation(project(":language-server-library"))
        implementation("com.microsoft.azure.cognitiveservices:azure-cognitiveservices-imagesearch:${Versions.AZURE_COGNITIVESERVICES_WEBSEARCH_VERSION}")
    }
}