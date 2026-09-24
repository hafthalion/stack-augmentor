plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Integration tests: the fixtures run in a test JVM started with the shaded agent jar,
// exactly as an application would use it.

val agent: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    agent(project(path = ":stack-augmentor-agent", configuration = "shadowRuntimeElements"))

    testImplementation(project(":stack-augmentor-api"))
    testImplementation(project(":stack-augmentor-logback"))
    testImplementation(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

fun Test.withAgent(configFile: String) {
    val agentJar = agent
    val config = layout.projectDirectory.file(configFile)
    inputs.files(agentJar).withPropertyName("agent")
    inputs.file(config).withPropertyName("agentConfig")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agentJar.singleFile.absolutePath}=config=${config.asFile.absolutePath}")
    })
}

tasks.test {
    withAgent("src/test/config/rewrite.properties")
    useJUnitPlatform { excludeTags("registry") }
}

val registryTest by tasks.registering(Test::class) {
    description = "Runs the integration tests for registry mode."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    withAgent("src/test/config/registry.properties")
    useJUnitPlatform { includeTags("registry") }
}

tasks.check {
    dependsOn(registryTest)
}
