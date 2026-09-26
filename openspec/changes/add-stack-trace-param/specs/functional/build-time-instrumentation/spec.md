# Spec Delta

## MODIFIED Requirements

### Requirement: Build plugin
`com.hafnium.stackaugmentor.build.StackAugmentorByteBuddyPlugin` SHALL be a ByteBuddy build plugin that adds
the same exit advice as the agent to the project's compiled Java and Kotlin classes that use
`@StackTraceId`, `@StackTraceParam` or `@StackTraceParams`. It SHALL be discoverable through
`META-INF/net.bytebuddy/build.plugins`, and then SHALL instrument annotated classes in all packages. When
it is given the path of a TOML configuration as argument 0, it SHALL apply that file's
`[instrument] annotatedClasses` and `debug`.

#### Scenario: Example project
- **GIVEN** `examples/build-time`, with the ByteBuddy Gradle plugin, the `DECORATE` entry point and the build plugin with its configuration argument
- **WHEN** the project is built and run without `-javaagent`
- **THEN** the frames of `ClassWithAnnotation` show `{objectId=object-1}` and `{param=object-param-1}`
- **AND** the frames of `ClassWithoutAnnotation` are unchanged

#### Scenario: Configuration change
- **GIVEN** `annotatedClasses` in the build plugin's configuration no longer matches the project's packages
- **WHEN** the project is rebuilt
- **THEN** its classes are not instrumented, and with `debug = true` the build lists the ignored annotations

#### Scenario: Class-level parameter annotation
- **GIVEN** a project class annotated only with `@StackTraceParams`
- **WHEN** the project is built
- **THEN** its methods with parameters are instrumented and show their parameter ids
