# Configuration Specification

## Purpose
Defines the TOML configuration file: where it is found, its sections and keys, and how invalid
configurations are reported.

## Requirements

### Requirement: File format and structure
The configuration SHALL be a TOML file ending in `.toml`, with these keys:
- `debug` (boolean, default `false`);
- `[augment]`: `frameFormat`, `receiverFormat`, `paramsFormat` (strings), `maxIdLength` (integer
  between 2 and 10000, default 64) and `maxParams` (integer between 1 and 255, default 4), and the tables:
  - `[augment.classes]` (class name or class pattern → field name, `method()`, `"@"` for the class's
    `@StackTraceId`, or `"-"` for no receiver id), which decides the receiver ids;
  - `[augment.methods]` (`"<class pattern>.<method pattern>"` → array of parameter names and 0-based
    indexes, `"*"` for all parameters, `"@"` for the method's parameter annotations, or `"-"` for none),
    which decides the parameter ids.

  Both tables default to empty. They SHALL be independent: no value in one table SHALL change what the
  other selects.

Sections, dotted keys (`augment.maxIdLength = 32`) and inline tables SHALL be equivalent. Quoted and
unquoted class names in `[augment.classes]` and `[augment.methods]` SHALL be equivalent; keys with
wildcards SHALL be quoted, as TOML requires.

#### Scenario: Full configuration
- **GIVEN** a file with `debug`, `[augment.classes]`, `[augment.methods]` and `[augment]` entries
- **WHEN** it is loaded
- **THEN** every value is available with its declared type

#### Scenario: Empty file
- **GIVEN** an empty configuration file
- **WHEN** it is loaded
- **THEN** all defaults apply, and no class gets ids

#### Scenario: Wildcard entry with all parameters
- **GIVEN** `"com.thirdparty.**.*Service.*" = "*"` in `[augment.methods]` and `maxParams = 4` in `[augment]`
- **WHEN** it is loaded
- **THEN** the entry selects all parameters of all methods of matching classes, and at most 4 parameter ids are shown per frame

#### Scenario: Class entries
- **GIVEN** `"com.acme.**" = "@"`, `"com.acme.legacy.*" = "getKey()"` and `"com.thirdparty.Customer" = "customerId"` in `[augment.classes]`
- **WHEN** it is loaded
- **THEN** the classes under `com.acme` use their `@StackTraceId`, classes directly in `com.acme.legacy` use `getKey()`, and `Customer` uses its `customerId` field, while no parameter annotations are used

#### Scenario: Method entry with "@"
- **GIVEN** `"com.acme.legacy.*.*" = "@"` in `[augment.methods]`
- **WHEN** it is loaded
- **THEN** the methods of the classes directly in `com.acme.legacy` use their `@StackTraceParam` and `@StackTraceParams` annotations

### Requirement: Invalid configuration
The system SHALL reject a configuration with a TOML syntax error, a value of the wrong type, a value out
of range, an invalid `[augment.classes]` or `[augment.methods]` entry, or an unknown key in any
section, including keys of earlier layouts (such as `annotatedClasses`, `classIds`, `methodParams`,
`include`, `fallback`, `maxIdLength` at the top level, or `[format]`). An `[augment.classes]` entry SHALL
be invalid when its key contains characters other than identifier characters, `$`, `.`, `*` and `?`, or
when its value is not a field name, a `method()` or `"@"`. An `[augment.methods]` entry SHALL be invalid
when its key has no class or no method part, when a part contains characters other than identifier
characters, `$`, `.` (class part only), `*` and `?`, or when its value is neither `"*"`, `"@"` nor a
non-empty array of parameter names and indexes (`"*"` and `"@"` are not allowed inside the array). The message SHALL name the file, the key and its line.

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
- **GIVEN** `[augment.methods]` with `"com.acme.Order.process" = "all"`, or `"com.acme.Order+.process" = "*"`, or `[augment]` with `maxParams = 0`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line

#### Scenario: Keys of the previous layout
- **GIVEN** an `[instrument]` section, e.g. `[instrument.classes]`, `[instrument.methods]` or `annotatedClasses = ["com.acme.**"]` in `[instrument]`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message that its tables are now `[augment.classes]` and `[augment.methods]`

#### Scenario: Invalid class entry
- **GIVEN** `[augment.classes]` with `"com.acme.Order" = "@id"`, or `"com.acme.Ord+er" = "@"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line

### Requirement: Locating the configuration
With the Java agent, the configuration SHALL be taken from the agent arguments (`config=<path>` or
`<path>`), otherwise from the `stackaugmentor.config` system property, otherwise the defaults apply. With
build-time instrumentation, the build plugin SHALL read the file passed as its argument, and at runtime
the configuration SHALL be taken from the `stackaugmentor.config` system property, otherwise from
`stack-augmentor.toml` on the classpath, otherwise the defaults apply. The defaults contain no
`[augment.classes]` or `[augment.methods]` entries, so the agent with the defaults augments nothing.

#### Scenario: Agent without configuration
- **GIVEN** `-javaagent:stack-augmentor-agent.jar` without arguments and without the system property
- **WHEN** the application starts
- **THEN** no class is augmented, and a warning says that the configuration has no `[augment.classes]` or `[augment.methods]` entries

#### Scenario: Missing file
- **GIVEN** `config=missing.toml` and no such file
- **WHEN** the agent starts
- **THEN** it fails with `Configuration file not found: missing.toml`

### Requirement: Sections by phase
What gets instrumented SHALL NOT be configured directly: the agent, when classes are loaded, and the build
plugin, at build time, SHALL instrument the classes and methods that `[augment.classes]` and
`[augment.methods]` need. The formats in `[augment]` and `debug` SHALL be read at runtime in both modes, and
so SHALL `[augment.classes]`, to find the receiver id sources of instrumented classes and of arguments. One
file SHALL be usable for both phases of build-time instrumentation.

#### Scenario: One file for build-time instrumentation
- **GIVEN** `src/main/resources/stack-augmentor.toml` is passed to the build plugin and packaged as a resource
- **WHEN** the project is built and run
- **THEN** the build plugin applies its `[augment.classes]` and `[augment.methods]` tables and the runtime applies its `[augment]` section with `[augment.classes]`
