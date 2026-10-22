import net.bytebuddy.build.EntryPoint

plugins {
    java
    alias(libs.plugins.quarkus)
    alias(libs.plugins.bytebuddy)
}

// A Quarkus application in JVM mode, instrumented at build time and packaged by Quarkus (fast-jar), whose classes and
// libraries Quarkus's own class loader loads. The tests run it as an application is run: without the agent, and with
// the agent and a configuration of its own, which applies before the application's stack-augmentor-context.toml.

val agent = configurations.create("agent") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    agent(project(path = ":stack-augmentor-agent", configuration = "shadowRuntimeElements"))
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.arc)
    implementation(project(":stack-augmentor-api"))
    implementation(project(":stack-augmentor-runtime"))
    byteBuddy(project(":stack-augmentor-build-plugin"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add("-parameters")
}

val stackAugmentorConfig = layout.projectDirectory.file("src/main/resources/stack-augmentor-context.toml")

byteBuddy {
    entryPoint = EntryPoint.Default.DECORATE
    transformation {
        pluginName = "com.hafnium.stackaugmentor.build.ByteBuddyPlugin"
        // In the order the files apply: none of the build's own, then the context file.
        argument { index = 0; value = "" }
        argument { index = 1; value = stackAugmentorConfig.asFile.absolutePath }
    }
}

tasks.matching { it.name == "byteBuddyJava" }.configureEach {
    inputs.file(stackAugmentorConfig)
    inputs.files(configurations.named("byteBuddy")).withNormalizer(ClasspathNormalizer::class.java)
}

tasks.test {
    dependsOn(tasks.named("quarkusBuild"))
    val application = layout.buildDirectory.file("quarkus-app/quarkus-run.jar")
    val agentJar = agent
    val agentConfig = layout.projectDirectory.file("stack-augmentor-global.toml")
    inputs.files(agentJar).withPropertyName("agent")
    inputs.file(agentConfig).withPropertyName("agentConfig")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dstackaugmentor.it.application=${application.get().asFile.absolutePath}",
            "-Dstackaugmentor.it.agentJar=${agentJar.singleFile.absolutePath}",
            "-Dstackaugmentor.it.agentConfig=${agentConfig.asFile.absolutePath}",
        )
    })
}
