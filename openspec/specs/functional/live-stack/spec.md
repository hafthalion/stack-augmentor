# Live-Stack Mode Specification

## Purpose
Defines the agent's experimental live-stack mode: instead of instrumenting the configured classes, the agent reads the
receiver and the arguments of the configured frames from the stack when an exception records its stack trace. It depends
on a native library and on internals of the JVM; there is no build-time equivalent.

## Requirements

### Requirement: Turning the mode on
The mode SHALL be used when the JVM was started with the agent and its native library. The library SHALL only enable what
the JVM must allow at startup for reading the values of compiled frames, and SHALL never stop the JVM. Before using the
mode, the agent SHALL check that the library matches its version and that the JVM behaves as the mode needs; if not, it
SHALL warn with the reason and instrument classes as usual. The native library SHALL be buildable for Linux, macOS and
Windows with the platform's C compiler; a build without a C compiler SHALL still succeed, without the library.

#### Scenario: Agent without the library
- **GIVEN** the agent started without the native library
- **THEN** it instruments classes as usual

#### Scenario: Library of another version
- **GIVEN** a native library of another version than the agent
- **THEN** the agent warns and instruments classes as usual

### Requirement: Capturing values from the live stack
In this mode, no application class SHALL be changed. When an augmented exception records its stack trace, the agent SHALL
read the receiver and the selected arguments of every frame whose method the configuration of its class selects, by the
same rules as the instrumenting modes (see the overview, the configuration and the receivers and parameters
specifications), and turn them into text right away. An exception SHALL be looked at when any configuration augments it,
and each frame SHALL be augmented only where the configuration of its class does. Unlike the instrumenting modes, every configured frame on the stack when the exception is
created SHALL be augmented, also the method that catches it and its callers, and a constructor while it calls
`super(...)` or `this(...)`; the values SHALL be those at that moment. The augmentation SHALL appear in the stack trace
when it is first read or printed, and not at all if the application replaced the stack trace. Exceptions created while a
class is being loaded SHALL be skipped, so that the mode never interferes with class loading.

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
The augmented frames of an exception SHALL also be written into the causes and suppressed exceptions that share them, as
the stack trace augmentation specification requires ("Frames shared with causes and suppressed exceptions").

#### Scenario: Wrapped exception
- **GIVEN** `wrap()` catches the exception of `inner(2)` and throws `RuntimeException("wrapped", cause)`
- **WHEN** the wrapper is printed
- **THEN** the frames the cause shares with the wrapper show the wrapper's augmentation, and the cause section ends with `... N more`

### Requirement: Values the JIT optimized away
A value that the JVM's optimizations made unreadable, such as an object argument the JIT never allocated, SHALL be shown
as `?`, never as a wrong value; a receiver that cannot be read SHALL show no receiver members. With those optimizations
turned off, every value SHALL be shown.

#### Scenario: Allocations not eliminated
- **GIVEN** the JVM started with `-XX:-EliminateAllocations`
- **THEN** a `null` argument is shown as `null` in every frame
