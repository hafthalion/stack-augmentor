# Design

## Context

Parameter ids are chosen at instrumentation time by `IdParameters.select(type, method)` in
`stack-augmentor-instrument`. It combines `@StackTraceId` on parameters (matched by name through
`IdParameters.idAnnotation`) with `AugmentorConfig.paramRefs(className, methodName)`, an exact lookup in
`Map<String, List<ParamRef>> params`. The selected parameters become constants and loads in the inlined
advice (`IdArgsMapping` and `IdArgNamesMapping`); the arrays are only built on the exception path.
`TypeMatching` decides which types are instrumented at all, and `select` is called several times per
method (type matching, the method matcher, the debug description and both offset mappings).

At runtime, `ThrowHandler` turns every captured value into a `NamedId` and `FrameFormat` renders all of
them. `@StackTraceId` currently targets `FIELD`, `METHOD` and `PARAMETER`. The Kotlin
primary-constructor case is handled by `IdResolver`, which maps an annotated constructor parameter to its
field.

## Goals / Non-Goals

**Goals:**
- `@StackTraceParam` on parameters, `@StackTraceParams` on methods and classes, and `@StackTraceId` for
  receiver ids only.
- Wildcard keys and `"*"` in `[instrument.methodParams]`, with no extra cost for exact keys.
- `[augment] maxParams`, applied at render time, without resolving ids that won't be shown.
- No change to the normal-return path (the allocation test stays as it is).

**Non-Goals:**
- Wildcards in `[instrument.classIds]`.
- Inheriting class-level `@StackTraceParams` by subclasses or nested classes (Kotlin companion objects
  included).
- An annotation attribute to override `maxParams` per method or class.
- Excluding single parameters from a method- or class-level selection.

## Decisions

### 1. The annotations
- New `api/src/main/java/com/hafnium/stackaugmentor/StackTraceParam.java`: `@Documented`,
  `@Retention(RUNTIME)`, `@Target(PARAMETER)`, with `String name() default ""`.
- New `api/src/main/java/com/hafnium/stackaugmentor/StackTraceParams.java`: `@Documented`,
  `@Retention(RUNTIME)`, `@Target({TYPE, METHOD})`, without attributes. Its Javadoc says that it selects all
  parameters of the method, or of every method declared in the class, and points to `@StackTraceParam` for
  labels.
- `StackTraceId` loses the `PARAMETER` target (`{FIELD, METHOD}`). In Kotlin,
  `class Node(@StackTraceId val name: String)` then resolves to the field, because a Java annotation cannot
  target the Kotlin `property`. `IdResolver` already finds annotated fields. Its constructor-parameter
  fallback stays, for classes compiled against the old annotation.
- Constants `IdResolver.STACK_TRACE_PARAM = "com.hafnium.stackaugmentor.StackTraceParam"` and
  `IdResolver.STACK_TRACE_PARAMS = "com.hafnium.stackaugmentor.StackTraceParams"`, next to
  `STACK_TRACE_ID`. Annotations are matched by name, as before.

*Alternatives*:
- Keep `PARAMETER` on `@StackTraceId` as deprecated. Rejected: the user chose a clean replacement, and a
  compile error points to every place that must change.
- One `@StackTraceParam` for parameters, methods and classes. Rejected (user choice): separate annotations
  make the intent visible where they are used, and the compiler rejects `name` on a method or class instead
  of ignoring it.

### 2. Parameter selection (`IdParameters.select`)
Selection order, collected into the existing `TreeMap<index, label>`:
1. if `config.honoursAnnotations(type)`:
   - `@StackTraceParams` on the method, or on `type` itself (not its supertypes): put every parameter
     with its name;
   - `@StackTraceParam` on a parameter: put it with `name` or its name. This overrides the plain name
     from the previous step.
2. `config.paramRefs(type.getName(), method.getInternalName())`: `All` puts every parameter; `ByName` and
   `ByIndex` put one parameter with `putIfAbsent`, as today, so a `@StackTraceParam(name)` label wins.

The package-private helper `idAnnotation(AnnotationList)` becomes `annotation(AnnotationList, String name)`.
Parameter selection uses `STACK_TRACE_PARAM` for parameters and `STACK_TRACE_PARAMS` for the method and
the type, and receiver detection in `TypeMatching` keeps `STACK_TRACE_ID`. `TypeMatching.usesAnnotations`
(for the "ignoring … not in annotatedClasses" debug message) also looks for `@StackTraceParams` on the type
and its methods, and for `@StackTraceParam` on their parameters. `hasIdParameters`
already goes through `select`, so class- and method-level annotations make a type instrumentable without
further changes. `isCandidate` is unchanged: constructors and synthetic, bridge, abstract and native
methods are excluded.

### 3. Configuration model for `[instrument.methodParams]`
- `ParamRef` gets a third variant, `record All() implements ParamRef`. The value `"*"` parses to
  `List.of(new ParamRef.All())`. An array keeps parsing to names and indexes, and a `"*"` inside an array is
  invalid.
- `params()` stays `Map<String, List<ParamRef>>`, keyed by the entry as written, so equality, `toString`
  and logging don't change.
- The constructor derives a private list of `ParamEntry(String key, Pattern classPattern, Pattern
  methodPattern, List<ParamRef> refs)` for the keys with `*` or `?`, the same way it derives
  `annotatedClassPatterns`. The key is split at the last `.`. Both parts use `globToRegex`: method names
  have no dots, so `*` and `**` both mean "any characters" there.
