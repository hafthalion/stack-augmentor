# Configuration Specification

## Purpose
Defines the TOML configuration file: where it is found, the built-in configuration, its keys and tables, how the keys of
the tables match classes and methods, which component reads what, and which files are rejected. It owns the key syntax
and the matching rules; the other specifications refer to them.

## Requirements

### Requirement: File and keys
The configuration SHALL be a TOML file ending in `.toml`, with these keys and defaults:
- `debug` (boolean, default `false`): diagnostics, see the diagnostics specification;
- `inPlaceModification` (boolean, default `false`): write augmented frames into the throwable's own stack trace instead of
  copying it, see the performance specification; the system property `stackaugmentor.inPlaceModification` SHALL override it;
- `[augment]`: `frameFormat`, `receiverFormat`, `paramsFormat` (templates, see the frame format specification) and
  `maxValueLength` (integer from 2 to 10000, default 64, see the receivers and parameters specification);
- `[augment.exceptions]` (class key → `true` or `false`): which throwables are augmented, see the stack trace augmentation
  specification;
- `[augment.annotations]` (class key → `true` or `false`): in which declaring classes the annotations are read, see the
  receivers and parameters specification;
- `[augment.receiver]` (method key → members): the receiver members by name;
- `[augment.params]` (method key → parameters): the parameter values by name or index.

The four tables SHALL default to empty. Without entries, `[augment.exceptions]` augments no throwable and
`[augment.annotations]` reads no annotations. Sections, dotted keys (`augment.maxValueLength = 32`) and inline tables
SHALL be equivalent, and the order of the tables SHALL NOT matter; the order of the entries within a table SHALL (see
"First matching entry"), so every table SHALL be read in the order of the file.

#### Scenario: Full configuration
- **GIVEN** a file with `debug`, `inPlaceModification`, `[augment]`, `[augment.exceptions]`, `[augment.annotations]`, `[augment.receiver]` and `[augment.params]` entries
- **WHEN** it is loaded
- **THEN** every value is available with its declared type, and the entries of each table in the order of the file

#### Scenario: Empty file
- **GIVEN** an empty configuration file
- **WHEN** it is loaded
- **THEN** the default values apply, the built-in configuration does not, and nothing is augmented

### Requirement: Class keys
A class key, the key of `[augment.exceptions]` and `[augment.annotations]` and the part before `::` of a method key, SHALL
be one of:
- a class name, dotted identifier segments with `$` for nested classes (`com.acme.Order$Line`): it SHALL match that class only;
- a class name followed by `+` (`com.acme.Order+`): it SHALL match that class, its subclasses and, for an interface, the
  classes implementing it, as `instanceof` does, comparing the names of the supertypes so that no class is loaded;
- a pattern with `*` (any characters within one segment), `**` (any characters across segments) and `?` (one character
  other than `.`):
  it SHALL match the names of classes only, never their subclasses by inheritance. `**` alone matches every class. `+`
  after a pattern SHALL be rejected.

What a class key is matched against SHALL be said by its table: the throwable's runtime class for `[augment.exceptions]`,
the declaring class of the frame's method for the other three tables.

#### Scenario: Class name, plus and pattern
- **GIVEN** the keys `"java.io.IOException"`, `"java.io.IOException+"` and `"java.io.IO*"` in `[augment.exceptions]`, each alone with `true`
- **WHEN** a `FileNotFoundException` leaves a configured method
- **THEN** only with `"java.io.IOException+"` is it augmented

### Requirement: Method keys
The keys of `[augment.receiver]` and `[augment.params]` SHALL have the same syntax, `"<class key>::<methods>"`: `::` is
required, and the methods are names or patterns separated by commas, without duplicates or empty items, where `*` alone
stands for all methods, `*` within a name for any characters and `?` for one character. A key SHALL match a method when its
class key matches the method's declaring class and one of its methods matches the method's name, every overload included.
The constructors are named `<init>`, allowed in `[augment.params]` keys only, as constructors show no receiver members;
wildcards in the method part SHALL match `<init>` like any name. Spaces around the commas SHALL NOT matter. One class MAY
have several entries with different methods.

#### Scenario: Several methods
- **GIVEN** `"com.thirdparty.Parcel::ship, cancel*" = "trackingCode"` in `[augment.receiver]`
- **WHEN** exceptions leave `ship()`, `cancelLater()` and `weigh()` of a `Parcel`
- **THEN** the entry matches `ship` and `cancelLater`, not `weigh`

