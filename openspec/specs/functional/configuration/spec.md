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
  - `[augment.annotations]` (class name or class pattern, with the keys of `[augment.exceptions]` → `true` or
    `false`), which decides the classes whose annotations (`@AugmentedReceiver`, `@AugmentedParam`,
    `@AugmentedParams`, `@Augmented`) are read, matched against the class that declares a frame's method, see the
    receivers and parameters spec. Its entries SHALL be read in the order of the file, and the first that matches
    SHALL decide. A class that no entry matches SHALL NOT read annotations; without entries no annotations are read;
  - `[augment.receiver]` (method key, see below → a string: field name, `method()`, several of them separated
    by commas, or `"-"` for no receiver members), which decides the receiver members by name, see the receivers and
    parameters spec;
  - `[augment.params]` (method key → a string: parameter names and 0-based indexes from 0 to 255, the JVM's
    maximum number of parameters, separated by commas, each optionally followed by `#` to hash the value (e.g.
    `"order, 2, email#"`), or `"-"` for none; there is no wildcard value for the parameters), which decides the
    parameter values by name or index.

  The keys of `[augment.receiver]` and `[augment.params]` SHALL have the same syntax and matching,
  `"<class>::<methods>"`: a class name, optionally followed by `+`, or a class pattern; then `::`, which is
  required; then method names or patterns separated by commas without duplicates, where `*` alone stands for all
  methods. A class name SHALL match the class that declares a frame's method; with `+` also its
  subclasses and, for an interface, the classes implementing it, as `instanceof` does; a pattern SHALL match
  class names only. `+` after a pattern SHALL be rejected. The method part `<init>` names the constructors, in
  `[augment.params]` only; wildcards in the method part SHALL match it like any method name. The entries of
  each table SHALL be read in the order of the file, and the first whose class part matches and whose methods
  include the method SHALL decide for that method. Only for a method that no entry of a table matches SHALL the
  annotations be used in that table's place, where `[augment.annotations]` selects the declaring class and
  `@Augmented` the method. These two tables SHALL name members and parameters only: `"@"` SHALL NOT be a value and
  `@` SHALL NOT be a method in their keys.

  The tables default to empty. `[augment.receiver]` and `[augment.params]` SHALL be independent: no value in one
  table SHALL change what the other selects.

Sections, dotted keys (`augment.maxValueLength = 32`) and inline tables SHALL be equivalent. Quoted and
unquoted class names in `[augment.exceptions]` and in `[augment.annotations]` SHALL be equivalent; keys with wildcards, and the keys of
`[augment.receiver]` and `[augment.params]`, which contain `::`, SHALL be quoted, as TOML requires. Two entries of
one table whose keys differ only in quoting, in spaces or in the order of their methods, such as `com.acme.Order` and `"com.acme.Order"`, or
`"com.acme.Order::a,b"`, `"com.acme.Order::a, b"` and `"com.acme.Order::b, a"`, SHALL be rejected, naming the key and the lines of both
entries, rather than one silently replacing the other.

#### Scenario: Full configuration
- **GIVEN** a file with `debug`, `[augment.annotations]`, `[augment.receiver]`, `[augment.params]` and `[augment]` entries
- **WHEN** it is loaded
- **THEN** every value is available with its declared type

#### Scenario: Class configured twice
- **GIVEN** `"com.acme.Order::a,b" = "x"` on line 2 and `"com.acme.Order::a, b" = "y"` on line 3 of `[augment.receiver]` in `stack-augmentor.toml`
- **WHEN** it is loaded
- **THEN** it is rejected with `stack-augmentor.toml, line 3: 'com.acme.Order::a, b' is configured twice; it is already configured on line 2 (keys that differ in spaces or in the order of their methods name the same methods)`

#### Scenario: Empty file
- **GIVEN** an empty configuration file
- **WHEN** it is loaded
- **THEN** the default values apply, the built-in configuration does not, and nothing is augmented

#### Scenario: Wildcard entry with named parameters
- **GIVEN** `"com.thirdparty.**.*Service::*" = "order, 1"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** the entry selects the parameter `order` and the second parameter of all methods of matching classes

#### Scenario: Class entries
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, and `"com.acme.legacy.*::*" = "getKey()"` and `"com.thirdparty.Customer::*" = "customerId"` in `[augment.receiver]`
- **WHEN** it is loaded
- **THEN** the classes directly in `com.acme.legacy` use `getKey()`, the other classes under `com.acme` with `@Augmented` their `@AugmentedReceiver` and their parameter annotations, and `Customer` uses its `customerId` field

#### Scenario: Annotations of some classes
- **GIVEN** `"com.acme.legacy.*" = true` in `[augment.annotations]`
- **WHEN** it is loaded
- **THEN** in the classes directly in `com.acme.legacy`, the methods that `@Augmented` selects use their `@AugmentedReceiver`, `@AugmentedParam` and `@AugmentedParams` annotations, and the constructors it selects their parameter annotations

#### Scenario: First matching annotations entry
- **GIVEN** `"com.acme.generated.**" = false` and then `"com.acme.**" = true` in `[augment.annotations]`
- **WHEN** it is loaded
- **THEN** the classes under `com.acme.generated` read no annotations, and the other classes under `com.acme` read them
- **AND** with the two entries the other way round, `"com.acme.**" = true` decides for the classes under `com.acme.generated` too

#### Scenario: Constructor entry
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, and `"com.acme.legacy.*::<init>" = "-"` and then `"com.acme.legacy.*::*" = "0"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** the constructors of the classes directly in `com.acme.legacy` show no parameter values, not even their annotated parameters; the `<init>` entry comes first and wins over `*`, and their methods show their first parameter

