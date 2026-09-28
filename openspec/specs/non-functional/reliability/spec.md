# Reliability Specification

## Purpose
Non-functional requirements ensuring that augmentation never changes the behaviour of the application,
and that configuration mistakes are found early.

## Requirements

### Requirement: The application's exceptions are never changed otherwise
Apart from the frames of its stack trace, an exception SHALL reach the application unchanged: same
object, type, message, cause and suppressed exceptions. Any failure inside the augmentation, including
failures of id sources and of creating the handler, SHALL be contained and SHALL NOT be thrown to the
application.

#### Scenario: Id source throws
- **GIVEN** an annotated id method that throws `IllegalStateException`
- **WHEN** a method of that class throws `IllegalStateException("fail")`
- **THEN** the application receives the original exception with the message `fail`, and the frame shows `id=?`

### Requirement: No recursion
Exceptions thrown while the augmentation itself runs on a thread, for example by an id source that is an
instrumented method, SHALL NOT be augmented.

#### Scenario: Instrumented id source throws
- **GIVEN** an id source that is itself an instrumented method and throws
- **WHEN** it is called to build an id
- **THEN** its exception is not augmented and the id is shown as `?`

### Requirement: No retained objects
The augmentation SHALL NOT keep references to application objects or exceptions beyond their use: ids
SHALL be converted to text immediately, and per-exception state SHALL be held weakly.

#### Scenario: Exception becomes unreachable
- **GIVEN** an augmented exception that the application no longer references
- **WHEN** the garbage collector runs
- **THEN** the exception and the objects its ids came from can be collected

### Requirement: Fail fast on invalid configuration
An invalid configuration SHALL stop the JVM when the agent starts, or fail the build when the build
plugin is created, with a message naming the file, the key and the line. With build-time instrumentation, an
invalid runtime configuration SHALL be printed as an error with the file, the key and the line, and the
application SHALL keep running with unchanged stack traces. It SHALL NOT be silently ignored.

#### Scenario: Typo in a key
- **GIVEN** `[augment]` with `frameformat = "…"`
- **WHEN** the agent starts
- **THEN** the JVM stops with a message naming the unknown key `frameformat` and the allowed keys

#### Scenario: Invalid runtime configuration of build-time instrumentation
- **GIVEN** a build-time instrumented application whose `stack-augmentor.toml` on the classpath has `frameformat = "…"`
- **WHEN** the first exception leaves an instrumented method
- **THEN** an error names the file, the unknown key `frameformat` and its line, and the exception is thrown with its original stack trace
