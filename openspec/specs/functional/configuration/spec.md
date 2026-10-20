# Configuration Specification

## Purpose
Defines the configuration file: where it comes from, the built-in configuration, its keys and tables, how table keys match
classes and methods, what each phase uses, and which files are rejected. It owns the key syntax and the matching rules; the
other specifications refer to them.

## Requirements

### Requirement: File and keys
The configuration SHALL be one TOML file whose name ends in `.toml`, with these keys and defaults:
- `debug` (boolean, default `false`): diagnostics, see the diagnostics specification;
- `inPlaceModification` (boolean, default `true`): a faster way of writing augmented frames that needs extra JVM
  permissions, used where the JVM allows it, see the performance specification; a system property
  `stackaugmentor.inPlaceModification` SHALL override it;
- `[augment]`: `frameFormat`, `receiverFormat`, `paramsFormat` (see the frame format specification) and `maxValueLength`
  (integer from 2 to 10000, default 64, see the receivers and parameters specification);
- `[augment.exceptions]` (class key → `true` or `false`): which throwables are augmented;
- `[augment.annotations]` (class key → `true` or `false`): in which declaring classes the annotations are read;
- `[augment.receiver]` (method key → members): the receiver members by name;
- `[augment.params]` (method key → parameters): the parameter values by name or index.

The four tables SHALL default to empty: without entries, no throwable is augmented and no annotations are read. Any TOML
notation for the same keys (sections, dotted keys, inline tables) SHALL mean the same. The order of the tables SHALL NOT
matter; the order of the entries within a table SHALL (see "First matching entry").

#### Scenario: Full configuration
- **GIVEN** a file with `debug`, `inPlaceModification`, `[augment]`, `[augment.exceptions]`, `[augment.annotations]`, `[augment.receiver]` and `[augment.params]` entries
- **WHEN** it is loaded
- **THEN** every value is used as written, and the entries of each table in the order of the file

#### Scenario: Empty file
- **GIVEN** an empty configuration file
- **WHEN** it is loaded
- **THEN** the default values apply, the built-in configuration does not, and nothing is augmented

### Requirement: Class keys
A class key, the key of `[augment.exceptions]` and `[augment.annotations]` and the part before `::` of a method key, SHALL
be one of:
- a class name, with `$` for nested classes (`com.acme.Order$Line`): it SHALL match that class only;
- a class name followed by `+` (`com.acme.Order+`): it SHALL match that class, its subclasses and, for an interface, the
  classes implementing it, without loading classes to find out;
- a pattern with `*` (any characters within one package segment), `**` (any characters across segments) and `?` (one
  character other than `.`): it SHALL match class names only, never subclasses by inheritance; `**` alone matches every
  class. `+` after a pattern SHALL be rejected.

A class key SHALL be matched against the throwable's class in `[augment.exceptions]`, and against the declaring class of
the frame's method in the other three tables.

#### Scenario: Class name, plus and pattern
- **GIVEN** the keys `"java.io.IOException"`, `"java.io.IOException+"` and `"java.io.IO*"` in `[augment.exceptions]`, each alone with `true`
- **WHEN** a `FileNotFoundException` leaves a configured method
- **THEN** only with `"java.io.IOException+"` is it augmented

### Requirement: Method keys
The keys of `[augment.receiver]` and `[augment.params]` SHALL have the same syntax, `"<class key>::<methods>"`: `::` is
required, and the methods are names or patterns separated by commas, without duplicates, where `*` alone stands for all
methods, `*` within a name for any characters and `?` for one character. A key SHALL match a method when its class key
matches the declaring class and one of its methods matches the method's name, with every overload. Constructors are named
`<init>`, in `[augment.params]` keys only, since constructors show no receiver members; wildcards SHALL match `<init>` like
any name. Spaces around the commas SHALL NOT matter. One class MAY have several entries with different methods.

#### Scenario: Several methods
- **GIVEN** `"com.thirdparty.Parcel::ship, cancel*" = "trackingCode"` in `[augment.receiver]`
- **WHEN** exceptions leave `ship()`, `cancelLater()` and `weigh()` of a `Parcel`
- **THEN** the entry applies to `ship` and `cancelLater`, not to `weigh`

