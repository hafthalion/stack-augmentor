# Build-Time Instrumentation Specification

## Purpose
Defines augmentation without a Java agent: the ByteBuddy Gradle plugin applies
`ByteBuddyPlugin` (`stack-augmentor-build-plugin`) to a project's compiled classes, and the
application only needs `stack-augmentor-runtime` at runtime.

## Requirements

### Requirement: Build plugin
`com.hafnium.stackaugmentor.build.ByteBuddyPlugin` SHALL be a ByteBuddy build plugin that adds
the same exit advice as the agent to the project's compiled Java and Kotlin classes. When it is given the
path of a TOML configuration as argument 0, it SHALL apply that file's `[augment.receiver]`,
`[augment.params]` and `debug`, instrumenting the classes and methods that these tables need, as the agent would with the same
configuration. An invalid configuration, including an invalid `[augment]` template that only the runtime
renders, SHALL fail the build, and the reason SHALL be printed as an error, because the ByteBuddy Gradle
plugin does not show the cause of a failing plugin constructor. It SHALL be discoverable through
`META-INF/net.bytebuddy/build.plugins`. Without a configuration, it SHALL apply the built-in configuration (see the
configuration spec), instrumenting the classes with annotated receiver members or parameters.

#### Scenario: Invalid template
- **GIVEN** `paramsFormat = "($name=$value, ...)"` on line 14 of the build plugin's configuration
- **WHEN** the project is built
- **THEN** the build fails, and `[stack-augmentor] ERROR build plugin: stack-augmentor.toml, line 14: paramsFormat must not contain '('` … is printed

#### Scenario: Example project
- **GIVEN** `examples/build-time`, with the ByteBuddy Gradle plugin, the `DECORATE` entry point and the build plugin with its configuration argument, which has `"com.hafnium.examples.buildtime.**::*" = "@"` in `[augment.receiver]` and `"com.hafnium.examples.buildtime.**::*" = "@"` in `[augment.params]`
- **WHEN** the project is built and run without `-javaagent`
- **THEN** the frames of `ClassWithAnnotation` show `{objectId=object-1}` and `{param=ObjectParam{name=object-param-1}, token=#fb07916a}`, the argument's `toString()` with its parentheses replaced by braces and the secret token hashed
- **AND** the frame of `ClassWithoutAnnotation.method` shows `{q=123}` from its own `[augment.params]` entry, which comes before the `"@"` pattern, and no receiver members

#### Scenario: Configuration change
- **GIVEN** the `"@"` entries in the build plugin's configuration no longer match the project's classes
- **WHEN** the project is rebuilt
- **THEN** its classes are not instrumented, and with `debug = true` the build lists the ignored annotations

#### Scenario: Method-level parameter annotation
- **GIVEN** a project class whose only annotation is `@AugmentedParams` on a method, which an `[augment.params]` `"@"` entry matches
- **WHEN** the project is built
- **THEN** that method is instrumented and shows its parameter values

#### Scenario: No configuration
- **GIVEN** the build plugin discovered through `META-INF/net.bytebuddy/build.plugins`, without an argument
- **WHEN** the project is built
- **THEN** the classes with an `@AugmentedReceiver` member or an annotated parameter are instrumented, the others are not changed, and no warning is printed

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
`com.hafnium.stackaugmentor.runtime.handler.ThrowHandler`, whose no-argument constructor reads the runtime
configuration. The runtime SHALL NOT match classes against `[augment.receiver]` again: the advice passes the key
of the entry that decided the method at build time, and the runtime entry with that key SHALL give the receiver
members. A method that no entry decided at build time, or whose key the runtime configuration does not have (e.g.
a key changed without rebuilding), SHALL show no receiver members, even if its class is annotated. If creating the handler fails, a warning with the reason SHALL be printed and
exceptions SHALL keep their original stack traces; an invalid runtime configuration SHALL be printed as an error
with the file, the key and the line (see the diagnostics specification).

#### Scenario: Tests without an agent
- **GIVEN** the tests of `stack-augmentor-it-build-time`, which run without `-javaagent`
- **WHEN** an instrumented method throws
- **THEN** the handler is created through `ServiceLoader` and the frame shows the augmentation

#### Scenario: No runtime configuration
- **GIVEN** a class with `@AugmentedReceiver`, instrumented at build time with the key `com.acme.Svc::*`, and no `stack-augmentor.toml` on the runtime classpath
- **WHEN** one of its methods throws an exception
- **THEN** the built-in configuration applies: the exception is augmented, but the frame shows no receiver members, since the built-in configuration has no entry with that key
- **AND** a warning says that there is no runtime configuration, so the built-in configuration is used

#### Scenario: Built-in configuration at build time and at runtime
- **GIVEN** a class with `@AugmentedReceiver`, instrumented by the build plugin without a configuration, and no runtime configuration
- **WHEN** one of its methods throws an exception
- **THEN** the frame shows the annotated receiver members, since both use the key `**::*`

#### Scenario: Receiver key changed without a rebuild
- **GIVEN** a class built with `"com.acme.Svc::*" = "id"`, and a runtime configuration with `"com.acme.Svc+::*" = "id"` instead
- **WHEN** a method of `Svc` throws
- **THEN** the frame shows no receiver members, since the runtime has no entry with the key `com.acme.Svc::*`

#### Scenario: Parameter entry only
- **GIVEN** `"com.acme.Svc::run" = "0"` in `[augment.params]`, no `[augment.receiver]` entry for `Svc`, and `Svc` with a `@AugmentedReceiver` field
- **WHEN** `run` throws in a build-time instrumented application
- **THEN** the frame shows the parameter value of `run`'s first parameter and no receiver members
