# Known Limitations Specification

## Purpose
Documents accepted, intentional limits of the design, so that they are not mistaken for defects and are
reconsidered deliberately when they change.

## Requirements

### Requirement: Only frames the exception passed through
Ids SHALL only be added to frames of methods the exception left. The frame of the method that caught the
exception and the frames below it SHALL stay unchanged, because ids are captured on method exit to keep
normal returns free of cost.

#### Scenario: Exception logged where it is caught
- **GIVEN** method `m` catches and logs an exception thrown by an instrumented method it called
- **WHEN** the log is read
- **THEN** the frame of `m` and the frames below it show no ids

### Requirement: Constructors show parameter ids only, not for exceptions from super(...)
Constructors SHALL NOT get receiver ids, because an exception can leave a constructor before the object is
initialised. Their parameter ids SHALL NOT be shown for exceptions thrown inside the constructor called with
`super(...)` or `this(...)`: the JVM's verifier accepts no exception handler around that call.

#### Scenario: Exception in a constructor without parameter ids
- **GIVEN** an annotated class whose constructor throws, and no `[augment.params]` entry for its `<init>`
- **WHEN** the stack trace is inspected
- **THEN** the constructor frame is unchanged

#### Scenario: Exception from a delegated constructor
- **GIVEN** a constructor with parameter ids that calls `this(...)`, and the called constructor throws
- **WHEN** the stack trace is inspected
- **THEN** the called constructor's frame shows its own ids and the delegating constructor's frame is unchanged

### Requirement: Parameter values at exit
Parameter ids SHALL show the parameter's value when the exception leaves the method; a parameter that the
method reassigned SHALL show its new value.

#### Scenario: Reassigned parameter
- **GIVEN** a method that reassigns its id parameter before it throws
- **WHEN** the stack trace is inspected
- **THEN** the frame shows the reassigned value

### Requirement: Changed class and method names
Rewritten frames SHALL contain the ids in the class and method names of their `StackTraceElement`s. Tools
that parse stack traces by class name, such as IDE links or error grouping, MAY not recognise these
frames.

#### Scenario: Class name of a rewritten frame
- **GIVEN** a frame rewritten with the default layout
- **WHEN** `StackTraceElement.getClassName()` is called
- **THEN** it returns the class name followed by the receiver group, e.g. `com.hafnium.ObjectClass{objectId=object-1}`