#### Scenario: Wildcard entry with named parameters
- **GIVEN** `"com.thirdparty.**.*Service::*" = "order, 1"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** the entry selects the parameter `order` and the second parameter of all methods and constructors of matching classes

### Requirement: First matching entry
In each of the four tables, the first entry in the order of the file that matches SHALL decide, however specific the later
ones are, and later entries SHALL NOT be combined with it: for `[augment.exceptions]` per throwable, for
`[augment.annotations]` per declaring class, and for `[augment.receiver]` and `[augment.params]` per method. A method that
no entry of `[augment.receiver]` or `[augment.params]` matches SHALL use the annotations in that table's place (see
"Annotations in use" in the receivers and parameters specification). The two method tables SHALL be independent: no
entry of one SHALL change what the other selects.

#### Scenario: First matching annotations entry
- **GIVEN** `"com.acme.generated.**" = false` and then `"com.acme.**" = true` in `[augment.annotations]`
- **WHEN** it is loaded
- **THEN** the classes under `com.acme.generated` read no annotations, and the other classes under `com.acme` read them
- **AND** with the two entries the other way round, `"com.acme.**" = true` decides for the classes under `com.acme.generated` too

#### Scenario: Constructor entry
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, and `"com.acme.legacy.*::<init>" = "-"` and then `"com.acme.legacy.*::*" = "0"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** the constructors of the classes directly in `com.acme.legacy` show no parameter values, not even their annotated parameters; the `<init>` entry comes first and wins over `*`, and their methods show their first parameter

#### Scenario: Class entries
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, and `"com.acme.legacy.*::*" = "getKey()"` and `"com.thirdparty.Customer::*" = "customerId"` in `[augment.receiver]`
- **WHEN** it is loaded
- **THEN** the classes directly in `com.acme.legacy` use `getKey()`, the other classes under `com.acme` with `@Augmented` their `@AugmentedReceiver` members and their parameter annotations, and `Customer` uses its `customerId` field

### Requirement: Values
The values SHALL be:
- `[augment.exceptions]` and `[augment.annotations]`: `true` or `false`;
- `[augment.receiver]`: a string with a field name or a no-argument method written with `()`, or several of them
  separated by commas (`"tenant, lineId()"`), or `"-"` for no receiver members;
- `[augment.params]`: a string with parameter names and 0-based indexes from 0 to 255 (the JVM's maximum number of
  parameters), separated by commas, each optionally followed by `#` to hash the value (`"order, 2, email#"`), or `"-"`
  for none. There SHALL be no value for all parameters.

The method tables SHALL name members and parameters only: where the annotations are read is said by
`[augment.annotations]`, so `"@"` SHALL NOT be a value and `@` SHALL NOT be a method in their keys.

#### Scenario: Annotations of some classes
- **GIVEN** `"com.acme.legacy.*" = true` in `[augment.annotations]`
- **WHEN** it is loaded
- **THEN** in the classes directly in `com.acme.legacy`, the methods and constructors that `@Augmented` selects use their annotations

### Requirement: Invalid configuration
The system SHALL reject, with a message that names the file, the key and its line:
- a file whose name does not end in `.toml`, and a TOML syntax error;
- an unknown key in any section, including a key in the wrong section (such as `maxValueLength` at the top level); the
  message SHALL name the section and the allowed keys;
- a value of the wrong type, including a TOML array in `[augment.receiver]` or `[augment.params]`, and a value out of range;
- an invalid template (see the frame format specification);
- a class key that is not as in "Class keys", e.g. `+` after a pattern or inside a name;
- a method key without `::`, with an empty, duplicate or invalid method (e.g. `ship()`), or with `<init>` in `[augment.receiver]`;
- an `[augment.exceptions]` or `[augment.annotations]` value other than `true` or `false`;
- an `[augment.receiver]` value with an empty item, the same member twice, an item that is not a field name or a
  `method()`, or `::` (the methods go in the key);
- an `[augment.params]` value with an empty item, an index above 255, `*`, `-` among other items, or the same name or
  index twice, hashed or not (a name and the index of the same parameter are allowed, and the parameter is shown once);
- `"@"` as a value, or `@` in the method part of a key, of `[augment.receiver]` or `[augment.params]`, with `"@" is not
  allowed here: these tables name members and parameters, and [augment.annotations] says where the annotations are read,
  e.g. "com.acme.**" = true`;
- two entries of one table whose keys differ only in quoting (`com.acme.Order` and `"com.acme.Order"`), in spaces or in
  the order of their methods (`"com.acme.Order::a,b"` and `"com.acme.Order::b, a"`), naming the lines of both.

Keys with `::`, `+` or wildcards SHALL be quoted, as TOML requires; unquoted class names in `[augment.exceptions]` and
`[augment.annotations]` SHALL be equivalent to quoted ones.