- `paramRefs(className, methodName)`: the exact map lookup first (the fast path, as today), then the
  refs of every wildcard entry whose patterns match, concatenated in configuration order.
  `hasParamEntries(className)` also checks the wildcard class patterns.
- New `static boolean isPattern(String key)`, used by `IdParameters.unmatchedEntries` to skip wildcard
  entries.
- Validation in `ConfigReader.target()` for `METHOD_PARAMS`: the class part matches
  `[\p{L}\p{N}_$.*?]+`, does not start or end with `.`, and has no `..`; the method part matches
  `[\p{L}\p{N}_$*?]+`. `paramRefs(path, value)` accepts the string `"*"` besides arrays.

*Alternative*: a per-type cache of matching entries. Not needed now: exact keys are a map lookup, and
wildcard entries are a handful of precompiled patterns evaluated at class-load or build time only.

### 4. `maxParams`
- `AugmentorConfig` gets `maxParams` (default 8, `DEFAULT_MAX_PARAMS`), `[augment] maxParams` (added to
  `AUGMENT_KEYS`), validated as 1–255 with the same message style as `maxIdLength`, plus a `Builder`
  setter.
- `FrameFormat` keeps `maxParams` (from `create(config)`; the three-string `create` overload uses the
  default, and a four-argument overload is added for tests). `ParamsTemplate.render(ids, omitted)` renders
  the first `min(ids.size, maxParams)` items and, when anything was left out, one more item `…`, joined
  with the separator and inside the prefix and suffix. The public
  `rewrite(element, receiverId, paramIds)` applies the limit itself. A new
  `rewrite(element, receiverId, paramIds, omitted)` takes an already cut list.
- `ThrowHandler` resolves only the first `format.maxParams()` values (`IdResolver.paramId` can call
  expensive `toString`s or id sources) and passes the number left out. The advice still captures every
  selected parameter, but only on the exception path, and this keeps the setting runtime-only.

*Alternative*: cap at instrumentation (`[instrument]`). Rejected (user choice): it needs a rebuild to
change, and the render-time cap costs nothing on normal returns.

### 5. Diagnostics and logging
- `IdParameters.unmatchedEntries` iterates only non-pattern entries.
- `StackAugmentorAgent.logConfiguration` prints `All` as `*`, and the format line adds
  `maxParams=<n>`.
- `TypeMatching.describe` is unchanged. It already lists each method's selected parameter labels,
  including those coming from class-level annotations or `"*"`.

### 6. Tests and examples
- Integration fixtures: every `@StackTraceId` on a parameter becomes `@StackTraceParam`, including
  `ManyParams` for the allocation test. New fixtures:
  - a method-level `@StackTraceParams` (3 parameters), one of them also with `@StackTraceParam(name)`;
  - a class-level `@StackTraceParams` (two methods, plus a subclass without its own annotation);
  - a method with 10 parameters under method-level `@StackTraceParams`, checking the `maxParams` default
    of 8 and the `…`;
  - a third-party stand-in `com.thirdparty.InventoryService`, selected by a wildcard entry
    `"com.thirdparty.Inventory*.*" = "*"` in the integration configuration;
  - an overlap case (an exact entry and a wildcard entry for `OrderService`).
- `JavaFixture` (compiled without `-parameters`) switches to `@StackTraceParam`, so the `arg<N>` case
  still holds.
- Runtime unit tests: parsing of wildcard keys, `"*"`, invalid keys and values, `maxParams` bounds, and
  `paramRefs` union and order (`AugmentorConfigTest`). `FrameFormat` truncation with the default and a
  custom `paramsFormat` (`FrameFormatTest`).
- Examples: `@StackTraceParam` in both `ClassWithAnnotation.kt`. The java-agent example gets a wildcard
  entry in its `stack-augmentor.toml` and a method-level `@StackTraceParams`, so the demo shows both.

## Risks / Trade-offs

- [Broad wildcards, e.g. `"com.**.*" = "*"`, make the agent instrument many classes] → Ignored packages
  still apply. The README warns about the cost, and the debug output lists every instrumented class, so
  over-matching is visible.
- [A class-level annotation on a Kotlin data class also covers generated methods (`copy`, `equals`,
  `component1`)] → Accepted and documented. They are real methods, and their frames only change when an
  exception leaves them.
- [Already compiled classes that use `@StackTraceId` on a parameter silently lose that parameter id] →
  Marked **BREAKING** in the proposal and the README. Recompiling surfaces every use as a compile error.
- [`select` is called several times per method, and wildcard entries add regex matches] → This happens at
  class-load or build time only, the fast path for exact keys is kept, and there's no runtime cost.
- [`…` could be confused with the truncation marker of long ids] → It's the same character with the same
  meaning ("more was left out"), used as a separate list item.

## Migration Plan

Bottom-up within one change: api → runtime (config, `ParamRef`, `FrameFormat`, `ThrowHandler`) →
instrument (`IdParameters`, `TypeMatching`) → agent logging → fixtures and examples (switch annotations,
add the new cases) → README. Users replace `@StackTraceId` on parameters with `@StackTraceParam`, and can
use `@StackTraceParams` on methods or classes to show all parameters. Their configuration files keep
working unchanged. To roll back, revert the commits.
