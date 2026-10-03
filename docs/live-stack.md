# Experimental: reading frames from the live stack

[Back to the README](../README.md)

The agent can also work without instrumenting any class. Start the JVM with the native library as well:

```
java -agentpath:/path/to/libstackaugmentor.so -javaagent:stack-augmentor-agent-<version>.jar=config=stack-augmentor.toml -jar app.jar
```

- **Building the library:** `./gradlew :stack-augmentor-native:compileNative` builds it into `stack-augmentor-native/build/native/` with the platform's C compiler: `libstackaugmentor.so` on Linux, `libstackaugmentor.dylib` on macOS (`cc`), `stackaugmentor.dll` on Windows (MinGW `gcc`). It first copies the JDK's headers to `stack-augmentor-native/jdk-include` (ignored by git), where `.idea/c_cpp_properties.json` points IntelliJ too.
- **Why a native library:** it asks the JVM at startup to keep the local variables of JIT-compiled code readable (the JVMTI capability `can_access_local_variables`). That can't be done later, so the agent jar can't do it.
- **How it works:** the agent adds code to `java.lang.Throwable` only. When a new exception records its stack trace, its frames are still on the stack, so the agent reads the receiver and arguments of the configured methods there (with the JDK-internal `java.lang.LiveStackFrame`). The ids are written into the stack trace when it is first read or printed. Configuration and formats are the same.

Differences from instrumenting classes:

- **Every frame on the stack when the exception is created gets ids**, also the method that catches it and the frames below.
- **Constructors show their parameter ids also for exceptions from `super(...)` or `this(...)`.** Still no receiver id.
- **Ids are read when the exception is created**, not when it leaves each method.
- **Every exception pays a stack walk**, also when no frame is configured: measured with JDK 25 on a Linux container, about 4 µs for a 10-frame stack and 13 µs for a 100-frame one, plus 2 to 4 µs per configured frame. `stack-augmentor-it-benchmark` compares the modes at 1000 frames. Exceptions created while a class loads, such as a class loader's `ClassNotFoundException`, are skipped.
- **An object argument that the JIT optimized away reads as `null`, and is shown as `?`**, e.g. a boxed `Integer` created in compiled code and never stored (scalar replacement). `-XX:-EliminateAllocations` turns that optimization off, at some cost; a receiver optimized away shows no receiver id.
- **It relies on JDK internals.** If they are missing or behave differently, the agent says so and instruments classes as usual. There is no build-time equivalent.

- `./gradlew :examples:live-agent:run`: a shop that logs the exceptions of three requests, where every frame shows its ids.
- `./gradlew :examples:live-agent:runInstrumented`: the same with the agent alone, which shows ids only on the frames the exceptions left.
- `./gradlew :stack-augmentor-it:testLiveStack`: the integration tests in this mode; skipped without a C compiler, except on CI.
