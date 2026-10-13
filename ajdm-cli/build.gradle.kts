plugins { application }
dependencies {
    implementation(project(":ajdm-ipc"))
    implementation("info.picocli:picocli:4.7.7")
    testImplementation(project(":ajdm-bench"))
}
application {
    mainClass = "io.ajdm.cli.AjdmCli"
    applicationName = "ajdm"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
tasks.test {
    systemProperty("ajdm.test.classpath", sourceSets.main.get().runtimeClasspath.asPath)
    systemProperty("ajdm.test.runtime.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
