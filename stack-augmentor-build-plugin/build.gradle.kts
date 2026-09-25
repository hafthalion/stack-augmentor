plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// A ByteBuddy build plugin: applied to compiled classes by the ByteBuddy Gradle (or Maven) plugin,
// found through META-INF/net.bytebuddy/build.plugins.

dependencies {
    implementation(project(":stack-augmentor-instrument"))
}
