import net.bytebuddy.build.EntryPoint

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.bytebuddy)
}

// Integration tests for build-time instrumentation: the ByteBuddy Gradle plugin applies
// stack-augmentor-build-plugin to the fixtures in src/main, as in examples/build-time, and the tests in
// src/test run them without -javaagent.

dependencies {
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
