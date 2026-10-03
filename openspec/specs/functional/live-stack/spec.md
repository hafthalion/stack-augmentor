# Live-Stack Mode Specification

## Purpose
Defines the agent's experimental live-stack mode: instead of instrumenting the configured classes, the agent reads
the receiver and the arguments of the configured frames from the live stack when the JVM records a stack trace.

## Requirements

### Requirement: Turning the mode on
The mode SHALL be used when the JVM was started with the native library `stack-augmentor-native`
(`-agentpath:<library>`) next to the agent, in either order. The library SHALL acquire the JVMTI capability
`can_access_local_variables` while the JVM starts, and SHALL never stop the JVM. The agent SHALL check that the
library is loaded and has the version it expects, that the JDK's live stack frames hold the receiver and the arguments
where it expects them, and that its code in `Throwable` runs. If any check fails, the agent SHALL print a warning
naming the reason, change nothing, and instrument classes as usual.

#### Scenario: Agent without the library
- **GIVEN** the agent started without the native library
- **THEN** it instruments classes as usual

#### Scenario: Library of another version
- **GIVEN** a native library whose version the agent does not expect
- **THEN** the agent warns and instruments classes as usual

### Requirement: Capturing ids from the live stack
In this mode the agent SHALL add code only to `java.lang.Throwable`: when the JVM has recorded a stack trace, for each
frame of that trace whose method an entry selects, as decided by the same rules as for instrumentation, it SHALL read
the receiver and the selected arguments from the live stack and keep them; it SHALL turn them into ids only when the
throwable's stack trace array is created. Creating a throwable SHALL cost no more than a walk over the classes of the
frames and, only if one of them may show ids, a walk that reads local variables down to the last such frame.
Exceptions created while a class is being loaded SHALL be skipped. The ids SHALL be written into the throwable's stack trace array when that is
created from the recorded frames, once, and SHALL not be written if the application replaced the stack trace.
The configuration and the formats SHALL be the same as with instrumentation.

#### Scenario: Frames below a catch
- **GIVEN** `Layers.catchAndReturn()` catches the exception that `Layers.inner(1)` throws, and returns it
- **THEN** both frames show the receiver id, `inner` also its parameter id

#### Scenario: Exception from the superclass constructor
- **GIVEN** `ExpressParcel(code, priority)` calls `super(code)`, which throws
- **THEN** the `ExpressParcel.<init>` frame shows its parameter ids, and no receiver id

#### Scenario: JIT-compiled frames
- **GIVEN** a configured method called often enough to be compiled
- **WHEN** it throws
- **THEN** its frame shows the same ids as before it was compiled

#### Scenario: Replaced stack trace
- **GIVEN** an exception with captured ids whose stack trace the application replaced with `setStackTrace`
- **THEN** the stack trace is exactly the one the application set

### Requirement: Values the JIT optimized away
An argument of an object type that reads as `null` in a JIT-compiled frame while the JVM may eliminate allocations
(`-XX:+EliminateAllocations`, the default) SHALL be shown as `?`. A receiver that is not an instance of the declaring
class SHALL give no receiver id.

#### Scenario: Allocations not eliminated
- **GIVEN** the JVM started with `-XX:-EliminateAllocations`
- **THEN** a `null` argument is shown as `null` in every frame
