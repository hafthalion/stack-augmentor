plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":stack-augmentor-api"))
    compileOnly(libs.logback.classic)
}
