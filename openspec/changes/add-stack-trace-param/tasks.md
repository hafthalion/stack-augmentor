# Tasks

## 1. API

- [x] 1.1 Add `stack-augmentor-api/src/main/java/com/hafnium/stackaugmentor/StackTraceParam.java` (`@Documented`, `@Retention(RUNTIME)`, `@Target(PARAMETER)`, `String name() default ""`) and `StackTraceParams.java` (`@Documented`, `@Retention(RUNTIME)`, `@Target({TYPE, METHOD})`, no attributes, Javadoc for method and class placement). Remove `PARAMETER` from `StackTraceId`'s targets and update its Javadoc to receiver ids only. Verify with `./gradlew :stack-augmentor-api:compileJava` that it compiles, and that `:stack-augmentor-it:compileTestKotlin` now fails on the old parameter uses (they are fixed in 5.1).

## 2. Runtime: configuration and formatting

- [x] 2.1 Add `ParamRef.All`. Parse `"*"` as the value of an `[instrument.methodParams]` entry, and reject `"*"` inside arrays and any other string. Validate the key parts as in design decision 3. Verify with new `AugmentorConfigTest` cases: `"*"`, the `"all"` value, a `+` in the key, an empty method part, and the messages naming the key and line.
- [x] 2.2 Derive the wildcard `ParamEntry` list in `AugmentorConfig` and extend `paramRefs` (exact lookup first, then matching wildcard entries in configuration order) and `hasParamEntries`. Add `isPattern(key)`. Verify with `AugmentorConfigTest` cases for `"com.thirdparty.OrderService.*"`, `"com.thirdparty.**.*Repository.find*"`, `?`, nested classes (`$`), and overlapping exact and wildcard entries.
- [x] 2.3 Add `maxParams` (`[augment]`, default 8, range 1–255) to `AugmentorConfig`, its `Builder`, `equals`/`hashCode`/`toString` and `AUGMENT_KEYS`. Verify with `AugmentorConfigTest`: the default, a parsed value, `maxParams = 0` and `256` rejected with the key and line, and the full-configuration test extended.
- [x] 2.4 Implement the `maxParams` cut and the `…` item in `FrameFormat`: `create(config)`, a four-argument `create` overload, `maxParams()`, and `rewrite(..., omitted)` plus the limiting three-argument `rewrite`. Verify with `FrameFormatTest`: more than, exactly, and fewer than the maximum; the custom `($name: $id; ...)` template; and a template without `...`.
- [x] 2.5 In `ThrowHandler.onThrow`, resolve only the first `format.maxParams()` parameter values and pass the number left out. Verify with `./gradlew :stack-augmentor-runtime:test`, and in 5.3 that a parameter beyond the limit whose `toString` throws does not show `?`.

## 3. Instrument: selection and matching

- [x] 3.1 Add `IdResolver.STACK_TRACE_PARAM` and `IdResolver.STACK_TRACE_PARAMS`. Generalise `IdParameters.idAnnotation` to `annotation(list, name)` and rewrite `select` as in design decision 2: method- and class-level `@StackTraceParams`, a parameter `@StackTraceParam(name)` overriding the label, and `ParamRef.All`. Verify with `./gradlew :stack-augmentor-instrument:compileJava` and the integration tests in 5.x.
- [x] 3.2 `TypeMatching`: `usesAnnotations` also finds `@StackTraceParams` on the type and its methods and `@StackTraceParam` on their parameters. Receiver detection stays on `@StackTraceId`. `IdParameters.unmatchedEntries` skips entries where `isPattern` is true. Verify with a new Java unit test in `stack-augmentor-instrument` (JUnit, `TypeDescription.ForLoadedType` of test classes): wildcard entries produce no messages, exact entries still report a missing method or parameter, and a class-level `@StackTraceParams` outside `annotatedClasses` yields the "ignoring" message.

## 4. Agent

- [x] 4.1 `StackAugmentorAgent.logConfiguration`: print `ParamRef.All` as `*`, and add `maxParams=<n>` to the format line. Verify by running the java-agent example with `debug = true` and checking the configuration lines.

## 5. Integration tests and examples

- [x] 5.1 Replace every `@StackTraceId` on a parameter with `@StackTraceParam` in `stack-augmentor-it` (`Fixtures.kt`, `Outside.kt`, `JavaFixture.java` with `name = "count"`) and in both examples' `ClassWithAnnotation.kt`. Verify that `./gradlew :stack-augmentor-it:test :examples:build-time:test` passes unchanged, including the allocation test and the `arg<N>` test.
- [x] 5.2 Add fixtures and tests for method-level `@StackTraceParams` (`transfer(from, to, amount)`), class-level `@StackTraceParams` (`Inventory` with `reserve` and `release`, plus an unannotated subclass method), a class-level `@StackTraceParams` in `com.hafnium.it.outside` (no ids), an annotation and a configuration entry selecting the same parameter (shown once), and `@StackTraceParam(name = …)` on a parameter of a `@StackTraceParams` method (`move`). Verify with `./gradlew :stack-augmentor-it:test`.
- [x] 5.3 Add a fixture with 10 parameters under method-level `@StackTraceParams`, asserting the first 8 and `…` (default `maxParams`), and that a parameter beyond the limit whose `toString` throws is not resolved. Verify with `./gradlew :stack-augmentor-it:test`.
- [x] 5.4 Third-party wildcards: add `com.thirdparty.InventoryService` and `com.thirdparty.db.OrderRepository` stand-ins. In `src/test/resources/stack-augmentor.toml`, add `"com.thirdparty.Inventory*.*" = "*"`, `"com.thirdparty.**.*Repository.find*" = [0]`, `"com.thirdparty.OrderService.*" = [2]` (overlap with the exact entry) and `"java.**.*" = "*"`. Update the `OrderService` expectation to `order=4711, quantity=3, note=rush`, and assert that JDK frames (e.g. from `Integer.parseInt("x")`) stay unchanged and that `findAll()` is unchanged. Verify with `./gradlew :stack-augmentor-it:test`.
- [x] 5.5 Build-time example: add a Kotlin class annotated only with `@StackTraceParams` and a test asserting its parameter ids without an agent. Verify with `./gradlew :examples:build-time:test`.
- [x] 5.6 Java-agent example: add a method-level `@StackTraceParams` and a wildcard `[instrument.methodParams]` entry in `stack-augmentor.toml`, and add `maxParams` to its `[augment]` section. Verify that `./gradlew :examples:java-agent:run` shows the new frames.

## 6. Documentation

- [x] 6.1 README: `@StackTraceId` for receiver ids, `@StackTraceParam` for single parameters and `@StackTraceParams` for all parameters of a method or class (in the Java and Kotlin quick-start snippets), the `[instrument.methodParams]` wildcard syntax and `"*"`, `maxParams` and the `…` marker, the breaking change with a migration note, and a warning about broad wildcards. Verify with `grep -n "StackTraceId" README.md` that no parameter use remains.

## 7. Final verification

- [x] 7.1 Run `./gradlew clean build` and `run.bat`. Verify that all tests and `verifyNoKotlinRuntime` pass, that the demo shows receiver ids, method-level parameter ids and wildcard-selected third-party parameters, and that `openspec validate add-stack-trace-param --strict` passes.
