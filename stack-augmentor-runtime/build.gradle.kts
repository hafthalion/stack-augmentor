plugins {
    // Only for the tests, which need Kotlin fixtures; the main sources are Java.
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// What runs when an instrumented method throws: configuration, id lookup, frame formatting.
// Shared by the Java agent and by build-time instrumentation. Written in Java, so applications need no
// Kotlin runtime (see kotlin.stdlib.default.dependency in gradle.properties).

dependencies {
    // Dispatch: for build-time instrumentation it is an ordinary dependency of the application.
    api(project(":stack-augmentor-instrument-bridge"))
    implementation(libs.tomlj) {
        exclude(group = "org.checkerframework")
    }
    // tomlj's nullness annotations: needed by the compiler, not at runtime.
    compileOnly(libs.checker.qual)

    testImplementation(libs.kotlin.stdlib)
    testImplementation(project(":stack-augmentor-api"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// StackTracesTest needs java.lang open to the runtime classes, as the Java agent opens it. It runs in a JVM of its
// own, so that the other tests run with the JDK's modules as an application sees them.
val openJavaLangTest = "com.hafnium.stackaugmentor.runtime.StackTracesTest"

tasks.test {
    filter.excludeTestsMatching(openJavaLangTest)
}

val testOpenJavaLang = tasks.register<Test>("testOpenJavaLang") {
    description = "Runs the tests that need java.lang open to the runtime classes."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(openJavaLangTest)
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

tasks.check {
    dependsOn(testOpenJavaLang)
}
