# Design Specification

## Purpose
Non-functional requirements that record the current design decisions: how ids are captured, dispatched
and rendered, and how the Java agent loads, so that changes to them are made deliberately.

## Requirements

### Requirement: Inlined exit advice
Instrumented methods SHALL get ByteBuddy exit advice (`@Advice.OnMethodExit(onThrowable = Throwable.class)`)
that is inlined into the method. The advice SHALL only check whether a throwable is leaving and, if so,
call `Dispatch.onThrow`. Exceptions inside the advice SHALL be suppressed.

#### Scenario: Normal return
- **GIVEN** an instrumented method
- **WHEN** it returns normally
- **THEN** the only added work is the null check of the thrown value

### Requirement: Lazily built advice arguments
The receiver, the parameter values and their labels SHALL be bound through custom ByteBuddy offset
mappings (`@IdArgs` for an `Object[]` of the id parameters, boxing primitives, and `@IdArgNames` for a
`String[]` of labels), so that the arrays are built only where the advice reads them: inside the
exception branch. Methods without id parameters SHALL get `null` for both.

#### Scenario: Six primitive id parameters
- **GIVEN** a method with six `Int` id parameters
- **WHEN** it returns normally
- **THEN** no `Object[]`, `String[]` or boxed `Integer` is created

### Requirement: Advice and bridge written in Java
The advice classes, the bridge (`Dispatch`) and every other class of a published module SHALL be written
in Java, so that no Kotlin runtime calls are inlined into instrumented classes and no Kotlin runtime is
needed by the agent, the runtime handler or the build plugin. The inlined code SHALL reference only JDK
classes and `Dispatch`.

#### Scenario: Instrumented class without Kotlin
- **GIVEN** a Java application without the Kotlin standard library
- **WHEN** its classes are instrumented
- **THEN** they load and run without `NoClassDefFoundError`

### Requirement: Single dispatch point
All instrumented code SHALL call the static `Dispatch.onThrow(self, thrown, owner, method, paramValues, paramNames)`,
which forwards to one installed `Dispatch.Handler`. The agent SHALL install the handler explicitly;
otherwise `Dispatch` SHALL look it up once with `ServiceLoader`. A thread-local flag SHALL prevent
re-entrant calls, and any `Throwable` from the handler SHALL be swallowed.

#### Scenario: Handler already running on the thread
- **GIVEN** the handler is building an id on a thread
- **WHEN** an instrumented method called by the id source throws on the same thread
- **THEN** `Dispatch.onThrow` returns immediately

### Requirement: Bridge on the bootstrap class path
The agent SHALL embed the bridge jar as a resource, copy it to the temporary directory under a name
derived from its SHA-256 hash (reusing an existing copy), and append it with
`Instrumentation.appendToBootstrapClassLoaderSearch` before any bridge class is referenced. It SHALL add
read edges from instrumented modules to the bridge.

#### Scenario: Repeated starts
- **GIVEN** the agent has run before on the machine
- **WHEN** it starts again with the same version
- **THEN** it reuses the existing bridge copy instead of writing a new file

### Requirement: Agent transformation strategy
The agent SHALL instrument with ByteBuddy's `AgentBuilder` using `disableClassFormatChanges`, the
`RETRANSFORMATION` redefinition strategy, the `DECORATE` type strategy and disabled class injection. It
SHALL disable ByteBuddy's Nexus and Unsafe use through ByteBuddy's (relocated) system properties.

#### Scenario: Retransformation of loaded classes
- **GIVEN** a class that was loaded before the agent's transformer was installed
- **WHEN** the agent installs
- **THEN** the class is retransformed without changing its fields or method signatures

### Requirement: Build-time transformation strategy
Build-time instrumentation SHALL use the ByteBuddy Gradle plugin with the `DECORATE` entry point, so
that methods are only decorated with advice and not rebased. The build plugin SHALL reuse the agent's
type matching and advice.

#### Scenario: Frames after build-time instrumentation
- **GIVEN** a class instrumented at build time
- **WHEN** one of its methods throws
- **THEN** the frame shows the original method name (no renamed or synthetic copies)

### Requirement: Matching by name
The agent, build plugin and runtime SHALL recognise `@StackTraceId` by its class name
(`com.hafnium.stackaugmentor.StackTraceId`), without depending on the API module, and read its `name`
reflectively.

#### Scenario: API loaded by an application class loader
- **GIVEN** the application loads its own copy of `stack-augmentor-api`
- **WHEN** its annotated classes are instrumented
- **THEN** the annotations are recognised

### Requirement: Caching and weak state
Id sources SHALL be looked up once per class and cached in a `ClassValue`. The per-exception search
position SHALL be kept in a weak, identity-based map. Ids SHALL be converted to `String` when captured.

#### Scenario: Exceptions that override equals
- **GIVEN** two distinct exceptions that are `equals` to each other
- **WHEN** both are being augmented
- **THEN** each keeps its own search position

### Requirement: Rewriting with StackTraceElement
A rewritten frame SHALL be a new `StackTraceElement` built with the seven-argument constructor, keeping
the class loader name, module name and version only where the original element printed them, and the
file name and line number. It SHALL be installed with `Throwable.setStackTrace`.

#### Scenario: Built-in class loader
- **GIVEN** a frame of a class loaded by the application class loader, printed without a loader prefix
- **WHEN** it is rewritten
- **THEN** the new frame is also printed without a loader prefix (not as `app//…`)

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