#### Scenario: Wildcard entry with named parameters
- **GIVEN** `"com.thirdparty.**.*Service::*" = "order, 1"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** the entry selects the parameter `order` and the second parameter of all methods and constructors of matching classes

### Requirement: First matching entry
In each table, the first entry in the order of the file that matches SHALL decide, however specific the later ones are,
and later entries SHALL NOT be combined with it: in `[augment.exceptions]` per throwable class, in `[augment.annotations]`
per declaring class, in `[augment.receiver]` and `[augment.params]` per method. A method that no entry of
`[augment.receiver]` or `[augment.params]` matches SHALL use the annotations in that table's place (see the receivers and
parameters specification). The two method tables SHALL be independent: no entry of one SHALL change what the other selects.

#### Scenario: First matching annotations entry
- **GIVEN** `"com.acme.generated.**" = false` and then `"com.acme.**" = true` in `[augment.annotations]`
- **WHEN** it is loaded
- **THEN** the classes under `com.acme.generated` read no annotations, and the other classes under `com.acme` read them
- **AND** with the two entries the other way round, `"com.acme.**" = true` decides for the classes under `com.acme.generated` too

#### Scenario: Constructor entry first
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, and `"com.acme.legacy.*::<init>" = "-"` and then `"com.acme.legacy.*::*" = "0"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** the constructors of the classes directly in `com.acme.legacy` show no parameter values, not even their annotated parameters, and their methods show their first parameter

### Requirement: Values
The values SHALL be:
- in `[augment.exceptions]` and `[augment.annotations]`: `true` or `false`;
- in `[augment.receiver]`: a string with a field name or a no-argument method written with `()`, or several of them
  separated by commas (`"tenant, lineId()"`), or `"-"` for no receiver members;
- in `[augment.params]`: a string with parameter names and 0-based indexes up to 255 (the JVM's maximum number of
  parameters), separated by commas, each optionally followed by `#` to show the value hashed (`"order, 2, email#"`), or
  `"-"` for none. There SHALL be no value that selects all parameters.

The method tables SHALL name members and parameters only; where the annotations are read SHALL be said by
`[augment.annotations]` alone.

#### Scenario: Annotations of some classes
- **GIVEN** `"com.acme.legacy.*" = true` in `[augment.annotations]`
- **WHEN** it is loaded
- **THEN** in the classes directly in `com.acme.legacy`, the methods and constructors that `@Augmented` selects use their annotations

### Requirement: Invalid configuration
The system SHALL reject a configuration that is not valid TOML, has an unknown key in any section (also a known key in the
wrong section), a value of the wrong type or out of range, an invalid template, a key or value that does not follow the
syntax above, the same member, parameter or method twice in one entry, or two entries of one table that name the same
classes and methods in another spelling (quoted and unquoted, other spaces, another order of methods). An error message
SHALL name the file, the key and its line, and say what is allowed; for an unknown key it SHALL list the allowed keys. An
attempt to say in `[augment.receiver]` or `[augment.params]` that the annotations are to be used (such as `"@"`) SHALL be
rejected with a pointer to `[augment.annotations]`.

#### Scenario: Value out of range
- **GIVEN** `[augment]` with `maxValueLength = 1` on line 3 of `stack-augmentor.toml`
- **WHEN** the configuration is loaded
- **THEN** it is rejected, naming `stack-augmentor.toml`, line 3, `maxValueLength`, the allowed range and the value

#### Scenario: Unknown key
- **GIVEN** `[augment]` with `frame = "$class.$method"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming `frame`, the section `[augment]` and the allowed keys

#### Scenario: Same entry twice
- **GIVEN** `"com.acme.Order::a,b"` on line 2 and `"com.acme.Order::b, a"` on line 3 of `[augment.receiver]`, or `"com.acme.Order"` and unquoted `com.acme.Order` in `[augment.annotations]`
- **WHEN** it is loaded
- **THEN** it is rejected with a message naming the key and the lines of both entries

#### Scenario: Not a boolean
- **GIVEN** `"java.io.IOException" = "yes"` in `[augment.exceptions]` or in `[augment.annotations]`
- **WHEN** it is loaded
- **THEN** it is rejected with a message that the value must be `true` or `false`

#### Scenario: Annotations requested in a method table
- **GIVEN** `"com.acme.**::*" = "@"` in `[augment.receiver]`, or `"com.acme.Order::ship, @" = "0"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** it is rejected with a message that `[augment.annotations]` says where the annotations are read

#### Scenario: Not a TOML file
- **GIVEN** a configuration path ending in `.properties`
- **WHEN** it is loaded
- **THEN** it is rejected with a message that the configuration must be a TOML file

#### Scenario: Invalid parameter entries
- **GIVEN** `[augment.params]` with `"com.acme.Order::process" = ["order"]`, `"order,"`, `"*"`, `"256"`, `"email, email#"`, or the keys `"com.acme.Ord+er::process"` or `"com.acme.Order.process"` (no `::`)
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line

