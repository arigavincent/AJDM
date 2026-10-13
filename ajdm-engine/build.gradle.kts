dependencies {
    api(project(":ajdm-net"))
    api(project(":ajdm-store"))
}
tasks.test {
    systemProperty("ajdm.schedulerRuns", providers.gradleProperty("schedulerRuns").getOrElse("100"))
}
