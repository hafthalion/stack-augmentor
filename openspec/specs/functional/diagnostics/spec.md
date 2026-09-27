# Diagnostics Specification

## Purpose
Defines the messages that explain what the agent, the build plugin and the runtime do, so that users can
find out why a class or frame is or is not augmented.

## Requirements

### Requirement: Warnings
The system SHALL always print a warning to standard error, prefixed `[stack-augmentor] WARN`:
- when a member named by an `[augment.receiver]` entry without wildcards does not exist;
- when an id source cannot be made accessible;
- when the runtime handler cannot be created;
- when the build-time runtime handler finds no configuration, so frames show no receiver ids;
- when the agent or the build plugin starts with a configuration that has no `[augment.receiver]` and no
  `[augment.params]` entries, so nothing will be augmented.

A member missing for an entry with wildcards SHALL only be reported as a debug message.

#### Scenario: Missing configured member
- **GIVEN** `"com.thirdparty.Customer" = "nope"` and `debug = false`
- **WHEN** the id source of `Customer` is first needed
- **THEN** `[stack-augmentor] WARN [augment.receiver] "com.thirdparty.Customer": no field nope found` is printed

#### Scenario: Missing member for a wildcard entry
- **GIVEN** `"com.thirdparty.*" = "getId()"`, `debug = false`, and `com.thirdparty.Customer` without `getId()`
- **WHEN** the id source of `Customer` is first needed
- **THEN** no warning is printed and frames of `Customer` show no receiver id

#### Scenario: Nothing configured
- **GIVEN** the agent started without a configuration
- **WHEN** it starts
- **THEN** a warning says that the configuration has no `[augment.receiver]` or `[augment.params]` entries, so nothing will be augmented

### Requirement: Debug messages
With `debug = true`, the system SHALL print messages to standard error, prefixed
`[stack-augmentor] DEBUG`, for:
- the configuration: its location, the `[augment.receiver]` entries (with `@` for annotations and `-` for none) and the `[augment.params]` entries (with `*` for all parameters, `@` for annotations and `-` for none), and the formats including `maxParams`;
- each instrumented class: why it is instrumented (configured receiver id, annotated receiver id, or parameter ids only), and its instrumented methods with their parameter ids;
- `@StackTraceId` annotations ignored because the class's deciding `[augment.receiver]` entry is not `"@"` or no entry matches the class, and `@StackTraceParam` and `@StackTraceParams` annotations ignored because no `[augment.params]` entry with `"@"` applies to the method, each naming the table that would enable them;
- `[augment.params]` entries without wildcards that name a missing method, a missing parameter or an index out of range, suggesting `-parameters` or an index when the class has no parameter names;
- members missing for `[augment.receiver]` entries with wildcards;
- the id source found for each class when it is first needed;
- exceptions whose frame is not found in their stack trace.

`[augment.params]` entries with wildcards SHALL NOT produce messages for classes or methods they do not
match, or for names and indexes a matched method does not have. With `debug = false`, no debug messages
SHALL be printed, and the message texts SHALL NOT be built.

#### Scenario: Unmatched configuration entry
- **GIVEN** `debug = true` and `"com.thirdparty.OrderService.process" = ["order", "missing", 7]`
- **WHEN** `OrderService` is loaded
- **THEN** a message reports that there is no parameter `missing` and no parameter `#7` in `process(Order order, int quantity, String note)`

#### Scenario: Instrumented class
- **GIVEN** `debug = true` and `OrderService.process` listed in `[augment.params]`
- **WHEN** `OrderService` is instrumented
- **THEN** `instrumenting com.thirdparty.OrderService (parameter ids only): process{order, quantity}` is printed

#### Scenario: Wildcard entry
- **GIVEN** `debug = true` and `"com.thirdparty.*.*" = ["order"]`
- **WHEN** `Customer`, whose methods have no parameter `order`, is loaded
- **THEN** no message about the entry is printed for `Customer`

#### Scenario: Ignored annotations
- **GIVEN** `debug = true`, `"com.hafnium.it.fixtures.**" = "@"` in `[augment.receiver]`, and a class in `com.hafnium.it.outside` with `@StackTraceId` and `@StackTraceParam`
- **WHEN** the class is loaded
- **THEN** one message reports that its `@StackTraceId` is ignored because no `[augment.receiver]` entry applies, and one that its parameter annotations are ignored because no `"@"` entry in `[augment.params]` applies

#### Scenario: Debug off
- **GIVEN** `debug = false`
- **WHEN** the demo application runs
- **THEN** no debug message is printed
