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
    // The example is written in Kotlin; stack-augmentor itself does not need the Kotlin runtime.
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

// One configuration for both phases: [instrument] is read here at build time, [augment] from the classpath at runtime.
val stackAugmentorConfig = layout.projectDirectory.file("src/main/resources/stack-augmentor.toml")

byteBuddy {
    // Only adds advice to existing methods: keep the classes' methods as they are (no rebasing).
    entryPoint = EntryPoint.Default.DECORATE
    transformation {
        pluginName = "com.hafnium.stackaugmentor.build.StackAugmentorBuildPlugin"
        // Without this argument, every class using @StackTraceId is instrumented.
        argument { value = stackAugmentorConfig.asFile.absolutePath }
    }
}

// byteBuddy transforms the Java classes, byteBuddyKotlin the Kotlin classes.
tasks.matching { it.name == "byteBuddy" || it.name == "byteBuddyKotlin" }.configureEach {
    // Re-instrument when the configuration changes (the task is registered after evaluation).
    inputs.file(stackAugmentorConfig)
}

// What a Java application instrumented at build time needs at runtime: no Kotlin, no ByteBuddy.
val javaApplication = configurations.create("javaApplication") {
    isCanBeConsumed = false
}

dependencies {
    javaApplication(project(":stack-augmentor-api"))
    javaApplication(project(":stack-augmentor-runtime"))
}

tasks.test {
    // WithoutKotlinTest runs the instrumented Java class (JavaOrder) in a JVM of its own, with this classpath.
    // The byteBuddy task's output. (java.classesDirectory is compileJava's output: the classes before instrumentation.)
    val javaClasses = layout.buildDirectory.dir("classes/java/main")
    val resources = sourceSets.main.get().output.resourcesDir
    val runtimeJars = javaApplication
    inputs.files(runtimeJars).withPropertyName("javaApplication")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val classpath = listOf(javaClasses.get().asFile, resources!!) + runtimeJars.files
        listOf("-Dstackaugmentor.example.javaClasspath=${classpath.joinToString(File.pathSeparator) { it.absolutePath }}")
    })
}

application {
    mainClass = "com.hafnium.Main"
}
