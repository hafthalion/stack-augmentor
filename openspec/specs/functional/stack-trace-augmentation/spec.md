# Stack Trace Augmentation Specification

## Purpose
Defines which methods are instrumented, when ids are captured, and how the frames of an exception's
stack trace are rewritten to show them.

## Requirements

### Requirement: Instrumented methods
The system SHALL add exit advice to methods that are not abstract, native, bridge or synthetic, in these
cases:
- instance methods of a class that has a receiver id source (a field or method named by its deciding
  `[instrument.classes]` entry, or an annotated member when that entry is `"@"`);
- instance or static methods that have at least one parameter id.

Constructors SHALL NOT be instrumented.

#### Scenario: Static method without parameter ids
- **GIVEN** a static function without id parameters
- **WHEN** it throws
- **THEN** its frame is unchanged

#### Scenario: Class with parameter ids only
- **GIVEN** a third-party class `OrderService` that is only listed in `[instrument.methods]`
- **WHEN** `process` throws
- **THEN** the frame shows the parameter ids after the method name and no receiver id

### Requirement: Capture when an exception leaves a method
The system SHALL capture the receiver and parameter ids when an exception leaves an instrumented method,
reading the parameters' values at that moment. Frames of methods the exception did not leave, such as the
method that caught it and the frames below, SHALL be left unchanged.

#### Scenario: Caught and returned exception
- **GIVEN** `inner(1)` throws and its caller `catchAndReturn()` catches the exception
- **WHEN** the stack trace is inspected
- **THEN** the `inner` frame shows its ids and the `catchAndReturn` frame is unchanged

#### Scenario: Wrapped exception
- **GIVEN** `wrap()` catches the exception of `inner(2)` and throws `RuntimeException("wrapped", cause)`
- **WHEN** the wrapper is printed
- **THEN** the wrapper's `wrap` frame and the cause's `inner` frame both show their ids

### Requirement: Rewriting the frame
The system SHALL find the frame of the method the exception left in the exception's own stack trace. It
SHALL search from the frame after the last one handled for that exception, and match on class name and
method name. It SHALL replace that frame with one whose class and method parts show the ids according to
the frame format, and SHALL keep the file name, the line number (including native methods) and the class
loader or module prefix. It SHALL install the result with `setStackTrace`, so that every printer and
logger shows the ids. A frame without any id SHALL be left unchanged.

#### Scenario: Recursion
- **GIVEN** a linked list of `Node`s named `a`, `b`, `c`, where `walk(depth)` calls the next node
- **WHEN** the last node throws
- **THEN** the frames show `Node{name=c}.walk{depth=2}`, `Node{name=b}.walk{depth=1}` and `Node{name=a}.walk{depth=0}` in this order

#### Scenario: Exception created elsewhere
- **GIVEN** an exception whose stack trace does not contain the method it is leaving
- **WHEN** it leaves an instrumented method
- **THEN** its stack trace is not changed

#### Scenario: Stack trace not writable
- **GIVEN** an exception created with `writableStackTrace = false`
- **WHEN** it leaves an instrumented method
- **THEN** it is thrown unchanged, with an empty stack trace

### Requirement: Lambdas and uninstrumented code
Frames of synthetic methods, such as Kotlin and Java lambda bodies, and of classes that are not
instrumented SHALL be left unchanged.

#### Scenario: Lambda inside an instrumented method
- **GIVEN** `viaLambda()` of an annotated class runs a `Runnable` lambda that throws
- **WHEN** the stack trace is inspected
- **THEN** the lambda frame is unchanged and the `viaLambda` frame shows the receiver id
