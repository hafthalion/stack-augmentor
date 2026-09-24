plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    // Provided at runtime by the agent, through the bootstrap class loader.
    compileOnly(project(":stack-augmentor-bridge"))
}
