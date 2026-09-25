plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// What runs when an instrumented method throws: configuration, id lookup, frame formatting.
// Shared by the Java agent and by build-time instrumentation.

dependencies {
    // Dispatch: for build-time instrumentation it is an ordinary dependency of the application.
    api(project(":stack-augmentor-instrument-bridge"))
    implementation(libs.tomlj) {
        exclude(group = "org.checkerframework")
    }
    // tomlj's nullness annotations: needed by the Kotlin compiler, not at runtime.
    compileOnly(libs.checker.qual)

    testImplementation(project(":stack-augmentor-api"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
