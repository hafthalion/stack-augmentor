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
| `[augment.receiver]` / `[augment.params]` for third-party classes | Yes | No |
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

2. Say which classes use their annotations, in `stack-augmentor.toml` (see [Configuration](#configuration)). The receiver ids and the parameter ids are configured separately:

   ```toml
   [augment.receiver]      # receiver ids: @StackTraceId
   "com.acme.**" = "@"

   [augment.params]      # parameter ids: @StackTraceParam and @StackTraceParams
   "com.acme.**.*" = "@"
   ```

3. Start the JVM with the agent (`./gradlew :stack-augmentor-agent:shadowJar` builds it):

   ```
   java -javaagent:stack-augmentor-agent-<version>.jar=config=stack-augmentor.toml -jar app.jar
   ```

   `-Dstackaugmentor.config=<path>` works as well. Without a configuration, or without `[augment.receiver]` and `[augment.params]` entries, nothing is augmented, and the agent prints a warning.

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
        argument { value = stackAugmentorConfig.asFile.absolutePath }   // required: its [augment.*] tables
    }
}

// byteBuddy transforms the Java classes, byteBuddyKotlin the Kotlin classes
tasks.matching { it.name == "byteBuddy" || it.name == "byteBuddyKotlin" }.configureEach {
    inputs.file(stackAugmentorConfig)   // re-instrument when the configuration changes
}
```

After compiling, the ByteBuddy Gradle plugin applies `StackAugmentorByteBuddyPlugin` to the project's classes (Java and Kotlin), instrumenting the classes and methods that the `[augment.receiver]` and `[augment.params]` entries of the given configuration need, as the agent does. Without the configuration argument, nothing is instrumented and the build prints a warning. No agent is needed at runtime: the application needs only `stack-augmentor-api` and `stack-augmentor-runtime`, which bring the bridge and tomlj, but neither ByteBuddy nor the Kotlin runtime. Libraries are not changed, so entries for third-party classes don't apply here.

With the configuration in `src/main/resources`, one file serves both phases: the build plugin reads `[augment.receiver]` and `[augment.params]`, and at runtime `[augment]` (with `[augment.receiver]`, for receiver ids) and `debug` are read from `stack-augmentor.toml` on the classpath (or from `-Dstackaugmentor.config=<file>`). At runtime, the `[augment.receiver]` entries apply as with the agent: a class without a matching entry gets no receiver id, even if it is annotated. Without a runtime configuration, a warning says so, and frames show only the parameter ids chosen at build time.

## Where ids come from

The two kinds of ids are configured independently: `[augment.receiver]` decides the receiver ids, `[augment.params]` the parameter ids. Neither table affects the other. Which classes and methods get instrumented is not configured directly: whatever either table needs is instrumented behind the scenes.

**Receiver id**: the object a frame runs on. It comes from the deciding `[augment.receiver]` entry of the class that declares the frame's method, and is read from the object. An entry applies only to the classes whose names it matches, not to their subclasses. The entry names:

- a field, or a no-argument `method()`, looked up in that class and its superclasses;
- `"@"`: the `@StackTraceId` on a field, a no-argument method, or (in Kotlin) a primary-constructor `val`; or
- `"-"`: nothing, so the class gets no receiver id. Its parameter ids are not affected.

When several entries match a class, the most specific one decides: an exact class name beats any pattern, and among patterns the one with the most characters other than `*` and `?` wins. So `"com.acme.Order" = "getId()"` overrides `"com.acme.**" = "@"` for `Order`, and `"com.acme.generated.**" = "-"` takes the generated classes out of `"com.acme.**" = "@"`. A class without a deciding entry, or without the member it names, gets no receiver id.

So a subclass shows the id of its superclass's entry in the frames of the methods it inherits, and an entry of its own only affects the methods it declares, which are instrumented only with such an entry:

```toml
[augment.receiver]
"com.acme.Order" = "id"
"com.acme.TrackedOrder" = "tracking"   # TrackedOrder extends Order
```

A `TrackedOrder` shows `Order{id=…}.ship` in the frame of `Order.ship()`, and `TrackedOrder{tracking=…}.track` in the frame of its own `track()`. The same holds for subclasses generated at runtime: a Spring CGLIB proxy `Order$$SpringCGLIB$$0`, a Hibernate proxy `Order$HibernateProxy$…`, a Mockito mock `Order$MockitoMock$…`, an anonymous subclass, or an enum constant with a body show `Order`'s id in `Order`'s frames, while their own overrides are not instrumented unless a pattern matches their names (`"com.acme.*"` matches `com.acme.Order$$SpringCGLIB$$0`, since `*` stops only at a `.`).

The label is the real field or method name (`{objectId=…}`, `{getKey=…}`). `@StackTraceId(name = "…")` sets a different label.

**Parameter ids** are shown after the method name, in declaration order. `[augment.params]` entries `"<class>.<method>"` select them (see [Configuration](#configuration)):
- by name or by 0-based index, e.g. `["order", 2]`;
- `"*"`: all parameters;
- `"@"`: the parameters the method's annotations select: `@StackTraceParam` on a parameter, `@StackTraceParams` on the method (all its parameters), or `@StackTraceParams` on the class declaring it (all parameters of every method declared in that class; not of subclasses or nested classes);
- `"-"`: none.

The keys match the class that declares the method, not its subclasses. When several entries match a method, they are taken from the most specific on (an exact `"<class>.<method>"` first, then the patterns with the most characters other than `*` and `?`) and combined up to the first `"-"`, which drops the less specific ones:

```toml
[augment.params]
"com.thirdparty.Inventory*.*" = "*"                # all parameters of the Inventory* classes ...
"com.thirdparty.InventoryAudit.*" = "-"            # ... except InventoryAudit's: more specific ...
"com.thirdparty.InventoryAudit.log" = ["reason"]   # ... except log's first parameter: exact
```

Because the tables are independent, a class can take its receiver id from an explicit entry and still use its parameter annotations, or ignore its receiver and keep its parameters:

```toml
[augment.receiver]
"com.acme.Order" = "getId()"
"com.acme.generated.**" = "-"      # no receiver ids ...

