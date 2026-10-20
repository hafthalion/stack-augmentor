# Diagnostics Specification

## Purpose
Defines the messages that explain what the agent, the build plugin and the runtime do, so that users can find out why a
class or frame is or is not augmented.

## Requirements

### Requirement: Message channel and form
Errors, warnings and debug messages SHALL be written to standard error, without a logging framework and without touching
the application's logging configuration. Each message SHALL start with `[stack-augmentor]` and its level (`ERROR`, `WARN`,
`DEBUG`); messages about a component's start SHALL name the component (`agent`, `build plugin` or `runtime`). The agent,
the build plugin and the runtime SHALL report the same configuration the same way.

#### Scenario: Same messages from every component
- **GIVEN** `debug = true` in the configuration of the agent, of the build plugin and of the runtime
- **WHEN** each of them starts
- **THEN** each prints the same description of its configuration, marked with its name

### Requirement: Errors
An invalid configuration, and `inPlaceModification = true` where the JVM does not allow it (see the performance
specification), SHALL be printed as an error, also where the tool that runs the component would not show the cause, such as
a build tool or the JVM's service lookup. An invalid configuration's message SHALL name the file, the key and the line.

#### Scenario: Invalid runtime configuration
- **GIVEN** a build-time instrumented application started with `-Dstackaugmentor.config=bad.toml`, where line 2 is `maxValueLength = 1`
- **WHEN** the first exception leaves an instrumented method
- **THEN** an error naming `bad.toml`, line 2 and `maxValueLength` is printed
- **AND** a warning says that stack traces stay unchanged

### Requirement: Warnings
The system SHALL always print a warning:
- when a receiver member named by an `[augment.receiver]` entry without wildcards does not exist, naming the entry and the member;
- when a configured or annotated receiver member exists but cannot be read, and no readable field or getter of that name
  replaces it, also for an entry with wildcards, instead of the missing-member warning;
- when the runtime cannot start, with the reason, so that stack traces stay unchanged;
- when the runtime of build-time instrumentation finds no configuration file, so that the built-in configuration is used;
- when the agent or the build plugin starts with a configuration that selects nothing to augment: no `[augment.receiver]`
  or `[augment.params]` entries other than `"-"` and no `[augment.annotations]` entry that is `true`;
- when a configuration selects something to augment but no `[augment.exceptions]` entry is `true`, suggesting
  `"java.lang.Exception+" = true`;
- when the agent was asked for the live-stack mode but cannot use it, with the reason;
- when the agent cannot use its shared temporary files safely and falls back to private ones.

A member missing for an entry with wildcards SHALL only be reported in debug output, since a pattern is not expected to
fit every class it matches.

#### Scenario: Missing configured member
- **GIVEN** `"com.thirdparty.Customer::*" = "customerNo"`, a misspelling: `Customer` has a field `customerId`, but no field and no getter for `customerNo`; and `debug = false`
- **WHEN** the receiver members of `Customer` are first needed
- **THEN** a warning names the entry and `customerNo`

#### Scenario: Missing member for a wildcard entry
- **GIVEN** `"com.thirdparty.*::*" = "getId()"`, `debug = false`, and `com.thirdparty.Customer` without `getId()`
- **WHEN** the receiver members of `Customer` are first needed
- **THEN** no warning is printed and frames of `Customer` show no receiver members

#### Scenario: Nothing configured
- **GIVEN** the agent started with a configuration that has only `debug = false`
- **WHEN** it starts
- **THEN** a warning says that nothing will be augmented

#### Scenario: Annotations without exceptions
- **GIVEN** the agent started with a configuration that has only `"com.acme.**" = true` in `[augment.annotations]`
- **WHEN** it starts
- **THEN** a warning says that no exception is augmented

### Requirement: Debug output
With `debug = true`, the system SHALL report:
- the configuration: where it came from, and the entries of every table and the formats;
- each instrumented class: why (which entries, or the annotations), and its instrumented methods with their parameter values;
- classes left alone, and why: annotations ignored because no `[augment.annotations]` entry selects the class or because
  entries or `@Augmented` decide otherwise; with the agent, classes already instrumented at build time;
- `[augment.params]` entries without wildcards or `+` that name a missing method, a missing parameter or an index out of
  range, with a hint when the class was compiled without parameter names;
- receiver members missing for `[augment.receiver]` entries with wildcards;
- the receiver members found for each class when they are first needed;
- exceptions whose frame for a call cannot be found in their stack trace;
- in the live-stack mode, that it is used.

Entries with wildcards or `+` SHALL NOT produce messages for classes or methods they do not match, or for names and
indexes a matched method does not have. With `debug = false`, no debug message SHALL be printed, and no work SHALL be spent
on building them.

#### Scenario: Unmatched configuration entry
- **GIVEN** `debug = true` and `"com.thirdparty.OrderService::process" = "order, missing, 7"`
- **WHEN** `OrderService` is loaded
- **THEN** a message reports that `process(Order order, int quantity, String note)` has no parameter `missing` and no parameter `#7`

#### Scenario: Instrumented class
- **GIVEN** `debug = true` and `"com.thirdparty.OrderService::process" = "order, 1"` in `[augment.params]`
- **WHEN** `OrderService` is instrumented
- **THEN** a message says that it is instrumented for parameter values only, with `process{order, quantity}`

#### Scenario: Wildcard entry
- **GIVEN** `debug = true` and `"com.thirdparty.*::*" = "order"`
- **WHEN** `Customer`, whose methods have no parameter `order`, is loaded
- **THEN** no message about the entry is printed for `Customer`

#### Scenario: Ignored annotations
- **GIVEN** `debug = true`, `"com.hafnium.it.fixtures.**" = true` in `[augment.annotations]`, and a class in `com.hafnium.it.outside` with `@Augmented`, `@AugmentedReceiver` and `@AugmentedParam`
- **WHEN** the class is loaded
- **THEN** messages report that its `@AugmentedReceiver` and its parameter annotations are ignored, because no `[augment.annotations]` entry selects it

#### Scenario: Annotations replaced by entries
- **GIVEN** `debug = true`, `"com.acme.**" = true` in `[augment.annotations]`, `"com.acme.Order::*" = "getId()"` in `[augment.receiver]`, and `com.acme.Order` with `@Augmented` and `@AugmentedReceiver`
- **WHEN** `Order` is loaded
- **THEN** a message reports that its `@AugmentedReceiver` is ignored, because entries decide for all its methods

#### Scenario: Debug off
- **GIVEN** `debug = false`
- **WHEN** the demo application runs
- **THEN** no debug message is printed
