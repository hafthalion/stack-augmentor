plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Integration tests: the fixtures run in a test JVM started with the shaded agent jar,
// exactly as an application would use it.

val agent = configurations.create("agent") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    agent(project(path = ":stack-augmentor-agent", configuration = "shadowRuntimeElements"))

    testImplementation(project(":stack-augmentor-api"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    val agentJar = agent
    val config = layout.projectDirectory.file("src/test/resources/stack-augmentor.toml")
    inputs.files(agentJar).withPropertyName("agent")
    inputs.file(config).withPropertyName("agentConfig")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agentJar.singleFile.absolutePath}=config=${config.asFile.absolutePath}")
    })
}
