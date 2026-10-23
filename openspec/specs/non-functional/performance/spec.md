# Performance Specification

## Purpose
Non-functional requirements on the runtime cost of augmentation, with the Java agent, with build-time instrumentation and
in the live-stack mode.

## Requirements

### Requirement: No cost on normal returns
An instrumented method that returns normally SHALL pay no more than a single check for a leaving throwable, and SHALL NOT
allocate: values, labels and boxed primitives SHALL only be prepared when an exception leaves the method.

#### Scenario: Many calls with primitive parameters
- **GIVEN** an instrumented method with six selected `Int` parameters, called 2,000 times with values outside the `Integer` cache
- **WHEN** the calls return normally
- **THEN** the calling thread allocates less than 32 KB in total

### Requirement: Bounded cost on exceptions
In the instrumenting modes, the work for an exception leaving an instrumented method SHALL be limited to that exception and
that frame, and to its causes and suppressed exceptions where they share the frame; it SHALL NOT grow with the number of
exceptions thrown before. What can be prepared once per class, such as finding its receiver members, SHALL be done once.
The cost per frame MAY grow with the length of the stack trace, which the JVM caps; writing frames in place, the
default, SHALL use a way whose cost per frame does not, where the JVM allows it: the agent SHALL make it possible itself,
and with build-time instrumentation the application's start options SHALL. Where it is not possible, frames SHALL be
written the slower way, without a warning. The documentation SHALL state
the costs with measured numbers.

#### Scenario: Deep recursion
- **GIVEN** in-place modification, with the agent or with build-time instrumentation in an application started with `--add-opens java.base/java.lang=ALL-UNNAMED`
- **WHEN** an exception leaves 1000 instrumented frames of a recursion
- **THEN** it costs about the same per frame as in a short stack

#### Scenario: In-place modification not possible
- **GIVEN** build-time instrumentation in an application started without `--add-opens`
- **WHEN** the first exception leaves an instrumented method
- **THEN** its frame is augmented the slower way, without a warning
- **AND** with debug output on, a message names `--add-opens java.base/java.lang=ALL-UNNAMED`

#### Scenario: Repeated exceptions of one class
- **GIVEN** a class whose receiver member is an annotated field
- **WHEN** many exceptions leave its methods
- **THEN** the field is looked up only once

### Requirement: Cost of the live-stack mode
In the live-stack mode, normal returns SHALL cost nothing, since no application class is changed. Every exception that
`[augment.exceptions]` selects SHALL pay for looking at its stack once, with the expensive reading of values only for the
configured frames; an exception that it does not select SHALL pay practically nothing. The documentation SHALL state this
cost with measured numbers, and the benchmark SHALL compare it with the agent.

#### Scenario: Exception without configured frames
- **GIVEN** the live-stack mode and an augmented exception none of whose frames is configured
- **WHEN** it is created
- **THEN** its trace is not changed, and no value is read

### Requirement: Debug output costs nothing when off
Without `-Dstackaugmentor.debug=true`, no debug message SHALL be built, and checks that only serve debug messages SHALL NOT run.

#### Scenario: Debug disabled
- **GIVEN** debug output off
- **WHEN** classes are instrumented and exceptions are handled
- **THEN** no debug message text is created
