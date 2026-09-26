plugins {
    `java-library`
}

// A ByteBuddy build plugin: applied to compiled classes by the ByteBuddy Gradle (or Maven) plugin,
// found through META-INF/net.bytebuddy/build.plugins.

dependencies {
    implementation(project(":stack-augmentor-instrument"))

    testImplementation(project(":stack-augmentor-api"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
