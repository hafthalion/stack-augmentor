# Toolchain Specification

## Purpose
Non-functional requirements on building and testing the project.

## Requirements

### Requirement: Languages
The delivered libraries, the agent and the build plugin SHALL be written in Java, so that none of them needs the Kotlin
runtime; the native library in C. Kotlin MAY be used for code that is not delivered: examples and tests.

#### Scenario: Module languages
- **GIVEN** the source tree
- **WHEN** the main sources of the delivered modules are inspected
- **THEN** they contain only Java sources, and C for the native library

### Requirement: One build
The project SHALL be built and tested with one command of a build tool that comes with the project (`./gradlew build`),
with JDK 25, and with its dependency versions declared in one place. Kotlin code SHALL be compiled with parameter names, so
that they are available as labels. The build SHALL fail when a delivered module would need the Kotlin runtime.

#### Scenario: Kotlin dependency added by mistake
- **GIVEN** a change that adds the Kotlin standard library to the runtime dependencies of a delivered module
- **WHEN** the project is built
- **THEN** the build fails and names the module and the Kotlin artifact

### Requirement: Few dependencies
The implementation SHALL depend only on one bytecode instrumentation library and one TOML parser (with their own
dependencies), and on nothing at runtime of build-time instrumented applications but the TOML parser.

#### Scenario: Dependency review
- **GIVEN** the delivered modules
- **WHEN** their runtime dependencies are listed
- **THEN** they are the instrumentation library and the TOML parser only

### Requirement: Tests
The build SHALL run unit tests of the configuration, the frame format and the resolution of values, and integration tests
that run real application classes with the built agent, in the live-stack mode, and instrumented at build time without
and with the agent, also in class loaders of their own with their own global and local configurations, and packaged Spring Boot and
Quarkus applications. The live-stack tests MAY be skipped on machines without a C compiler, but not on CI. A test SHALL check
that normal returns of instrumented methods allocate nothing.

#### Scenario: Integration tests use the real agent
- **GIVEN** the integration tests
- **WHEN** they run
- **THEN** the test JVM is started with the built agent jar and a test configuration

#### Scenario: Advice changed to allocate
- **GIVEN** a change that makes instrumented methods allocate on every call
- **WHEN** the tests run
- **THEN** the allocation test fails

### Requirement: Benchmark
The project SHALL contain a benchmark of exceptions in deep stacks, with and without configured frames, caught near the
top or the bottom, logged or not, as single exceptions and as cause chains, and with exceptions that the configuration
does not select, run without Stack Augmentor, with the agent in its ways of writing frames, and in the live-stack mode. It
SHALL write a report that is published with the documentation, and SHALL NOT run as part of the normal build.

#### Scenario: Project build
- **GIVEN** the project
- **WHEN** it is built
- **THEN** the benchmark is compiled but not run
