# Stack Augmentor

A Java agent that shows **which object** (and optionally **which arguments**) each frame of a stack trace was running on:

```
Exception in thread "main" java.lang.Exception: An error has occured
	at com.hafnium.ObjectClass{objectId=object-1}.objectMethod{orderId=42}(ObjectClass.kt:10)
	at com.hafnium.Main.main(Main.kt:15)
```

Ids come from a `@StackTraceId` field, method or parameter, or from an external configuration for classes you cannot change. Classes without either are left alone.

## Quick start

```bash
./gradlew :examples:java-agent:run
```

Use it in your own application:

1. Add `stack-augmentor-api` to your dependencies and annotate:

   ```kotlin
   class ObjectClass {
       @StackTraceId
       val objectId = "object-1"

       fun objectMethod(@StackTraceId orderId: Int) { ... }
   }
   ```

2. Start the JVM with the agent (`./gradlew :stack-augmentor-agent:shadowJar` builds it):

   ```
   java -javaagent:stack-augmentor-agent-<version>.jar=config=stack-augmentor.toml -jar app.jar
   ```

   `-Dstackaugmentor.config=<path>` works as well. The configuration is a TOML file (see [Configuration](#configuration)). Without a configuration, classes with `@StackTraceId` in any package are instrumented.

## Where ids come from

**Receiver id**: the object a frame runs on. It is looked up in this order, including superclasses:

1. External configuration: an entry in the `[augmentClassIds]` table, naming a field or a `method()`.
2. `@StackTraceId` on a field, a no-argument method, or (in Kotlin) a primary-constructor `val`, in a class matched by `augmentAnnotatedClasses`.

A class with neither gets no receiver id.

The label is the real field or method name (`{objectId=…}`, `{getKey=…}`). `@StackTraceId(name = "…")` sets a different label.

**Parameter ids** are shown after the method name. A parameter becomes an id when it is:
- annotated with `@StackTraceId`, in a class matched by `augmentAnnotatedClasses`, or
- listed in the `[augmentMethodParams]` table, by name or 0-based index.

The label is the parameter name. That needs the `MethodParameters` attribute (`javac -parameters`, Kotlin `javaParameters = true`); without it, the label is `arg<N>`. An argument whose class has a receiver id source is shown by that id, e.g. `order=4711`. Anything else is shown with `toString()`.

All ids become Strings when they are captured. Line breaks are replaced, the length is capped at `maxIdLength`, and an id source that throws shows `?`.

## Configuration

The configuration is a TOML file (ending in `.toml`):

```toml
# Packages whose @StackTraceId annotations are used (fields, methods and parameters).
# Empty or missing: all packages. Classes without annotations are never augmented.
# '*' matches within one package, '**' across packages.
augmentAnnotatedClasses = ["com.hafnium.**", "com.acme.orders.*"]

maxIdLength = 64

# Layout (these are the defaults)
frameFormat = "{class}{receiver}.{method}{params}"
receiverFormat = "{$name=$id}"
paramsFormat = "{$name=$id, ...}"

# Print diagnostics to stderr: the configuration, which classes and methods get instrumented and why,
# where each id comes from, and config entries or annotations that have no effect.
debug = false

# Receiver ids for classes you cannot annotate: a field, or a no-argument method ending in "()"
[augmentClassIds]
"com.thirdparty.Order" = "getOrderNumber()"
"com.thirdparty.Customer" = "customerId"

# Parameter ids for methods you cannot annotate: parameter names, or 0-based indexes
[augmentMethodParams]
"com.thirdparty.OrderService.process" = ["order", 2]
```

Quote class names in `[augmentClassIds]` and `[augmentMethodParams]`. Without quotes, TOML treats each `.` as a nested table; the agent accepts that too, but the quoted form is the clear one.

An invalid configuration stops the JVM at startup. The message names the key and its line, e.g. `agent.toml, line 2: maxIdLength must be between 2 and 10000, was 1`.

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

The agent (ByteBuddy, shaded) adds exit advice to instrumented methods:
- instance methods of matched classes;
- static methods that have id parameters.

When an exception leaves such a method, the advice passes `this`, the id arguments and their labels to the handler. The handler finds that method's frame in the exception's stack trace and replaces it (`setStackTrace`), so every printer and logger shows the ids. The advice is inlined, so on a normal return it costs one null check: the argument array is only built on the exception path. A test checks that normal calls allocate nothing.

The classes the advice calls (`stack-augmentor-bridge`) are appended to the bootstrap class loader, so every class loader can see them.

## Limitations

- **Only frames the exception passed through get ids.** If an exception is caught and logged in method `m`, then `m` and the frames below it show no ids.
- **Constructors are not instrumented.**
- **Parameter values are read when the exception leaves the method.** A parameter that was reassigned shows its new value.
- **Class and method names in the `StackTraceElement`s change.** Tools that parse stack traces (IDE links, error grouping) may not recognise the changed frames.
- **The JVM prints `Sharing is only supported for boot loader classes because bootstrap classpath has been appended`** at startup. This is expected, because the agent extends the bootstrap class path; add `-Xshare:off` to silence it.

## Project layout

| Module | Contents |
|---|---|
| `stack-augmentor-api` | `@StackTraceId` |
| `stack-augmentor-bridge` | Classes the advice calls, loaded into the bootstrap class loader (Java, no dependencies) |
| `stack-augmentor-agent` | The agent; `shadowJar` builds the `-javaagent` jar |
| `stack-augmentor-it` | Integration tests, run with the agent attached |
| `examples/java-agent` | The demo: the example above, plus third-party stand-ins configured in `stack-augmentor.toml` |

Build and test with `./gradlew build`; run the demo with `run.bat`. This needs JDK 25.
