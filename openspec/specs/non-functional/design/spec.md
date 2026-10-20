# Design Specification

## Purpose
Non-functional constraints on the architecture: what the parts of the system must guarantee about each other, without
prescribing how they are written.

## Requirements

### Requirement: Code added to application classes
The code that instrumentation adds to application methods SHALL do nothing but check whether a throwable is leaving and,
only then, hand the values to Stack Augmentor; it SHALL depend on nothing but the JDK and one small entry point of
Stack Augmentor that every class loader can reach, and nothing it does SHALL be able to fail the method. It SHALL NOT
change a method's name, signature or behaviour, so that frames show the original methods.

#### Scenario: Frames after instrumentation
- **GIVEN** a class instrumented by the agent or at build time
- **WHEN** one of its methods throws
- **THEN** the frame shows the original class and method name, with no renamed or generated copies

### Requirement: One handler per JVM
All instrumented code in a JVM SHALL reach one handler: the agent's when an agent runs, otherwise the runtime library's,
found without configuration by the application. Calls into the handler while it is already working on the same thread
SHALL be ignored, and nothing it throws SHALL reach the application.

#### Scenario: Handler already running on the thread
- **GIVEN** the handler is reading a receiver member on a thread
- **WHEN** an instrumented method called by that member throws on the same thread
- **THEN** its exception is not augmented and the application sees it unchanged

### Requirement: Shared behaviour
The agent, the build plugin and the live-stack mode SHALL share one implementation of the configuration, of the rules that
decide which methods are augmented with which values, and of turning values into text and frames, so that they cannot
drift apart.

#### Scenario: Same rules everywhere
- **GIVEN** a configuration and a class
- **WHEN** the class is used with the agent, at build time and in the live-stack mode
- **THEN** the same methods are augmented with the same values in the same layout

### Requirement: Annotations matched by name
The annotations SHALL be recognised by their names, so that the instrumentation needs no compile-time dependency on the
application's copy of the API and also recognises a copy loaded by another class loader or an older copy; an `@Augmented`
of an older API without its on/off value SHALL count as on. The annotations SHALL have no attributes other than `secret`
on `@AugmentedParam` and the on/off value of `@Augmented`.

#### Scenario: API loaded by an application class loader
- **GIVEN** the application loads its own copy of the API, and its classes use that copy's annotations
- **WHEN** they are instrumented
- **THEN** the annotations are recognised

### Requirement: Agent files on the machine
Files that the agent writes to make its entry point reachable from every class loader SHALL be private to the user who runs
the JVM: never shared with or writable by other users, reused by later JVMs of the same user, replaced when damaged, and
not piling up. When such a place cannot be used safely, the agent SHALL warn and use a private copy for this JVM.

#### Scenario: Several users on one machine
- **GIVEN** another user's JVM has started with the agent on the same machine
- **WHEN** the agent starts
- **THEN** it never uses a file that another user created

#### Scenario: Later runs of the same user
- **GIVEN** an earlier JVM of the same user started with the same agent version
- **WHEN** the agent starts
- **THEN** no new file accumulates on the machine

#### Scenario: Damaged file
- **GIVEN** the agent's file on the machine was changed
- **WHEN** the agent starts
- **THEN** it replaces it with its own

### Requirement: Validated, immutable configuration
The configuration SHALL be read once per component, validated completely, including the templates also where a component
does not render frames, and SHALL NOT change afterwards. An empty file SHALL be equal to the default configuration.

#### Scenario: Template error at startup
- **GIVEN** an invalid `receiverFormat`
- **WHEN** the agent starts
- **THEN** it fails before any class is instrumented
