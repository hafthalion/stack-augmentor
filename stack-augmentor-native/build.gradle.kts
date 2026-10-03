plugins {
    // Only for the Java toolchain, whose JDK provides the JVMTI headers.
    `java-base`
}

// The native library of the agent's live-stack mode (see src/main/c). It is not part of the agent jar: the JVM must load
// it with -agentpath before anything is compiled. Built with the platform's C compiler: cc on Linux and macOS, gcc
// (MinGW) on Windows; -PnativeCompiler=<path> picks another one. Without a compiler, the library and the integration
// tests of that mode are skipped, except on CI, where the build fails.

val osName: String = System.getProperty("os.name").lowercase()
val windows = osName.startsWith("windows")
val mac = osName.startsWith("mac")
val includeDir = if (windows) "win32" else if (mac) "darwin" else "linux"
val libraryName = if (windows) "stackaugmentor.dll" else if (mac) "libstackaugmentor.dylib" else "libstackaugmentor.so"

val compiler: String = providers.gradleProperty("nativeCompiler").getOrElse(if (windows) "gcc" else "cc")
val compilerFound: Boolean = File(compiler).isAbsolute && File(compiler).canExecute() ||
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { dir ->
        listOf(compiler, "$compiler.exe").any { File(dir, it).canExecute() }
    }
val onCi = System.getenv("CI") != null

val jdk = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }.map { it.metadata.installationPath }
val source = layout.projectDirectory.file("src/main/c/stack_augmentor.c")
val library = layout.buildDirectory.file("native/$libraryName")

// The JDK's JNI and JVMTI headers, with the platform's jni_md.h next to them, in one folder at a fixed place in the
// project: the compiler and the IDE (.idea/c_cpp_properties.json) both read them from there.
val jdkHeaders = tasks.register<Sync>("jdkHeaders") {
    description = "Copies the JDK's JNI and JVMTI headers to build/jdk-include."
    group = "build"
    val jdkInclude = jdk.map { it.dir("include") }
    from(jdkInclude) { include("*.h") }
    from(jdkInclude.map { it.dir(includeDir) })
    into(layout.buildDirectory.dir("jdk-include"))
}

// IntelliJ excludes the build folder, but shows the headers as a source folder of the main source set, which this
// module otherwise does not need. compileJava finds no Java files there.
sourceSets.create("main") {
    java.srcDir(jdkHeaders)
}

val compileNative = tasks.register<Exec>("compileNative") {
    description = "Compiles the native library of the agent's live-stack mode."
    group = "build"
    inputs.file(source).withPropertyName("source")
    inputs.files(jdkHeaders).withPropertyName("headers")
    inputs.property("compiler", compiler)
    outputs.file(library).withPropertyName("library")
    val found = compilerFound
    val ci = onCi
    onlyIf("a C compiler '$compiler' is on the PATH") {
        if (!found && ci) {
            throw GradleException("no C compiler '$compiler' on the PATH, needed for the native library")
        }
        if (!found) {
            logger.warn("Skipping the native library: no C compiler '$compiler' on the PATH (-PnativeCompiler=<path>)")
        }
        found
    }
    doFirst {
        library.get().asFile.parentFile.mkdirs()
    }
    executable = compiler
    val headers = jdkHeaders.map { it.destinationDir }
    argumentProviders.add(CommandLineArgumentProvider {
        listOfNotNull(
            "-O2",
            "-shared",
            if (windows) null else "-fPIC",
            "-I${headers.get().absolutePath}",
            "-o",
            library.get().asFile.absolutePath,
            source.asFile.absolutePath,
        )
    })
}

// For the integration tests: the library, if it was built.
configurations.consumable("nativeLibrary") {
    outgoing.artifact(library) {
        builtBy(compileNative)
    }
}

tasks.assemble {
    dependsOn(compileNative)
}
