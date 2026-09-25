# Diagnostics Specification

## Purpose
Defines the messages that explain what the agent, the build plugin and the runtime do, so that users can
find out why a class or frame is or is not augmented.

## Requirements

### Requirement: Warnings
The system SHALL always print a warning to standard error, prefixed `[stack-augmentor] WARN`, when a
member named in `[instrument.classIds]` does not exist, when an id source cannot be made accessible, and
when the runtime handler cannot be created.

#### Scenario: Missing configured member
- **GIVEN** `"com.thirdparty.Customer" = "nope"` and `debug = false`
- **WHEN** the id source of `Customer` is first needed
- **THEN** `[stack-augmentor] WARN [instrument.classIds] "com.thirdparty.Customer": no field nope found` is printed

### Requirement: Debug messages
With `debug = true`, the system SHALL print messages to standard error, prefixed
`[stack-augmentor] DEBUG`, for:
- the configuration: its location, `annotatedClasses`, the configured class ids and method parameters, and the formats;
- each instrumented class: why it is instrumented (configured receiver id, annotated receiver id, or parameter ids only), and its instrumented methods with their parameter ids;
- `@StackTraceId` annotations ignored because the class is not in `annotatedClasses`;
- `[instrument.methodParams]` entries that name a missing method, a missing parameter or an index out of range, suggesting `-parameters` or an index when the class has no parameter names;
- the id source found for each class when it is first needed;
- exceptions whose frame is not found in their stack trace.

With `debug = false`, no debug messages SHALL be printed, and the message texts SHALL NOT be built.

#### Scenario: Unmatched configuration entry
- **GIVEN** `debug = true` and `"com.thirdparty.OrderService.process" = ["order", "missing", 7]`
- **WHEN** `OrderService` is loaded
- **THEN** a message reports that there is no parameter `missing` and no parameter `#7` in `process(Order order, int quantity, String note)`

#### Scenario: Instrumented class
- **GIVEN** `debug = true` and `OrderService.process` listed in `[instrument.methodParams]`
- **WHEN** `OrderService` is instrumented
- **THEN** `instrumenting com.thirdparty.OrderService (parameter ids only): process{order, quantity}` is printed

#### Scenario: Debug off
- **GIVEN** `debug = false`
- **WHEN** the demo application runs
- **THEN** no debug message is printed
