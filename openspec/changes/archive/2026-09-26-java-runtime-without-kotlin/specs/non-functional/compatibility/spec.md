# Spec Delta

## MODIFIED Requirements

### Requirement: No conflicts with the application's dependencies
The Java agent SHALL shade and relocate its dependencies (ByteBuddy, tomlj, ANTLR), so that it does not
conflict with versions used by the application, and system properties it sets for its own ByteBuddy copy
SHALL NOT affect the application's ByteBuddy. The agent SHALL NOT contain Kotlin, so it cannot conflict
with the application's Kotlin version.

#### Scenario: Application uses its own ByteBuddy or Kotlin
- **GIVEN** an application with its own ByteBuddy and Kotlin versions
- **WHEN** it runs with the agent
- **THEN** the application uses its own versions, and the agent uses its relocated ByteBuddy and no Kotlin classes

## ADDED Requirements

### Requirement: Java applications without the Kotlin runtime
Both modes SHALL work for an application that does not have the Kotlin standard library on its
classpath: the agent and the runtime handler SHALL NOT load any `kotlin.*` class, whether they
instrument classes, resolve ids, format frames, log or report configuration errors.

#### Scenario: Agent with a Java application
- **GIVEN** a Java application without `kotlin-stdlib`, started with the agent and `debug = true`
- **WHEN** an annotated method throws
- **THEN** the frame shows its ids, and no `NoClassDefFoundError` or `ClassNotFoundException` for a `kotlin.*` class occurs

#### Scenario: Build-time instrumentation of a Java application
- **GIVEN** a Java application instrumented at build time, with only `stack-augmentor-api` and `stack-augmentor-runtime` added
- **WHEN** an annotated method throws
- **THEN** the frame shows its ids

#### Scenario: Invalid configuration
- **GIVEN** a Java application without `kotlin-stdlib` and an invalid `stack-augmentor.toml`
- **WHEN** the agent starts
- **THEN** the error message names the key and its line, and no Kotlin class is needed to report it
