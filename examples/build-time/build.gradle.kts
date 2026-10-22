import net.bytebuddy.build.EntryPoint

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.bytebuddy)
    application
}

// Build-time instrumentation: the ByteBuddy Gradle plugin applies stack-augmentor-build-plugin to the
// compiled classes, so the application runs without -javaagent. Only this project's classes that the
// configuration's [augment.receiver] and [augment.params] entries need are changed; libraries (third-party
// classes) are not.

dependencies {
    // The example is written in Kotlin; stack-augmentor itself does not need the Kotlin runtime.
    implementation(libs.kotlin.stdlib)
    implementation(project(":stack-augmentor-api"))
    // Dispatch and the handler that the instrumented code calls at runtime.
    implementation(project(":stack-augmentor-runtime"))
    // The ByteBuddy build plugin.
    byteBuddy(project(":stack-augmentor-build-plugin"))
}

// One configuration for both phases: [augment.receiver] and [augment.params] are read here at build time,
// [augment], [augment.exceptions] and [augment.receiver] from the classpath at runtime.
val stackAugmentorConfig = layout.projectDirectory.file("src/main/resources/stack-augmentor-global.toml")
val buildSettings = layout.projectDirectory.file("stack-augmentor-build.toml")

byteBuddy {
    // Only adds advice to existing methods: keep the classes' methods as they are (no rebasing).
    entryPoint = EntryPoint.Default.DECORATE
    transformation {
        pluginName = "com.hafnium.stackaugmentor.build.ByteBuddyPlugin"
        // The files in the order they apply. First the build's own file, like the agent's: only it may have [settings],
        // e.g. debug = true to see what gets instrumented.
        argument { index = 0; value = buildSettings.asFile.absolutePath }
        // Then the global file. Without it, nothing is instrumented and the build prints a warning. A third argument
        // could name the module's local stack-augmentor.toml.
        argument { index = 1; value = stackAugmentorConfig.asFile.absolutePath }
    }
}

tasks.matching { it.name == "byteBuddyKotlin" }.configureEach {
    // Re-instrument when the configuration or the build plugin's code changes (the task is registered after
    // evaluation, and does not track either by itself).
    inputs.file(stackAugmentorConfig)
    inputs.file(buildSettings)
    inputs.files(configurations.named("byteBuddy")).withNormalizer(ClasspathNormalizer::class.java)
}

application {
    mainClass = "com.hafnium.examples.buildtime.Main"
}
