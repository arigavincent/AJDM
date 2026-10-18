plugins { application }
val javafxPlatform = providers.gradleProperty("javafxPlatform").orElse(provider { when {
    System.getProperty("os.name").lowercase().contains("win") -> "win"
    System.getProperty("os.name").lowercase().contains("mac") -> if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
    else -> if (System.getProperty("os.arch") == "aarch64") "linux-aarch64" else "linux"
} }).get()
dependencies {
    implementation(project(":ajdm-ipc"))
    for (module in listOf("base", "graphics", "controls", "fxml")) {
        implementation("org.openjfx:javafx-$module:25.0.4:$javafxPlatform") { isTransitive = false }
    }
    testImplementation(project(":ajdm-bench"))
}
// The Terms of Use, Privacy Policy and third-party notices shown in Help come from the same files as the repository's.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("docs")) { include("TERMS_OF_USE.md", "PRIVACY_POLICY.md"); into("io/ajdm/ui/legal") }
    from(rootProject.file("packaging/windows")) { include("THIRD-PARTY-NOTICES.md"); into("io/ajdm/ui/legal") }
}
application {
    mainClass = "io.ajdm.ui.UiLauncher"
    applicationName = "ajdm-ui"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED,javafx.graphics")
}

// Keep AJDM on the classpath and load JavaFX's native modules through their supported module path.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val unix = unixScript.readText()
        val unixMarker = "-classpath \"\$CLASSPATH\""
        check(unix.contains(unixMarker)) { "Gradle launcher template changed" }
        unixScript.writeText(unix.replace(unixMarker, "--module-path \"\$APP_HOME/lib\" --add-modules javafx.controls,javafx.fxml $unixMarker"))
        val windows = windowsScript.readText()
        val windowsMarker = "-classpath \"%CLASSPATH%\""
        check(windows.contains(windowsMarker)) { "Gradle Windows launcher template changed" }
        windowsScript.writeText(windows.replace(windowsMarker, "--module-path \"%APP_HOME%\\lib\" --add-modules javafx.controls,javafx.fxml $windowsMarker"))
    }
}
val javafxModulePath = configurations.runtimeClasspath.map { files -> files.filter { it.name.startsWith("javafx-") }.asPath }
tasks.named<JavaExec>("run") {
    jvmArgs("--module-path", javafxModulePath.get(), "--add-modules", "javafx.controls,javafx.fxml")
}
tasks.register<JavaExec>("uiSmoke") {
    description = "Exercise the real JavaFX window and local HTTP/core fixture; requires a graphical display."
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.ajdm.ui.UiSmoke"
    jvmArgs("--module-path", javafxModulePath.get(), "--add-modules", "javafx.controls,javafx.fxml", "--enable-native-access=ALL-UNNAMED,javafx.graphics",
            "-Dajdm.afterQueueSeconds=1", "-Dajdm.afterQueueDryRun=true")
}
