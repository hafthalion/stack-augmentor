plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Benchmark: how long an exception takes in a 1000-frame stack without the agent, with it (frames written in place, or
// the whole trace copied for every frame) and in its live-stack mode. Each mode runs the same tests in its own JVM;
// `./gradlew :stack-augmentor-it-benchmark:benchmark` runs them all and writes build/reports/benchmark/index.html.
// `test` only checks, with short measurements and without the agent, that the benchmark runs.

val agent = configurations.create("agent") {
    isCanBeConsumed = false
    isTransitive = false
}

// The native library of the agent's live-stack mode, if a C compiler built it.
val nativeLibrary = configurations.create("nativeLibrary") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    agent(project(path = ":stack-augmentor-agent", configuration = "shadowRuntimeElements"))
    nativeLibrary(project(path = ":stack-augmentor-native", configuration = "nativeLibrary"))

    testImplementation(libs.kotlin.stdlib)
    testImplementation(project(":stack-augmentor-api"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    systemProperty("benchmark.warmupMillis", "50")
    systemProperty("benchmark.batchMillis", "10")
    systemProperty("benchmark.batches", "1")
}

val results = layout.buildDirectory.dir("benchmark")
val config = layout.projectDirectory.file("src/test/resources/stack-augmentor.toml")

// The modes, as Mode in the tests names them.
val modes = listOf("plain", "agentInPlace", "agentCopying", "live")

val benchmarkTasks = modes.map { mode ->
    tasks.register<Test>("benchmark" + mode.replaceFirstChar { it.uppercase() }) {
        description = "Measures exceptions in a 1000-frame stack, mode $mode."
        group = "benchmark"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        outputs.upToDateWhen { false }
        val resultsDir = results.get().asFile
        systemProperty("benchmark.mode", mode)
        systemProperty("benchmark.results", resultsDir.absolutePath)
        doFirst {
            resultsDir.listFiles { file -> file.name.startsWith("$mode.") || file.name.startsWith("$mode-") }?.forEach { it.delete() }
        }
        if (mode != "plain") {
            val agentJar = agent
            inputs.files(agentJar).withPropertyName("agent")
            jvmArgumentProviders.add(CommandLineArgumentProvider {
                listOf("-javaagent:${agentJar.singleFile.absolutePath}=config=${config.asFile.absolutePath}")
            })
        }
        if (mode == "agentCopying") {
            systemProperty("stackaugmentor.copyStackTraces", "true")
        }
        if (mode == "live") {
            val library = nativeLibrary
            inputs.files(library).withPropertyName("nativeLibrary")
            onlyIf("the native library was built") { library.files.any { it.exists() } }
            jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-agentpath:${library.singleFile.absolutePath}") })
        }
    }
}
// One after the other, so that they don't share the machine.
benchmarkTasks.zipWithNext { first, second -> second.configure { mustRunAfter(first) } }

tasks.register<JavaExec>("benchmark") {
    description = "Runs the benchmark in every mode and writes build/reports/benchmark/index.html."
    group = "benchmark"
    dependsOn(benchmarkTasks)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.hafnium.it.benchmark.ReportKt"
    val report = layout.buildDirectory.file("reports/benchmark/index.html")
    args(results.get().asFile.absolutePath, report.get().asFile.absolutePath)
    outputs.file(report)
    outputs.upToDateWhen { false }
}
