import net.bytebuddy.build.EntryPoint

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.bytebuddy)
}

// Integration tests for build-time instrumentation: the ByteBuddy Gradle plugin applies
// stack-augmentor-build-plugin to the fixtures in src/main, as in examples/build-time, and the tests in
// src/test run them without -javaagent (and with it in testWithAgent).

// The shaded agent jar, for testWithAgent.
val agent = configurations.create("agent") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    agent(project(path = ":stack-augmentor-agent", configuration = "shadowRuntimeElements"))
    // The fixtures are written in Kotlin; stack-augmentor itself does not need the Kotlin runtime.
    implementation(libs.kotlin.stdlib)
    implementation(project(":stack-augmentor-api"))
    // Dispatch and the handler that the instrumented code calls at runtime.
    implementation(project(":stack-augmentor-runtime"))
    // The ByteBuddy build plugin.
    byteBuddy(project(":stack-augmentor-build-plugin"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// One configuration for both phases: [augment.receiver] and [augment.params] are read here at build time,
// [augment] and [augment.receiver] from the classpath at runtime.
val stackAugmentorConfig = layout.projectDirectory.file("src/main/resources/stack-augmentor.toml")

byteBuddy {
    // Only adds advice to existing methods: keep the classes' methods as they are (no rebasing).
    entryPoint = EntryPoint.Default.DECORATE
    transformation {
        pluginName = "com.hafnium.stackaugmentor.build.ByteBuddyPlugin"
        // Without this argument, nothing is instrumented and the build prints a warning.
        argument { value = stackAugmentorConfig.asFile.absolutePath }
    }
}

tasks.matching { it.name == "byteBuddyKotlin" }.configureEach {
    // Re-instrument when the configuration or the build plugin's code changes (the task is registered after
    // evaluation, and does not track either by itself).
    inputs.file(stackAugmentorConfig)
    inputs.files(configurations.named("byteBuddy")).withNormalizer(ClasspathNormalizer::class.java)
}

// The same tests with java.lang open, as with --add-opens java.base/java.lang=ALL-UNNAMED in an application, and
// inPlaceModification on: then the handler writes frames into the exception's own stack trace instead of copying it.
val testOpenJavaLang = tasks.register<Test>("testOpenJavaLang") {
    description = "Runs the integration tests with java.lang open to the runtime classes."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    systemProperty("stackaugmentor.inPlaceModification", "true")
}

// The same tests with the agent attached as well: it must leave the classes instrumented at build time alone, their
// advice already calls the agent's handler.
val testWithAgent = tasks.register<Test>("testWithAgent") {
    description = "Runs the integration tests with the agent attached."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    val agentJar = agent
    inputs.files(agentJar).withPropertyName("agent")
    inputs.file(stackAugmentorConfig).withPropertyName("agentConfig")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agentJar.singleFile.absolutePath}=config=${stackAugmentorConfig.asFile.absolutePath}")
    })
}

tasks.check {
    dependsOn(testOpenJavaLang, testWithAgent)
}
