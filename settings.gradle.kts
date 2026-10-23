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
    "stack-augmentor-it:agent",
    "stack-augmentor-it:build-time",
    "stack-augmentor-it:benchmark",
    "stack-augmentor-it:spring-boot",
    "stack-augmentor-it:quarkus",
    "stack-augmentor-example:java-agent",
    "stack-augmentor-example:live-agent",
    "stack-augmentor-example:build-time",
)
