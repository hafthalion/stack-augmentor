# Design

## Context

Today `AugmentorConfig` holds three things:
- `annotatedClasses` (globs, compiled to `annotatedClassPatterns`, queried through
  `honoursAnnotations(className)`);
- `ids` (`[instrument.classIds]`, exact class name → `IdSpec.FieldSpec`/`MethodSpec`);
- `params` (`[instrument.methodParams]`, exact or wildcard keys → `ParamRef`s, with `paramRefs` and
  `PatternEntry`s).

The annotation gate is used in three places, always with the concrete class's own name:
- `IdResolver.findSource` (runtime);
- `TypeMatching.receiverRelevant`/`usesAnnotations`;
- `IdParameters.select` (instrumentation).

The configured receiver id is searched up the superclass chain first (`config.ids().get(owner.getName())`);
annotations are the fallback. The runtime handler for build-time instrumentation (`ThrowHandler()`) reads
the runtime configuration, which is often missing, so today it honours annotations everywhere by default.

## Goals / Non-Goals

**Goals:**
- One resolution rule, "the deciding `[instrument.classes]` entry of a class", used by the runtime and
  by instrumentation.
- Exact keys stay a map lookup. Pattern keys are precompiled and pre-sorted by specificity.
- Clear failure for old configurations (unknown key), and a warning when nothing is configured.

**Non-Goals:**
- Automatic migration or aliases for the old keys.
- An `[instrument.methods]` `"@"` entry enabling `@StackTraceId`: it enables parameter annotations only.
  The receiver id is always decided by `[instrument.classes]`.
- `"@"` inside an `[instrument.methods]` array (e.g. `["@", 2]`). Separate entries combine instead.

## Decisions

### 1. Configuration model
- `IdSpec` gets a third variant, `record Annotations() implements IdSpec` (parsed from `"@"`).
  `memberName()` moves from the interface to `FieldSpec`/`MethodSpec`, and callers switch on the variant.
- `ParamRef` gets a fourth variant, `record Annotations() implements ParamRef`, parsed from the
  `[instrument.methods]` value `"@"` (like `"*"` for `All`). `"*"` and `"@"` inside arrays stay invalid.
- `AugmentorConfig`:
  - `ids()` becomes `classes()` (`Map<String, IdSpec>`, keyed as written) and `params()` becomes
    `methods()`;
  - `annotatedClasses`, `annotatedClassPatterns` and `honoursAnnotations` are removed, and so are the
    matching `Builder` setters, which become `classes(...)` and `methods(...)`;
  - `hasParamEntries` becomes `hasMethodEntries`;
  - new `hasAugmentEntries()` (`!classes.isEmpty() || !methods.isEmpty()`) for the warning.
- New `public record ClassEntry(String key, IdSpec spec)` with `isPattern()`, and
  `ClassEntry classEntry(String className)`:
  - an exact map hit wins;
  - otherwise, the first matching pattern from a list sorted once in the constructor by specificity
    (`key.length()` minus the number of `*` and `?`, descending), then by key (ascending).
  - `null` when nothing matches. This only looks at the given name; walking the superclass chain is the
    caller's job, because the runtime uses `Class<?>` and instrumentation uses `TypeDescription`.
- `ConfigReader`:
  - `INSTRUMENT_KEYS = ["classes", "methods"]`, and the tables become `CLASSES` and `METHODS`;
  - class keys are validated with the existing `CLASS_PART` pattern;
  - class values accept `"@"`, `name` or `name()`, with the error message listing all three;
  - `stringArray` for `annotatedClasses` is removed. The old keys fall through `checkKeys` as unknown keys.

*Alternative*: keep `ids()` with a separate `annotationClasses` list. Rejected: the user chose a single table
where `"@"` competes with explicit values by specificity, which needs one ordered entry set.

