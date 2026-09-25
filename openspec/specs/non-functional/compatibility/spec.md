# Compatibility Specification

## Purpose
Non-functional requirements on supported platforms, languages and tools, and on coexisting with the
application's own dependencies.

## Requirements

### Requirement: Java 25
The project SHALL build with Gradle 9 and JDK 25, and the agent, the build plugin and the runtime SHALL
run on Java 25.

#### Scenario: Build
- **GIVEN** JDK 25
- **WHEN** `./gradlew build` runs
- **THEN** all modules compile and all tests pass

### Requirement: Java and Kotlin classes
Instrumentation SHALL work for classes compiled from Java and from Kotlin, including Kotlin top-level
functions, primary-constructor properties and data classes, and Java classes compiled with or without
`-parameters`.

#### Scenario: Java class without parameter names
- **GIVEN** a Java class compiled without `-parameters`
- **WHEN** an annotated parameter's method throws
- **THEN** the frame shows the parameter id labelled `arg<N>`

### Requirement: No conflicts with the application's dependencies
The Java agent SHALL shade and relocate its dependencies (ByteBuddy, tomlj, ANTLR), so that it does not
conflict with versions used by the application, and system properties it sets for its own ByteBuddy copy
SHALL NOT affect the application's ByteBuddy. The agent SHALL NOT contain Kotlin, so it cannot conflict
with the application's Kotlin version.

#### Scenario: Application uses its own ByteBuddy or Kotlin
- **GIVEN** an application with its own ByteBuddy and Kotlin versions
- **WHEN** it runs with the agent
- **THEN** the application uses its own versions, and the agent uses its relocated ByteBuddy and no Kotlin classes

### Requirement: Stack trace output
Rewritten frames SHALL keep the JDK's frame layout `<class>.<method>(<file>:<line>)`, including the
class loader and module prefix, so that `printStackTrace` and loggers need no changes.

#### Scenario: Frame with a module prefix
- **GIVEN** a frame of a class in a named module with a version
- **WHEN** it is rewritten
- **THEN** it keeps the `<loader>/<module>@<version>/` prefix

### Requirement: No JDK warnings from the agent's dependencies
The agent SHALL NOT cause `sun.misc.Unsafe` deprecation warnings. The class data sharing notice that the
JVM prints when the bootstrap class path is extended SHALL be the only expected JVM message.

#### Scenario: Agent start
- **GIVEN** the agent on Java 25
- **WHEN** the application starts
- **THEN** no `sun.misc.Unsafe` warning is printed

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
