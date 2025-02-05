apply(plugin = "project")
apply(plugin = "azure-maven")
apply(plugin = "publish")
apply(plugin = "licenses")

plugins {
    `java-library`
}

subprojects {
    group = "com.wonder"
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

    dependencies {
        // import a BOM
        implementation(platform("com.wonder:wonder-dependencies:1.0.0"))
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
        implementation("com.wonder:core-ng")
        testImplementation("com.wonder:core-ng-test")
    }
}

configure(subprojects.filter { it.name.endsWith("-interface") || it.name.matches(Regex("(-|\\w)+-interface-v\\d+$")) }) {
    apply(plugin = "lib")
    dependencies {
        implementation("com.wonder:core-ng-api")
    }
}

project(":litellm-library") {
    dependencies {
        implementation("com.wonder:core-ng")
        testImplementation("com.wonder:core-ng-test")
        implementation("com.fasterxml.jackson.core:jackson-core")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    }
}

project(":huggingface-library") {
    dependencies {
        implementation("com.wonder:core-ng")
        testImplementation("com.wonder:core-ng-test")
        implementation("com.fasterxml.jackson.core:jackson-core")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    }
}

project(":language-server-library") {
    dependencies {
        implementation("com.wonder:core-ng")
        testImplementation("com.wonder:core-ng-test")
        implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:${Versions.ECLIPSE_LSP4J_VERSION}")
    }
}


project(":core-ai") {
    dependencies {
        implementation("com.wonder:core-ng")
        implementation(project(":litellm-library"))
        testImplementation("com.wonder:core-ng-test")
        implementation("com.google.guava:guava:${Versions.GOOGLE_GUAVA_JRE_VERSION}")
        implementation("com.theokanning.openai-gpt3-java:client:${Versions.OPENAI_JAVA_VERSION}")
        implementation("com.azure:azure-ai-openai:${Versions.AZURE_OPENAI_JAVA_VERSION}")
        implementation("com.github.spullara.mustache.java:compiler:${Versions.MUSTACHE_JAVA_VERSION}")
        implementation("io.milvus:milvus-sdk-java:${Versions.MILVUS_JAVA_VERSION}")
    }
}


project(":example-service") {
    dependencies {
        implementation(project(":core-ai"))
        implementation(project(":example-service-interface"))
        implementation(project(":huggingface-library"))
        implementation("com.microsoft.azure.cognitiveservices:azure-cognitiveservices-imagesearch:${Versions.AZURE_COGNITIVESERVICES_WEBSEARCH_VERSION}")
    }
}


project(":example-site") {
    apply(plugin = "frontend")
    project.ext["frontendDir"] = "${rootDir}/example-site-frontend"
    dependencies {
        implementation(project(":example-service-interface"))
        implementation(project(":example-site-interface"))
        implementation("com.wonder:azure-library")
    }
}