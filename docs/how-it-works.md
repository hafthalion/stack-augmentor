# How it works

[Back to the README](../README.md)

The agent (ByteBuddy, shaded), or the ByteBuddy build plugin, adds exit advice to instrumented methods:
- instance methods of matched classes;
- static methods that have id parameters;
- constructors that have id parameters. ByteBuddy's advice cannot catch exceptions in constructors, so these get handlers of stack-augmentor's own (`ConstructorExit`): one around the code before the `super(...)` or `this(...)` call and one around the code after it, each passing the id arguments without a receiver and rethrowing the exception. On a normal return they run no code at all.

When an exception leaves such a method, the advice passes `this`, the id arguments and their labels to `Dispatch` (in `stack-augmentor-instrument-bridge`), which hands them to the handler. The handler finds the method's frame in the exception's stack trace and replaces it, so every printer and logger shows the ids. By default it copies the trace and writes it back with `setStackTrace`; with `inPlaceModification = true` it replaces the one element in the exception's own array. On a normal return the inlined advice costs one null check and allocates nothing (a test checks that).

- **Java agent:** the bridge is appended to the bootstrap class loader, so every class loader can see it, and the agent installs the handler at startup.
- **Build time:** the bridge is an ordinary dependency (through `stack-augmentor-runtime`), and `Dispatch` finds the runtime's handler with `ServiceLoader` when the first exception needs it.
- **Both:** an application with classes instrumented at build time can also run with the agent. The agent leaves those classes as they are (it recognizes them by their reference to `Dispatch`), and their advice reaches the agent's handler, because `Dispatch` is then the one in the bootstrap class loader. Which of their methods have ids is decided at build time; the agent's `[augment.receiver]` entries decide the receiver ids at runtime.


## Project layout

The library modules are written in Java and don't depend on the Kotlin runtime; `./gradlew check` verifies that (`verifyNoKotlinRuntime`). The integration tests, the runtime's unit tests and the examples are written in Kotlin.

| Module | Contents |
|---|---|
| `stack-augmentor-api` | `@StackTraceId`, `@StackTraceParam`, `@StackTraceParams` (no dependencies) |
| `stack-augmentor-instrument-bridge` | `Dispatch`, which the advice calls, and `LiveDispatch`, which the live-stack mode's code in `Throwable` calls (Java, no dependencies) |
| `stack-augmentor-runtime` | Configuration, id lookup, frame formatting, and the handler; shared by both ways |
| `stack-augmentor-instrument` | Which classes and methods get the advice, the advice itself, and the live-stack mode's code in `Throwable` with how it reads ids from the live stack (ByteBuddy); shared by both ways |
| `stack-augmentor-agent` | The Java agent; `shadowJar` builds the `-javaagent` jar |
| `stack-augmentor-native` | The native library (C) for the agent's experimental live-stack mode, loaded with `-agentpath` |
| `stack-augmentor-build-plugin` | The ByteBuddy build plugin for build-time instrumentation |
| `stack-augmentor-it` | Integration tests, run with the agent attached (`test`) and in the live-stack mode (`testLiveStack`), including a Java application run without the Kotlin runtime |
| `examples/java-agent` | Demo with the agent: the example in the README, plus third-party stand-ins configured in `stack-augmentor.toml` |
| `examples/live-agent` | Demo of the agent's live-stack mode, where every frame of the logged stack traces shows its ids |
| `stack-augmentor-it-build-time` | Integration tests for build-time instrumentation: fixtures instrumented by the build plugin, run without an agent, and again with it (`testWithAgent`) |
| `examples/build-time` | The same demo with build-time instrumentation (the stand-ins are compiled with it) |
| `stack-augmentor-it-benchmark` | Benchmark of exceptions 1000 frames deep without stack-augmentor, with the agent copying stack traces, with the agent writing frames in place (`-Dstackaugmentor.inPlaceModification=true`), and in the live-stack mode; `./gradlew :stack-augmentor-it-benchmark:benchmark` writes an HTML report to `build/reports/benchmark/index.html`; the project build only compiles it |

The packages under `com.hafnium.stackaugmentor` show which way of working uses which code:

| Package | Contents | Used by |
|---|---|---|
| `runtime.config` | The configuration, and how each component starts | All |
| `runtime.ids` | Ids of receivers and parameters, and how frames show them | All |
| `runtime` | `Log`, `WeakIdentityMap` | All |
| `runtime.handler` | `ThrowHandler`, called by the code added to methods, and how it writes stack traces | Agent instrumenting classes, build-time instrumentation |
| `instrument` | Which classes, methods and parameters show ids (`TypeMatching`, `IdParameters`) | Agent in both modes, build plugin |
| `instrument.advice` | The code added to methods and constructors | Agent instrumenting classes, build plugin |
| `instrument.live` | The code the live-stack mode adds to `Throwable`, and how it reads ids from the live stack | Agent with the native library |
| `instrument.bridge` | `Dispatch`, the entry point of the code added to methods; `LiveDispatch`, of the code the live-stack mode adds to `Throwable` | `Dispatch`: all; `LiveDispatch`: agent with the native library |
| `agent` | The agent's entry point, the bridge jar, and the choice of mode | Agent |
| `agent.instrument` | Instrumenting the configured classes | Agent |
| `agent.live` | Turning the experimental live-stack mode on: the native library and the startup checks | Agent with the native library |
| `build` | The ByteBuddy build plugin | Build-time instrumentation |
