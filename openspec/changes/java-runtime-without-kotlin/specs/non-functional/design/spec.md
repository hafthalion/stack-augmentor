# Spec Delta

## MODIFIED Requirements

### Requirement: Advice and bridge written in Java
The advice classes, the bridge (`Dispatch`) and every other class of a published module SHALL be written
in Java, so that no Kotlin runtime calls are inlined into instrumented classes and no Kotlin runtime is
needed by the agent, the runtime handler or the build plugin. The inlined code SHALL reference only JDK
classes and `Dispatch`.

#### Scenario: Instrumented class without Kotlin
- **GIVEN** a Java application without the Kotlin standard library
- **WHEN** its classes are instrumented
- **THEN** they load and run without `NoClassDefFoundError`

### Requirement: Configuration model
The configuration SHALL be parsed with tomlj and mapped directly onto an immutable `AugmentorConfig`
Java class, with typed access and value-based equality. Every key SHALL be validated. The receiver id
sources, parameter references and named ids SHALL be Java records, grouped under sealed interfaces
where there are alternatives (`IdSpec`, `ParamRef`). The frame templates SHALL be parsed and validated
once, when the agent starts or the runtime handler is created.

#### Scenario: Template error at startup
- **GIVEN** an invalid `receiverFormat`
- **WHEN** the agent starts
- **THEN** it fails before any class is instrumented

#### Scenario: Equal configurations
- **GIVEN** an empty TOML file
- **WHEN** it is parsed
- **THEN** the result equals the default configuration

### Requirement: Logging without a framework
Warnings and debug messages SHALL be written to standard error by a small internal `Log` class, without
a logging framework. Debug messages SHALL be passed as a `Supplier<String>`, so that disabled debug
messages are never built. Where a message would capture values on a path that runs for every class or every
exception, the call SHALL be guarded by a check of the debug flag, so that no lambda is allocated when
debugging is off.

#### Scenario: Application with its own logging setup
- **GIVEN** an application that configures SLF4J or java.util.logging
- **WHEN** the agent writes messages
- **THEN** it does not use or change the application's logging configuration

#### Scenario: Debug disabled
- **GIVEN** `debug = false`
- **WHEN** exceptions leave instrumented methods
- **THEN** no debug message text and no message supplier is created
