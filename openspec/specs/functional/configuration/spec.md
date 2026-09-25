# Configuration Specification

## Purpose
Defines the TOML configuration file: where it is found, its sections and keys, and how invalid
configurations are reported.

## Requirements

### Requirement: File format and structure
The configuration SHALL be a TOML file ending in `.toml`, with these keys:
- `debug` (boolean, default `false`);
- `[instrument]`: `annotatedClasses` (array of globs, default all packages), `[instrument.classIds]`
  (class name → field name or `method()`), `[instrument.methodParams]` (`"<class>.<method>"` → array of
  parameter names and 0-based indexes);
- `[augment]`: `frameFormat`, `receiverFormat`, `paramsFormat` (strings) and `maxIdLength` (integer
  between 2 and 10000, default 64).

Sections, dotted keys (`augment.maxIdLength = 32`) and inline tables SHALL be equivalent. Quoted and
unquoted class names in `[instrument.classIds]` and `[instrument.methodParams]` SHALL be equivalent.

#### Scenario: Full configuration
- **GIVEN** a file with `debug`, `[instrument]`, `[instrument.classIds]`, `[instrument.methodParams]` and `[augment]` entries
- **WHEN** it is loaded
- **THEN** every value is available with its declared type

#### Scenario: Empty file
- **GIVEN** an empty configuration file
- **WHEN** it is loaded
- **THEN** all defaults apply

### Requirement: Invalid configuration
The system SHALL reject a configuration with a TOML syntax error, a value of the wrong type, a value out
of range, an invalid `[instrument.classIds]` or `[instrument.methodParams]` entry, or an unknown key in
any section, including keys of earlier layouts (such as `include`, `fallback`, `maxIdLength` at the top
level, or `[format]`). The message SHALL name the file, the key and its line.

#### Scenario: Value out of range
- **GIVEN** `[augment]` with `maxIdLength = 1` on line 3 of `stack-augmentor.toml`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with `stack-augmentor.toml, line 3: maxIdLength must be between 2 and 10000, was 1`

#### Scenario: Unknown key
- **GIVEN** `[augment]` with `frame = "{class}.{method}"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming `frame`, the section `[augment]` and the allowed keys

#### Scenario: Not a TOML file
- **GIVEN** a configuration path ending in `.properties`
- **WHEN** it is loaded
- **THEN** it is rejected with a message that the configuration must be a TOML file ending in `.toml`

### Requirement: Locating the configuration
With the Java agent, the configuration SHALL be taken from the agent arguments (`config=<path>` or
`<path>`), otherwise from the `stackaugmentor.config` system property, otherwise the defaults apply. With
build-time instrumentation, the build plugin SHALL read the file passed as its argument, and at runtime
the configuration SHALL be taken from the `stackaugmentor.config` system property, otherwise from
`stack-augmentor.toml` on the classpath, otherwise the defaults apply.

#### Scenario: Agent without configuration
- **GIVEN** `-javaagent:stack-augmentor-agent.jar` without arguments and without the system property
- **WHEN** the application starts
- **THEN** annotated classes in all packages are augmented with the default layout

#### Scenario: Missing file
- **GIVEN** `config=missing.toml` and no such file
- **WHEN** the agent starts
- **THEN** it fails with `Configuration file not found: missing.toml`

### Requirement: Sections by phase
`[instrument]` SHALL decide what gets instrumented: the agent reads it when classes are loaded, the build
plugin at build time. `[augment]` and `debug` SHALL be read at runtime in both modes. One file SHALL be
usable for both phases of build-time instrumentation.

#### Scenario: One file for build-time instrumentation
- **GIVEN** `src/main/resources/stack-augmentor.toml` is passed to the build plugin and packaged as a resource
- **WHEN** the project is built and run
- **THEN** the build plugin applies its `[instrument]` section and the runtime applies its `[augment]` section