#### Scenario: Invalid receiver entries
- **GIVEN** `[augment.receiver]` with `"com.acme.Order::*" = "@id"`, `["id"]`, `"id, @"`, `"id :: ship"`, or the keys `"com.acme.Order"` (no `::`), `"com.acme.Order::"`, `"com.acme.Order::ship()"`, `"com.acme.Order::<init>"`, `"com.acme.Order::ship, ship"` or `"com.acme.*+::*"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line

### Requirement: Locating the configuration
The base configuration file SHALL be taken from:
- with the Java agent, in both of its modes: the agent argument (`config=<path>` or `<path>`), otherwise the system
  property `stackaugmentor.config`;
- with the build plugin: its argument;
- at runtime with build-time instrumentation: the system property `stackaugmentor.config`, otherwise
  `stack-augmentor.toml` visible to the runtime library.

Without a file, the built-in configuration SHALL apply. A named file that does not exist SHALL be an error.

#### Scenario: Missing file
- **GIVEN** `config=missing.toml` and no such file
- **WHEN** the agent starts
- **THEN** it fails with a message that `missing.toml` was not found

### Requirement: Configurations of applications
At runtime, with the agent in both of its modes and with build-time instrumentation, an application that has a class
loader of its own, as in an application server, in Spring Boot or in Quarkus, MAY bring its own `stack-augmentor.toml`.
Each class SHALL use the `stack-augmentor.toml` of the nearest class loader, from its own class loader upwards, that has
one its parent class loader does not see; the classes of the JVM's own class loaders, of the class loader of Stack
Augmentor, and of class loaders without such a file SHALL use the base configuration. With the agent, that configuration
SHALL decide everything for the class; with build-time instrumentation, what the runtime decides. Each configuration
SHALL be read once per class loader, before the first class of it is augmented. `inPlaceModification` SHALL be decided
once, by the base configuration, and `debug = true` in any configuration SHALL turn on the debug output.

#### Scenario: Two applications in one server
- **GIVEN** the agent with a configuration that selects nothing, and two applications in class loaders of their own, each with a `stack-augmentor.toml` that selects its classes, one with `receiverFormat = "<$value>"`
- **WHEN** an augmented exception leaves an annotated method of each
- **THEN** each frame shows its receiver members in the format of its own application's configuration

#### Scenario: Spring Boot application
- **GIVEN** a Spring Boot executable jar with `stack-augmentor.toml`, run with the agent and another configuration
- **WHEN** an exception leaves a library method that the jar's configuration selects
- **THEN** the frame shows the parameter values that the jar's configuration selects, in its format

#### Scenario: Invalid configuration of an application
- **GIVEN** an application whose own `stack-augmentor.toml` has `maxValueLength = 1` on line 2
- **WHEN** its first class is loaded with the agent
- **THEN** an error names the file, line 2 and `maxValueLength`, the JVM keeps running, and that application's frames stay unchanged

### Requirement: Built-in configuration
Without a file, this configuration SHALL apply, with the default values of every other key, in the agent, the build plugin
and the runtime alike:

```toml
[augment.exceptions]
"java.lang.Exception+" = true

[augment.annotations]
"**" = true
```

So all exceptions (not errors) are augmented with the annotated receiver members and parameter values of the classes and
methods that `@Augmented` selects. A file SHALL replace it completely, also where it leaves a table out.

#### Scenario: Agent without configuration
- **GIVEN** the agent without a configuration, and a class with `@Augmented`, an `@AugmentedReceiver` field `key = "java-1"` and a method `run(@AugmentedParam int value)` that throws an `IllegalStateException`
- **WHEN** `run(42)` throws
- **THEN** the frame shows `{key=java-1}` and `{value=42}`, and no warning is printed

#### Scenario: Classes without @Augmented
- **GIVEN** no configuration file and a class with annotated members but without `@Augmented`
- **WHEN** one of its methods throws
- **THEN** its frame is unchanged

#### Scenario: File without entries
- **GIVEN** a configuration file with only `debug = false`
- **WHEN** the agent starts
- **THEN** nothing is augmented, and a warning says so

### Requirement: What each phase uses
What is instrumented SHALL follow from `[augment.annotations]`, `[augment.receiver]` and `[augment.params]`; it SHALL NOT be
configured directly. Every component SHALL validate the whole file. With the agent, the whole file SHALL apply at runtime.
With build-time instrumentation:
- the build SHALL decide, from `[augment.annotations]`, `[augment.receiver]` and `[augment.params]`, which methods are
  augmented, which parameter values they show, and where their receiver members come from;
- the runtime SHALL apply `[augment]`, `[augment.exceptions]`, `debug`, `inPlaceModification`, and the members of the
  `[augment.receiver]` entries the build chose (see the build-time instrumentation specification), from the
  configuration of each class (see "Configurations of applications").

One file SHALL serve both phases of build-time instrumentation.

#### Scenario: One file for build-time instrumentation
- **GIVEN** `src/main/resources/stack-augmentor.toml` passed to the build plugin and packaged with the application
- **WHEN** the project is built and run
- **THEN** the build uses its method and annotation tables, and the runtime its formats, `[augment.exceptions]` and receiver members
