plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

val agent: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    agent(project(path = ":stack-augmentor-agent", configuration = "shadowRuntimeElements"))
    implementation(project(":stack-augmentor-api"))
}

application {
    mainClass = "com.hafnium.Main"
}

tasks.named<JavaExec>("run") {
    val agentJar = agent
    val config = layout.projectDirectory.file("stack-augmentor.properties")
    inputs.files(agentJar)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agentJar.singleFile.absolutePath}=config=${config.asFile.absolutePath}")
    })
    // The demo ends with an uncaught exception on purpose.
    isIgnoreExitValue = true
}
