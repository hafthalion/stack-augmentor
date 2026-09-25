rootProject.name = "stack-augmentor"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "stack-augmentor-api",
    "stack-augmentor-bridge",
    "stack-augmentor-agent",
    "stack-augmentor-it",
    "examples:java-agent",
)
