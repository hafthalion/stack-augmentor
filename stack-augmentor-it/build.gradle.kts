plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Integration tests: the fixtures run in a test JVM started with the shaded agent jar,
// exactly as an application would use it.

val agent = configurations.create("agent") {
    isCanBeConsumed = false
    isTransitive = false
}

// The native library of the agent's live-stack mode, if a C compiler built it.
val nativeLibrary = configurations.create("nativeLibrary") {
    isCanBeConsumed = false
    isTransitive = false
}

// The API jar and its dependencies (none), for the Java-only application run without the Kotlin runtime.
val javaApplication = configurations.create("javaApplication") {
    isCanBeConsumed = false
}

dependencies {
    agent(project(path = ":stack-augmentor-agent", configuration = "shadowRuntimeElements"))
    nativeLibrary(project(path = ":stack-augmentor-native", configuration = "nativeLibrary"))
    javaApplication(project(":stack-augmentor-api"))

    testImplementation(libs.kotlin.stdlib)
    testImplementation(project(":stack-augmentor-api"))
    // The agent jar carries the runtime classes: ReceiverEntries reads the configuration with them.
    testCompileOnly(project(":stack-augmentor-runtime"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Generated subclasses, as frameworks create them: InheritanceTest.
    testImplementation(libs.bytebuddy)
    testImplementation(libs.spring.core)
    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<Test>().configureEach {
    val agentJar = agent
    val config = layout.projectDirectory.file("src/test/resources/stack-augmentor.toml")
    inputs.files(agentJar).withPropertyName("agent")
    inputs.file(config).withPropertyName("agentConfig")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agentJar.singleFile.absolutePath}=config=${config.asFile.absolutePath}")
    })

    // WithoutKotlinTest starts a JVM of its own with the agent and only the Java test classes and the API jar:
    // Gradle compiles the Kotlin test classes to a separate directory, so that classpath has no Kotlin.
    val javaClasses = sourceSets.test.get().java.classesDirectory
    val apiJars = javaApplication
    inputs.files(apiJars).withPropertyName("javaApplication")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val classpath = listOf(javaClasses.get().asFile) + apiJars.files
        listOf(
            "-Dstackaugmentor.it.agentJar=${agentJar.singleFile.absolutePath}",
            "-Dstackaugmentor.it.javaClasspath=${classpath.joinToString(File.pathSeparator) { it.absolutePath }}",
        )
    })
}

// The same tests with the agent's experimental live-stack mode: the native library loaded with -agentpath. The tests
// read stackaugmentor.it.mode where the two modes differ. Skipped when no C compiler built the library.
val testLiveStack = tasks.register<Test>("testLiveStack") {
    description = "Runs the integration tests with the agent's live-stack mode."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    val library = nativeLibrary
    inputs.files(library).withPropertyName("nativeLibrary")
    onlyIf("the native library was built") { library.files.any { it.exists() } }
    systemProperty("stackaugmentor.it.mode", "live")
    systemProperty("stackaugmentor.it.frames", layout.buildDirectory.dir("reports/frames-live").get().asFile.absolutePath)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val path = library.singleFile.absolutePath
        listOf("-agentpath:$path", "-Dstackaugmentor.it.nativeLibrary=$path")
    })
}

tasks.check {
    dependsOn(testLiveStack)
}
