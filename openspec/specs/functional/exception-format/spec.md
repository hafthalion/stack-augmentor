# Exception Format Specification

## Purpose
Defines `ExceptionFormat` in `stack-augmentor-api`, which prints a throwable with its causes root cause first,
for code that formats exceptions itself or logs through `java.util.logging`. Logback and Log4j 2 print the same
order with `%rEx`.

## Requirements

### Requirement: Root cause first
`ExceptionFormat.rootCauseFirst(Throwable)` SHALL return the throwable and its causes in reverse order: the root
cause first, then each exception that wrapped it, headed `Wrapped by: ` followed by its `toString()`.
`rootCauseFirst(Throwable, Appendable)` SHALL append the same text to the `Appendable`. Each frame SHALL be printed
as `\tat ` followed by the `StackTraceElement`, as `Throwable.getStackTrace()` returns it, so with the ids that
Stack Augmentor added; each line SHALL end with `\n`.

#### Scenario: Cause chain
- **GIVEN** `outer` wraps `middle`, which wraps `root`
- **WHEN** `rootCauseFirst(outer)` is called
- **THEN** it prints `root` with its frames, then `Wrapped by: ` and `middle` with its frames, then `Wrapped by: ` and `outer` with its frames

#### Scenario: Same text to an Appendable
- **WHEN** `rootCauseFirst(outer, writer)` is called with a `StringWriter`
- **THEN** the writer holds the same text as `rootCauseFirst(outer)` returns

### Requirement: Common frames omitted
Each exception SHALL leave out the frames at the bottom of its trace that are equal to those at the bottom of
the trace of the exception that wrapped it, and print `\t... N common frames omitted` in their place. The
outermost exception SHALL print all its frames. Read from the top, every frame of the stack is then printed once.

#### Scenario: Augmented cause chain
- **GIVEN** an instrumented `outer()` calls `wrap()`, which catches the exception of `inner(2)` and throws `RuntimeException("wrapped", cause)`
- **WHEN** the wrapper leaves `outer()` and is formatted root cause first
- **THEN** the cause prints only the frames below `wrap`, with ids, then `... N common frames omitted`, and the wrapper prints its frames with the same ids in `outer`

### Requirement: Suppressed exceptions and cycles
Suppressed exceptions SHALL follow the frames of the exception they were suppressed in, each line indented by one
more tab, the first one headed `Suppressed: `, with their own causes again root cause first and the frames they
share with that exception omitted. An exception that was already printed SHALL be printed as
`[CIRCULAR REFERENCE: ` followed by its `toString()` and `]`, and its causes SHALL not be followed further.

#### Scenario: Suppressed exception
- **GIVEN** an exception with a suppressed exception from closing a resource
- **WHEN** it is formatted root cause first
- **THEN** after its frames follows `\tSuppressed: ` and the suppressed exception, with its frames indented by two tabs and its shared frames as `\t\t... N common frames omitted`

#### Scenario: Circular cause chain
- **GIVEN** `first` has the cause `second`, whose cause is `first`
- **WHEN** `rootCauseFirst(second)` is called
- **THEN** it starts with `[CIRCULAR REFERENCE: ` and `second`, followed by `first` and `second`, each headed `Wrapped by: `