### Requirement: Invalid configuration
The system SHALL reject a configuration with a TOML syntax error, a value of the wrong type, a value out
of range, an invalid `[augment.exceptions]`, `[augment.annotations]`, `[augment.receiver]` or `[augment.params]` entry, an invalid `frameFormat`,
`receiverFormat` or `paramsFormat` (see the frame format specification), or an unknown key in any
section, including keys in the wrong section (such as `maxValueLength` at the top level). `[augment.receiver]` and `[augment.params]` keys SHALL
be invalid when they are not a class part and a method part separated by `::`; when the class part is not
dotted segments of identifier characters, `$`, `*` and `?`, optionally followed by `+`, or has `+` after a
pattern; or when the method part is not method names or patterns (identifier characters, `$`, `*` and `?`)
separated by commas, without duplicates or empty items. `<init>` SHALL be allowed in the method part of
`[augment.params]` keys only. An `[augment.receiver]` value SHALL be invalid when it is not a string with a field
name, a `method()`, several of them separated by commas without duplicates or empty items, or `"-"`, or
when it contains `::`. An `[augment.params]` value SHALL be invalid when it is not a string with `"-"` or
parameter names and indexes (0 to 255) separated by commas, each optionally followed by `#`, without empty
items and without the same name or index twice, hashed or not (`"*"`, `"@"` and `"-"` are not allowed among the items, and `"*"` is not allowed as the value). Spaces
around the items SHALL NOT matter. A TOML array SHALL be
invalid in both tables. The value `"@"`, and `@` in the method part of a key (`"com.acme.Order::@"`), SHALL be
rejected in both tables with `"@" is not allowed here: these tables name members and parameters, and
[augment.annotations] says where the annotations are read, e.g. "com.acme.**" = true`. An `[augment.exceptions]` or
`[augment.annotations]` key SHALL be invalid when it is not a class part as above, and its value when it is not
`true` or `false`; two keys of one of these tables that differ only in quoting SHALL be rejected as above. The
message SHALL name the file, the key and its line.

#### Scenario: Exceptions entry that is not a boolean
- **GIVEN** `"java.io.IOException" = "@"` on line 2 of `[augment.exceptions]` in `stack-augmentor.toml`
- **WHEN** it is loaded
- **THEN** it is rejected with `stack-augmentor.toml, line 2: must be true to augment the exceptions of this class or class pattern, or false not to, was @`

#### Scenario: Annotations entry that is not a boolean
- **GIVEN** `"com.acme.**" = "yes"` on line 2 of `[augment.annotations]` in `stack-augmentor.toml`
- **WHEN** it is loaded
- **THEN** it is rejected with `stack-augmentor.toml, line 2: must be true to read the annotations of this class or class pattern, or false not to, was yes`

#### Scenario: Annotations entry configured twice
- **GIVEN** `"com.acme.Order" = true` and unquoted `com.acme.Order = false` in `[augment.annotations]`
- **WHEN** it is loaded
- **THEN** it is rejected with a message naming the key and the lines of both entries

#### Scenario: "@" in the receiver and parameter tables
- **GIVEN** `"com.acme.**::*" = "@"` on line 2 of `[augment.receiver]` or of `[augment.params]`, or `"com.acme.Order::@" = "id"` in `[augment.receiver]`, or `"com.acme.Order::ship, @" = "0"` in `[augment.params]`, in `stack-augmentor.toml`
- **WHEN** it is loaded
- **THEN** it is rejected with `stack-augmentor.toml, line 2: "@" is not allowed here: these tables name members and parameters, and [augment.annotations] says where the annotations are read, e.g. "com.acme.**" = true` (with the line of the entry)

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
- **GIVEN** `[augment.params]` with `"com.acme.Order::process" = ["order"]`, `"com.acme.Order::process" = "order,"`, `"com.acme.Order::process" = "*"`, or `"com.acme.Ord+er::process" = "0"`, or `"com.acme.Order.process" = "0"` (no `::`)
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line

