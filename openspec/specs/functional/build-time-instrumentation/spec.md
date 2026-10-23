# Build-Time Instrumentation Specification

## Purpose
Defines augmentation without a Java agent: a build plugin instruments a project's compiled classes, and the application
only needs the API and a small runtime library.

## Requirements

### Requirement: Build plugin
A build plugin for Gradle SHALL instrument the project's compiled Java and Kotlin classes so that they are augmented as with the agent
and the same configuration. It SHALL take a configuration file of the build itself, a context configuration file and the
module's local configuration file as optional arguments, in the order they apply, then the built-in configuration. The context and the local file, which the application also has on its
classpath, SHALL be read as every file on the classpath is; the build's file plays the role of the JVM's file for the
build, and a system property of the build, as at runtime, SHALL turn on the debug output. Each class it instruments SHALL then
use that local configuration at runtime too, wherever it is on the classpath of its class loader, also where classes and
resources are in different directories, as in a build directory; where it is not found, e.g. as it changed after the
build, a warning SHALL say so and the class's jar or directory SHALL decide. An invalid configuration, including an invalid template that only the runtime renders, SHALL fail the build,
with the reason printed. The build SHALL be repeatable: a changed configuration SHALL lead to re-instrumented classes.

#### Scenario: Invalid template
- **GIVEN** `paramsFormat = "($name=$value, ...)"` on line 14 of the build plugin's configuration
- **WHEN** the project is built
- **THEN** the build fails, with an error naming the file, line 14 and `paramsFormat`

#### Scenario: Example project
- **GIVEN** `stack-augmentor-example/build-time`, built with the build plugin and its context configuration
- **WHEN** it is run without `-javaagent`
- **THEN** the frames of `ClassWithAnnotation` show `{objectId=object-1}` and `{param=ObjectParam{name=object-param-1}, token=#fb07916a}`, the argument's `toString()` with its parentheses replaced by braces and the secret token hashed
- **AND** the frame of `ClassWithoutAnnotation.method` shows `{q=123}` from its own `[augment.params]` entry, and no receiver members

#### Scenario: Configuration change
- **GIVEN** `"-"` entries in `[augment.receiver]` and `[augment.params]` of the build plugin's configuration that match all methods of the project's classes
- **WHEN** the project is rebuilt
- **THEN** its classes are not instrumented, and with `-Dstackaugmentor.debug=true` the build lists the ignored annotations

#### Scenario: No configuration
- **GIVEN** the build plugin without a configuration
- **WHEN** the project is built
- **THEN** the methods that `@Augmented` selects in classes with annotated receiver members or parameters are instrumented, the others are not changed, and no warning is printed

### Requirement: Only the project's own classes
Build-time instrumentation SHALL change only the classes of the project being built, so entries for library classes SHALL
NOT take effect. It SHALL leave the same types alone as the agent (see the Java agent specification).

#### Scenario: Library class
- **GIVEN** a library class listed in `[augment.receiver]`
- **WHEN** one of its methods throws in a build-time instrumented application
- **THEN** its frame is unchanged

### Requirement: Runtime without an agent
At runtime, the runtime library SHALL start by itself on the first exception that leaves an instrumented method, reading
its configuration, and each application's own where it has one (see the configuration specification). What the build decided SHALL stand: which methods are augmented,
their parameter values, and whether their receiver members come from the annotations or from an `[augment.receiver]`
entry. For an entry, the runtime SHALL show the members of its own entry with the same key; a method whose key the runtime
configuration does not have, e.g. after a key was changed without rebuilding, SHALL show no receiver members. For the
annotations, the runtime SHALL show the `@AugmentedReceiver` members. If
the runtime cannot start, it SHALL warn with the reason, and exceptions SHALL keep their original stack traces.

#### Scenario: Tests without an agent
- **GIVEN** classes instrumented at build time, run without `-javaagent`
- **WHEN** an instrumented method throws
- **THEN** its frame is augmented

#### Scenario: No runtime configuration
- **GIVEN** a class instrumented at build time with the `[augment.receiver]` entry `"com.acme.Svc::*"`, and no configuration at runtime
- **WHEN** one of its methods throws an exception
- **THEN** the built-in configuration applies: the exception is augmented, but the frame shows no receiver members, since the built-in configuration has no such entry
- **AND** a warning says that the built-in configuration is used

#### Scenario: Built-in configuration at build time and at runtime
- **GIVEN** a class with `@Augmented` and `@AugmentedReceiver`, instrumented without a configuration, and no runtime configuration
- **WHEN** one of its methods throws an exception
- **THEN** the frame shows the annotated receiver members

#### Scenario: The build decides where the annotations are read
- **GIVEN** a class with `@Augmented`, `@AugmentedReceiver` and `@AugmentedParam`, built without entries for it, and a runtime configuration with `"com.acme.**::*" = "-"` in `[augment.receiver]`
- **WHEN** one of its methods throws
- **THEN** the frame shows the annotated receiver members and parameter values, as the build decided

#### Scenario: Receiver key changed without a rebuild
- **GIVEN** a class built with `"com.acme.Svc::*" = "id"`, and a runtime configuration with `"com.acme.Svc+::*" = "id"` instead
- **WHEN** a method of `Svc` throws
- **THEN** the frame shows no receiver members

#### Scenario: Parameter entry only
- **GIVEN** `"com.acme.Svc::run" = "0"` in `[augment.params]`, no `[augment.receiver]` entry for `Svc`, and `Svc` with a `@AugmentedReceiver` field
- **WHEN** `run` throws in a build-time instrumented application
- **THEN** the frame shows the parameter value of `run`'s first parameter and no receiver members
