# Stack Augmentor

Shows **which object** (and optionally **which arguments**) each frame of a stack trace was running on, either with a Java agent or by instrumenting your classes at build time:

```
Exception in thread "main" java.lang.Exception: An error has occurred
	at com.hafnium.ObjectClass{objectId=object-1}.objectMethod{orderId=42}(ObjectClass.kt:10)
	at com.hafnium.Main.main(Main.kt:15)
```

Ids come from annotations (`@StackTraceId` on a field or method for the object, `@StackTraceParam` and `@StackTraceParams` for arguments), or from an external configuration for classes you cannot change. Classes without either are left alone.

It works for Java and Kotlin classes. The library is written in Java, so it does not need the Kotlin runtime; only the examples and tests use Kotlin.

## Two ways to use it

| | Java agent | Build-time instrumentation |
|---|---|---|
| How | `-javaagent:stack-augmentor-agent.jar` at startup | The ByteBuddy Gradle plugin changes your compiled classes |
| Classes | Your classes and libraries | Only the classes of the project being built |
| `[instrument.classIds]` / `[instrument.methodParams]` (third-party classes) | Yes | No |
| At runtime | The agent jar (self-contained) | `stack-augmentor-runtime` on the classpath (with tomlj; no ByteBuddy, no Kotlin) |
| Example | `./gradlew :examples:java-agent:run` | `./gradlew :examples:build-time:run` |

## Quick start: Java agent

```bash
./gradlew :examples:java-agent:run
```

Use it in your own application:

1. Add `stack-augmentor-api` to your dependencies and annotate:

   ```java
   public class ObjectClass {
       @StackTraceId
       private final String objectId = "object-1";

       public void objectMethod(@StackTraceParam int orderId) { }

       @StackTraceParams   // all parameters
       public void transfer(String from, String to, long amount) { }
   }
   ```

   or in Kotlin:

   ```kotlin
   class ObjectClass {
       @StackTraceId
       val objectId = "object-1"

       fun objectMethod(@StackTraceParam orderId: Int) { }

       @StackTraceParams   // all parameters
       fun transfer(from: String, to: String, amount: Long) { }
   }
   ```

