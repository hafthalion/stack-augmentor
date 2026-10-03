# Performance Specification

## Purpose
Non-functional requirements on the runtime cost of augmentation, in both the Java agent and build-time
instrumentation.

## Requirements

### Requirement: No cost on normal returns
An instrumented method that returns normally SHALL pay no more than one null check for the augmentation.
It SHALL NOT allocate: the array of parameter values, the boxing of primitive parameters and the labels
SHALL only be created when an exception leaves the method.

#### Scenario: Many calls with primitive parameters
- **GIVEN** an instrumented method with six `Int` id parameters, called 2,000 times with values outside the `Integer` cache
- **WHEN** the calls return normally
- **THEN** the calling thread allocates less than 32 KB in total

### Requirement: Bounded cost on exceptions
The work done when an exception leaves an instrumented method SHALL be limited to that exception: one
search of its stack trace from the last handled frame, one walk of the current thread's stack to find the
exiting method's caller when a frame of the same class and method is found, the id lookups for the frame,
and one write of the rewritten frame. The id source of each class SHALL be looked up once and cached.
By default the trace SHALL be copied once (`getStackTrace`) and written back once (`setStackTrace`). With
`inPlaceModification = true` the handler SHALL write the frame into the throwable's own stack trace array, so
no trace is copied; this needs `java.lang` open to the runtime classes. The Java agent SHALL open it; build-time
instrumentation needs the application to open it (`--add-opens java.base/java.lang=ALL-UNNAMED`). If
`inPlaceModification` is set and `java.lang` is not open, the component SHALL report an error and fail to start
(the agent stops the JVM; build-time instrumentation installs no handler), instead of silently copying. A trace the application replaced
after it was read SHALL NOT be overwritten by an in-place write. With copying, an exception that leaves N
instrumented frames costs O(N × trace length); the JVM caps the trace length
(`-XX:MaxJavaStackTraceDepth`, 1024 by default). The README SHALL state this cost with measured numbers.

#### Scenario: Deep recursion with the agent
- **GIVEN** the Java agent with `inPlaceModification = true` and a trace of more than 1000 frames
- **WHEN** an exception leaves an instrumented method
- **THEN** its stack trace is not copied: the frame is replaced in the throwable's own array

#### Scenario: Deep recursion with build-time instrumentation
- **GIVEN** build-time instrumentation with `inPlaceModification = true` in an application started with `--add-opens java.base/java.lang=ALL-UNNAMED`
- **WHEN** an exception leaves an instrumented method
- **THEN** its stack trace is not copied: the frame is replaced in the throwable's own array

#### Scenario: Default copying
- **GIVEN** the agent or build-time instrumentation without `inPlaceModification`
- **WHEN** an exception leaves an instrumented method
- **THEN** the frame is rewritten by copying the trace and writing it back with `setStackTrace`

#### Scenario: In-place modification without java.lang open
- **GIVEN** build-time instrumentation with `inPlaceModification = true` in an application started without `--add-opens`
- **WHEN** the first exception leaves an instrumented method
- **THEN** an error names `--add-opens java.base/java.lang=ALL-UNNAMED`, and stack traces stay unchanged

#### Scenario: Repeated exceptions of one class
- **GIVEN** a class whose id source is an annotated field
- **WHEN** many exceptions leave its methods
- **THEN** the field is found by reflection only once

### Requirement: Debug output costs nothing when off
With `debug = false`, debug message texts SHALL NOT be built, and the checks that only serve debug
messages SHALL NOT run.

#### Scenario: Debug disabled
- **GIVEN** `debug = false`
- **WHEN** classes are instrumented and exceptions are handled
- **THEN** no debug message text is created
