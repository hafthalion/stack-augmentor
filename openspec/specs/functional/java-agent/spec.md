# Java Agent Specification

## Purpose
Defines augmentation with the Java agent: instrumenting classes as they are loaded, including third-party classes, without
changing the application's build.

## Requirements

### Requirement: Starting the agent
The agent SHALL be one self-contained jar, started with `-javaagent:<agent jar>`, without arguments (see the configuration specification), and MAY also be attached
to a running JVM. A second start in the same JVM SHALL have no effect. At startup it SHALL read and validate the
configuration (see the configuration specification), and use the live-stack mode when the JVM was started with its native
library and can use it (see the live-stack specification), otherwise instrument classes.

#### Scenario: Demo application
- **GIVEN** the `examples/java-agent` application started with the agent and its `stack-augmentor.toml`
- **WHEN** its methods throw
- **THEN** the printed stack traces show the receiver members and parameter values of the annotated classes and of the configured third-party classes

### Requirement: Instrumenting at class load
The agent SHALL instrument the classes that need it (see the overview) when they are loaded, and classes loaded before it
started. It SHALL never instrument the JDK (also classes of the bootstrap class loader), Kotlin's standard library, its own
dependencies or Stack Augmentor itself, nor compiler-generated classes, even when an entry matches them; the build plugin
SHALL leave the same types alone. Instrumenting SHALL NOT change the classes' fields or method signatures.

#### Scenario: JDK classes
- **GIVEN** `"java.**::*" = "0"` in `[augment.params]`, `"java.**::*" = "id"` in `[augment.receiver]` and `"java.**" = true` in `[augment.annotations]`
- **WHEN** an exception is thrown inside a JDK method
- **THEN** the JDK frames are unchanged

#### Scenario: Class loaded by a child class loader
- **GIVEN** an instrumented class loaded by an application-specific class loader, or in a named module
- **WHEN** one of its methods throws
- **THEN** its frame is augmented

### Requirement: Third-party classes
The agent SHALL augment classes of any package, also without annotations, through `[augment.receiver]` and
`[augment.params]` entries, with class patterns too, each table independently of the other.

#### Scenario: Configured library class
- **GIVEN** `"com.thirdparty.Customer::*" = "customerId"` in `[augment.receiver]`
- **WHEN** `Customer("c-9").rename()` throws
- **THEN** the frame reads `com.thirdparty.Customer{customerId=c-9}.rename(…)`

#### Scenario: Library classes selected by a wildcard
- **GIVEN** `"com.thirdparty.*Service::*" = "sku, count"` in `[augment.params]`
- **WHEN** `InventoryService().reserve("x-1", 2)` throws
- **THEN** the frame reads `com.thirdparty.InventoryService.reserve{sku=x-1, count=2}(…)`

#### Scenario: Library classes with a receiver member selected by a wildcard
- **GIVEN** `"com.thirdparty.*Account::*" = "number"` in `[augment.receiver]`
- **WHEN** `SavingsAccount("S-1").withdraw()` throws
- **THEN** the frame reads `com.thirdparty.SavingsAccount{number=S-1}.withdraw(…)`

### Requirement: Classes instrumented at build time
Under the agent, classes instrumented at build time SHALL keep working and SHALL NOT be instrumented again. Their
augmentation SHALL be as without the agent: the build decides which methods are augmented and which parameter values they
show, and the configuration of the class at runtime (the agent's, or its application's, see the configuration
specification) gives the formats, `[augment.exceptions]` and the members of the `[augment.receiver]` entries the build
chose. In the live-stack mode, those configurations SHALL decide every frame.

#### Scenario: Build-time classes under the agent
- **GIVEN** classes instrumented at build time, run with the agent
- **WHEN** an instrumented constructor or method throws
- **THEN** the classes load and run normally, and their frames show the same augmentation as without the agent
