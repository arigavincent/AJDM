dependencies {
    api(project(":ajdm-model"))
    implementation("com.google.code.gson:gson:2.14.0")
}
tasks.test { systemProperty("ajdm.media.test.classpath", sourceSets.test.get().runtimeClasspath.asPath) }
