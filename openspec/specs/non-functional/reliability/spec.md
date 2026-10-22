# Reliability Specification

## Purpose
Non-functional requirements ensuring that augmentation never changes the behaviour of the application,
and that configuration mistakes are found early.

## Requirements

### Requirement: The application's exceptions are never changed otherwise
Apart from the frames of its stack trace, an exception SHALL reach the application unchanged: same
object, type, message, cause and suppressed exceptions. Any failure inside the augmentation, including
failures of receiver members and of starting the runtime, SHALL be contained and SHALL NOT be thrown to the
application.

#### Scenario: Receiver member throws
- **GIVEN** an annotated receiver member `id()` that throws `IllegalStateException`
- **WHEN** a method of that class throws `IllegalStateException("fail")`
- **THEN** the application receives the original exception with the message `fail`, and the frame shows `id=?`

### Requirement: No recursion
Exceptions thrown while the augmentation itself runs on a thread, for example by a receiver member that is an
instrumented method, SHALL NOT be augmented.

#### Scenario: Instrumented receiver member throws
- **GIVEN** a receiver member that is itself an instrumented method and throws
- **WHEN** it is called to read its value
- **THEN** its exception is not augmented and the value is shown as `?`

### Requirement: No retained objects
The augmentation SHALL NOT keep application objects or exceptions alive: values SHALL be converted to text when they are
captured, and anything kept per exception SHALL be released when the exception is no longer used.

#### Scenario: Exception becomes unreachable
- **GIVEN** an augmented exception that the application no longer references
- **WHEN** the garbage collector runs
- **THEN** the exception and the objects its values came from can be collected

### Requirement: Fail fast on invalid configuration
An invalid configuration SHALL stop the JVM when the agent starts with `-javaagent` (an agent attached later fails to
start instead), or fail the build when the build plugin starts, with a message naming the file, the key and the line.
With build-time instrumentation, an invalid runtime configuration, and in every way to run an invalid configuration of an
application (see the configuration specification), SHALL be reported as an error with the file, the key and the line,
and the application SHALL keep running with the stack traces it covers unchanged. It SHALL NOT be silently ignored.

#### Scenario: Typo in a key
- **GIVEN** `[augment]` with `frameformat = "…"`
- **WHEN** the agent starts
- **THEN** the JVM stops with a message naming the unknown key `frameformat` and the allowed keys

#### Scenario: Invalid runtime configuration of build-time instrumentation
- **GIVEN** a build-time instrumented application whose `stack-augmentor-global.toml` on the classpath has `frameformat = "…"`
- **WHEN** the first exception leaves an instrumented method
- **THEN** an error names the file, the unknown key `frameformat` and its line, and the exception is thrown with its original stack trace
