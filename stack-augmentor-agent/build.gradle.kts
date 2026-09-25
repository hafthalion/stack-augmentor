plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

val bridge = configurations.create("bridge") {
    isTransitive = false
}

dependencies {
    implementation(project(":stack-augmentor-instrument")) {
        // Loaded into the bootstrap class loader at runtime (see BridgeInjector), never shaded.
        exclude(group = "com.hafnium", module = "stack-augmentor-instrument-bridge")
    }
    compileOnly(project(":stack-augmentor-instrument-bridge"))
    bridge(project(":stack-augmentor-instrument-bridge"))
}

tasks.processResources {
    from(bridge) {
        into("META-INF/stack-augmentor")
        rename { "stack-augmentor-instrument-bridge.jar" }
    }
}

tasks.jar {
    archiveClassifier = "plain"
}

tasks.shadowJar {
    archiveClassifier = ""
    relocate("net.bytebuddy", "com.hafnium.stackaugmentor.shaded.bytebuddy")
    relocate("kotlin", "com.hafnium.stackaugmentor.shaded.kotlin")
    relocate("org.tomlj", "com.hafnium.stackaugmentor.shaded.tomlj")
    relocate("org.antlr", "com.hafnium.stackaugmentor.shaded.antlr")
    exclude(
        "META-INF/*.kotlin_module",
        "META-INF/versions/*/module-info.class",
        "module-info.class",
        // The agent installs its handler itself; the service is for build-time instrumentation.
        "META-INF/services/com.hafnium.stackaugmentor.instrument.bridge.Dispatch\$Handler",
    )
    manifest {
        attributes(
            "Premain-Class" to "com.hafnium.stackaugmentor.agent.StackAugmentorAgent",
            "Agent-Class" to "com.hafnium.stackaugmentor.agent.StackAugmentorAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
