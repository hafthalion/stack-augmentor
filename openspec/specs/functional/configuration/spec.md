# Configuration Specification

## Purpose
Defines the configuration files: where they come from, in which order they apply to a class, the built-in configuration,
their keys and tables, the options of the whole JVM, how table keys match classes and methods, what each phase uses, and which files are rejected. It owns the key syntax and the matching rules; the
other specifications refer to them.

## Requirements

### Requirement: File and keys
A configuration SHALL be a TOML file whose name ends in `.toml`, with these keys and defaults, and no others:
- `[augment]`: `frameFormat`, `receiverFormat`, `paramsFormat` (see the frame format specification) and `maxValueLength`
  (integer from 2 to 10000, default 64, see the receivers and parameters specification);
- `[augment.exceptions]` (class key → `true` or `false`): which throwables are augmented;
- `[augment.receiver]` (method key → members): the receiver members by name;
- `[augment.params]` (method key → parameters): the parameter values by name or index.

The three tables SHALL default to empty: a file without entries augments no throwable by itself, while the
configurations after it in the chain still apply (see "Configuration chain"). Where the
annotations are read SHALL NOT be configured: `@Augmented` in the code says it (see the receivers and parameters
specification). Any TOML notation for the same keys (sections, dotted keys, inline tables) SHALL mean the same. The order of the tables SHALL NOT
matter; the order of the entries within a table SHALL (see "First matching entry").

#### Scenario: Full configuration
- **GIVEN** a file with `[augment]`, `[augment.exceptions]`, `[augment.receiver]` and `[augment.params]` entries
- **WHEN** it is loaded
- **THEN** every value is used as written, and the entries of each table in the order of the file

#### Scenario: Empty file
- **GIVEN** an empty configuration file
- **WHEN** it is loaded
- **THEN** its default values apply where no earlier configuration sets them, and the built-in configuration after it decides the tables

#### Scenario: Annotations table
- **GIVEN** a configuration file with `[augment.annotations]`
- **WHEN** it is loaded
- **THEN** it is rejected as having an unknown key in `[augment]`

### Requirement: Options of the whole JVM
What applies to the whole JVM, or to the whole build, SHALL be given by system properties, never by a configuration file,
and the same way for the agent, the build plugin and the runtime:
- `stackaugmentor.config`: the path of the JVM's global configuration file (see "Locating the configuration");
- `stackaugmentor.debug` (boolean, default `false`): diagnostics, see the diagnostics specification; for the build plugin,
  a system property of the build.

The agent SHALL take no arguments; an agent started with arguments SHALL fail as with an invalid configuration, saying
to use `stackaugmentor.config`.

#### Scenario: Debug output for a run
- **GIVEN** an application started with the agent and `-Dstackaugmentor.debug=true`
- **WHEN** it starts
- **THEN** the debug output describes the configuration files, whatever they contain

#### Scenario: Agent arguments
- **GIVEN** `-javaagent:<agent jar>=config=agent.toml`
- **WHEN** the agent starts
- **THEN** it fails with a message that the agent takes no arguments and that `-Dstackaugmentor.config=<file>` names the file

#### Scenario: Options in a file
- **GIVEN** a configuration file with `[settings]` or `debug = true`
- **WHEN** it is loaded
- **THEN** it is rejected as having an unknown key, naming the allowed `augment`

### Requirement: Class keys
A class key, the key of `[augment.exceptions]` and the part before `::` of a method key, SHALL
be one of:
- a class name, with `$` for nested classes (`com.acme.Order$Line`): it SHALL match that class only;
- a class name followed by `+` (`com.acme.Order+`): it SHALL match that class, its subclasses and, for an interface, the
  classes implementing it, without loading classes to find out;
- a pattern with `*` (any characters within one package segment), `**` (any characters across segments) and `?` (one
  character other than `.`): it SHALL match class names only, never subclasses by inheritance; `**` alone matches every
  class. `$` SHALL be an ordinary character in patterns, so `com.acme.Order*` also matches `com.acme.Order$Line`. `+`
  after a pattern SHALL be rejected.

A class key SHALL be matched against the throwable's class in `[augment.exceptions]`, and against the declaring class of
the frame's method in the other two tables.

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
and later entries SHALL NOT be combined with it: in `[augment.exceptions]` per throwable class, in `[augment.receiver]`
and `[augment.params]` per method. A method that no entry of
`[augment.receiver]` or `[augment.params]` matches SHALL use the annotations in that table's place (see the receivers and
parameters specification). The two method tables SHALL be independent: no entry of one SHALL change what the other selects.

#### Scenario: First matching exceptions entry
- **GIVEN** `"java.io.FileNotFoundException+" = false` and then `"java.io.IOException+" = true` in `[augment.exceptions]`
- **WHEN** it is loaded
- **THEN** a `FileNotFoundException` is not augmented, and the other `IOException`s are
- **AND** with the two entries the other way round, `"java.io.IOException+" = true` decides for `FileNotFoundException` too

