# Proposal

## Why

Every published stack-augmentor module except the bridge is compiled by the Kotlin plugin, so
`stack-augmentor-api` and `stack-augmentor-runtime` bring `kotlin-stdlib` onto the classpath of every
application that uses build-time instrumentation, and the agent jar carries a relocated copy of it. A Java
application should not need the Kotlin runtime to show ids in its stack traces. Writing everything that
runs in the application (or in the build) in Java removes that dependency. Examples and tests do not
ship, so they can stay in Kotlin.

## What Changes

- Rewrite the published modules in Java: `stack-augmentor-api` (`@StackTraceId`), `stack-augmentor-runtime`
  (`AugmentorConfig`, `FrameFormat`, `IdResolver`, `Log`, `ThrowHandler`, `WeakIdentityMap`),
  `stack-augmentor-instrument` (`IdParameters`, `TypeMatching`, the offset mappings, `exitAdvice`),
  `stack-augmentor-agent` (`StackAugmentorAgent`, `Installer`, `BridgeInjector`) and
  `stack-augmentor-build-plugin` (`StackAugmentorBuildPlugin`). The bridge and the advice classes are
  already in Java.
- Stop applying the Kotlin Gradle plugin to published modules, except where a module keeps Kotlin tests.
  There, `kotlin-stdlib` goes on the test classpath only.
- **BREAKING** (JVM-level API of runtime classes): the Kotlin data and sealed classes become Java
  records, sealed interfaces and final classes. Anyone calling `AugmentorConfig`, `IdSpec`, `ParamRef`,
  `NamedId`, `FrameFormat`, `IdResolver` or `Log` directly sees new constructors and accessors.
  Configuration files, the annotation and stack trace output stay the same.
- The agent jar no longer contains or relocates Kotlin. `kotlin` stays in the agent's list of ignored
  packages, because that list is about the application's classes.
- Add a build check that fails when `kotlin-stdlib` appears on the runtime classpath of a published
  module, or when a `kotlin/` class appears in the agent jar.
- Keep in Kotlin: the examples, the integration tests (`stack-augmentor-it`), and the runtime's unit
  tests, which need Kotlin fixtures such as primary-constructor properties. They are adjusted to the Java
  API.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `non-functional/toolchain`: *Languages* now requires Java for every published module and allows Kotlin
  only in examples and tests. *Dependencies* drops the Kotlin standard library from the implementation's
  dependencies and from the agent's relocations. A new requirement adds the check that no Kotlin runtime
  reaches a published module.
- `non-functional/packaging`: *Runtime dependencies of build-time instrumentation* no longer lists the
  Kotlin standard library. The API artifact has no dependencies.
- `non-functional/compatibility`: *No conflicts with the application's dependencies* no longer shades
  Kotlin. A new requirement covers Java applications without the Kotlin runtime, in both modes.
- `non-functional/design`: *Advice and bridge written in Java* is widened to all published modules.
  *Configuration model* describes an immutable Java class instead of a Kotlin data class. *Logging without
  a framework* uses `Supplier<String>` messages instead of Kotlin inline lambdas, and still never builds
  disabled debug messages.

## Impact

- **Code**: every `src/main/kotlin` directory under `stack-augmentor-api`, `-runtime`, `-instrument`,
  `-agent` and `-build-plugin` moves to `src/main/java`. The runtime's Kotlin unit tests change to use the
  Java API (constructors or a builder instead of named arguments, record accessors).
- **Build**: `build.gradle.kts` of those modules, `gradle.properties` (turn off Kotlin's automatic
  stdlib dependency), the shadow configuration (no Kotlin relocation), and a new verification task
  wired into `check`.
- **Dependencies**: `kotlin-stdlib` is removed from the runtime classpath of `stack-augmentor-api`,
  `-instrument-bridge`, `-runtime`, `-instrument`, `-agent` and `-build-plugin`. ByteBuddy, tomlj and
  ANTLR stay.
- **Docs**: the README and the project context in `openspec/config.yaml` say Java for the published
  modules and Kotlin for examples and tests.
- **Unchanged**: configuration format, annotation semantics (including Kotlin primary-constructor
  properties), frame output, the agent's command line, the build plugin's name and arguments.
