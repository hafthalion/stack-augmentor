plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

val agent = configurations.create("agent") {
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
    val config = layout.projectDirectory.file("stack-augmentor.toml")
    inputs.files(agentJar)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agentJar.singleFile.absolutePath}=config=${config.asFile.absolutePath}")
    })
}
