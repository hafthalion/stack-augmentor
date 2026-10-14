# Configuration Specification

## Purpose
Defines the TOML configuration file: where it is found, its sections and keys, and how invalid
configurations are reported.

## Requirements

### Requirement: File format and structure
The configuration SHALL be a TOML file ending in `.toml`, with these keys:
- `debug` (boolean, default `false`);
- `inPlaceModification` (boolean, default `false`): write rewritten frames into the throwable's own stack
  trace instead of copying it, see the performance spec; the system property
  `stackaugmentor.inPlaceModification` SHALL override it;
- `[augment]`: `frameFormat`, `receiverFormat`, `paramsFormat` (strings), `maxValueLength` (integer
  between 2 and 10000, default 64), and the tables:
  - `[augment.exceptions]` (class name or class pattern → `true` or `false`), which decides the throwables
    that are augmented, see the stack trace augmentation spec. Its entries SHALL be read in the order of the
    file. Without entries no throwable is augmented;
  - `[augment.receiver]` (method key, see below → a string: field name, `method()`, several of them separated
    by commas, `"@"` for the class's `@StackTraceReceiver` members, or `"-"` for no receiver members), which decides the
    receiver members, see the receivers and parameters spec;
  - `[augment.params]` (method key → a string: parameter names and 0-based indexes from 0 to 255, the JVM's
    maximum number of parameters, separated by commas, each optionally followed by `#` to hash the value (e.g.
    `"order, 2, email#"`), `"@"` for the method's parameter annotations, or `"-"` for none; there is no wildcard
    value for the parameters), which decides the parameter values.

  The keys of `[augment.receiver]` and `[augment.params]` SHALL have the same syntax and matching,
  `"<class>::<methods>"`: a class name, optionally followed by `+`, or a class pattern; then `::`, which is
  required; then method names or patterns separated by commas without duplicates, where `*` alone stands for all
  methods and `@` for the methods (and in `[augment.params]` the constructors) annotated with
  `@StackTraceAugment`. A class name SHALL match the class that declares a frame's method; with `+` also its
  subclasses and, for an interface, the classes implementing it, as `instanceof` does; a pattern SHALL match
  class names only. `+` after a pattern SHALL be rejected. The method part `<init>` names the constructors, in
  `[augment.params]` only; wildcards in the method part SHALL match it like any method name. The entries of
  each table SHALL be read in the order of the file, and the first whose class part matches and whose methods
  include the method SHALL decide for that method.

  The tables default to empty. They SHALL be independent: no value in one table SHALL change what the
  other selects.

Sections, dotted keys (`augment.maxValueLength = 32`) and inline tables SHALL be equivalent. Quoted and
unquoted class names in `[augment.exceptions]` SHALL be equivalent; keys with wildcards, and the keys of
`[augment.receiver]` and `[augment.params]`, which contain `::`, SHALL be quoted, as TOML requires. Two entries of
one table whose keys differ only in quoting, in spaces or in the order of their methods, such as `com.acme.Order` and `"com.acme.Order"`, or
`"com.acme.Order::a,b"`, `"com.acme.Order::a, b"` and `"com.acme.Order::b, a"`, SHALL be rejected, naming the key and the lines of both
entries, rather than one silently replacing the other.

#### Scenario: Full configuration
- **GIVEN** a file with `debug`, `[augment.receiver]`, `[augment.params]` and `[augment]` entries
- **WHEN** it is loaded
- **THEN** every value is available with its declared type

#### Scenario: Class configured twice
- **GIVEN** `"com.acme.Order::a,b" = "x"` on line 2 and `"com.acme.Order::a, b" = "y"` on line 3 of `[augment.receiver]` in `stack-augmentor.toml`
- **WHEN** it is loaded
- **THEN** it is rejected with `stack-augmentor.toml, line 3: 'com.acme.Order::a, b' is configured twice; it is already configured on line 2 (keys that differ in spaces only name the same methods)`

#### Scenario: Empty file
- **GIVEN** an empty configuration file
- **WHEN** it is loaded
- **THEN** all defaults apply, and nothing is augmented

#### Scenario: Wildcard entry with named parameters
- **GIVEN** `"com.thirdparty.**.*Service::*" = "order, 1"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** the entry selects the parameter `order` and the second parameter of all methods of matching classes

#### Scenario: Class entries
- **GIVEN** `"com.acme.legacy.*::*" = "getKey()"`, `"com.acme.**::*" = "@"` and `"com.thirdparty.Customer::*" = "customerId"` in `[augment.receiver]`, in this order
- **WHEN** it is loaded
- **THEN** the classes under `com.acme` use their `@StackTraceReceiver`, classes directly in `com.acme.legacy` use `getKey()`, and `Customer` uses its `customerId` field, while no parameter annotations are used

#### Scenario: Method entry with "@"
- **GIVEN** `"com.acme.legacy.*::*" = "@"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** the methods of the classes directly in `com.acme.legacy` use their `@StackTraceParam` and `@StackTraceParams` annotations, and so do their constructors

#### Scenario: Constructor entry
- **GIVEN** `"com.acme.legacy.*::<init>" = "@"` and then `"com.acme.legacy.*::*" = "0"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** the constructors of the classes directly in `com.acme.legacy` use their `@StackTraceParam` and `@StackTraceParams` annotations; the `<init>` entry comes first and wins over `*`, and their methods show their first parameter

### Requirement: Invalid configuration
The system SHALL reject a configuration with a TOML syntax error, a value of the wrong type, a value out
of range, an invalid `[augment.receiver]` or `[augment.params]` entry, an invalid `frameFormat`,
`receiverFormat` or `paramsFormat` (see the frame format specification), or an unknown key in any
section, including keys in the wrong section (such as `maxValueLength` at the top level). `[augment.receiver]` and `[augment.params]` keys SHALL
be invalid when they are not a class part and a method part separated by `::`; when the class part is not
dotted segments of identifier characters, `$`, `*` and `?`, optionally followed by `+`, or has `+` after a
pattern; or when the method part is not method names or patterns (identifier characters, `$`, `*` and `?`)
or `@` separated by commas, without duplicates or empty items. `<init>` SHALL be allowed in the method part of
`[augment.params]` keys only. An `[augment.receiver]` value SHALL be invalid when it is not a string with a field
name, a `method()`, several of them separated by commas without duplicates or empty items, `"@"` or `"-"`, or
when it contains `::`. An `[augment.params]` value SHALL be invalid when it is not a string with `"@"`, `"-"` or
parameter names and indexes (0 to 255) separated by commas, each optionally followed by `#`, without empty
items and without the same name or index twice, hashed or not (`"*"`, `"@"` and `"-"` are not allowed among the items, and `"*"` is not allowed as the value). Spaces
around the items SHALL NOT matter. A TOML array SHALL be
invalid in both tables. The message SHALL name the file, the key and its line.

#### Scenario: Exceptions entry that is not a boolean
- **GIVEN** `"java.io.IOException" = "@"` on line 2 of `[augment.exceptions]` in `stack-augmentor.toml`
- **WHEN** it is loaded
- **THEN** it is rejected with `stack-augmentor.toml, line 2: must be true to augment the exceptions of this class or class pattern, or false not to, was @`

#### Scenario: Value out of range
- **GIVEN** `[augment]` with `maxValueLength = 1` on line 3 of `stack-augmentor.toml`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with `stack-augmentor.toml, line 3: maxValueLength must be between 2 and 10000, was 1`

#### Scenario: Unknown key
- **GIVEN** `[augment]` with `frame = "$class.$method"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming `frame`, the section `[augment]` and the allowed keys

#### Scenario: Not a TOML file
- **GIVEN** a configuration path ending in `.properties`
- **WHEN** it is loaded
- **THEN** it is rejected with a message that the configuration must be a TOML file ending in `.toml`

#### Scenario: Invalid parameter entries
- **GIVEN** `[augment.params]` with `"com.acme.Order::process" = ["order"]`, `"com.acme.Order::process" = "order,"`, `"com.acme.Order::process" = "*"`, or `"com.acme.Ord+er::process" = "@"`, or `"com.acme.Order.process" = "@"` (no `::`)
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line

#### Scenario: Invalid class entry
- **GIVEN** `[augment.receiver]` with `"com.acme.Order::*" = "@id"`, `"com.acme.Order::*" = ["id"]`, `"com.acme.Order::*" = "id, @"`, `"com.acme.Order::*" = "id :: ship"` (the methods go in the key), `"com.acme.Order" = "id"` (no `::`), `"com.acme.Order::" = "id"`, `"com.acme.Order::ship()" = "id"`, `"com.acme.Order::<init>" = "id"`, `"com.acme.Order::ship, ship" = "id"`, `"com.acme.*+::*" = "id"`, or `"com.acme.Ord+er::*" = "@"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line

### Requirement: Locating the configuration
With the Java agent, the configuration SHALL be taken from the agent arguments (`config=<path>` or
`<path>`), otherwise from the `stackaugmentor.config` system property, otherwise the defaults apply. With
build-time instrumentation, the build plugin SHALL read the file passed as its argument, and at runtime
the configuration SHALL be taken from the `stackaugmentor.config` system property, otherwise from
`stack-augmentor.toml` on the classpath, otherwise the defaults apply. The classpath resource SHALL be looked
up through the runtime jar's class loader, and when that loader does not find it, through the context class
loader of the thread that creates the handler, i.e. the thread of the first exception: in an application
server or a fat jar, the runtime jar can be loaded by a parent of the application's class loader. The defaults contain no
`[augment.receiver]` or `[augment.params]` entries, so the agent with the defaults augments nothing.

#### Scenario: Configuration visible only to the application's class loader
- **GIVEN** a build-time instrumented application whose `stack-augmentor.toml` is visible to the context class loader of the throwing thread, but not to the class loader of `stack-augmentor-runtime`
- **WHEN** the first exception leaves an instrumented method
- **THEN** the runtime handler uses that `stack-augmentor.toml`

#### Scenario: Agent without configuration
- **GIVEN** `-javaagent:stack-augmentor-agent.jar` without arguments and without the system property
- **WHEN** the application starts
- **THEN** no class is augmented, and a warning says that the configuration has no `[augment.receiver]` or `[augment.params]` entries

#### Scenario: Missing file
- **GIVEN** `config=missing.toml` and no such file
- **WHEN** the agent starts
- **THEN** it fails with `Configuration file not found: missing.toml`

### Requirement: Sections by phase
What gets instrumented SHALL NOT be configured directly: the agent, when classes are loaded, and the build
plugin, at build time, SHALL instrument the classes and methods that `[augment.receiver]` and
`[augment.params]` need. The formats in `[augment]`, `[augment.exceptions]`, `debug` and `inPlaceModification` SHALL be read at runtime
in both modes, and so SHALL `[augment.receiver]`, to find the receiver members of instrumented classes (with
build-time instrumentation by the key of the entry that decided the method at build time). One
file SHALL be usable for both phases of build-time instrumentation.

#### Scenario: One file for build-time instrumentation
- **GIVEN** `src/main/resources/stack-augmentor.toml` is passed to the build plugin and packaged as a resource
- **WHEN** the project is built and run
- **THEN** the build plugin applies its `[augment.receiver]` and `[augment.params]` tables and the runtime applies its `[augment]` section with `[augment.exceptions]` and `[augment.receiver]`