### 2. Deciding entry along the superclass chain
- **Runtime** (`IdResolver.findSource(type)`): walk `type` and its superclasses. For the first `owner` with
  `config.classEntry(owner.getName()) != null`, return the source for that entry:
  - `FieldSpec`/`MethodSpec`: `sourceFor(owner, spec)`, looked up in `owner` and its superclasses, as
    today;
  - `Annotations`: the existing annotation search, started at `owner`.

  If no class in the chain matches, the result is `null`, or the annotation search from `type` when the
  resolver is in fallback mode (decision 4).
- A missing member warns only when `!entry.isPattern()`; for patterns, it is a debug message. The message
  keeps the format `[instrument.classes] "<key>": no field x found`.
- **Instrumentation**: a new package-private helper `ClassEntries` in `stack-augmentor-instrument` with
  `Deciding decide(TypeDescription type)`, returning `(TypeDescription owner, ClassEntry entry)` or `null`.
  It uses the existing lazy `firstInHierarchy`, which moves there from `TypeMatching`, and a
  one-element cache of the last type, because `select` asks several times per type while the type is
  transformed.
  - `TypeMatching.receiverRelevant`: an entry is found and it is explicit, or it is `Annotations` and
    `owner`'s chain has an annotated member.
  - `IdParameters.select`: the `honoursAnnotations(type.getName())` gate for `@StackTraceParam` and
    `@StackTraceParams` (on the method or the type) becomes "the deciding entry of `type` is `Annotations`,
    **or** `config.paramRefs(type, method)` contains `ParamRef.Annotations`". The loop over the configured
    refs skips `ParamRef.Annotations`, because the gate already handled it.
  - The "ignoring" debug message becomes per member. `@StackTraceId` members are ignored when the deciding
    entry is not `Annotations`. A method's parameter annotations are ignored when neither gate holds for
    that method. One message per type lists what is ignored, e.g. `ignoring @StackTraceId and the parameter
    annotations of run, stop in X: no "@" entry in [instrument.classes] or [instrument.methods] applies`.
  - `TypeMatching.describe` reasons: `receiver id from [instrument.classes] "<key>"` for explicit entries,
    `receiver id from @StackTraceId ("<key>" = "@")` for annotations, and `parameter ids only`.

### 3. Warnings for an empty instrument configuration
`StackAugmentorAgent.start` (after loading) and the `StackAugmentorByteBuddyPlugin` constructor warn when
`!config.hasAugmentEntries()`: `the configuration has no [instrument.classes] or [instrument.methods]
entries, so nothing will be augmented`. For the build plugin, this includes the no-argument constructor.
The runtime handler doesn't warn, because it doesn't decide what gets instrumented.

### 4. Runtime fallback for build-time instrumentation
`IdResolver` gets a second constructor parameter, `boolean annotationsWithoutEntry`. The agent's
`Installer` and `ThrowHandler(AugmentorConfig)` pass `false`. The `ThrowHandler()` used by `ServiceLoader`
passes `true` (through a private constructor), so build-time applications show annotated receiver ids even
without a runtime configuration, as they do today.

*Alternative*: require the runtime configuration to repeat the `"@"` entries. Rejected: a missing
`stack-augmentor.toml` at runtime would silently lose receiver ids, while parameter ids (baked in at build
time) would still show.

### 5. Logging
`StackAugmentorAgent.logConfiguration` prints `[instrument.classes]: key=@ | key=field | key=method()` and
`[instrument.methods]: …` (with `*` for `All` and `@` for `ParamRef.Annotations`), and drops the
`annotatedClasses` line. `StackAugmentorByteBuddyPlugin` logs its
`[instrument.classes]` entries instead of `annotatedClasses`.

### 6. Tests and examples
- **Runtime unit tests**:
  - `AugmentorConfigTest`: the new keys, `"@"` in both tables (`["@"]` rejected), class patterns,
    specificity and ties, old keys rejected, `hasAugmentEntries`.
  - `IdResolverTest`: the helper builds `classes` entries; add cases for the `"@"` gate, the superclass
    chain, most-specific-wins, a pattern with a missing member (no warning), and fallback mode.
  - New `ThrowHandlerTest`: the no-argument handler without a classpath configuration shows the annotated
    receiver id.
