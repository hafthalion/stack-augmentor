# Configuration Specification

## Purpose
Defines the TOML configuration file: where it is found, its sections and keys, and how invalid
configurations are reported.

## Requirements

### Requirement: File format and structure
The configuration SHALL be a TOML file ending in `.toml`, with these keys:
- `debug` (boolean, default `false`);
- `[augment]`: `frameFormat`, `receiverFormat`, `paramsFormat` (strings), `maxIdLength` (integer
  between 2 and 10000, default 64), `maxParams` (integer between 1 and 255, default 4) and `sensitiveParams`
  (array of parameter names that `"*#?"` and `"@#?"` hash, replacing the built-in list of secrets and personal
  data such as `password`, `token`, `email`, `phone` and `firstName`), and the tables:
  - `[augment.receiver]` (class name or class pattern → field name, `method()`, `"@"` for the class's
    `@StackTraceId`, or `"-"` for no receiver id), which decides the receiver ids;
  - `[augment.params]` (`"<class pattern>.<method pattern>"` → array of parameter names and 0-based
    indexes from 0 to 255, the JVM's maximum number of parameters, `"*"` for all parameters, `"@"` for the method's parameter annotations, or `"-"` for none;
    a `#` after a name, an index (then written as a string, e.g. `"2#"`), `"*"` or `"@"` hashes the values, and
    `"*#?"` and `"@#?"` hash those with sensitive names, see the id sources specification), which decides the parameter ids.

  Both tables default to empty. They SHALL be independent: no value in one table SHALL change what the
  other selects.

Sections, dotted keys (`augment.maxIdLength = 32`) and inline tables SHALL be equivalent. Quoted and
unquoted class names in `[augment.receiver]` and `[augment.params]` SHALL be equivalent; keys with
wildcards SHALL be quoted, as TOML requires. Two entries of one table whose keys name the same class or
method this way SHALL be rejected, naming the key and the lines of both entries, rather than one silently
replacing the other.

#### Scenario: Full configuration
- **GIVEN** a file with `debug`, `[augment.receiver]`, `[augment.params]` and `[augment]` entries
- **WHEN** it is loaded
- **THEN** every value is available with its declared type

#### Scenario: Class configured twice
- **GIVEN** `"com.acme.Order" = "a"` on line 2 and `com.acme.Order = "b"` on line 3 of `[augment.receiver]` in `stack-augmentor.toml`
- **WHEN** it is loaded
- **THEN** it is rejected with `stack-augmentor.toml, line 3: 'com.acme.Order' is configured twice; it is already configured on line 2 (quoted and unquoted keys name the same class)`

#### Scenario: Empty file
- **GIVEN** an empty configuration file
- **WHEN** it is loaded
- **THEN** all defaults apply, and no class gets ids

#### Scenario: Wildcard entry with all parameters
- **GIVEN** `"com.thirdparty.**.*Service.*" = "*"` in `[augment.params]` and `maxParams = 4` in `[augment]`
- **WHEN** it is loaded
- **THEN** the entry selects all parameters of all methods of matching classes, and at most 4 parameter ids are shown per frame

#### Scenario: Class entries
- **GIVEN** `"com.acme.**" = "@"`, `"com.acme.legacy.*" = "getKey()"` and `"com.thirdparty.Customer" = "customerId"` in `[augment.receiver]`
- **WHEN** it is loaded
- **THEN** the classes under `com.acme` use their `@StackTraceId`, classes directly in `com.acme.legacy` use `getKey()`, and `Customer` uses its `customerId` field, while no parameter annotations are used

#### Scenario: Method entry with "@"
- **GIVEN** `"com.acme.legacy.*.*" = "@"` in `[augment.params]`
- **WHEN** it is loaded
- **THEN** the methods of the classes directly in `com.acme.legacy` use their `@StackTraceParam` and `@StackTraceParams` annotations

### Requirement: Invalid configuration
The system SHALL reject a configuration with a TOML syntax error, a value of the wrong type, a value out
of range, an invalid `[augment.receiver]` or `[augment.params]` entry, an invalid `frameFormat`,
`receiverFormat` or `paramsFormat` (see the frame format specification), or an unknown key in any
section, including keys in the wrong section (such as `maxIdLength` at the top level). An `[augment.receiver]` entry SHALL
be invalid when its key contains characters other than identifier characters, `$`, `.`, `*` and `?`, or
when its value is not a field name, a `method()`, `"@"` or `"-"`. An `[augment.params]` entry SHALL be invalid
when its key has no class or no method part, when a part contains characters other than identifier
characters, `$`, `.` (class part only), `*` and `?`, or when its value is neither `"*"`, `"@"`, `"-"`, `"*#"`,
`"@#"`, `"*#?"`, `"@#?"` nor a non-empty array of parameter names and indexes, each optionally followed by `#`
(`"*"`, `"@"`, `"-"` and `#?` are not allowed inside the array). `sensitiveParams` SHALL be invalid when it is
not an array of parameter names. The message SHALL name the file, the key and its line.

#### Scenario: Value out of range
- **GIVEN** `[augment]` with `maxIdLength = 1` on line 3 of `stack-augmentor.toml`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with `stack-augmentor.toml, line 3: maxIdLength must be between 2 and 10000, was 1`

#### Scenario: Unknown key
- **GIVEN** `[augment]` with `frame = "$class.$method"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming `frame`, the section `[augment]` and the allowed keys

#### Scenario: Not a TOML file
- **GIVEN** a configuration path ending in `.properties`
- **WHEN** it is loaded
- **THEN** it is rejected with a message that the configuration must be a TOML file ending in `.toml`

#### Scenario: Invalid parameter entries
- **GIVEN** `[augment.params]` with `"com.acme.Order.process" = "all"`, or `"com.acme.Order+.process" = "*"`, or `[augment]` with `maxParams = 0`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line

#### Scenario: Invalid class entry
- **GIVEN** `[augment.receiver]` with `"com.acme.Order" = "@id"`, or `"com.acme.Ord+er" = "@"`
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
`[augment.params]` need. The formats in `[augment]` and `debug` SHALL be read at runtime in both modes, and
so SHALL `[augment.receiver]`, to find the receiver id sources of instrumented classes. One
file SHALL be usable for both phases of build-time instrumentation.

#### Scenario: One file for build-time instrumentation
- **GIVEN** `src/main/resources/stack-augmentor.toml` is passed to the build plugin and packaged as a resource
- **WHEN** the project is built and run
- **THEN** the build plugin applies its `[augment.receiver]` and `[augment.params]` tables and the runtime applies its `[augment]` section with `[augment.receiver]`