2. Start the JVM with the agent (`./gradlew :stack-augmentor-agent:shadowJar` builds it):

   ```
   java -javaagent:stack-augmentor-agent-<version>.jar=config=stack-augmentor.toml -jar app.jar
   ```

   `-Dstackaugmentor.config=<path>` works as well. The configuration is a TOML file (see [Configuration](#configuration)). Without a configuration, annotated classes in any package are instrumented.

## Quick start: build-time instrumentation

```bash
./gradlew :examples:build-time:run
```

Use it in your own Gradle project (see [examples/build-time/build.gradle.kts](examples/build-time/build.gradle.kts)):

```kotlin
import net.bytebuddy.build.EntryPoint

plugins {
    id("net.bytebuddy.byte-buddy-gradle-plugin") version "<byte-buddy version>"
}

dependencies {
    implementation("com.hafnium:stack-augmentor-api:<version>")
    implementation("com.hafnium:stack-augmentor-runtime:<version>")   // called by the instrumented code
    byteBuddy("com.hafnium:stack-augmentor-build-plugin:<version>")   // the ByteBuddy build plugin
}

val stackAugmentorConfig = layout.projectDirectory.file("src/main/resources/stack-augmentor.toml")

byteBuddy {
    entryPoint = EntryPoint.Default.DECORATE   // only add advice, keep the methods as they are
    transformation {
        pluginName = "com.hafnium.stackaugmentor.build.StackAugmentorByteBuddyPlugin"
        argument { value = stackAugmentorConfig.asFile.absolutePath }   // optional: the [instrument] section
    }
}

// byteBuddy transforms the Java classes, byteBuddyKotlin the Kotlin classes
tasks.matching { it.name == "byteBuddy" || it.name == "byteBuddyKotlin" }.configureEach {
    inputs.file(stackAugmentorConfig)   // re-instrument when the configuration changes
}
```

After compiling, the ByteBuddy Gradle plugin applies `StackAugmentorByteBuddyPlugin` to the project's classes that use `@StackTraceId`, `@StackTraceParam` or `@StackTraceParams` (Java and Kotlin), limited to `[instrument] annotatedClasses` of the given configuration. No agent is needed at runtime: the application needs only `stack-augmentor-api` and `stack-augmentor-runtime`, which bring the bridge and tomlj, but neither ByteBuddy nor the Kotlin runtime. Libraries are not changed, so `[instrument.classIds]` and `[instrument.methodParams]` don't apply here.

With the configuration in `src/main/resources`, one file serves both phases: the build plugin reads `[instrument]`, and at runtime `[augment]` and `debug` are read from `stack-augmentor.toml` on the classpath (or from `-Dstackaugmentor.config=<file>`).

## Where ids come from

**Receiver id**: the object a frame runs on. It is looked up in this order, including superclasses:

1. External configuration: an entry in the `[instrument.classIds]` table, naming a field or a `method()`.
2. `@StackTraceId` on a field, a no-argument method, or (in Kotlin) a primary-constructor `val`, in a class matched by `annotatedClasses`.

A class with neither gets no receiver id.

The label is the real field or method name (`{objectId=…}`, `{getKey=…}`). `@StackTraceId(name = "…")` sets a different label.

**Parameter ids** are shown after the method name, in declaration order. A parameter becomes an id when:
- it is annotated with `@StackTraceParam`;
- its method is annotated with `@StackTraceParams` (all parameters of that method);
- its class is annotated with `@StackTraceParams` (all parameters of every method declared in that class; not of subclasses or nested classes);
- or an `[instrument.methodParams]` entry selects it, by name, by 0-based index, or with `"*"` for all parameters (see [Configuration](#configuration)).

Annotations count only in classes matched by `annotatedClasses`. At most `maxParams` parameter ids (default 8) are shown per frame; if there are more, the list ends with `…`, e.g. `process{a=1, b=2, …}`. The others are not even converted to text.

The label is the parameter name, or `@StackTraceParam(name = "…")`.

> **Upgrading:** `@StackTraceId` no longer applies to parameters. Replace `@StackTraceId` on a parameter with `@StackTraceParam` (keeping any `name`); the compiler reports every place that needs it. Classes compiled against the old annotation show no id for such parameters until they are recompiled. Configuration files need no changes. That needs the `MethodParameters` attribute (`javac -parameters`, Kotlin `javaParameters = true`); without it, the label is `arg<N>`. An argument whose class has a receiver id source is shown by that id, e.g. `order=4711`. Anything else is shown with `toString()`.

All ids become Strings when they are captured. Line breaks are replaced, the length is capped at `maxIdLength`, and an id source that throws shows `?`.

## Configuration

The configuration is a TOML file (ending in `.toml`). `[instrument]` decides what gets instrumented, `[augment]` how frames look:

```toml
# Print diagnostics to stderr: the configuration, which classes and methods get instrumented and why,
# where each id comes from, and config entries or annotations that have no effect.
debug = false

# What gets instrumented: read by the agent when classes load, or by the build plugin at build time.
[instrument]
# Packages whose @StackTraceId, @StackTraceParam and @StackTraceParams annotations are used.
# Empty or missing: all packages. Classes without annotations are never augmented.
# '*' matches within one package, '**' across packages.
annotatedClasses = ["com.hafnium.**", "com.acme.orders.*"]

# Receiver ids for classes you cannot annotate (agent only): a field, or a no-argument method ending in "()"
[instrument.classIds]
"com.thirdparty.Order" = "getOrderNumber()"
"com.thirdparty.Customer" = "customerId"

# Parameter ids for methods you cannot annotate (agent only): "<class>.<method>" = parameter names and 0-based
# indexes, or "*" for all parameters. Keys may use wildcards: in the class, '*' within one package, '**' across
# packages, '?' one character; in the method, '*' and '?'. Entries that match the same method are combined.
[instrument.methodParams]
"com.thirdparty.OrderService.process" = ["order", 2]
"com.thirdparty.InventoryService.*" = "*"             # all methods of a class
"com.thirdparty.**.*Repository.find*" = [0]          # across packages

# How frames look: read at runtime, in both modes (these are the defaults).
[augment]
frameFormat = "{class}{receiver}.{method}{params}"
receiverFormat = "{$name=$id}"
paramsFormat = "{$name=$id, ...}"
maxIdLength = 64
maxParams = 8        # at most this many parameter ids per frame, then "…"
```

Quote class names in `[instrument.classIds]` and `[instrument.methodParams]`. Without quotes, TOML treats each `.` as a nested table; the agent accepts that too, but the quoted form is the clear one. Keys with wildcards must be quoted. Broad wildcards such as `"com.**.*" = "*"` make the agent instrument many classes, which costs time when they are loaded; `debug = true` lists every instrumented class. Dotted keys (`augment.maxIdLength = 32`) work as well as sections.

An invalid configuration stops the JVM (or the build) at startup. The message names the key and its line, e.g. `stack-augmentor.toml, line 3: maxIdLength must be between 2 and 10000, was 1`. Unknown keys are rejected, so a typo doesn't go unnoticed.

### Formats

| Template | Placeholders |
|---|---|
| `frameFormat` | `{class}`, `{simpleClass}`, `{method}`, `{receiver}`, `{params}` |
| `receiverFormat` | `$name`, `$id`. Renders empty when the frame has no receiver id. |
| `paramsFormat` | `$name`, `$id`, and `...` to mark repetition. Renders empty when the method has no parameter ids. |

- **`paramsFormat`** is split as follows. The text before the first placeholder and the text after `...` wrap the list. The part from the first to the last placeholder is repeated for each parameter. The text between the last placeholder and `...` separates the items. So `($name: $id; ...)` renders `(orderId: 42; customer: 7)`.
- **In `frameFormat`**, placeholders are in braces and `{{` and `}}` are literal braces. **In `receiverFormat` and `paramsFormat`**, placeholders start with `$` and everything else is literal, braces included; `$$` is a literal `$`.
- **`frameFormat` must contain `.{method}` exactly once**, because the JDK always prints `<class>.<method>(<file>:<line>)`.

Examples:

| Setting | Frame |
|---|---|
| defaults | `com.hafnium.ObjectClass{objectId=1}.process{orderId=42}(ObjectClass.java:13)` |
| `frameFormat = "{class}.{method}{receiver}{params}"` | `com.hafnium.ObjectClass.process{objectId=1}{orderId=42}(ObjectClass.java:13)` |
| `receiverFormat = "<$id>"` | `com.hafnium.ObjectClass<1>.process{orderId=42}(ObjectClass.java:13)` |
| `receiverFormat = "[$name=$id]"`, `paramsFormat = "[$name=$id, ...]"` | `com.hafnium.ObjectClass[objectId=1].process[orderId=42](ObjectClass.java:13)` |

## How it works

The agent (ByteBuddy, shaded), or the ByteBuddy build plugin, adds exit advice to instrumented methods:
- instance methods of matched classes;
- static methods that have id parameters.

When an exception leaves such a method, the advice passes `this`, the id arguments and their labels to `Dispatch` (in `stack-augmentor-instrument-bridge`), which hands them to the handler. The handler finds that method's frame in the exception's stack trace and replaces it (`setStackTrace`), so every printer and logger shows the ids. The advice is inlined, so on a normal return it costs one null check: the argument array is only built on the exception path. A test checks that normal calls allocate nothing.

- **Java agent:** the bridge is appended to the bootstrap class loader, so every class loader can see it, and the agent installs the handler at startup.
- **Build time:** the bridge is an ordinary dependency (through `stack-augmentor-runtime`), and `Dispatch` finds the runtime's handler with `ServiceLoader` when the first exception needs it.

## Limitations

- **Only frames the exception passed through get ids.** If an exception is caught and logged in method `m`, then `m` and the frames below it show no ids.
- **Constructors are not instrumented.**
- **Parameter values are read when the exception leaves the method.** A parameter that was reassigned shows its new value.
- **Class and method names in the `StackTraceElement`s change.** Tools that parse stack traces (IDE links, error grouping) may not recognise the changed frames.
- **With the agent, the JVM prints `Sharing is only supported for boot loader classes because bootstrap classpath has been appended`** at startup. This is expected, because the agent extends the bootstrap class path; add `-Xshare:off` to silence it.

## Project layout

The library modules are written in Java and don't depend on the Kotlin runtime; `./gradlew check` verifies that (`verifyNoKotlinRuntime`). The integration tests, the runtime's unit tests and the examples are written in Kotlin.

| Module | Contents |
|---|---|
| `stack-augmentor-api` | `@StackTraceId`, `@StackTraceParam`, `@StackTraceParams` (no dependencies) |
| `stack-augmentor-instrument-bridge` | `Dispatch`, which the advice calls (Java, no dependencies) |
| `stack-augmentor-runtime` | Configuration, id lookup, frame formatting, and the handler; shared by both ways |
| `stack-augmentor-instrument` | Which classes and methods get the advice, and the advice itself (ByteBuddy); shared by both ways |
| `stack-augmentor-agent` | The Java agent; `shadowJar` builds the `-javaagent` jar |
| `stack-augmentor-build-plugin` | The ByteBuddy build plugin for build-time instrumentation |
| `stack-augmentor-it` | Integration tests, run with the agent attached, including a Java application run without the Kotlin runtime |
| `examples/java-agent` | Demo with the agent: the example above, plus third-party stand-ins configured in `stack-augmentor.toml` |
| `examples/build-time` | Demo with build-time instrumentation, including tests that run without an agent |

Build and test with `./gradlew build`; run the agent demo with `run.bat`. This needs JDK 25.
