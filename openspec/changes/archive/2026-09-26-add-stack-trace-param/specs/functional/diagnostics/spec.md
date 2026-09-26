# Spec Delta

## MODIFIED Requirements

### Requirement: Debug messages
With `debug = true`, the system SHALL print messages to standard error, prefixed
`[stack-augmentor] DEBUG`, for:
- the configuration: its location, `annotatedClasses`, the configured class ids and method parameters (with `*` for all parameters), and the formats including `maxParams`;
- each instrumented class: why it is instrumented (configured receiver id, annotated receiver id, or parameter ids only), and its instrumented methods with their parameter ids;
- `@StackTraceId`, `@StackTraceParam` and `@StackTraceParams` annotations ignored because the class is not in `annotatedClasses`;
- `[instrument.methodParams]` entries without wildcards that name a missing method, a missing parameter or an index out of range, suggesting `-parameters` or an index when the class has no parameter names;
- the id source found for each class when it is first needed;
- exceptions whose frame is not found in their stack trace.

Entries with wildcards SHALL NOT produce messages for classes or methods they do not match, or for names
and indexes a matched method does not have. With `debug = false`, no debug messages SHALL be printed, and
the message texts SHALL NOT be built.

#### Scenario: Unmatched configuration entry
- **GIVEN** `debug = true` and `"com.thirdparty.OrderService.process" = ["order", "missing", 7]`
- **WHEN** `OrderService` is loaded
- **THEN** a message reports that there is no parameter `missing` and no parameter `#7` in `process(Order order, int quantity, String note)`

#### Scenario: Instrumented class
- **GIVEN** `debug = true` and `OrderService.process` listed in `[instrument.methodParams]`
- **WHEN** `OrderService` is instrumented
- **THEN** `instrumenting com.thirdparty.OrderService (parameter ids only): process{order, quantity}` is printed

#### Scenario: Wildcard entry
- **GIVEN** `debug = true` and `"com.thirdparty.*.*" = ["order"]`
- **WHEN** `Customer`, whose methods have no parameter `order`, is loaded
- **THEN** no message about the entry is printed for `Customer`

#### Scenario: Debug off
- **GIVEN** `debug = false`
- **WHEN** the demo application runs
- **THEN** no debug message is printed
