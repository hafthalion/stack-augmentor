# Toolchain Specification

## Purpose
Non-functional requirements that record the current build, dependency and test decisions.

## Requirements

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

### Requirement: Gradle build
The project SHALL be built with the Gradle wrapper (Gradle 9, Kotlin DSL) and a version catalog in
`gradle/libs.versions.toml`. All modules SHALL use a JDK 25 toolchain, and Kotlin SHALL compile with
`javaParameters = true`, so that parameter names are available as labels.

#### Scenario: Dependency upgrade
- **GIVEN** a new ByteBuddy version
- **WHEN** it is changed in `gradle/libs.versions.toml`
- **THEN** the agent, the build plugin and the ByteBuddy Gradle plugin all use it

### Requirement: Dependencies
The implementation SHALL depend only on ByteBuddy (instrumentation) and tomlj (configuration, with its
ANTLR runtime; checker-qual only at compile time). The agent jar SHALL be built with the Shadow plugin,
relocating them under `com.hafnium.stackaugmentor.shaded`.

#### Scenario: Agent jar packages
- **GIVEN** the built agent jar
- **WHEN** its packages are listed
- **THEN** ByteBuddy, tomlj and ANTLR appear only under `com/hafnium/stackaugmentor/shaded/`, and no Kotlin classes appear anywhere

### Requirement: Tests
The project SHALL have unit tests for configuration, frame formatting, id resolution and the weak map,
integration tests that run fixtures in a test JVM started with the built agent jar and a test
configuration, and tests in the build-time example that run without an agent. `./gradlew build` SHALL run
them all.

#### Scenario: Integration tests use the real agent
- **GIVEN** the integration test module
- **WHEN** its tests run
- **THEN** the test JVM is started with `-javaagent:<shadow jar>=config=src/test/resources/stack-augmentor.toml`

### Requirement: Allocation check
A test SHALL verify the no-allocation requirement by measuring the calling thread's allocated bytes over
many normal calls of an instrumented method with primitive id parameters.

#### Scenario: Advice changed to allocate
- **GIVEN** a change that makes the advice build its arguments on every call
- **WHEN** the tests run
- **THEN** the allocation test fails

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
