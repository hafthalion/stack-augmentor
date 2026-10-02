# Packaging Specification

## Purpose
Non-functional requirements on the modules and artifacts, and what each mode of use needs on the
application's classpath.

## Requirements

### Requirement: Modules
The project SHALL be split into these modules:
- `stack-augmentor-api`: the `@StackTraceId`, `@StackTraceParam` and `@StackTraceParams` annotations;
- `stack-augmentor-instrument-bridge`: `Dispatch`, the class the advice calls, and `LiveDispatch`, the class the
  live-stack mode's code in `Throwable` calls (Java, no dependencies);
- `stack-augmentor-runtime`: configuration, id lookup, frame formatting and `ThrowHandler`;
- `stack-augmentor-instrument`: type and method matching and the exit advice (ByteBuddy);
- `stack-augmentor-agent`: the Java agent;
- `stack-augmentor-native`: the native library (C) of the agent's experimental live-stack mode;
- `stack-augmentor-build-plugin`: the ByteBuddy build plugin.

The agent and build-time instrumentation SHALL share the runtime and instrument modules.

#### Scenario: Shared behaviour
- **GIVEN** the same annotated class and the same `[augment]` configuration
- **WHEN** it is instrumented once by the agent and once at build time
- **THEN** its frames look the same

### Requirement: Self-contained agent jar
The agent SHALL be a single jar that contains its dependencies and embeds the bridge jar. The bridge
classes SHALL NOT be part of the agent's own classes, and the runtime's service registration SHALL NOT be
included.

#### Scenario: Agent jar contents
- **GIVEN** the built agent jar
- **WHEN** its contents are listed
- **THEN** it contains `META-INF/stack-augmentor/stack-augmentor-instrument-bridge.jar` and no `Dispatch` class outside it

### Requirement: Runtime dependencies of build-time instrumentation
An application instrumented at build time SHALL need only `stack-augmentor-api` and
`stack-augmentor-runtime` (with the bridge and tomlj) at runtime. It SHALL NOT need ByteBuddy or the
Kotlin standard library. `stack-augmentor-api` SHALL have no dependencies.

#### Scenario: Installed example
- **GIVEN** the build-time example installed with `installDist`
- **WHEN** its `lib` folder is listed
- **THEN** it contains no ByteBuddy jar

#### Scenario: Java application
- **GIVEN** a Java project that depends on `stack-augmentor-api` and `stack-augmentor-runtime`
- **WHEN** its runtime classpath is resolved
- **THEN** it contains no `org.jetbrains.kotlin` artifact

### Requirement: Examples
The project SHALL contain a runnable example for each mode: `examples/java-agent` (including third-party
stand-ins configured in `stack-augmentor.toml`) and `examples/build-time` (the same classes and entries,
instrumented at build time). `run.bat` SHALL build the project and run the agent example.

#### Scenario: Running the examples
- **GIVEN** the project
- **WHEN** `./gradlew :examples:java-agent:run` and `./gradlew :examples:build-time:run` are run
- **THEN** both print stack traces with ids
