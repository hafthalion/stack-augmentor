# Spec Delta

## MODIFIED Requirements

### Requirement: Languages
The published modules (`stack-augmentor-api`, `stack-augmentor-instrument-bridge`,
`stack-augmentor-runtime`, `stack-augmentor-instrument`, `stack-augmentor-agent` and
`stack-augmentor-build-plugin`) SHALL be written in Java. Kotlin SHALL be used only for code that is not
published: the examples, the integration tests and unit tests.

#### Scenario: Module languages
- **GIVEN** the source tree
- **WHEN** the main source sets of the published modules are inspected
- **THEN** they contain only Java sources

#### Scenario: Kotlin tests
- **GIVEN** a published module whose unit tests are written in Kotlin
- **WHEN** the module's main classes are compiled
- **THEN** they are compiled by `javac` only, and Kotlin is compiled only for the test source set

### Requirement: Dependencies
The implementation SHALL depend only on ByteBuddy (instrumentation) and tomlj (configuration, with its
ANTLR runtime; checker-qual only at compile time). The agent jar SHALL be built with the Shadow plugin,
relocating them under `com.hafnium.stackaugmentor.shaded`.

#### Scenario: Agent jar packages
- **GIVEN** the built agent jar
- **WHEN** its packages are listed
- **THEN** ByteBuddy, tomlj and ANTLR appear only under `com/hafnium/stackaugmentor/shaded/`, and no Kotlin classes appear anywhere

## ADDED Requirements

### Requirement: No Kotlin runtime in published modules
The build SHALL fail when `kotlin-stdlib` (or any other `org.jetbrains.kotlin` artifact) is on the
runtime classpath of a published module, or when the agent jar contains Kotlin classes. The check SHALL
run as part of `./gradlew build`.

#### Scenario: Kotlin dependency added by mistake
- **GIVEN** a change that adds `kotlin-stdlib` to the runtime dependencies of `stack-augmentor-runtime`
- **WHEN** `./gradlew build` runs
- **THEN** the build fails and names the module and the Kotlin artifact

#### Scenario: Clean build
- **GIVEN** the project without such a change
- **WHEN** `./gradlew build` runs
- **THEN** the check passes, while the examples and tests still compile and run with Kotlin
