plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

val bridge: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation(libs.bytebuddy)
    implementation(libs.tomlj) {
        exclude(group = "org.checkerframework") // annotations only
    }
    // Loaded into the bootstrap class loader at runtime (see BridgeInjector), never shaded.
    compileOnly(project(":stack-augmentor-bridge"))
    bridge(project(":stack-augmentor-bridge"))

    testImplementation(project(":stack-augmentor-bridge"))
    testImplementation(project(":stack-augmentor-api"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.processResources {
    from(bridge) {
        into("META-INF/stack-augmentor")
        rename { "stack-augmentor-bridge.jar" }
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
    exclude("META-INF/*.kotlin_module", "META-INF/versions/*/module-info.class", "module-info.class")
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
