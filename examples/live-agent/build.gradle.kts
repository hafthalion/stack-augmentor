plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The agent's experimental live-stack mode: the same agent jar as examples/java-agent, plus the native library.

val agent = configurations.create("agent") {
    isCanBeConsumed = false
    isTransitive = false
}

val nativeLibrary = configurations.create("nativeLibrary") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    agent(project(path = ":stack-augmentor-agent", configuration = "shadowRuntimeElements"))
    nativeLibrary(project(path = ":stack-augmentor-native", configuration = "nativeLibrary"))
    // The example is written in Kotlin; stack-augmentor itself does not need the Kotlin runtime.
    implementation(libs.kotlin.stdlib)
    implementation(project(":stack-augmentor-api"))
}

application {
    mainClass = "com.hafnium.examples.live.Main"
}

tasks.withType<JavaExec>().configureEach {
    val agentJar = agent
    val config = layout.projectDirectory.file("stack-augmentor.toml")
    inputs.files(agentJar)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agentJar.singleFile.absolutePath}=config=${config.asFile.absolutePath}")
    })
}

tasks.named<JavaExec>("run") {
    description = "Runs the example with the agent's live-stack mode."
    val library = nativeLibrary
    inputs.files(library)
    doFirst {
        if (library.files.none { it.exists() }) {
            throw GradleException("The native library was not built: no C compiler, see stack-augmentor-native")
        }
    }
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-agentpath:${library.singleFile.absolutePath}")
    })
}

// For comparison: the same application with the agent alone, which instruments classes.
tasks.register<JavaExec>("runInstrumented") {
    description = "Runs the example with the agent instrumenting classes, for comparison."
    group = "application"
    mainClass = application.mainClass
    classpath = sourceSets.main.get().runtimeClasspath
}
