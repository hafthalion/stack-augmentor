# Live-Stack Mode Specification

## Purpose
Defines the agent's experimental live-stack mode: instead of instrumenting the configured classes, the agent reads
the receiver and the arguments of the configured frames from the live stack when the JVM records a stack trace. It relies
on JDK internals (`java.lang.LiveStackFrame`) and on a native JVMTI library; there is no build-time equivalent.

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

### Requirement: Capturing values from the live stack
In this mode the agent SHALL add code only to `java.lang.Throwable`: when the JVM has recorded a stack trace, for each
frame of that trace whose method an entry selects, as decided by the same rules as for instrumentation, it SHALL read
the receiver and the selected arguments from the live stack and turn them into text right away. Exceptions created while
a class loader method is on the stack, such as a class loader's `ClassNotFoundException`, SHALL be skipped: looking at
classes then could load the class being loaded again. The augmentation SHALL be written into the throwable's stack trace array when that is
created from the recorded frames, once, and SHALL not be written if the application replaced the stack trace.
The configuration, the formats and the rules of the configuration, receivers and parameters and stack trace augmentation
specifications SHALL be the same as with instrumentation: `[augment.exceptions]` decides which throwables are augmented,
the deciding entries or, where they are in use, the annotations decide each frame, and the types the agent ignores (see
the Java agent specification), synthetic and bridge methods stay unchanged. Unlike with instrumentation, every configured
frame on the stack when the throwable is created SHALL be augmented, also the frames of the method that catches it and of
its callers, and of a constructor while it calls `super(...)` or `this(...)`, and the values SHALL be those at that moment.

#### Scenario: Frames below a catch
- **GIVEN** `Layers.catchAndReturn()` catches the exception that `Layers.inner(1)` throws, and returns it
- **THEN** both frames show the receiver member, `inner` also its parameter value

#### Scenario: Exception from the superclass constructor
- **GIVEN** `ExpressParcel(code, priority)` calls `super(code)`, which throws
- **THEN** the `ExpressParcel.<init>` frame shows its parameter values, and no receiver members

#### Scenario: JIT-compiled frames
- **GIVEN** a configured method called often enough to be compiled
- **WHEN** it throws
- **THEN** its frame shows the same augmentation as before it was compiled

#### Scenario: Replaced stack trace
- **GIVEN** an exception with captured values whose stack trace the application replaced with `setStackTrace`
- **THEN** the stack trace is exactly the one the application set

### Requirement: Causes and suppressed exceptions
When the stack trace array of an augmented throwable is created, its augmented frames SHALL also be written into its causes
and suppressed exceptions, and theirs, where they share them, as the stack trace augmentation specification requires for
instrumented classes ("Frames shared with causes and suppressed exceptions"), so that printed traces still collapse.

#### Scenario: Wrapped exception
- **GIVEN** `wrap()` catches the exception of `inner(2)` and throws `RuntimeException("wrapped", cause)`, in the live-stack mode
- **WHEN** the wrapper is printed
- **THEN** the frames the cause shares with the wrapper show the wrapper's augmentation, and the cause section ends with `... N more`

### Requirement: Values the JIT optimized away
An argument of an object type that reads as `null` in a JIT-compiled frame while the JVM may eliminate allocations
(`-XX:+EliminateAllocations`, the default) SHALL be shown as `?`, and so SHALL an argument whose slot cannot be read
as a value of its type. A receiver that is not an instance of the declaring
class SHALL show no receiver members.

#### Scenario: Allocations not eliminated
- **GIVEN** the JVM started with `-XX:-EliminateAllocations`
- **THEN** a `null` argument is shown as `null` in every frame

### Requirement: Native library
`stack-augmentor-native` SHALL be a C library built with the platform's C compiler (`cc`, or MinGW `gcc` on Windows) by
`./gradlew :stack-augmentor-native:compileNative`, into `libstackaugmentor.so`, `libstackaugmentor.dylib` or
`stackaugmentor.dll`. Its only job SHALL be to acquire `can_access_local_variables` at JVM start and report its version;
the build SHALL skip it, and the tests of this mode, when no C compiler is found, except on CI.

#### Scenario: No C compiler
- **GIVEN** a machine without a C compiler
- **WHEN** `./gradlew build` runs
- **THEN** the native library and the live-stack tests are skipped, and the build succeeds
