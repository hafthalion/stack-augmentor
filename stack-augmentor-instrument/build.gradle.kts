plugins {
    `java-library`
}

// Which classes and methods get the exit advice, and the advice itself.
// Shared by the Java agent and the ByteBuddy build plugin.

dependencies {
    api(project(":stack-augmentor-runtime"))
    api(libs.bytebuddy)

    testImplementation(project(":stack-augmentor-api"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.compileTestJava {
    // The test classes' parameter names are part of the expected labels.
    options.compilerArgs.add("-parameters")
}