#### Scenario: Invalid class entry
- **GIVEN** `[augment.receiver]` with `"com.acme.Order::*" = "@id"`, `"com.acme.Order::*" = ["id"]`, `"com.acme.Order::*" = "id, @"`, `"com.acme.Order::*" = "id :: ship"` (the methods go in the key), `"com.acme.Order" = "id"` (no `::`), `"com.acme.Order::" = "id"`, `"com.acme.Order::ship()" = "id"`, `"com.acme.Order::<init>" = "id"`, `"com.acme.Order::ship, ship" = "id"`, `"com.acme.*+::*" = "id"`, or `"com.acme.Ord+er::*" = "id"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line

### Requirement: Locating the configuration
With the Java agent, the configuration SHALL be taken from the agent arguments (`config=<path>` or
`<path>`), otherwise from the `stackaugmentor.config` system property, otherwise the built-in configuration applies. With
build-time instrumentation, the build plugin SHALL read the file passed as its argument, and at runtime
the configuration SHALL be taken from the `stackaugmentor.config` system property, otherwise from
`stack-augmentor.toml` on the classpath, otherwise the built-in configuration applies. The classpath resource SHALL be looked
up through the runtime jar's class loader, and when that loader does not find it, through the context class
loader of the thread that creates the handler, i.e. the thread of the first exception: in an application
server or a fat jar, the runtime jar can be loaded by a parent of the application's class loader. Without a configuration
the build plugin SHALL apply the built-in configuration as well.

The built-in configuration SHALL be the classpath resource `com/hafnium/stackaugmentor/runtime/config/built-in.toml` of
`stack-augmentor-runtime`, which the agent jar contains too:

```toml
[augment.exceptions]
"java.lang.Exception+" = true

[augment.annotations]
"**" = true
```

It SHALL apply only when no file is given; a file SHALL replace it completely, also where it leaves a table out.

#### Scenario: Configuration visible only to the application's class loader
- **GIVEN** a build-time instrumented application whose `stack-augmentor.toml` is visible to the context class loader of the throwing thread, but not to the class loader of `stack-augmentor-runtime`
- **WHEN** the first exception leaves an instrumented method
- **THEN** the runtime handler uses that `stack-augmentor.toml`

#### Scenario: Agent without configuration
- **GIVEN** `-javaagent:stack-augmentor-agent.jar` without arguments and without the system property, and a class with `@Augmented`, an `@AugmentedReceiver` field `key = "java-1"` and a method `run(@AugmentedParam int value)` that throws an `IllegalStateException`
- **WHEN** `run(42)` throws
- **THEN** the built-in configuration applies, the frame shows `{key=java-1}` and `{value=42}`, and no warning is printed

#### Scenario: Built-in configuration
- **GIVEN** no configuration file
- **WHEN** the agent, the build plugin or the runtime of build-time instrumented classes starts
- **THEN** all exceptions are augmented, `[augment.annotations]` is `"**" = true`, and `[augment.receiver]` and `[augment.params]` have no entries, so the classes with `@Augmented` show their annotated receiver members and parameter values, as `@Augmented` selects their methods, and the classes without `@Augmented` are skipped

#### Scenario: File without receiver, parameter and annotations entries
- **GIVEN** `config=stack-augmentor.toml` with only `debug = false`
- **WHEN** the agent starts
- **THEN** no class is augmented, and a warning says that the configuration has no `[augment.receiver]` or `[augment.params]` entries other than `"-"` and no `[augment.annotations]` entry that is `true`

#### Scenario: Missing file
- **GIVEN** `config=missing.toml` and no such file
- **WHEN** the agent starts
- **THEN** it fails with `Configuration file not found: missing.toml`

### Requirement: Sections by phase
What gets instrumented SHALL NOT be configured directly: the agent, when classes are loaded, and the build
plugin, at build time, SHALL instrument the classes and methods that `[augment.annotations]`, `[augment.receiver]` and
`[augment.params]` need. The formats in `[augment]`, `[augment.exceptions]`, `debug` and `inPlaceModification` SHALL be read at runtime
in both modes, and so SHALL `[augment.receiver]` and `[augment.annotations]`, to find the receiver members of instrumented classes (with
build-time instrumentation by the key of the entry that decided the method at build time, or for the key `@` the
`@AugmentedReceiver` members, if the runtime's `[augment.annotations]` selects the declaring class). One
file SHALL be usable for both phases of build-time instrumentation.

#### Scenario: One file for build-time instrumentation
- **GIVEN** `src/main/resources/stack-augmentor.toml` is passed to the build plugin and packaged as a resource
- **WHEN** the project is built and run
- **THEN** the build plugin applies its `[augment.annotations]`, `[augment.receiver]` and `[augment.params]` tables and the runtime applies its `[augment]` section with `[augment.exceptions]`, `[augment.annotations]` and `[augment.receiver]`
