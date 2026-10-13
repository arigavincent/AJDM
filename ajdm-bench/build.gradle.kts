plugins { application }
dependencies { implementation(project(":ajdm-engine")) }
application {
    mainClass.set("io.ajdm.bench.EngineRunner")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
tasks.test {
    systemProperty("ajdm.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    val crashRuns = providers.gradleProperty("crashRuns").getOrElse("10")
    systemProperty("ajdm.crashRuns", crashRuns)
    val parallelCrashRuns = providers.gradleProperty("parallelCrashRuns").getOrElse("10")
    systemProperty("ajdm.parallelCrashRuns", parallelCrashRuns)
    val adaptiveCrashRuns = providers.gradleProperty("adaptiveCrashRuns").getOrElse("10")
    systemProperty("ajdm.adaptiveCrashRuns", adaptiveCrashRuns)
    val refreshCrashRuns = providers.gradleProperty("refreshCrashRuns").getOrElse("3")
    systemProperty("ajdm.refreshCrashRuns", refreshCrashRuns)
    val chaosRuns = providers.gradleProperty("chaosRuns").getOrElse("25")
    systemProperty("ajdm.chaosRuns", chaosRuns)
    testLogging.showStandardStreams = adaptiveCrashRuns.toInt() > 10 || crashRuns.toInt() > 10 || parallelCrashRuns.toInt() > 10 || chaosRuns.toInt() > 25
}
