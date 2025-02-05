import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

plugins {
    `maven-publish`
}

@Serializable
data class Publish(
    val modules: List<Module>
)

@Serializable
data class Module(
    val name: String,
    val artifactId: String,
    val version: String
)

var json = Json { ignoreUnknownKeys = true }
var publishConf = json.decodeFromString<Publish>(rootProject.file("publish.json").readText())

val publishMavenURL = project.findProperty("mavenURL") ?: "https://pkgs.dev.azure.com/foodtruckinc/Wonder/_packaging/maven-local/maven/v1"
val publishMavenAccessToken: String? = project.findProperty("mavenAccessToken")?.toString() ?: System.getenv("MAVEN_ACCESS_TOKEN")

publishConf.modules.forEach { module ->
    project(module.name) {
        pluginManager.apply(MavenPublishPlugin::class.java)

        publishing {
            publications {
                register("apiInterface", MavenPublication::class) {
                    groupId = "com.wonder"
                    artifactId = module.artifactId
                    version = module.version
                    from(components["java"])
                }
            }
            repositories {
                maven {
                    url = uri(publishMavenURL)
                    credentials {
                        username = "foodtruckinc"
                        password = publishMavenAccessToken
                    }
                }
            }
        }
    }
}