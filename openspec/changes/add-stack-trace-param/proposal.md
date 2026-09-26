# Proposal

## Why

`@StackTraceId` does two different jobs: it marks the receiver id (a field or method) and it selects
parameters to show. Selecting parameters one at a time is tedious when a method's arguments are all
interesting, or when every method of a class should show its arguments. Third-party classes need the same
thing, and today each method has to be listed exactly, by class and method name. Dedicated parameter
annotations, method- and class-level selection, wildcards in the configuration, and a cap on how many
parameters a frame shows make parameter ids practical to use widely.

## What Changes

- Two new annotations in `stack-augmentor-api`:
  - `@StackTraceParam` (`com.hafnium.stackaugmentor.StackTraceParam`), on parameters only: that parameter
    is shown, and the optional `name` sets its label, as before;
  - `@StackTraceParams` (`com.hafnium.stackaugmentor.StackTraceParams`), on methods and classes, without
    attributes. On a method, all parameters of that method are shown. On a class, all parameters of every
    method declared in that class are shown.
- **BREAKING**: `@StackTraceId` no longer applies to parameters; it only marks receiver ids (a field, a
  no-argument method, or a Kotlin primary-constructor property). Code with `@StackTraceId` on a parameter no
  longer compiles and must switch to `@StackTraceParam`.
- `[instrument.methodParams]` gains wildcards and an "all parameters" value:
  - keys are `"<class pattern>.<method pattern>"`: the class pattern uses the `annotatedClasses` globs (`*`,
    `**`, `?`), and the method pattern uses `*` and `?`. For example, `"com.thirdparty.OrderService.*"`
    covers all methods of a class, and `"com.thirdparty.**.*Service.find*"` covers methods across packages;
  - the value is either the existing array of parameter names and indexes, or `"*"` for all parameters;
  - when several entries match a method, their parameters are combined.
- New `[augment] maxParams` (integer 1–255, default 8): at most that many parameter ids are shown per frame,
  in declaration order. When more are selected, the list ends with `…`, e.g. `process{a=1, b=2, …}`.
  It is read at runtime in both modes, so it can be changed without re-instrumenting.
- Debug output covers the new annotations (ignored outside `annotatedClasses`) and the new entry forms.
  Only exact `[instrument.methodParams]` entries report missing methods and parameters, so that wildcard
  entries don't flood the log.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `functional/id-sources`:
  - "Parameter ids" and "Labels" switch from `@StackTraceId` to `@StackTraceParam`, and add method- and
    class-level selection with `@StackTraceParams`, `"*"` and wildcard entries.
  - "Packages whose annotations are used" covers all three annotations.
  - The "Id values as text" scenario is updated to the new annotation.
- `functional/configuration`: the file format gains `maxParams` and the new `[instrument.methodParams]` key
  and value forms. Invalid wildcard keys and values are rejected.
- `functional/frame-format`: new requirement for the maximum number of parameter ids and the `…` marker.
- `functional/diagnostics`: debug messages for `@StackTraceParam` and `@StackTraceParams`, and
  missing-method and missing-parameter messages only for exact entries.
- `functional/java-agent`: third-party classes are selected by wildcard entries too.
- `functional/build-time-instrumentation`: the build plugin instruments classes that use any of the three
  annotations.
- `non-functional/design`: all three annotations are matched by name.
- `non-functional/packaging`: the API module contains all three annotations.

## Impact

- **Code**:
  - `stack-augmentor-api`: new `StackTraceParam` and `StackTraceParams`, and `StackTraceId` without the
    `PARAMETER` target.
  - `stack-augmentor-runtime`: `AugmentorConfig` (wildcard entries, `"*"`, `maxParams`), `ParamRef` (an
    "all" variant) and `FrameFormat` (cap and marker).
  - `stack-augmentor-instrument`: `IdParameters` and `TypeMatching` (method- and class-level annotations,
    wildcard matching).
  - `stack-augmentor-agent`: configuration logging.
- **Tests and examples**: every `@StackTraceId` on a parameter (integration fixtures, both examples,
  `JavaFixture`) switches to `@StackTraceParam`. New fixtures cover method- and class-level
  `@StackTraceParams`, wildcard and `maxParams` behaviour.
- **Docs**: the README (annotations, configuration, examples).
- **Compatibility**: configuration files stay valid, since exact entries and arrays keep their meaning.
  Source code with `@StackTraceId` on a parameter must be updated. Already compiled classes with that
  annotation on a parameter get no parameter id.
- **Unchanged**: receiver ids, `[instrument.classIds]` (no wildcards), the frame layout, and the cost of
  normal returns.
