plugins {
    `java-library`
}

// Everything in this module is appended to the bootstrap class loader by the agent,
// so it must stay free of dependencies (no Kotlin, no ByteBuddy); only its tests have some.

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
