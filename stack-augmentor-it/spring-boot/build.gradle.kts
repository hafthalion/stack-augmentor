import net.bytebuddy.build.EntryPoint
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.bytebuddy)
}

// A Spring Boot application, instrumented at build time and packaged as an executable jar, whose classes and libraries
// Spring Boot's own class loader loads. The tests run the jar as an application is run: without the agent, and with the
// agent and a configuration of its own, which applies before the application's stack-augmentor-context.toml.

val agent = configurations.create("agent") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    agent(project(path = ":stack-augmentor-agent", configuration = "shadowRuntimeElements"))
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(libs.spring.boot.starter)
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
    val bootJar = tasks.named<Jar>("bootJar").flatMap { it.archiveFile }
    val agentJar = agent
    val agentConfig = layout.projectDirectory.file("stack-augmentor-global.toml")
    inputs.file(bootJar).withPropertyName("bootJar")
    inputs.files(agentJar).withPropertyName("agent")
    inputs.file(agentConfig).withPropertyName("agentConfig")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dstackaugmentor.it.application=${bootJar.get().asFile.absolutePath}",
            "-Dstackaugmentor.it.agentJar=${agentJar.singleFile.absolutePath}",
            "-Dstackaugmentor.it.agentConfig=${agentConfig.asFile.absolutePath}",
        )
    })
}