[augment.params]
"com.acme.Order.*" = "@"
"com.acme.**.*" = "@"              # ... but their parameter annotations still count
```

At most `maxParams` parameter ids (default 4) are shown per frame; if there are more, the list ends with `…`, e.g. `process{a=1, b=2, …}`. The others are not even converted to text.

The label is the parameter name, or `@StackTraceParam(name = "…")`. That needs the `MethodParameters` attribute (`javac -parameters`, Kotlin `javaParameters = true`); without it, the label is `arg<N>`. The value is the argument's `toString()` (arrays with their elements), even when its class has an `[augment.receiver]` entry: that table only applies to receivers. Give a class a `toString()` to control how it appears as an argument.

All ids become Strings when they are captured. Line breaks are replaced, the length is capped at `maxIdLength`, and an id source that throws shows `?`.

## Configuration

The configuration is a TOML file (ending in `.toml`). `[augment]` sets how frames look, `[augment.receiver]` which receivers get ids and `[augment.params]` which parameters:

```toml
# Print diagnostics to stderr: the configuration, which classes and methods get instrumented and why,
# where each id comes from, and config entries or annotations that have no effect.
debug = false

# How frames look: read at runtime, in both modes (these are the defaults).
[augment]
frameFormat = "$class$receiver.$method$params"
receiverFormat = "{$name=$id}"
paramsFormat = "{$name=$id, ...}"
maxIdLength = 64
maxParams = 4        # at most this many parameter ids per frame, then "…"

# Which receivers and parameters get ids: two independent tables. Read by the agent when classes load, or by
# the build plugin at build time, which instrument whatever classes and methods they need.
# Keys may use wildcards: '*' within one package (or name), '**' across packages, '?' one character.

# Receiver ids: "@" for the class's @StackTraceId (without an "@" entry, it is not used), or, for classes you
# cannot annotate, a field or a no-argument method ending in "()"; "-" for none. An entry applies to the
# classes it matches, not to their subclasses; the most specific entry wins.
[augment.receiver]
"com.hafnium.**" = "@"
"com.hafnium.generated.**" = "-"                     # except these
"com.acme.orders.*" = "@"
"com.thirdparty.Order" = "getOrderNumber()"
"com.thirdparty.Customer" = "customerId"
"com.thirdparty.**.*Account" = "number"

# Parameter ids: "<class>.<method>" = parameter names and 0-based indexes, "*" for all parameters, "@" for
# the method's @StackTraceParam and @StackTraceParams annotations, or "-" for none. Entries that match the same
# method are combined from the most specific on, up to the first "-".
[augment.params]
"com.hafnium.**.*" = "@"                             # the annotations of these methods
"com.acme.orders.*.*" = "@"
"com.thirdparty.OrderService.process" = ["order", 2]
"com.thirdparty.InventoryService.*" = "*"             # all methods of a class
"com.thirdparty.**.*Repository.find*" = [0]          # across packages
"com.thirdparty.**.AuditRepository.find*" = "-"      # except these
```

Quote class names in `[augment.receiver]` and `[augment.params]`. Without quotes, TOML treats each `.` as a nested table; the agent accepts that too, but the quoted form is the clear one. Keys with wildcards must be quoted. Broad wildcards such as `"com.**.*" = "*"` make the agent instrument many classes, which costs time when they are loaded; `debug = true` lists every instrumented class, and the annotations it ignores. A missing field or method is a warning for exact class names, and only a debug message for patterns. Dotted keys (`augment.maxIdLength = 32`) work as well as sections.

An invalid configuration stops the JVM (or the build) at startup. The message names the key and its line, e.g. `stack-augmentor.toml, line 3: maxIdLength must be between 2 and 10000, was 1`. Unknown keys are rejected, so a typo doesn't go unnoticed.

### Formats

| Template | Placeholders |
|---|---|
| `frameFormat` | `$class`, `$simpleClass`, `$method`, `$receiver`, `$params` |
| `receiverFormat` | `$name`, `$id`. Renders empty when the frame has no receiver id. |
| `paramsFormat` | `$name`, `$id`, and `...` to mark repetition. Renders empty when the method has no parameter ids. |

- **`paramsFormat`** is split as follows. The text before the first placeholder and the text after `...` wrap the list. The part from the first to the last placeholder is repeated for each parameter. The text between the last placeholder and `...` separates the items. So `($name: $id; ...)` renders `(orderId: 42; customer: 7)`.
- **In all three templates**, placeholders start with `$` and everything else is literal, braces included; `$$` is a literal `$`. A placeholder name ends at the first character that is not a letter or digit, so `$class$receiver.$method$params` needs no separators. When letters or digits follow directly, write the name in braces: `${method}X` renders `processX`, while `$methodX` is rejected as an unknown placeholder. A brace without a `$` before it is literal.
- **`frameFormat` must contain `.$method` (or `.${method}`) exactly once**, because the JDK always prints `<class>.<method>(<file>:<line>)`.

Examples:

| Setting | Frame |
|---|---|
| defaults | `com.hafnium.ObjectClass{objectId=1}.process{orderId=42}(ObjectClass.java:13)` |
| `frameFormat = "$class.$method$receiver$params"` | `com.hafnium.ObjectClass.process{objectId=1}{orderId=42}(ObjectClass.java:13)` |
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
