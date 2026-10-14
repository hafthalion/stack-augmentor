# Diagnostics Specification

## Purpose
Defines the messages that explain what the agent, the build plugin and the runtime do, so that users can
find out why a class or frame is or is not augmented.

## Requirements

### Requirement: Startup messages
The agent, the build plugin and the runtime handler of build-time instrumentation SHALL report their
configuration the same way, and their messages SHALL name them after the level: `agent:`, `build plugin:` or
`runtime:`. An invalid configuration SHALL be printed to standard error, prefixed `[stack-augmentor] ERROR`, with
the file, the key and the line, also when the exception carrying it is thrown on: the ByteBuddy Gradle plugin and
`ServiceLoader` do not show its message.

#### Scenario: Invalid runtime configuration
- **GIVEN** a build-time instrumented application started with `-Dstackaugmentor.config=bad.toml`, where line 2 is `maxValueLength = 1`
- **WHEN** the first exception leaves an instrumented method
- **THEN** `[stack-augmentor] ERROR runtime: bad.toml, line 2: maxValueLength must be between 2 and 10000, was 1` is printed
- **AND** a warning says that the stack trace handler cannot be created because the configuration is invalid, and stack traces stay unchanged

#### Scenario: Same messages in both modes
- **GIVEN** `debug = true` in the configuration of the agent, of the build plugin and of the runtime handler
- **WHEN** each of them starts
- **THEN** each prints the same debug messages about its configuration, prefixed with its name, e.g. `[stack-augmentor] DEBUG build plugin: [augment.receiver] com.hafnium.**::*=@`

### Requirement: Warnings
The system SHALL always print a warning to standard error, prefixed `[stack-augmentor] WARN`:
- when a member named by an `[augment.receiver]` entry without wildcards does not exist;
- when the member named by an `[augment.receiver]` entry exists but cannot be made accessible, and no
  accessible field or getter of that name replaces it; this warning, which names the member, SHALL replace the
  missing-member warning, also for an entry with wildcards;
- when an annotated receiver member cannot be made accessible;
- when the runtime handler cannot be created, with the reason;
- when the build-time runtime handler finds no configuration, so the built-in configuration is used;
- when the agent or the build plugin starts with a configuration that has no `[augment.receiver]` and no
  `[augment.params]` entries other than `"-"`, so nothing will be augmented;
- when the agent, the build plugin or the runtime starts with `[augment.receiver]` or `[augment.params]` entries but
  no `[augment.exceptions]` entry that is `true`: `no [augment.exceptions] entry is true, so no exception is augmented;
  "java.lang.Exception+" = true augments all exceptions, "**" = true all throwables`.

A member missing for an entry with wildcards SHALL only be reported as a debug message.

#### Scenario: Missing configured member
- **GIVEN** `"com.thirdparty.Customer::*" = "customerNo"`, a misspelling: `Customer` has a field `customerId`, but no field and no getter for `customerNo`; and `debug = false`
- **WHEN** the receiver members of `Customer` are first needed
- **THEN** `[stack-augmentor] WARN [augment.receiver] "com.thirdparty.Customer::*": no field or property customerNo found` is printed

#### Scenario: Missing member for a wildcard entry
- **GIVEN** `"com.thirdparty.*::*" = "getId()"`, `debug = false`, and `com.thirdparty.Customer` without `getId()`
- **WHEN** the receiver members of `Customer` are first needed
- **THEN** no warning is printed and frames of `Customer` show no receiver members

#### Scenario: Nothing configured
- **GIVEN** the agent started with a configuration that has only `debug = false`
- **WHEN** it starts
- **THEN** `[stack-augmentor] WARN agent: the configuration has no [augment.receiver] or [augment.params] entries other than "-", so nothing will be augmented` is printed

### Requirement: Debug messages
With `debug = true`, the system SHALL print messages to standard error, prefixed
`[stack-augmentor] DEBUG`, for:
- the configuration, from the agent, the build plugin and the runtime handler alike: its location, the `[augment.receiver]` entries (with `@` for annotations and `-` for none) and the `[augment.params]` entries (with `#` for hashed parameters, `@` for annotations and `-` for none), and the formats;
- each instrumented class: why it is instrumented (configured receiver members, annotated receiver members, or parameter values only), and its instrumented methods with their parameter values;
- `@AugmentedReceiver` annotations ignored because the class's deciding `[augment.receiver]` entry is not `"@"` or no entry matches the class, and `@AugmentedParam` and `@AugmentedParams` annotations ignored because no `[augment.params]` entry with `"@"` applies to the method, each naming the table that would enable them;
- `[augment.params]` entries without wildcards or `+` that name a missing method, a missing parameter or an index out of range, suggesting `-parameters` or an index when the class has no parameter names;
- members missing for `[augment.receiver]` entries with wildcards;
- the receiver members found for each class when they are first needed;
- exceptions whose frame is not found in their stack trace.

`[augment.params]` entries with wildcards or `+` SHALL NOT produce messages for classes or methods they do not
match, or for names and indexes a matched method does not have. With `debug = false`, no debug messages
SHALL be printed, and the message texts SHALL NOT be built, also for the messages about members missing for
`[augment.receiver]` entries with wildcards.

#### Scenario: Unmatched configuration entry
- **GIVEN** `debug = true` and `"com.thirdparty.OrderService::process" = "order, missing, 7"`
- **WHEN** `OrderService` is loaded
- **THEN** a message reports that there is no parameter `missing` and no parameter `#7` in `process(Order order, int quantity, String note)`

#### Scenario: Instrumented class
- **GIVEN** `debug = true` and `"com.thirdparty.OrderService::process" = "order, 1"` in `[augment.params]`
- **WHEN** `OrderService` is instrumented
- **THEN** `instrumenting com.thirdparty.OrderService (parameter values only): process{order, quantity}` is printed

#### Scenario: Wildcard entry
- **GIVEN** `debug = true` and `"com.thirdparty.*::*" = "order"`
- **WHEN** `Customer`, whose methods have no parameter `order`, is loaded
- **THEN** no message about the entry is printed for `Customer`

#### Scenario: Ignored annotations
- **GIVEN** `debug = true`, `"com.hafnium.it.fixtures.**::*" = "@"` in `[augment.receiver]`, and a class in `com.hafnium.it.outside` with `@AugmentedReceiver` and `@AugmentedParam`
- **WHEN** the class is loaded
- **THEN** one message reports that its `@AugmentedReceiver` is ignored because no `[augment.receiver]` entry applies, and one that its parameter annotations are ignored because no `"@"` entry in `[augment.params]` applies

#### Scenario: Debug off
- **GIVEN** `debug = false`
- **WHEN** the demo application runs
- **THEN** no debug message is printed