- **Instrument**: `ParameterSelectionTest` switches from `annotatedClasses` to `"@"` entries. It adds an
  explicit entry that disables annotations, a `[instrument.methods]` `"@"` entry that re-enables them for one
  method (including `@StackTraceParams` on the class), and the per-member "ignoring" message.
- **Build plugin**: a new Java test. Without an argument, the plugin matches no annotated class and warns.
- **Integration configuration**: `"com.hafnium.it.fixtures.**" = "@"`, `[instrument.classes]`,
  `[instrument.methods]`, `"java.**" = "@"`, `"com.thirdparty.*Account" = "number"`, and
  `"com.hafnium.it.fixtures.Overridden" = "getId()"`; `[instrument.methods]` `"@"` entries for
  `Overridden.failAnnotated`, `com.hafnium.it.outside.MethodAnnotationsOutside.run` and
  `com.hafnium.it.outside.ClassParamsViaMethods.*`. New stand-ins and fixtures:
  - `com.thirdparty.SavingsAccount` (open);
  - `com.hafnium.it.outside.PremiumAccount` extending it;
  - `com.hafnium.it.fixtures.Overridden` (`fail` and `failAnnotated`, each with an `@StackTraceParam`);
  - `MethodAnnotationsOutside` and the `@StackTraceParams` class `ClassParamsViaMethods` in
    `com.hafnium.it.outside`.
  - The existing `DerivedOutside` expectation becomes `DerivedOutside{baseId=b1}`.
- **Java-only child JVM tests**: `"@"` entries in their configurations, plus a run without a configuration
  that asserts the warning and an unchanged frame.
- **Examples**:
  - build-time: `"com.hafnium.**" = "@"`;
  - java-agent: `"com.hafnium.**" = "@"`, the tables renamed, and the user's uncommitted
    `"com.hafnium.ClassWithoutAnnotation.*" = "*"` kept, under `[instrument.methods]`.

## Risks / Trade-offs

- [Existing configurations stop loading] → Intended (BREAKING). The unknown-key error names the key and
  lists `classes, methods`, and the README has a migration table.
- [The agent without a configuration now does nothing] → A startup warning, and the README quick start
  shows the `"@"` entry.
- [A superclass `"@"` entry now gives subclasses in other packages the superclass's annotated receiver id
  (e.g. `DerivedOutside`)] → Follows the chosen "first class up the chain decides" rule. It's documented,
  and pinned by a test.
- [An explicit entry for a class disables that class's `@StackTraceId` and, by default, its
  `@StackTraceParam(s)`] → Follows "most specific wins". An `[instrument.methods]` entry such as
  `"com.acme.Order.*" = "@"` re-enables the parameter annotations while the explicit entry keeps the
  receiver id.
- [`"@"` method entries make `select` check a method's annotations even outside `"@"` classes] → The check
  only runs for methods the entry matches, at class-load or build time.
- [Broad `"@"` patterns such as `"**" = "@"` make the agent inspect every class's annotations] → That's
  today's cost with the default `annotatedClasses`, so nothing gets worse.
- [Runtime fallback: the build-time runtime configuration can't restrict annotations] → Not needed,
  because only classes chosen at build time are instrumented. Explicit runtime entries still apply.

## Migration Plan

Bottom-up: runtime config and model → `IdResolver`/`ThrowHandler` → instrument `ClassEntries`,
`TypeMatching`, `IdParameters` → agent and build-plugin logging and warnings → tests, fixtures and
examples → README. Users rewrite their configuration:
- `annotatedClasses = [globs]` → one `"<glob>" = "@"` per glob, or `"**" = "@"` for all packages;
- `[instrument.classIds]` → `[instrument.classes]`;
- `[instrument.methodParams]` → `[instrument.methods]`.

To roll back, revert the commits.
