# Stack Trace Augmentation Specification

## Purpose
Defines, for the instrumenting modes, which frames are augmented, when their values are captured, which exceptions are
augmented, and how the frames shared with causes and suppressed exceptions are kept consistent. The live-stack
specification says where its mode differs.

## Requirements

### Requirement: Augmented methods
A frame SHALL be augmented when its method has receiver members or parameter values (see the overview and the receivers
and parameters specification) and the exception left that method. Instance methods SHALL show both, static methods
parameter values only. Abstract and native methods, and compiler-generated methods such as lambda bodies, bridge methods
and synthetic accessors, SHALL stay unchanged, and so SHALL static initializers, which keys cannot name. A frame without any receiver member or parameter value SHALL stay unchanged.

#### Scenario: Static method without parameter values
- **GIVEN** a static function without selected parameters
- **WHEN** it throws
- **THEN** its frame is unchanged

#### Scenario: Class with parameter values only
- **GIVEN** a third-party class `OrderService` that is only listed in `[augment.params]`
- **WHEN** `process` throws
- **THEN** the frame shows the parameter values after the method name and no receiver members

#### Scenario: Lambda inside an augmented method
- **GIVEN** `viaLambda()` of an annotated class runs a `Runnable` lambda that throws
- **WHEN** the stack trace is inspected
- **THEN** the lambda frame is unchanged and the `viaLambda` frame shows the receiver member

### Requirement: Constructors
A constructor SHALL show its parameter values, never receiver members, for exceptions thrown in its body (including
Kotlin `init` blocks) and while it computes the arguments of its `super(...)` or `this(...)` call. In the instrumenting
modes, an exception thrown inside the called constructor itself SHALL leave the calling constructor's frame unchanged (see
the limitations specification).

#### Scenario: Constructor with parameter values
- **GIVEN** `@Augmented class Shipment(@AugmentedParam val orderId: Long, val weight: Int)` whose `init` block calls `require(weight > 0)`, and `"com.acme.**" = true` in `[augment.annotations]`
- **WHEN** `Shipment(42, 0)` throws
- **THEN** its frame shows `Shipment.<init>{orderId=42}`

#### Scenario: Exception while computing the arguments of super(...)
- **GIVEN** `@Augmented class Crate @AugmentedParams constructor(code: String?) : Parcel(requireNotNull(code))`
- **WHEN** `Crate(null)` throws
- **THEN** its frame shows `Crate.<init>{code=null}`

#### Scenario: Exception from the superclass constructor
- **GIVEN** a constructor with parameter values whose superclass constructor throws
- **WHEN** the stack trace is inspected
- **THEN** that constructor's frame is unchanged

### Requirement: Capture when an exception leaves a method
The values SHALL be captured when the exception leaves the method, as they are at that moment. Frames of methods the
exception did not leave, such as the method that caught it and the frames below, SHALL be left unchanged, except for
frames shared with an exception that left them (see "Frames shared with causes and suppressed exceptions").

#### Scenario: Caught and returned exception
- **GIVEN** `inner(1)` throws and its caller `catchAndReturn()` catches the exception
- **WHEN** the stack trace is inspected
- **THEN** the `inner` frame is augmented and the `catchAndReturn` frame is unchanged

#### Scenario: Wrapped exception
- **GIVEN** `wrap()` catches the exception of `inner(2)` and throws `RuntimeException("wrapped", cause)`
- **WHEN** the wrapper is printed
- **THEN** the wrapper's `wrap` frame and the cause's `inner` frame are both augmented

### Requirement: The right frame
Each set of values SHALL be written into the frame of the call it was captured in, also in recursion and when other frames
of the same method are on the stack. An exception whose stack trace does not contain the call it is leaving, because it
was created elsewhere, SHALL NOT be changed for that call. The rest of each frame (class, method, file, line, module and
class loader information) SHALL stay as the JVM recorded it, and the augmentation SHALL be visible to everything that reads
the stack trace.

#### Scenario: Recursion
- **GIVEN** a linked list of `Node`s named `a`, `b`, `c`, where `walk(depth)` calls the next node
- **WHEN** the last node throws
- **THEN** the frames show `Node{name=c}.walk{depth=2}`, `Node{name=b}.walk{depth=1}` and `Node{name=a}.walk{depth=0}` in this order

