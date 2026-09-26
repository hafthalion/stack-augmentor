# Proposal

## Why

Which classes get ids is spread over three settings: `annotatedClasses` decides where annotations count,
`[instrument.classIds]` gives receiver ids to classes you cannot annotate (exact class names only), and
`[instrument.methodParams]` selects parameters. One table per concern reads better: `[instrument.classes]`
for classes, with wildcards and `"@"` for "use this class's annotations", and `[instrument.methods]` for
methods. Making annotations opt-in per class also stops the agent from augmenting annotated classes nobody
asked for.

## What Changes

- **BREAKING**: `[instrument.methodParams]` is renamed to `[instrument.methods]`. Keys and values are
  unchanged: `"<class pattern>.<method pattern>"` mapped to parameter names and indexes, or `"*"`.
- `[instrument.methods]` also accepts the value `"@"`, e.g. `"com.acme.Order.*" = "@"`. For the methods it
  matches, `@StackTraceParam` on their parameters and `@StackTraceParams` on the method or its class are used,
  whatever the class's `[instrument.classes]` entry. So a class can take its receiver id from an exact
  explicit entry (which wins over `@StackTraceId`) and still use its parameter annotations.
- **BREAKING**: `[instrument.classIds]` is renamed to `[instrument.classes]`:
  - Keys are class names or class patterns, with the same globs as `[instrument.methods]` (`*`, `**`, `?`).
  - Values are a field name, a no-argument `method()`, or `"@"`. `"@"` means the class's `@StackTraceId`,
    `@StackTraceParam` and `@StackTraceParams` annotations are used.
  - An entry applies to the matching class and its subclasses. For a given class, the first class up its
    superclass chain that some entry matches decides. When several entries match that class, the most
    specific one wins: an exact name beats any pattern, and among patterns, the one with the most literal
    characters wins.
- **BREAKING**: `[instrument] annotatedClasses` is removed. Annotations are used only in classes whose
  deciding entry is `"@"`, and parameter annotations also in methods matched by an `[instrument.methods]`
  `"@"` entry. Without any `"@"` entry, no annotations are used. So the agent started without a
  configuration augments nothing, and neither does the build plugin without its configuration argument.
  Both print a warning when the configuration has no `[instrument.classes]` or `[instrument.methods]`
  entries.
- Explicit entries with wildcards (e.g. `"com.acme.**.*Entity" = "getId()"`) give receiver ids to whole
  groups of classes. A missing member is a warning for exact entries only, and a debug message for patterns.
- At runtime after build-time instrumentation, a class without a matching `[instrument.classes]` entry is
  treated as `"@"`: the build plugin already chose which classes to instrument, so the runtime
  configuration (often missing) must not hide their annotated receiver ids.
- The old keys (`annotatedClasses`, `classIds`, `methodParams`) are rejected as unknown keys, as other
  keys of earlier layouts already are.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `functional/configuration`: the file format (`[instrument.classes]`, `[instrument.methods]` with `"@"`,
  no `annotatedClasses`), invalid entries, and the agent without a configuration (nothing augmented, with a
  warning).
- `functional/id-sources`:
  - Receiver ids from annotations only apply to classes with an `"@"` entry.
  - "Receiver id from external configuration" moves to `[instrument.classes]`, with wildcards and
    precedence.
  - "Packages whose annotations are used" is replaced by "Classes whose annotations are used".
  - Parameter-id requirements are updated to the table rename, and `[instrument.methods]` `"@"` entries enable
    parameter annotations for the methods they match.
- `functional/diagnostics`: warnings (the renamed table, exact entries only, and an empty instrument
  configuration) and debug messages (the new tables, and annotations ignored without an `"@"` entry in
  either table).
- `functional/java-agent`: third-party classes via `[instrument.classes]` and `[instrument.methods]`,
  including wildcards.
- `functional/build-time-instrumentation`:
  - The build plugin applies `[instrument.classes]` and `[instrument.methods]`, and instruments nothing
    without a configuration.
  - The runtime handler treats classes without an entry as `"@"`.
- `functional/stack-trace-augmentation`: the rule for instrumented methods refers to `[instrument.classes]`
  instead of `annotatedClasses`.

## Impact

- **Code**:
  - `AugmentorConfig`: tables, class entries and patterns, specificity, removal of `annotatedClasses` and
    `honoursAnnotations`.
  - `IdSpec` and `ParamRef`: annotations variants (`"@"`).
  - `IdResolver`: entry resolution along the superclass chain, and the build-time fallback.
  - `ThrowHandler`: selects the fallback mode.
  - `TypeMatching` and `IdParameters`: annotations gated by `"@"` class entries, and parameter annotations
    also by `"@"` method entries.
  - `StackAugmentorAgent`, `StackAugmentorByteBuddyPlugin`: logging and the empty-configuration warning.
- **Tests**: configuration, id-resolution and parameter-selection unit tests, and the integration tests.
  In the integration configuration, `annotatedClasses` becomes a `"@"` entry and the tables are renamed.
  `DerivedOutside` now gets the receiver id of its annotated superclass `Base`, whose `"@"` entry applies
  to subclasses. The Java-only child-JVM tests need `"@"` entries in their configurations.
- **Examples**: all three TOML files. The java-agent example keeps its uncommitted
  `"com.hafnium.ClassWithoutAnnotation.*" = "*"` entry, moved to `[instrument.methods]`.
- **Docs**: the README configuration section, quick starts and upgrade notes.
- **Unchanged**: the annotations themselves, the frame format, `[augment]`, and the parameter-selection
  semantics of `[instrument.methods]`.
