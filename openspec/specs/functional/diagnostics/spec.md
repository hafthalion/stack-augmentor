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
- **GIVEN** `-Dstackaugmentor.debug=true` for the agent, the build plugin and the runtime
- **WHEN** each of them starts
- **THEN** each prints the same description of its configuration, marked with its name

### Requirement: Errors
An invalid configuration, also that of an application (see the configuration specification), SHALL be printed as an
error, also where the tool that runs the component would not show the cause, such as a build tool or the JVM's service
lookup. An invalid configuration's message SHALL name the file, the key and the line.

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
- when the runtime of build-time instrumentation finds no configuration file for a class, so that the built-in
  configuration is used alone;
- when the runtime of build-time instrumentation does not find the local configuration a class was instrumented with,
  so that the local file of its jar or directory, if any, is used instead;
- when the JVM was started with the native library of the live-stack mode but the agent cannot use it, with the reason;
- when the agent cannot use its shared temporary files safely and falls back to private ones;
- when frames cannot be written in place because `java.lang` is not open, so that stack traces are copied, naming
  `--add-opens java.base/java.lang=ALL-UNNAMED` for build-time instrumentation.

A member missing for an entry with wildcards SHALL only be reported in debug output, since a pattern is not expected to
fit every class it matches.

#### Scenario: Missing configured member
- **GIVEN** `"com.thirdparty.Customer::*" = "customerNo"`, a misspelling: `Customer` has a field `customerId`, but no field and no getter for `customerNo`, and debug output off
- **WHEN** the receiver members of `Customer` are first needed
- **THEN** a warning names the entry and `customerNo`

#### Scenario: Missing member for a wildcard entry
- **GIVEN** `"com.thirdparty.*::*" = "getId()"`, debug output off, and `com.thirdparty.Customer` without `getId()`
- **WHEN** the receiver members of `Customer` are first needed
- **THEN** no warning is printed and frames of `Customer` show no receiver members

### Requirement: Debug output
With `-Dstackaugmentor.debug=true`, the system SHALL report:
- each configuration file: where it came from, and the entries of every table and the formats, naming the class loader
  of a file on the classpath;
- each instrumented class: why (which entries, or the annotations), and its instrumented methods with their parameter values;
- classes left alone, and why: annotations ignored because entries or `@Augmented` decide otherwise, including
  `@Augmented` on methods of a type without it; with the agent, classes already instrumented at build time;
- `[augment.params]` entries without wildcards or `+` that name a missing method, a missing parameter or an index out of
  range, with a hint when the class was compiled without parameter names;
- receiver members missing for `[augment.receiver]` entries with wildcards;
- the receiver members found for each class when they are first needed;
- exceptions whose frame for a call cannot be found in their stack trace;
- in the live-stack mode, that it is used.

Entries with wildcards or `+` SHALL NOT produce messages for classes or methods they do not match, or for names and
indexes a matched method does not have. Without it, no debug message SHALL be printed, and no work SHALL be spent
on building them.

#### Scenario: Unmatched configuration entry
- **GIVEN** debug output on and `"com.thirdparty.OrderService::process" = "order, missing, 7"`
- **WHEN** `OrderService` is loaded
- **THEN** a message reports that `process(Order order, int quantity, String note)` has no parameter `missing` and no parameter `#7`

#### Scenario: Instrumented class
- **GIVEN** debug output on and `"com.thirdparty.OrderService::process" = "order, 1"` in `[augment.params]`
- **WHEN** `OrderService` is instrumented
- **THEN** a message says that it is instrumented for parameter values only, with `process{order, quantity}`

#### Scenario: Wildcard entry
- **GIVEN** debug output on and `"com.thirdparty.*::*" = "order"`
- **WHEN** `Customer`, whose methods have no parameter `order`, is loaded
- **THEN** no message about the entry is printed for `Customer`

#### Scenario: Ignored annotations
- **GIVEN** debug output on, and a class with `@Augmented(false)`, `@Augmented` on one method and an `@AugmentedParam` parameter in another
- **WHEN** the class is loaded
- **THEN** a message reports that the parameter annotations of the other method are ignored, because an entry or `@Augmented` decides for it

#### Scenario: @Augmented on a method of a type without it
- **GIVEN** debug output on, and a class without `@Augmented` whose method `send` has `@Augmented`
- **WHEN** the class is loaded
- **THEN** a message reports that the `@Augmented` of `send` is ignored, because the type has no `@Augmented`

#### Scenario: Annotations replaced by entries
- **GIVEN** debug output on, `"com.acme.Order::*" = "getId()"` in `[augment.receiver]`, and `com.acme.Order` with `@Augmented` and `@AugmentedReceiver`
- **WHEN** `Order` is loaded
- **THEN** a message reports that its `@AugmentedReceiver` is ignored, because entries decide for all its methods

#### Scenario: Debug off
- **GIVEN** debug output off
- **WHEN** the demo application runs
- **THEN** no debug message is printed