#### Scenario: Class configured twice
- **GIVEN** `"com.acme.Order::a,b" = "x"` on line 2 and `"com.acme.Order::a, b" = "y"` on line 3 of `[augment.receiver]` in `stack-augmentor.toml`
- **WHEN** it is loaded
- **THEN** it is rejected with `stack-augmentor.toml, line 3: 'com.acme.Order::a, b' is configured twice; it is already configured on line 2 (keys that differ in spaces or in the order of their methods name the same methods)`

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
- **GIVEN** `[augment.params]` with `"com.acme.Order::process" = ["order"]`, `"com.acme.Order::process" = "order,"`, `"com.acme.Order::process" = "*"`, `"com.acme.Order::process" = "email, email#"`, `"com.acme.Ord+er::process" = "0"`, or `"com.acme.Order.process" = "0"` (no `::`)
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line

#### Scenario: Invalid receiver entries
- **GIVEN** `[augment.receiver]` with `"com.acme.Order::*" = "@id"`, `"com.acme.Order::*" = ["id"]`, `"com.acme.Order::*" = "id, @"`, `"com.acme.Order::*" = "id :: ship"` (the methods go in the key), `"com.acme.Order" = "id"` (no `::`), `"com.acme.Order::" = "id"`, `"com.acme.Order::ship()" = "id"`, `"com.acme.Order::<init>" = "id"`, `"com.acme.Order::ship, ship" = "id"`, `"com.acme.*+::*" = "id"`, or `"com.acme.Ord+er::*" = "id"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line

### Requirement: Locating the configuration
The configuration file SHALL be taken from:
- with the Java agent, also in the live-stack mode: the agent arguments (`config=<path>` or `<path>`), otherwise the
  `stackaugmentor.config` system property;
- with the build plugin: its argument 0;
- with the runtime of classes instrumented at build time: the `stackaugmentor.config` system property, otherwise the
  classpath resource `stack-augmentor.toml`, looked up through the runtime jar's class loader and, when that does not find
  it, through the context class loader of the thread that creates the handler, i.e. the thread of the first exception (in
  an application server or a fat jar the runtime jar can be loaded by a parent of the application's class loader).

Without a file, each of them SHALL apply the built-in configuration. A file that does not exist SHALL be an error.

#### Scenario: Configuration visible only to the application's class loader
- **GIVEN** a build-time instrumented application whose `stack-augmentor.toml` is visible to the context class loader of the throwing thread, but not to the class loader of `stack-augmentor-runtime`
- **WHEN** the first exception leaves an instrumented method
- **THEN** the runtime handler uses that `stack-augmentor.toml`

#### Scenario: Missing file
- **GIVEN** `config=missing.toml` and no such file
- **WHEN** the agent starts
- **THEN** it fails with `Configuration file not found: missing.toml`

### Requirement: Built-in configuration
The built-in configuration SHALL be the classpath resource
`com/hafnium/stackaugmentor/runtime/config/stack-augmentor-defaults.toml` of `stack-augmentor-runtime`, which the agent jar
contains too. It SHALL list every key with its default value, and add these entries:

```toml
[augment.exceptions]
"java.lang.Exception+" = true

[augment.annotations]
"**" = true
```

It SHALL apply only when no file is given; a file SHALL replace it completely, also where it leaves a table out.

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

### Requirement: Sections by phase
What gets instrumented SHALL NOT be configured directly: the agent, when classes are loaded, and the build plugin, at build
time, SHALL instrument the classes and methods that `[augment.annotations]`, `[augment.receiver]` and `[augment.params]`
need. Each component SHALL validate the whole file, and read:

| Component | Reads |
|---|---|
| Java agent, also in the live-stack mode | everything, at JVM start |
| Build plugin | `[augment.annotations]`, `[augment.receiver]`, `[augment.params]`, `debug` |
| Runtime of build-time classes | `[augment]`, `[augment.exceptions]`, `[augment.receiver]` (by the key that decided a method at build time), `debug`, `inPlaceModification` |

The runtime of build-time classes SHALL NOT check `[augment.annotations]` again, and takes the parameters from the build
(see the build-time instrumentation specification). One file SHALL be usable for both phases of build-time instrumentation.

#### Scenario: One file for build-time instrumentation
- **GIVEN** `src/main/resources/stack-augmentor.toml` is passed to the build plugin and packaged as a resource
- **WHEN** the project is built and run
- **THEN** the build plugin applies its `[augment.annotations]`, `[augment.receiver]` and `[augment.params]` tables and the runtime applies its `[augment]` section with `[augment.exceptions]` and `[augment.receiver]`
