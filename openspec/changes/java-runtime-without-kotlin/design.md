# Design

## Context

Six modules are published. `stack-augmentor-instrument-bridge` is plain Java. The other five apply
`org.jetbrains.kotlin.jvm`, which adds `kotlin-stdlib` as a dependency whether or not any Kotlin code needs
it. The Java parts so far are `Dispatch`, `ExitAdvice`, `IdArgs` and `IdArgNames`. The Kotlin code is
about 1,100 lines: `AugmentorConfig`, `FrameFormat`, `IdResolver`, `Log`, `ThrowHandler`,
`WeakIdentityMap` (runtime), `Instrumentation.kt` (instrument), `StackAugmentorAgent.kt`/`Installer.kt`
(agent), `StackAugmentorBuildPlugin` and the `StackTraceId` annotation. The agent's shadow jar relocates
`kotlin` to `com.hafnium.stackaugmentor.shaded.kotlin`.

Outside the published modules, only the `@StackTraceId` annotation is used, by the examples and the
integration tests. The runtime's unit tests (Kotlin) use `AugmentorConfig`, `IdSpec`, `ParamRef`,
`NamedId`, `FrameFormat` and `IdResolver` directly. `IdResolverTest` needs Kotlin fixtures, such as a
primary-constructor property.

## Goals / Non-Goals

**Goals:**
- A 1:1 port: the same classes, packages, responsibilities and behaviour, written in Java 25.
- No `org.jetbrains.kotlin` artifact on the runtime classpath of any published module, and no Kotlin
  classes in the agent jar, enforced by the build.
- A Java-only run of each mode in the tests.

**Non-Goals:**
- Converting the examples, the integration tests or the runtime's unit tests to Java.
- Redesigning class boundaries, the configuration format, or the frame output.
- Publishing to a repository (there is no publishing setup yet). "Published module" means the six
  library modules.

## Decisions

### 1. Java language level and idioms
Use the JDK 25 toolchain that is already configured. Kotlin constructs map as follows:
- `data class` → `record` (`NamedId`, `IdParameter`, `IdSpec.FieldSpec`/`MethodSpec`,
  `ParamRef.ByName`/`ByIndex`).
- `sealed interface` → `sealed interface ... permits`, and `when (x)` → pattern-matching `switch`.
- `object` → a final class with static members and a private constructor (`Log`, `StackAugmentorAgent`,
  `Installer`, `BridgeInjector`).
- `internal` → package-private. `WeakIdentityMap` stays package-private in `runtime`. The
  `idAnnotation` helper in `instrument` becomes a package-private static method.
- Top-level `const val STACK_TRACE_ID` → `public static final String STACK_TRACE_ID` in `IdResolver`.
  The instrument module imports it from there.
- Top-level `fun exitAdvice(parameters)` → a static factory `ExitAdviceFactory.create(IdParameters)` in
  `com.hafnium.stackaugmentor.instrument`. `Instrumentation.kt` is split into one Java file per public
  class.
- `Regex` → `java.util.regex.Pattern`, `sequence {}` → loops, and Kotlin string helpers → the JDK
  equivalents.

Keep `null` for "absent", as the Kotlin code does (`receiverId` returns `null`). Don't introduce
`Optional`, which would add allocations on the exception path.

*Alternative considered*: a redesign, for example splitting `AugmentorConfig`. Rejected: a straight port
keeps the review diff readable and the specs unchanged.

### 2. `AugmentorConfig` as a final class with a builder, not a record
It keeps the compiled `annotatedClasses` patterns as derived state, and a record cannot have
non-component fields. So it becomes a final immutable class:
- a public canonical constructor (defensive `List.copyOf`/`Map.copyOf`), and a public no-argument
  constructor for the defaults;
- a `Builder` (`AugmentorConfig.builder()...build()`), used by `ConfigReader` and by the Kotlin tests in
  place of named arguments;
- record-style accessors (`frameFormat()`, `debug()`, ...), plus `equals`/`hashCode`/`toString` over the
  components only.

`ConfigReader` becomes a private static nested class. Kotlin's `inline reified value<T>` becomes
`value(path, Class<T>, expected)`. `load(String agentArgs)` and `load(Path)` keep their names. Java callers
passing `null` cast it explicitly.

*Alternatives*: a record that recompiles the globs on every call, which is too slow because
`honoursAnnotations` runs for every class loaded; or a record with a static pattern cache, which adds
hidden global state.

### 3. `Log`: `Supplier<String>` plus explicit guards
`Log.debug(Supplier<String>)` checks the volatile flag before calling the supplier. `Log.warn(String)`
prints right away. Kotlin inlining meant a disabled call cost one field read. In Java a capturing lambda
is allocated even when the flag is off, so calls on paths that run for every loaded class or every
handled exception (`TypeMatching.instrument`, `ThrowHandler.onThrow`, the transformer in `Installer`)
are wrapped in `if (Log.isDebug())`. Startup-only calls don't need the guard.

