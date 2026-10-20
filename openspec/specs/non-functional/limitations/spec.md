# Known Limitations Specification

## Purpose
Documents accepted, intentional limits of the design, so that they are not mistaken for defects and are
reconsidered deliberately when they change.

## Requirements

### Requirement: Only frames the exception passed through
In the instrumenting modes, augmentation SHALL only be added to frames of methods the exception left. The frame of the
method that caught the exception and the frames below it SHALL stay unchanged, because receiver members and parameter
values are captured on method exit to keep normal returns free of cost. The live-stack mode does not have this limit.

#### Scenario: Exception logged where it is caught
- **GIVEN** method `m` catches and logs an exception thrown by an instrumented method it called
- **WHEN** the log is read
- **THEN** the frame of `m` and the frames below it are not augmented

### Requirement: Constructors show parameter values only, not for exceptions from super(...)
Constructors SHALL NOT show receiver members, because an exception can leave a constructor before the object is
initialised. In the instrumenting modes, their parameter values SHALL NOT be shown for exceptions thrown inside the
constructor called with `super(...)` or `this(...)`, because the JVM does not let added code catch exceptions of that
call.

#### Scenario: Exception in a constructor without parameter values
- **GIVEN** an annotated class whose constructor, without annotated parameters, throws, and no `[augment.params]` entry for its `<init>`
- **WHEN** the stack trace is inspected
- **THEN** the constructor frame is unchanged

#### Scenario: Exception from a delegated constructor
- **GIVEN** a constructor with parameter values that calls `this(...)`, and the called constructor throws
- **WHEN** the stack trace is inspected
- **THEN** the called constructor's frame shows its own augmentation and the delegating constructor's frame is unchanged

### Requirement: Parameter values at exit
In the instrumenting modes, a parameter SHALL be shown with its value when the exception leaves the method (in the
live-stack mode, when the exception is created); a parameter that the
method reassigned SHALL show its new value.

#### Scenario: Reassigned parameter
- **GIVEN** a method that reassigns its selected parameter before it throws
- **WHEN** the stack trace is inspected
- **THEN** the frame shows the reassigned value

### Requirement: Changed class and method names
Augmented frames SHALL carry the receiver members and parameter values in their class and method names. Tools that
parse stack traces by class name, such as IDE links or error grouping, MAY not recognise these frames.

#### Scenario: Class name of an augmented frame
- **GIVEN** a frame augmented with the default layout
- **WHEN** `StackTraceElement.getClassName()` is called
- **THEN** it returns the class name followed by the receiver group, e.g. `com.hafnium.ObjectClass{objectId=object-1}`

### Requirement: Reused exceptions keep their first values
An exception instance thrown more than once SHALL keep the augmentation of its first throw: its stack trace is recorded
once, when it is created, and the frames already augmented are not augmented again.

#### Scenario: Preallocated exception
- **GIVEN** a static exception instance with a stack trace, thrown from instrumented methods twice
- **WHEN** its stack trace is printed after the second throw
- **THEN** it shows the values of the first throw

### Requirement: Causes attached late or created earlier
A cause or suppressed exception attached after the wrapper left some frames (`initCause` or `addSuppressed` called later)
SHALL keep those frames as they are; only the frames the wrapper leaves afterwards are written into it. A cause created in
an earlier call with the very same frames below (same methods and lines, e.g. a stored exception from the previous loop
iteration) SHALL show the values of the call that the wrapper leaves in those shared frames.

#### Scenario: Cause added in a caller
- **GIVEN** a wrapper whose cause is set with `initCause` by a caller after the wrapper left two instrumented frames
- **WHEN** the wrapper is printed
- **THEN** the cause's copies of those two frames are not augmented

### Requirement: Kotlin functions compiled differently
Frames SHALL follow the compiled form of Kotlin functions. An inline function has no frame of its own: the frame of the
function it was inlined into SHALL show that function's values. The wrapper the compiler generates for default arguments
SHALL stay unchanged, and the function's own frame SHALL show every value, the default ones included. A suspend function
that throws after it resumed from a suspension does not get its arguments again, so in the instrumenting modes its frame
SHALL show the values that call got, such as `null` and `0`; the live-stack mode MAY show the values the function still
holds.

#### Scenario: Default argument
- **GIVEN** `fun withDefault(@AugmentedParam key: String, @AugmentedParam count: Int = 3)` in a class that the annotations select
- **WHEN** `withDefault("k-3")` throws
- **THEN** its frame shows `withDefault{key=k-3, count=3}`, and the frame of the generated `withDefault$default` below it is unchanged

#### Scenario: Suspend function after a suspension
- **GIVEN** `suspend fun load(@AugmentedParam key: String)` that suspends and throws after it is resumed
- **WHEN** `load("k-2")` is run with the agent
- **THEN** its frame shows `load{key=null}`, and in the live-stack mode `load{key=k-2}`

### Requirement: Live-stack mode limits
The live-stack mode SHALL depend on JDK internals and on HotSpot; an object argument that the JIT optimized away SHALL be
shown as `?` (see the live-stack specification), and every augmented exception SHALL pay a stack walk, also when no frame
is configured (see the performance specification).

#### Scenario: JDK without the internals
- **GIVEN** a JDK that does not provide the internals the live-stack mode needs
- **WHEN** the agent starts with the native library
- **THEN** it warns and instruments classes as usual