#### Scenario: Exception created elsewhere
- **GIVEN** an exception whose stack trace does not contain the method it is leaving
- **WHEN** it leaves an augmented method
- **THEN** its stack trace is not changed

#### Scenario: Exception created by the caller in a recursion
- **GIVEN** `Relay("a")`, whose `pass(depth)` creates an exception and passes it to `pass` of `Relay("b")`, which throws it
- **WHEN** the exception leaves both calls
- **THEN** the only `pass` frame in its stack trace, the one of `a` where it was created, shows `Relay{name=a}.pass{depth=0}`

#### Scenario: Stack trace not writable
- **GIVEN** an exception created with `writableStackTrace = false`
- **WHEN** it leaves an augmented method
- **THEN** it is thrown unchanged, with an empty stack trace

### Requirement: Only some exceptions
Frames SHALL be augmented only for throwables whose deciding `[augment.exceptions]` entry is `true`; a throwable that no
entry matches SHALL NOT be augmented. The frames that other throwables leave SHALL stay unchanged, in their own traces and
in those of their causes and suppressed exceptions; a frame that an augmented throwable leaves SHALL still be written into
the traces it shares, whatever their class (see "Frames shared with causes and suppressed exceptions"). What is
instrumented SHALL NOT depend on this table. This SHALL hold in every way to run.

#### Scenario: Cause that is not augmented
- **GIVEN** `"com.acme.PlainCause" = false` before `"**" = true`, and `wrap()` catches a `PlainCause` from `inner()` and throws `RuntimeException("wrapped", cause)`
- **WHEN** the wrapper is printed
- **THEN** the cause's `inner` frame is unchanged, its frames shared with the wrapper show the wrapper's augmentation, and they collapse into `... N more`

#### Scenario: First matching entry
- **GIVEN** `"java.io.FileNotFoundException" = false` before `"java.io.IOException+" = true`
- **WHEN** configured methods throw `FileNotFoundException` and `IOException`
- **THEN** the frames of the `FileNotFoundException` are unchanged and those of the `IOException` are augmented

#### Scenario: Subclass of an exact entry
- **GIVEN** only `"java.io.IOException" = true` and a configured method that throws `FileNotFoundException`
- **WHEN** the stack trace is inspected
- **THEN** its frame is unchanged

#### Scenario: No entries
- **GIVEN** no `[augment.exceptions]` table, or one without entries, and a configured method that throws
- **WHEN** the stack trace is inspected
- **THEN** its frame is unchanged

### Requirement: Frames shared with causes and suppressed exceptions
When a frame of an exception is augmented, the same frame SHALL be augmented the same way in its causes and suppressed
exceptions, and theirs, where they share it: an equal frame at the same distance from the bottom of the trace, with equal
frames below it. Printed traces SHALL then still collapse the shared frames into `... N more`, as without augmentation. A
frame of the same method at that place but at another line, e.g. where the method caught the cause it wraps, SHALL show the
same values with its own line.

#### Scenario: Wrapped exception with an augmented caller
- **GIVEN** `outer()` calls `wrap()`, which catches the exception of `inner(2)` and throws `RuntimeException("wrapped", cause)`
- **WHEN** the wrapper leaves `outer()` and is printed
- **THEN** the cause's `outer` frame shows the same augmentation as the wrapper's, and the cause section ends with `... N more` as without augmentation

#### Scenario: Frame that caught the cause
- **GIVEN** `wrap()` catches the exception of `inner(2)` and throws `RuntimeException("wrapped", cause)`
- **WHEN** the wrapper leaves `wrap()`
- **THEN** the cause's `wrap` frame, at the line of the call to `inner(2)`, shows the same augmentation as the wrapper's `wrap` frame

#### Scenario: Suppressed exception
- **GIVEN** `outerClosing()` calls `closing()`, where `inner(3)` throws and closing the resource throws too
- **WHEN** the exception leaves `outerClosing()`
- **THEN** the suppressed exception's `outerClosing` frame shows the same augmentation as the exception's
