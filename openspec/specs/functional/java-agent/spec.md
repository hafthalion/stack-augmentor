# Java Agent Specification

## Purpose
Defines augmentation with the Java agent (`stack-augmentor-agent`): instrumenting classes as they are
loaded, including third-party classes, without changing the application's build.

## Requirements

### Requirement: Starting the agent
The agent SHALL be started with `-javaagent:<agent jar>[=config=<path>]` (`premain`), and MAY be
attached to a running JVM (`agentmain`). A second start in the same JVM SHALL have no effect. The agent
jar SHALL be self-contained.

#### Scenario: Demo application
- **GIVEN** the `examples/java-agent` application started with the agent and its `stack-augmentor.toml`
- **WHEN** its methods throw
- **THEN** the printed stack traces show the ids of the annotated classes and of the configured third-party classes

### Requirement: Instrumenting at class load
The agent SHALL instrument classes when they are loaded, and classes already loaded by retransformation.
It SHALL ignore synthetic classes, classes loaded by the bootstrap class loader, and classes in the
`java`, `javax`, `jdk`, `sun`, `com.sun`, `kotlin`, `net.bytebuddy` and `com.hafnium.stackaugmentor`
packages.

#### Scenario: JDK classes
- **GIVEN** an exception thrown inside a JDK method
- **WHEN** the stack trace is inspected
- **THEN** the JDK frames are unchanged

### Requirement: Third-party classes
The agent SHALL instrument classes whose deciding `[instrument.classes]` entry names a field or method
(including subclasses of the matched classes), and classes with methods selected by `[instrument.methods]`
entries, in any package and without annotations. Both tables SHALL accept class patterns with wildcards.
Classes in the ignored packages SHALL stay uninstrumented, even when a wildcard entry matches them.

#### Scenario: Configured library class
- **GIVEN** `"com.thirdparty.Customer" = "customerId"` in `[instrument.classes]`
- **WHEN** `Customer("c-9").rename()` throws
- **THEN** the frame reads `com.thirdparty.Customer{customerId=c-9}.rename(…)`

#### Scenario: Library classes selected by a wildcard
- **GIVEN** `"com.thirdparty.*Service.*" = "*"` in `[instrument.methods]`
- **WHEN** `InventoryService().reserve("x-1", 2)` throws
- **THEN** the frame reads `com.thirdparty.InventoryService.reserve{sku=x-1, count=2}(…)`

#### Scenario: Library classes with a receiver id selected by a wildcard
- **GIVEN** `"com.thirdparty.*Account" = "number"` in `[instrument.classes]`
- **WHEN** `SavingsAccount("S-1").withdraw()` throws
- **THEN** the frame reads `com.thirdparty.SavingsAccount{number=S-1}.withdraw(…)`

#### Scenario: Wildcard matching JDK classes
- **GIVEN** `"java.**.*" = "*"` in `[instrument.methods]` and `"java.**" = "@"` in `[instrument.classes]`
- **WHEN** an exception is thrown inside a JDK method
- **THEN** the JDK frames are unchanged

### Requirement: Visibility of the dispatch class
The class the advice calls (`com.hafnium.stackaugmentor.instrument.bridge.Dispatch`, module
`stack-augmentor-instrument-bridge`) SHALL be appended to the bootstrap class loader, so that it is
visible from classes of every class loader, and SHALL be readable from named modules. The agent SHALL
install its handler before any class is instrumented.

#### Scenario: Class loaded by a child class loader
- **GIVEN** an instrumented class loaded by an application-specific class loader
- **WHEN** one of its methods throws
- **THEN** the frame shows its ids
