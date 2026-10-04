plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.bytebuddy) apply false
}

allprojects {
    group = "com.hafnium"
    version = "0.1.0-SNAPSHOT"
}

// The modules that applications (or, for the build plugin, their builds) depend on. They are written in Java,
// so that no one needs the Kotlin runtime to use them; Kotlin is only used by the examples and the tests.
val publishedModules = setOf(
    "stack-augmentor-api",
    "stack-augmentor-instrument-bridge",
    "stack-augmentor-runtime",
    "stack-augmentor-instrument",
    "stack-augmentor-agent",
    "stack-augmentor-build-plugin",
)

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(25)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
            compilerOptions {
                // Keep real parameter names in the class files, so parameter ids get their labels.
                javaParameters = true
            }
        }
    }
    // The examples: their jar names the main class and, as the jars installDist puts next to it in lib, the
    // classpath, so that `java -jar lib/<example>.jar` runs them.
    plugins.withId("application") {
        val mainClass = extensions.getByType<JavaApplication>().mainClass
        val runtimeClasspath = configurations.named("runtimeClasspath")
        tasks.named<Jar>("jar") {
            manifest {
                attributes(
                    "Main-Class" to mainClass,
                    "Class-Path" to runtimeClasspath.map { classpath -> classpath.joinToString(" ") { it.name } },
                )
            }
        }
    }
    if (name in publishedModules) {
        plugins.withType<JavaPlugin> {
            tasks.named<JavaCompile>("compileJava") {
                // Real parameter names in our own classes. Only the main classes: some test fixtures are
                // deliberately compiled without them.
                options.compilerArgs.add("-parameters")
            }
            val verifyNoKotlinRuntime = tasks.register("verifyNoKotlinRuntime") {
                group = "verification"
                description = "Fails if the Kotlin runtime is on this module's runtime classpath."
                val module = path
                val artifacts = configurations.named("runtimeClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }
                inputs.files(configurations.named("runtimeClasspath")).withPropertyName("runtimeClasspath")
                doLast {
                    val kotlin = artifacts.get()
                        .map { it.id.componentIdentifier }
                        .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
                        .filter { it.group == "org.jetbrains.kotlin" }
                    if (kotlin.isNotEmpty()) {
                        throw GradleException(
                            "$module must not depend on the Kotlin runtime, but its runtime classpath contains " +
                                kotlin.joinToString { it.displayName },
                        )
                    }
                }
            }
            tasks.named("check") {
                dependsOn(verifyNoKotlinRuntime)
            }
        }
        plugins.withId("com.gradleup.shadow") {
            tasks.named("verifyNoKotlinRuntime") {
                val module = path
                val jar = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
                inputs.file(jar).withPropertyName("shadowJar")
                doLast {
                    val file = jar.get().asFile
                    val kotlinEntries = java.util.zip.ZipFile(file).use { zip ->
                        zip.stream()
                            .map { it.name }
                            .filter { it.startsWith("kotlin/") || it.startsWith("com/hafnium/stackaugmentor/shaded/kotlin/") }
                            .limit(5)
                            .toList()
                    }
                    if (kotlinEntries.isNotEmpty()) {
                        throw GradleException("$module: ${file.name} must not contain Kotlin classes, but contains $kotlinEntries")
                    }
                }
            }
        }
    }
}
