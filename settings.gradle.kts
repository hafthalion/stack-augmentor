rootProject.name = "stack-augmentor"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "stack-augmentor-api",
    "stack-augmentor-instrument-bridge",
    "stack-augmentor-runtime",
    "stack-augmentor-instrument",
    "stack-augmentor-agent",
    "stack-augmentor-native",
    "stack-augmentor-build-plugin",
    "stack-augmentor-it",
    "stack-augmentor-it-build-time",
    "examples:java-agent",
    "examples:live-agent",
    "examples:build-time",
)
