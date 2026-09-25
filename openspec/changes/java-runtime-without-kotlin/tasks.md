# Tasks

## 1. API module

- [x] 1.1 Replace `StackTraceId.kt` with `src/main/java/com/hafnium/stackaugmentor/StackTraceId.java` (`@Target({FIELD, METHOD, PARAMETER})`, `@Retention(RUNTIME)`, `@Documented`, `String name() default ""`, same Javadoc including the Kotlin primary-constructor note). Switch `stack-augmentor-api/build.gradle.kts` to `java-library` only. Verify that `./gradlew :stack-augmentor-it:test :examples:build-time:test` passes, which exercises class-body properties, primary-constructor properties, getters and `name = ...`.

## 2. Runtime module

- [x] 2.1 Port `IdSpec`, `ParamRef` (sealed interfaces with records), `ConfigException` and `AugmentorConfig` (final class, public canonical and no-arg constructors, `Builder`, record-style accessors, `equals`/`hashCode`/`toString` over the components, private static `ConfigReader`, package-private `globToRegex`) to Java under `src/main/java`. Verify that `AugmentorConfigTest` passes after switching it to the builder and `()` accessors, with its assertions unchanged.
- [x] 2.2 Port `Log` to a final class (`setDebug`/`isDebug`, `debug(Supplier<String>)`, `warn(String)`) and `NamedId` to a record. Port `FrameFormat`, keeping the `Token`/`ParamsTemplate` structure as private nested types. Verify that `FrameFormatTest` passes with unchanged assertions.
- [x] 2.3 Port `IdResolver` (with `public static final String STACK_TRACE_ID`, static `idAnnotation`/`label`, `ClassValue` cache, sanitising and truncation with `…`) to Java. Verify that `IdResolverTest` passes, including the Kotlin `ConstructorProperty` fixture.
- [x] 2.4 Port `WeakIdentityMap` (package-private) and `ThrowHandler` (both constructors plus the public no-arg one for `ServiceLoader`, `runtimeConfig()`, and the "frame not found" debug message guarded by `Log.isDebug()`) to Java. Keep the `META-INF/services` registration. Verify that `WeakIdentityMapTest` and `./gradlew :examples:build-time:test` pass.
- [x] 2.5 Delete `stack-augmentor-runtime/src/main/kotlin`. Verify that `./gradlew :stack-augmentor-runtime:test` passes and that `build/classes/kotlin/main` is empty or absent.

## 3. Instrument module

- [x] 3.1 Split `Instrumentation.kt` into Java classes in `com.hafnium.stackaugmentor.instrument`: `IdParameter` (record), `IdParameters`, `TypeMatching` (debug paths guarded by `Log.isDebug()`), `IdArgsMapping`, `IdArgNamesMapping` and `ExitAdviceFactory.create(IdParameters)`. The package-private `idAnnotation` helper uses `IdResolver.STACK_TRACE_ID`. Switch the build script to `java-library` only and delete `src/main/kotlin`. Verify that `./gradlew :stack-augmentor-instrument:compileJava` succeeds.

## 4. Build plugin module

- [x] 4.1 Port `StackAugmentorBuildPlugin` to Java with the same public constructors (no-arg and `String configFile`) and behaviour. Keep `META-INF/net.bytebuddy/build.plugins`, switch the build script to `java-library` only and delete `src/main/kotlin`. Verify that `./gradlew :examples:build-time:test` passes and its debug output lists the instrumented classes.

## 5. Agent module

- [x] 5.1 Port `StackAugmentorAgent` (static `premain`/`agentmain`, configuration logging), `BridgeInjector` and `Installer` (`IGNORED_PACKAGES` still including `"kotlin"`, transformer debug message guarded) to Java. Keep the class names so the manifest's `Premain-Class`/`Agent-Class` stay valid. Delete `src/main/kotlin`. Verify that `./gradlew :stack-augmentor-it:test` passes.
- [x] 5.2 In `stack-augmentor-agent/build.gradle.kts`, drop the Kotlin plugin, `relocate("kotlin", ...)` and the `*.kotlin_module` exclusion, and use `java` with shadow. Verify with `jar tf stack-augmentor-agent/build/libs/stack-augmentor-agent-0.1.0-SNAPSHOT.jar` that there are no `kotlin/` or `shaded/kotlin/` entries, and that the bridge jar is still embedded.

