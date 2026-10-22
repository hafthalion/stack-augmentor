# Compatibility Specification

## Purpose
Non-functional requirements on supported platforms, languages and tools, and on coexisting with the
application's own dependencies.

## Requirements

### Requirement: Java 25
The agent, the build plugin and the runtime SHALL run on Java 25, and the project SHALL build with JDK 25.

#### Scenario: Build
- **GIVEN** JDK 25
- **WHEN** `./gradlew build` runs
- **THEN** all modules compile and all tests pass

### Requirement: Java and Kotlin classes
Instrumentation SHALL work for classes compiled from Java and from Kotlin, including Kotlin top-level
functions, primary-constructor properties and data classes, and Java classes compiled with or without
`-parameters`. Parameter values are labelled with the compiled parameter names, so Java code SHOULD be compiled with
`javac -parameters`; without it, the labels are `arg<N>`.

#### Scenario: Java class without parameter names
- **GIVEN** a Java class compiled without `-parameters`
- **WHEN** an annotated parameter's method throws
- **THEN** the frame shows the parameter value labelled `arg<N>`

### Requirement: Application frameworks
The agent and build-time instrumentation SHALL work for applications packaged by Spring Boot (executable jar) and Quarkus
(JVM mode), whose classes and libraries those frameworks load in class loaders of their own, and for applications in an
application server. Classes that a framework generates at runtime or at build time, such as proxies, SHALL be treated
like any other class: augmented by entries that match them, and left alone when they are compiler-generated.

#### Scenario: Quarkus application
- **GIVEN** a Quarkus application instrumented at build time, with `stack-augmentor-global.toml` and `stack-augmentor.toml` in its resources
- **WHEN** it is run from its packaged form, without and with the agent
- **THEN** the frames of its instrumented methods show their augmentation in the format of its configurations, and those of its libraries without the format of its local one

### Requirement: No conflicts with the application's dependencies
The Java agent SHALL keep its own dependencies apart from the application's, so that the application can use any
version of the same libraries, and nothing the agent sets up for its own copies SHALL affect the application's. The agent
SHALL NOT contain Kotlin, so it cannot conflict with the application's Kotlin version.

#### Scenario: Application uses its own versions
- **GIVEN** an application with its own versions of the agent's dependencies and of Kotlin
- **WHEN** it runs with the agent
- **THEN** the application uses its own versions, and the agent works with its own

### Requirement: Stack trace output
Augmented frames SHALL keep the JDK's frame layout `<class>.<method>(<file>:<line>)`, including the
class loader and module prefix, so that `printStackTrace`, loggers and IDEs need no changes.

#### Scenario: Frame with a module prefix
- **GIVEN** a frame of a class in a named module with a version
- **WHEN** it is rewritten
- **THEN** it keeps the `<loader>/<module>@<version>/` prefix

### Requirement: No JDK warnings from the agent
The agent SHALL NOT cause deprecation warnings of the JDK, such as for `sun.misc.Unsafe`. The class data sharing notice
that the JVM prints when the bootstrap class path is extended (`Sharing is only supported for boot loader classes because bootstrap
classpath has been appended`, silenced by `-Xshare:off`) SHALL be the only expected JVM message.

#### Scenario: Agent start
- **GIVEN** the agent on Java 25
- **WHEN** the application starts
- **THEN** no `sun.misc.Unsafe` warning is printed

### Requirement: Java applications without the Kotlin runtime
Every way to run SHALL work for an application that does not have the Kotlin standard library on its classpath: Stack
Augmentor SHALL NOT need any Kotlin class at runtime, also not for debug output or configuration errors.

#### Scenario: Agent with a Java application
- **GIVEN** a Java application without `kotlin-stdlib`, started with the agent and `debug = true`
- **WHEN** an annotated method throws
- **THEN** the frame shows its augmentation, and no `NoClassDefFoundError` or `ClassNotFoundException` for a `kotlin.*` class occurs

#### Scenario: Build-time instrumentation of a Java application
- **GIVEN** a Java application instrumented at build time, with only the API and runtime libraries added
- **WHEN** an annotated method throws
- **THEN** the frame shows its augmentation

#### Scenario: Invalid configuration
- **GIVEN** a Java application without `kotlin-stdlib` and an invalid `stack-augmentor.toml`
- **WHEN** the agent starts
- **THEN** the error message names the key and its line, and no Kotlin class is needed to report it
