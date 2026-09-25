import net.bytebuddy.build.EntryPoint

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.bytebuddy)
    application
}

// Build-time instrumentation: the ByteBuddy Gradle plugin applies stack-augmentor-build-plugin to the
// compiled classes, so the application runs without -javaagent. Only this project's classes that use
// @StackTraceId are changed; libraries (third-party classes) are not.

dependencies {
    implementation(project(":stack-augmentor-api"))
    // Dispatch and the handler that the instrumented code calls at runtime.
    implementation(project(":stack-augmentor-runtime"))
    // The ByteBuddy build plugin, found through META-INF/net.bytebuddy/build.plugins.
    byteBuddy(project(":stack-augmentor-build-plugin"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

byteBuddy {
    // Only adds advice to existing methods: keep the classes' methods as they are (no rebasing).
    entryPoint = EntryPoint.Default.DECORATE
}

application {
    mainClass = "com.hafnium.Main"
}
