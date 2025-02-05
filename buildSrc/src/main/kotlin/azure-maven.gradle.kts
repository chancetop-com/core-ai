import org.gradle.kotlin.dsl.extra
import java.util.Properties

val azureProps = Properties()
val azureFile = file("${rootDir}/azure.properties")

if (azureFile.exists()) {
    azureProps.load(azureFile.inputStream())
}
val mavenAccessToken: String by project
extra["mavenAccessToken"] = System.getenv("ARTIFACT_ACCESSTOKEN") ?: azureProps.getProperty("azure.packages.accessToken")

subprojects {
    repositories {
        maven {
            url = uri("https://pkgs.dev.azure.com/foodtruckinc/Wonder/_packaging/maven-local/maven/v1")
            name = "maven-local"
            credentials {
                username = "foodtruckinc"
                password = mavenAccessToken
            }
        }
    }
}
