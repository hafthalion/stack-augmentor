# Build-Time Instrumentation Specification

## Purpose
Defines augmentation without a Java agent: the ByteBuddy Gradle plugin applies
`StackAugmentorBuildPlugin` (`stack-augmentor-build-plugin`) to a project's compiled classes, and the
application only needs `stack-augmentor-runtime` at runtime.

## Requirements

### Requirement: Build plugin
`com.hafnium.stackaugmentor.build.StackAugmentorBuildPlugin` SHALL be a ByteBuddy build plugin that adds
the same exit advice as the agent to the project's compiled Java and Kotlin classes that use
`@StackTraceId`. It SHALL be discoverable through `META-INF/net.bytebuddy/build.plugins`, and then
SHALL instrument annotated classes in all packages. When it is given the path of a TOML configuration as
argument 0, it SHALL apply that file's `[instrument] annotatedClasses` and `debug`.

#### Scenario: Example project
- **GIVEN** `examples/build-time`, with the ByteBuddy Gradle plugin, the `DECORATE` entry point and the build plugin with its configuration argument
- **WHEN** the project is built and run without `-javaagent`
- **THEN** the frames of `ClassWithAnnotation` show `{objectId=object-1}` and `{param=object-param-1}`
- **AND** the frames of `ClassWithoutAnnotation` are unchanged

#### Scenario: Configuration change
- **GIVEN** `annotatedClasses` in the build plugin's configuration no longer matches the project's packages
- **WHEN** the project is rebuilt
- **THEN** its classes are not instrumented, and with `debug = true` the build lists the ignored annotations

### Requirement: Only the project's own classes
Build-time instrumentation SHALL change only the classes of the project being built. Libraries SHALL
NOT be changed, so `[instrument.classIds]` and `[instrument.methodParams]` entries for third-party
classes SHALL NOT take effect for their methods.

#### Scenario: Library class
- **GIVEN** a library class listed in `[instrument.classIds]`
- **WHEN** one of its methods throws in a build-time instrumented application
- **THEN** its frame is unchanged

### Requirement: Runtime handler without an agent
When no agent has installed a handler, `Dispatch` SHALL look up the handler with `ServiceLoader` once,
on the first exception that leaves an instrumented method. `stack-augmentor-runtime` SHALL register
`com.hafnium.stackaugmentor.runtime.ThrowHandler`, whose no-argument constructor reads the runtime
configuration. If creating the handler fails, a warning SHALL be printed and exceptions SHALL keep their
original stack traces.

#### Scenario: Tests without an agent
- **GIVEN** the build-time example's tests, which run without `-javaagent`
- **WHEN** an instrumented method throws
- **THEN** the handler is created through `ServiceLoader` and the frame shows the ids
