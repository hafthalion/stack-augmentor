plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Which classes and methods get the exit advice, and the advice itself.
// Shared by the Java agent and the ByteBuddy build plugin.

dependencies {
    api(project(":stack-augmentor-runtime"))
    api(libs.bytebuddy)
}
