# Spec Delta

## MODIFIED Requirements

### Requirement: File format and structure
The configuration SHALL be a TOML file ending in `.toml`, with these keys:
- `debug` (boolean, default `false`);
- `[instrument]`: `annotatedClasses` (array of globs, default all packages), `[instrument.classIds]`
  (class name → field name or `method()`), `[instrument.methodParams]` (`"<class pattern>.<method pattern>"`
  → array of parameter names and 0-based indexes, or `"*"` for all parameters);
- `[augment]`: `frameFormat`, `receiverFormat`, `paramsFormat` (strings), `maxIdLength` (integer
  between 2 and 10000, default 64) and `maxParams` (integer between 1 and 255, default 8).

Sections, dotted keys (`augment.maxIdLength = 32`) and inline tables SHALL be equivalent. Quoted and
unquoted class names in `[instrument.classIds]` and `[instrument.methodParams]` SHALL be equivalent; keys
with wildcards SHALL be quoted, as TOML requires.

#### Scenario: Full configuration
- **GIVEN** a file with `debug`, `[instrument]`, `[instrument.classIds]`, `[instrument.methodParams]` and `[augment]` entries
- **WHEN** it is loaded
- **THEN** every value is available with its declared type

#### Scenario: Empty file
- **GIVEN** an empty configuration file
- **WHEN** it is loaded
- **THEN** all defaults apply

#### Scenario: Wildcard entry with all parameters
- **GIVEN** `"com.thirdparty.**.*Service.*" = "*"` in `[instrument.methodParams]` and `maxParams = 4` in `[augment]`
- **WHEN** it is loaded
- **THEN** the entry selects all parameters of all methods of matching classes, and at most 4 parameter ids are shown per frame

### Requirement: Invalid configuration
The system SHALL reject a configuration with a TOML syntax error, a value of the wrong type, a value out
of range, an invalid `[instrument.classIds]` or `[instrument.methodParams]` entry, or an unknown key in
any section, including keys of earlier layouts (such as `include`, `fallback`, `maxIdLength` at the top
level, or `[format]`). An `[instrument.methodParams]` entry SHALL be invalid when its key has no class or
no method part, when a part contains characters other than identifier characters, `$`, `.` (class part
only), `*` and `?`, or when its value is neither `"*"` nor a non-empty array of parameter names and
indexes. The message SHALL name the file, the key and its line.

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

#### Scenario: Invalid parameter entries
- **GIVEN** `[instrument.methodParams]` with `"com.acme.Order.process" = "all"`, or `"com.acme.Order+.process" = "*"`, or `[augment]` with `maxParams = 0`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the key and its line