*Alternative*: `debug(String format, Object... args)`. Rejected: varargs allocate an array, and boxing
allocates too.

### 4. `@StackTraceId` as a Java annotation
`@Target({FIELD, METHOD, PARAMETER}) @Retention(RUNTIME) @Documented`, with `String name() default ""`.
From Kotlin, Java's `METHOD` covers both `FUNCTION` and `PROPERTY_GETTER`, and `PARAMETER` covers
`VALUE_PARAMETER`. So `@StackTraceId val x` in a class body still lands on the field, and on a
primary-constructor `val` it still lands on the constructor parameter, which `IdResolver` already maps to
the field. `name` is not `value`, so Kotlin callers keep writing `@StackTraceId(name = "...")`, as all
current call sites do.

### 5. Build setup
- `stack-augmentor-api`, `-instrument`, `-agent` and `-build-plugin`: `java-library` (or `java` plus
  shadow) only, with no Kotlin plugin. Java compiles with `-parameters`, so our own classes keep their
  parameter names, as `javaParameters = true` did.
- `stack-augmentor-runtime` keeps the Kotlin plugin for its test source set only; its main sources are
  Java. The root `gradle.properties` sets `kotlin.stdlib.default.dependency=false`, and every module that
  compiles Kotlin (runtime tests, `stack-augmentor-it`, both examples) declares
  `kotlin-stdlib` explicitly: `testImplementation` for tests, `implementation` for the examples. A
  `kotlin-stdlib` entry with `version.ref = "kotlin"` is added to the catalog.
  *Alternative*: a module-level `gradle.properties` in the runtime module only. Rejected: it isn't
  reliably visible to plugins that read Gradle properties through `providers.gradleProperty`.
- Agent shadow jar: drop `relocate("kotlin", ...)` and the `*.kotlin_module` exclusion. `IGNORED_PACKAGES`
  keeps `"kotlin"`, because it stops the agent from instrumenting the application's Kotlin classes.

### 6. The "no Kotlin runtime" check
A `verifyNoKotlinRuntime` task registered by the root build script in each published module, wired into
`check`:
- it resolves `runtimeClasspath` through `incoming.artifacts.resolvedArtifacts`, a provider that keeps
  the task configuration-cache friendly, and fails on any component in group `org.jetbrains.kotlin`,
  naming the module and the artifact;
- in `stack-augmentor-agent` it also opens the shadow jar and fails on entries under `kotlin/` or
  `com/hafnium/stackaugmentor/shaded/kotlin/`.

*Alternative*: a unit test that inspects the classpath. Rejected: it would itself run with Kotlin on the
test classpath, and it could not see the published configuration.

### 7. Java-only end-to-end tests
- **Agent**: `stack-augmentor-it` gets a Java `JavaMain` in `src/test/java` that calls `JavaFixture` and
  prints the stack trace. A new test starts a child JVM with `-javaagent:<shadow jar>`, `debug = true` and
  the classpath `build/classes/java/test` + the API jar. That classpath has no Kotlin, because Gradle
  compiles Kotlin test classes to a separate directory. The test asserts the rewritten frame and the
  absence of `kotlin/` class-loading errors. Gradle passes the paths as system properties.
- **Build time**: `examples/build-time` gets a Java class in `src/main/java`, instrumented by
  `byteBuddyJava`. A test starts a child JVM with that class's output directory plus the runtime
  classpath of `stack-augmentor-api` and `stack-augmentor-runtime` (no Kotlin), and asserts the frame.

## Risks / Trade-offs

- [Behaviour drift during the port, e.g. glob escaping, `…` truncation, line-break replacement, the frame
  prefix logic] → Port file by file, keeping the existing runtime unit tests and integration tests as the
  oracle. Run them after each module is ported, and change only how the tests construct objects, never
  what they assert.
- [Kotlin's default annotation target rules (`param`/`property`/`field`) resolve differently for a Java
  annotation] → Covered by the existing IT and `IdResolverTest` cases for class-body properties,
  primary-constructor properties and getters. Section 4 explains why the result is unchanged.
- [Turning off `kotlin.stdlib.default.dependency` breaks a Kotlin module that doesn't declare the stdlib]
  → Every Kotlin-compiling module is listed in the tasks, and `./gradlew build` catches any that were
  missed.
- [Capturing lambdas on hot paths add allocations] → Guard with `Log.isDebug()` (Decision 3). The existing
  allocation test covers the normal-return path. The exception path has no allocation limit.
- [Tests that construct runtime classes need changes] → Mechanical: builder calls and `()` accessors.
  Assertions stay the same.
- [Binary compatibility of the runtime classes' JVM API] → Accepted. Marked **BREAKING** in the proposal.
  Nothing outside this repository is known to call them.

## Migration Plan

This is a single change on `master`, ported bottom-up so the build stays green between steps: api →
runtime → instrument → build-plugin → agent → build setup and checks → Java-only tests → docs. There
is nothing to deploy; to roll back, revert the commits. Users only see new jar dependencies (no
`kotlin-stdlib`) and a smaller agent jar.
