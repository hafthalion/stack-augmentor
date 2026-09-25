# Toolchain Specification

## Purpose
Non-functional requirements that record the current build, dependency and test decisions.

## Requirements

### Requirement: Languages
The modules SHALL be written in Kotlin, except for the bridge (`Dispatch`) and the advice classes
(`ExitAdvice`, `IdArgs`, `IdArgNames`), which SHALL be written in Java.

#### Scenario: Module languages
- **GIVEN** the source tree
- **WHEN** the bridge and the advice package are inspected
- **THEN** they contain only Java sources

### Requirement: Gradle build
The project SHALL be built with the Gradle wrapper (Gradle 9, Kotlin DSL) and a version catalog in
`gradle/libs.versions.toml`. All modules SHALL use a JDK 25 toolchain, and Kotlin SHALL compile with
`javaParameters = true`, so that parameter names are available as labels.

#### Scenario: Dependency upgrade
- **GIVEN** a new ByteBuddy version
- **WHEN** it is changed in `gradle/libs.versions.toml`
- **THEN** the agent, the build plugin and the ByteBuddy Gradle plugin all use it

### Requirement: Dependencies
The implementation SHALL depend only on ByteBuddy (instrumentation), tomlj (configuration, with its
ANTLR runtime; checker-qual only at compile time) and the Kotlin standard library. The agent jar SHALL
be built with the Shadow plugin, relocating them under `com.hafnium.stackaugmentor.shaded`.

#### Scenario: Agent jar packages
- **GIVEN** the built agent jar
- **WHEN** its packages are listed
- **THEN** ByteBuddy, Kotlin, tomlj and ANTLR appear only under `com/hafnium/stackaugmentor/shaded/`

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
