# Packaging Specification

## Purpose
Non-functional requirements on what is delivered, and what each way to run needs in the application.

## Requirements

### Requirement: Deliverables
The project SHALL deliver:
- an API library with the annotations and the exception formatting, without dependencies, the only thing application code
  compiles against;
- a runtime library for build-time instrumented applications;
- the Java agent, as one self-contained jar;
- the native library of the live-stack mode;
- the build plugin.

#### Scenario: Application code
- **GIVEN** an application that uses the annotations
- **WHEN** its compile classpath is listed
- **THEN** it needs only the API library

### Requirement: Self-contained agent
The agent jar SHALL contain everything it needs, with its dependencies hidden from the application, so that it neither
conflicts with nor changes the libraries the application uses (see the compatibility specification).

#### Scenario: Agent jar contents
- **GIVEN** the built agent jar
- **WHEN** its contents are listed
- **THEN** its third-party dependencies are only present under Stack Augmentor's own package, and it contains no Kotlin classes

### Requirement: Runtime dependencies of build-time instrumentation
An application instrumented at build time SHALL need at runtime only the API library and the runtime library with their
small dependencies; it SHALL NOT need the instrumentation library or the Kotlin standard library.

#### Scenario: Installed example
- **GIVEN** the build-time example installed for distribution
- **WHEN** its libraries are listed
- **THEN** it contains no instrumentation library

#### Scenario: Java application
- **GIVEN** a Java project that depends on the API and runtime libraries
- **WHEN** its runtime classpath is resolved
- **THEN** it contains no Kotlin artifact

### Requirement: Examples
The project SHALL contain a runnable example for each way to run: with the agent (including stand-ins for third-party
classes configured by name), in the live-stack mode, and with build-time instrumentation. Scripts for Linux, macOS and
Windows SHALL build the project and run the example or the benchmark named by their argument, with JVM options after it,
and ask which one without an argument. The live-stack example SHALL also run with the agent alone, to show the difference,
and SHALL skip the live-stack run with a message when the native library was not built.

#### Scenario: Running the examples
- **GIVEN** the project
- **WHEN** the agent and build-time examples are run
- **THEN** both print augmented stack traces

#### Scenario: Running the live-stack example
- **GIVEN** the project, with the native library built
- **WHEN** the live-stack example is run
- **THEN** every frame of its stack traces, except `Main.main`, is augmented, also below the catching method and for the constructor whose `super(...)` call throws
