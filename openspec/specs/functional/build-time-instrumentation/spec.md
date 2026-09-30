# Build-Time Instrumentation Specification

## Purpose
Defines augmentation without a Java agent: the ByteBuddy Gradle plugin applies
`StackAugmentorByteBuddyPlugin` (`stack-augmentor-build-plugin`) to a project's compiled classes, and the
application only needs `stack-augmentor-runtime` at runtime.

## Requirements

### Requirement: Build plugin
`com.hafnium.stackaugmentor.build.StackAugmentorByteBuddyPlugin` SHALL be a ByteBuddy build plugin that adds
the same exit advice as the agent to the project's compiled Java and Kotlin classes. When it is given the
path of a TOML configuration as argument 0, it SHALL apply that file's `[augment.receiver]`,
`[augment.params]` and `debug`, instrumenting the classes and methods that these tables need, as the agent would with the same
configuration. An invalid configuration, including an invalid `[augment]` template that only the runtime
renders, SHALL fail the build, and the reason SHALL be printed as an error, because the ByteBuddy Gradle
plugin does not show the cause of a failing plugin constructor. It SHALL be discoverable through
`META-INF/net.bytebuddy/build.plugins`. Without a configuration, it SHALL instrument nothing and SHALL print
a warning.

#### Scenario: Invalid template
- **GIVEN** `paramsFormat = "($name=$id, ...)"` on line 14 of the build plugin's configuration
- **WHEN** the project is built
- **THEN** the build fails, and `[stack-augmentor] ERROR build plugin: stack-augmentor.toml, line 14: paramsFormat must not contain '('` … is printed

#### Scenario: Example project
- **GIVEN** `examples/build-time`, with the ByteBuddy Gradle plugin, the `DECORATE` entry point and the build plugin with its configuration argument, which has `"com.hafnium.**" = "@"` in `[augment.receiver]` and `"com.hafnium.**.*" = "@"` in `[augment.params]`
- **WHEN** the project is built and run without `-javaagent`
- **THEN** the frames of `ClassWithAnnotation` show `{objectId=object-1}` and `{param=ObjectParam{name=object-param-1}}`, the argument's `toString()` with its parentheses replaced by braces
- **AND** the frames of `ClassWithoutAnnotation` are unchanged

#### Scenario: Configuration change
- **GIVEN** the `"@"` entries in the build plugin's configuration no longer match the project's classes
- **WHEN** the project is rebuilt
- **THEN** its classes are not instrumented, and with `debug = true` the build lists the ignored annotations

#### Scenario: Method-level parameter annotation
- **GIVEN** a project class whose only annotation is `@StackTraceParams` on a method, which an `[augment.params]` `"@"` entry matches
- **WHEN** the project is built
- **THEN** that method is instrumented and shows its parameter ids

#### Scenario: No configuration
- **GIVEN** the build plugin discovered through `META-INF/net.bytebuddy/build.plugins`, without an argument
- **WHEN** the project is built
- **THEN** no class is changed and a warning says that nothing will be augmented

### Requirement: Only the project's own classes
Build-time instrumentation SHALL change only the classes of the project being built. Libraries SHALL
NOT be changed, so `[augment.receiver]` and `[augment.params]` entries for third-party classes SHALL
NOT take effect for their methods. The build plugin SHALL leave the same types alone as the agent (see
"Instrumenting at class load" in the Java agent specification): synthetic types and the types in the ignored
packages, even when an entry matches them.

#### Scenario: Library class
- **GIVEN** a library class listed in `[augment.receiver]`
- **WHEN** one of its methods throws in a build-time instrumented application
- **THEN** its frame is unchanged

### Requirement: Runtime handler without an agent
When no agent has installed a handler, `Dispatch` SHALL look up the handler with `ServiceLoader` once,
on the first exception that leaves an instrumented method. `stack-augmentor-runtime` SHALL register
`com.hafnium.stackaugmentor.runtime.ThrowHandler`, whose no-argument constructor reads the runtime
configuration. The `[augment.receiver]` entries SHALL apply as with the agent: a class that no entry
matches SHALL get no receiver id, even if it is annotated or a superclass is matched. If creating the handler fails, a warning with the reason SHALL be printed and
exceptions SHALL keep their original stack traces; an invalid runtime configuration SHALL be printed as an error
with the file, the key and the line (see the diagnostics specification).

#### Scenario: Tests without an agent
- **GIVEN** the build-time example's tests, which run without `-javaagent`
- **WHEN** an instrumented method throws
- **THEN** the handler is created through `ServiceLoader` and the frame shows the ids

#### Scenario: No runtime configuration
- **GIVEN** a class with `@StackTraceId`, instrumented at build time, and no `stack-augmentor.toml` on the runtime classpath
- **WHEN** one of its methods throws
- **THEN** the frame shows no receiver id
- **AND** a warning says that there is no runtime configuration, so frames show no receiver ids

#### Scenario: Parameter entry only
- **GIVEN** `"com.acme.Svc.run" = [0]` in `[augment.params]`, no `[augment.receiver]` entry for `Svc`, and `Svc` with a `@StackTraceId` field
- **WHEN** `run` throws in a build-time instrumented application
- **THEN** the frame shows the parameter id of `run`'s first parameter and no receiver id