## 6. Build setup and the no-Kotlin check

- [x] 6.1 Add `kotlin-stdlib` (`version.ref = "kotlin"`) to `gradle/libs.versions.toml`, and set `kotlin.stdlib.default.dependency=false` in a new root `gradle.properties`. Declare the stdlib explicitly: `testImplementation` in `stack-augmentor-runtime` and `stack-augmentor-it`, and `implementation` in `examples:java-agent` and `examples:build-time`. Verify that `./gradlew build` passes.
- [x] 6.2 In the root `build.gradle.kts`, add `-parameters` to `compileJava` of the published modules only. `stack-augmentor-it`'s Java test fixtures must stay compiled without it, because they test the `arg<N>` labels. Verify that the `arg<N>` IT case still passes and that `javap -v` shows a `MethodParameters` attribute on, for example, `ThrowHandler.onThrow`.
- [x] 6.3 Register `verifyNoKotlinRuntime` in each published module (resolving `runtimeClasspath` through `incoming.artifacts.resolvedArtifacts`, failing on group `org.jetbrains.kotlin` with the module and artifact named). In the agent, also scan the shadow jar for `kotlin/` and `com/hafnium/stackaugmentor/shaded/kotlin/` entries. Wire it into `check`. Verify that it passes on the clean tree, and that it fails with a clear message when `implementation(libs.kotlin.stdlib)` is temporarily added to `stack-augmentor-runtime` (then revert that).
- [x] 6.4 Verify with `./gradlew :stack-augmentor-runtime:dependencies --configuration runtimeClasspath` and the same for `stack-augmentor-api` that no `org.jetbrains.kotlin` artifact appears, and that the api has no dependencies at all.

## 7. Java-only end-to-end tests

- [x] 7.1 Add `JavaMain` in `stack-augmentor-it/src/test/java` (calls `JavaFixture`, prints the stack trace). Add a test that starts a child JVM with `-javaagent:<shadow jar>` and a TOML with `debug = true`, on the classpath `build/classes/java/test` + the API jar (paths passed from Gradle as system properties). It asserts the rewritten `JavaFixture` frame and that the output contains no `kotlin/` `NoClassDefFoundError`/`ClassNotFoundException`. Verify with `./gradlew :stack-augmentor-it:test`.
- [x] 7.2 In the same child-JVM style, add a test that uses an invalid TOML and asserts that the JVM stops with the key-and-line message and no Kotlin class errors. Verify with `./gradlew :stack-augmentor-it:test`.
- [x] 7.3 Add a Java class with `@StackTraceId` to `examples/build-time/src/main/java` (instrumented by `byteBuddyJava`, with the task's config input added like `byteBuddyKotlin`). Add a test that runs it in a child JVM whose classpath is the instrumented Java classes dir + the runtime classpath of `stack-augmentor-api` and `stack-augmentor-runtime` (no Kotlin), and asserts the rewritten frame. Verify with `./gradlew :examples:build-time:test`.

## 8. Documentation and specs context

- [x] 8.1 Update `README.md`: say that the published modules are pure Java with no Kotlin runtime, remove the Kotlin standard library from the build-time runtime dependencies, and mention the Java quick-start annotation form next to the Kotlin one. Verify by reading the README top to bottom for stale Kotlin-runtime statements (`grep -n -i "kotlin" README.md`).
- [x] 8.2 Update the `context` in `openspec/config.yaml` ("Tech stack: Java for the published modules, Kotlin for examples and tests"). Update the build script comment in `stack-augmentor-instrument-bridge/build.gradle.kts` if it mentions Kotlin as a contrast. Verify with `openspec validate java-runtime-without-kotlin`.

## 9. Final verification

- [x] 9.1 Run `./gradlew clean build` and `run.bat`. Verify that all tests and `verifyNoKotlinRuntime` pass, that the java-agent example prints the same augmented frames as before the change, and that `git ls-files "stack-augmentor-*/src/main/**/*.kt"` returns nothing.
