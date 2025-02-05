import com.github.jk1.license.render.*

subprojects {
    if (!childProjects.isEmpty()) return@subprojects   // ignore parent project
    apply(plugin = "com.github.jk1.dependency-license-report")

    configure<com.github.jk1.license.LicenseReportExtension> {
        configurations = listOf(
            "compileClasspath",
            "testCompileClasspath",
            "runtimeClasspath",
            "testRuntimeClasspath",
        ).toTypedArray()
        excludeGroups = listOf("com.wonder.*").toTypedArray()
        excludeBoms = true
        renderers = listOf(SimpleHtmlReportRenderer(), CsvReportRenderer()).toTypedArray()
    }
}
