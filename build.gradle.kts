import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins { base; jacoco }

extensions.configure<JacocoPluginExtension> { toolVersion = "0.8.15" }
dependencyLocking { lockAllConfigurations() }

allprojects {
    group = "io.ajdm"
    version = "0.1.0-SNAPSHOT"
    repositories {
        // Optional local mirror for offline development; ordinary builds use Maven Central.
        (providers.gradleProperty("ajdmMavenRepository").orNull
            ?: providers.environmentVariable("AJDM_MAVEN_REPOSITORY").orNull)?.let { maven { url = uri(it) } }
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    extensions.configure<JacocoPluginExtension> { toolVersion = "0.8.15" }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        maxParallelForks = 1
        testLogging { events("failed", "skipped") }
    }
    dependencyLocking { lockAllConfigurations() }
}

tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }); dependsOn("backendCoverageGate") }

// Include exercised backend bytecode from every test JVM, including CLI/UI integration suites.
val backendProjects = subprojects.filter { it.name !in setOf("ajdm-ui", "ajdm-bench", "ajdm-verify") }
val backendClasses = files(backendProjects.map { it.extensions.getByType<SourceSetContainer>().named("main").get().output })
val backendSources = files(backendProjects.map { it.extensions.getByType<SourceSetContainer>().named("main").get().allSource.srcDirs })
val backendExecutionData = files(subprojects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
tasks.register<JacocoReport>("backendCoverage") {
    group = "verification"
    description = "Aggregate backend coverage from all project test suites."
    dependsOn(subprojects.map { "${it.path}:test" })
    executionData.setFrom(backendExecutionData)
    classDirectories.setFrom(backendClasses)
    sourceDirectories.setFrom(backendSources)
    reports { xml.required.set(true); html.required.set(true); csv.required.set(true) }
}
tasks.register<JacocoCoverageVerification>("backendCoverageGate") {
    group = "verification"
    description = "Enforce the PRD's 80% line coverage on engine and store."
    dependsOn("backendCoverage")
    executionData.setFrom(backendExecutionData)
    classDirectories.setFrom(backendClasses)
    sourceDirectories.setFrom(backendSources)
    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("io.ajdm.engine", "io.ajdm.store")
            limit { counter = "LINE"; value = "COVEREDRATIO"; minimum = "0.80".toBigDecimal() }
        }
    }
}
