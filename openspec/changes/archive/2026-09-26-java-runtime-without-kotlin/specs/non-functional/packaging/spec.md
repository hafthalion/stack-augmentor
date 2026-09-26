# Spec Delta

## MODIFIED Requirements

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