#### Scenario: Constructor entry first
- **GIVEN** classes with `@Augmented` in `com.acme.legacy`, and `"com.acme.legacy.*::<init>" = "-"` and then `"com.acme.legacy.*::*" = "0"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** the constructors of the classes directly in `com.acme.legacy` show no parameter values, not even their annotated parameters, and their methods show their first parameter

### Requirement: Values
The values SHALL be:
- in `[augment.exceptions]`: `true` or `false`;
- in `[augment.receiver]`: a string with a field name or a no-argument method written with `()`, or several of them
  separated by commas (`"tenant, lineId()"`), or `"-"` for no receiver members;
- in `[augment.params]`: a string with parameter names and 0-based indexes from 0 to 254 (a method has at most 255
  parameters), separated by commas, each optionally followed by `#` to show the value hashed (`"order, 2, email#"`), or
  `"-"` for none. There SHALL be no value that selects all parameters.

The method tables SHALL name members and parameters only; where the annotations are read SHALL be said by `@Augmented`
alone.

### Requirement: Invalid configuration
The system SHALL reject a configuration that is not valid TOML, has an unknown key in any section (also a known key in the
wrong section), a value of the wrong type or out of range, an invalid template, a key or value that does not follow the
syntax above, the same member, parameter or method twice in one entry, or two entries of one table that name the same
classes and methods in another spelling (quoted and unquoted, other spaces, another order of methods). An error message
SHALL name the file, the key and its line, and say what is allowed; for an unknown key it SHALL list the allowed keys. An
attempt to say in `[augment.receiver]` or `[augment.params]` that the annotations are to be used (such as `"@"`) SHALL be
rejected with a pointer to `@Augmented`.

#### Scenario: Value out of range
- **GIVEN** `[augment]` with `maxValueLength = 1` on line 3 of `stack-augmentor.toml`
- **WHEN** the configuration is loaded
- **THEN** it is rejected, naming `stack-augmentor.toml`, line 3, `maxValueLength`, the allowed range and the value

#### Scenario: Unknown key
- **GIVEN** `[augment]` with `frame = "$class.$method"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming `frame`, the section `[augment]` and the allowed keys

#### Scenario: Same entry twice
- **GIVEN** `"com.acme.Order::a,b"` on line 2 and `"com.acme.Order::b, a"` on line 3 of `[augment.receiver]`, or `"java.io.IOException"` and unquoted `java.io.IOException` in `[augment.exceptions]`
- **WHEN** it is loaded
- **THEN** it is rejected with a message naming the key and the lines of both entries

#### Scenario: Not a boolean
- **GIVEN** `"java.io.IOException" = "yes"` in `[augment.exceptions]`
- **WHEN** it is loaded
- **THEN** it is rejected with a message that the value must be `true` or `false`

#### Scenario: Annotations requested in a method table
- **GIVEN** `"com.acme.**::*" = "@"` in `[augment.receiver]`, or `"com.acme.Order::ship, @" = "0"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** it is rejected with a message that `@Augmented` says where the annotations are read

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
There SHALL be three kinds of configuration files: the JVM's global one and the context ones of class loaders, which
apply to many classes, and local ones, which apply to the classes of one jar or directory. They SHALL be taken from:
- the global configuration of the JVM: the system property `stackaugmentor.config`, for the agent in both of its modes
  and for the runtime of build-time instrumentation alike;
- the context configuration of a class loader: a `stack-augmentor-context.toml` on its classpath;
- the local configuration of a jar or directory: a `stack-augmentor.toml` in it;
- with the build plugin: its arguments, the build's own configuration, a context and a local one.

A named file that does not exist SHALL be an error.

#### Scenario: Missing file
- **GIVEN** `-Dstackaugmentor.config=missing.toml` and no such file
- **WHEN** the agent starts
- **THEN** it fails with a message that `missing.toml` was not found

### Requirement: Configuration chain
At runtime, with the agent in both of its modes and with build-time instrumentation, each class SHALL use these
configurations, in this order:
1. the global configuration of the JVM;
2. the `stack-augmentor-context.toml` of the nearest class loader, from the class's own upwards, that has one its parent
   class loader does not see, otherwise that of the system class loader; a class loader SHALL have one at most, and
   several SHALL be an error;
3. the `stack-augmentor.toml` of the jar or directory the class comes from, if its class loader has one there that its
   parent does not see, or for a class instrumented at build time the module's local file (see the build-time
   instrumentation specification); a class loader MAY have several, one per jar or directory, and each SHALL apply to
   the classes of its own jar or directory only, `[augment]` included;
4. the built-in configuration.

In each of `[augment.exceptions]`, `[augment.receiver]` and `[augment.params]`, the first
matching entry of the first configuration in the chain that has one SHALL decide, so a `false` or `"-"` entry SHALL stop
the later configurations too; each other key SHALL come from the first configuration that sets it. Every file SHALL be
read the same way, wherever it comes from.

With the agent, the chain SHALL decide everything for the class; with build-time instrumentation, what the runtime
decides. With the agent, the global configuration of the JVM and the context configuration of the system class loader
SHALL be read at startup, and an invalid one SHALL stop the agent as any invalid configuration does; with build-time
instrumentation, the runtime SHALL read them when the first exception needs them, and an invalid one SHALL be reported
and leave stack traces unchanged (see the reliability specification). The others SHALL be read once per class loader, before its first
class is augmented; an invalid one SHALL be reported, the JVM SHALL keep running, and the classes it applies to SHALL stay
unchanged.

#### Scenario: Two applications in one server
- **GIVEN** the agent with a configuration that sets no format, and two applications in class loaders of their own, with classes that have `@Augmented`, each with a `stack-augmentor-context.toml`, one with `receiverFormat = "<$value>"`
- **WHEN** an augmented exception leaves an annotated method of each
- **THEN** each frame shows its receiver members in the format of its own application's configuration

#### Scenario: Local configuration of a jar
- **GIVEN** an application whose own classes come with a `stack-augmentor.toml` that sets `paramsFormat`, and a library jar without one, in the same class loader
- **WHEN** augmented exceptions leave a method of each
- **THEN** only the application's frame shows its parameter values in that format

#### Scenario: Context before local
- **GIVEN** a context configuration with `receiverFormat = "<$value>"` and a local one with `receiverFormat = "[$value]"` and `maxValueLength = 10`
- **WHEN** a frame of a class of the local configuration's jar is augmented
- **THEN** it shows its receiver members as `<…>`, with values cut off at 10 characters

#### Scenario: Spring Boot application
- **GIVEN** a Spring Boot executable jar with `stack-augmentor-context.toml`, run with the agent and another configuration
- **WHEN** an exception leaves a library method that the jar's configuration selects
- **THEN** the frame shows the parameter values that the jar's configuration selects, in the format of the agent's configuration, which applies first

#### Scenario: Several context files in one class loader
- **GIVEN** a class loader with two jars that each have a `stack-augmentor-context.toml`
- **WHEN** its first class is loaded with the agent
- **THEN** an error names both files, and the classes of that class loader stay unchanged

#### Scenario: Invalid configuration of an application
- **GIVEN** an application whose own `stack-augmentor-context.toml` has `maxValueLength = 1` on line 2
- **WHEN** its first class is loaded with the agent
- **THEN** an error names the file, line 2 and `maxValueLength`, the JVM keeps running, and that application's frames stay unchanged

### Requirement: Built-in configuration
This configuration SHALL apply last in every chain, after the files, with the default values of every other key, in the
agent, the build plugin and the runtime alike:

```toml
[augment.exceptions]
"java.lang.Exception+" = true
```

So, unless an earlier configuration decides otherwise, all exceptions (not errors) are augmented, with the annotated
receiver members and parameter values of the methods that `@Augmented` selects. A file that should not augment some
exceptions SHALL say so with `false` entries, e.g. `"**" = false`.

#### Scenario: Agent without configuration
- **GIVEN** the agent without a configuration, and a class with `@Augmented`, an `@AugmentedReceiver` field `key = "java-1"` and a method `run(@AugmentedParam int value)` that throws an `IllegalStateException`
- **WHEN** `run(42)` throws
- **THEN** the frame shows `{key=java-1}` and `{value=42}`, and no warning is printed

#### Scenario: Classes without @Augmented
- **GIVEN** no configuration file and a class with annotated members but without `@Augmented`
- **WHEN** one of its methods throws
- **THEN** its frame is unchanged

#### Scenario: File without entries
- **GIVEN** a configuration file with only `[augment]` `maxValueLength = 32`
- **WHEN** the agent starts
- **THEN** the built-in configuration decides, as without the file

### Requirement: What each phase uses
What is instrumented SHALL follow from `@Augmented`, `[augment.receiver]` and `[augment.params]`; it SHALL NOT be
configured directly. Every component SHALL validate the whole file. With the agent, the whole file SHALL apply at runtime.
With build-time instrumentation:
- the build SHALL decide, from `@Augmented`, `[augment.receiver]` and `[augment.params]`, which methods are
  augmented, which parameter values they show, and where their receiver members come from;
- the runtime SHALL apply `[augment]`, `[augment.exceptions]`, and the
  members of the `[augment.receiver]` entries the build chose (see the build-time instrumentation specification), from
  the configuration of each class (see "Configuration chain").

One context file, and one local file of the module, SHALL serve both phases of build-time instrumentation.

#### Scenario: Local file of a module in its build directories
- **GIVEN** `src/main/resources/stack-augmentor.toml` with `[augment.receiver]` entries, passed to the build plugin as the module's local file
- **WHEN** the classes run from the module's build directories, where classes and resources are in different directories
- **THEN** the frames show the receiver members of its entries

#### Scenario: One file for build-time instrumentation
- **GIVEN** `src/main/resources/stack-augmentor-context.toml` passed to the build plugin and packaged with the application
- **WHEN** the project is built and run
- **THEN** the build uses its receiver and parameter tables, and the runtime its formats, `[augment.exceptions]` and receiver members
