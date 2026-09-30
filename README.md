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

       public void invite(@StackTraceParam String user, @StackTraceParam(secret = true) String email) { }
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

       fun invite(@StackTraceParam user: String, @StackTraceParam(secret = true) email: String) { }
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

With the configuration in `src/main/resources`, one file serves both phases: the build plugin reads `[augment.receiver]` and `[augment.params]`, and at runtime `[augment]` (with `[augment.receiver]`, for receiver ids) and `debug` are read from `stack-augmentor.toml` on the classpath (or from `-Dstackaugmentor.config=<file>`); when the runtime jar's class loader does not see it, e.g. in an application server, the context class loader of the first throwing thread is asked. At runtime, the `[augment.receiver]` entries apply as with the agent: a class without a matching entry gets no receiver id, even if it is annotated. Without a runtime configuration, a warning says so, and frames show only the parameter ids chosen at build time. An invalid runtime configuration is printed as an error naming the file, the key and the line, and stack traces then stay unchanged.

## Where ids come from

The two kinds of ids are configured independently: `[augment.receiver]` decides the receiver ids, `[augment.params]` the parameter ids. Neither table affects the other. Which classes and methods get instrumented is not configured directly: whatever either table needs is instrumented behind the scenes.

**Receiver id**: the object a frame runs on. It comes from the deciding `[augment.receiver]` entry of the class that declares the frame's method, and is read from the object. An entry applies only to the classes whose names it matches, not to their subclasses. The entry names:

- a field, or a no-argument `method()`, looked up in that class and its superclasses (for an interface: in it and the interfaces it extends); a method or getter is also found as a default method of an implemented interface; a name without a field, such as a Kotlin property of an interface, uses the property's getter (`code` reads `getCode()`);
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

Default methods of interfaces work the same way: with `"com.acme.Labeled" = "label()"`, the frame of `Labeled.relabel()` shows `Labeled{label=…}` whatever class implements it, with `label()` called on the object. The implementing class's entry does not apply to it, and without an entry for `Labeled` the frame is unchanged. Bridge methods are never instrumented, including the one Kotlin compiles into each implementing class to call the default method, so its frame stays as it is.

The label is the real field or method name (`{objectId=…}`, `{getKey=…}`); it cannot be changed.

**Parameter ids** are shown after the method name, in declaration order. `[augment.params]` entries `"<class>.<method>"` select them (see [Configuration](#configuration)):
- by name or by 0-based index (0 to 255), e.g. `["order", 2]`;
- `"@"`: the parameters the method's annotations select: `@StackTraceParam` on a parameter, or `@StackTraceParams` on the method (all its parameters; not of methods that override it);
- `"-"`: none.

There is no wildcard for the parameters themselves: each one is named, or selected by an annotation.

**Hashed parameter ids.** A `#` after a name or an index in `[augment.params]` (`["user", "email#", "2#"]`), or `@StackTraceParam(secret = true)` on the parameter (also under `@StackTraceParams`, which then shows the others as text), shows the value as a short hash instead of its text: `#` and the first 8 hex digits of the SHA-256 of the `toString()`, e.g. `invite{user=ann, email=#71d4f55f}`. The same value always gives the same hash, so a value can still be followed across log lines and incidents without appearing in them. `null` stays `null`. When a parameter is selected twice (by name and by index, or by both annotations), it is hashed if either hashes it. The hash is not salted: values from a small set, e.g. PINs, can be found by trying them all, so leave such parameters out rather than hashing them.

The keys match the class that declares the method, not its subclasses. When several entries match a method, only the most specific one decides, as in `[augment.receiver]`: an exact `"<class>.<method>"` beats any pattern, and among patterns the one with the most characters other than `*` and `?` wins. Entries are not combined, so a more specific `"-"` takes a method out of a less specific entry:

```toml
[augment.params]
"com.thirdparty.Inventory*.*" = ["sku", "count"]   # these parameters of the Inventory* classes ...
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

The label is the parameter name compiled into the class file, so compile Java code with `javac -parameters` (Kotlin: `javaParameters = true`); without it, the label is `arg<N>`. The value is the argument's `toString()` (arrays with their elements), even when its class has an `[augment.receiver]` entry: that table only applies to receivers. Give a class a `toString()` to control how it appears as an argument.

All ids become Strings when they are captured. Line breaks are replaced by a space, `(` and `)` by `{` and `}` (so that IDEs still find the frame's `(File.kt:12)`, e.g. after a data class's `Point(x=1)`), the length is capped at `maxIdLength`, and an id source that throws shows `?`.

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

# Parameter ids: "<class>.<method>" = parameter names and 0-based indexes, a "#" after one to show its value
# hashed, "@" for the method's @StackTraceParam and @StackTraceParams annotations, or "-" for none. Entries that
# match the same method are not combined: the most specific entry wins.
[augment.params]
"com.hafnium.**.*" = "@"                             # the annotations of these methods
"com.acme.orders.*.*" = "@"
"com.thirdparty.OrderService.process" = ["order", 2]
"com.thirdparty.InventoryService.*" = ["sku"]         # all methods of a class
"com.thirdparty.**.*Repository.find*" = [0]          # across packages
"com.thirdparty.**.AuditRepository.find*" = "-"      # except these
"com.thirdparty.UserService.invite" = ["user", "email#"]     # email hashed
```

Quote class names in `[augment.receiver]` and `[augment.params]`. Without quotes, TOML treats each `.` as a nested table; the agent accepts that too, but the quoted form is the clear one. Keys with wildcards must be quoted. Broad wildcards such as `"com.**.*" = "@"` make the agent instrument many classes, which costs time when they are loaded; `debug = true` lists every instrumented class, and the annotations it ignores. A missing field or method is a warning for exact class names, and only a debug message for patterns. Startup messages name their source: `agent:`, `build plugin:` or `runtime:` (the handler of build-time instrumentation), and with `debug = true` each of them lists its configuration file, both tables and the `[augment]` values. Dotted keys (`augment.maxIdLength = 32`) work as well as sections.

An invalid configuration stops the JVM (or the build) at startup. The message names the key and its line, e.g. `stack-augmentor.toml, line 3: maxIdLength must be between 2 and 10000, was 1`. Unknown keys are rejected, so a typo doesn't go unnoticed.

### Formats

| Template | Placeholders |
|---|---|
| `frameFormat` | `$class`, `$simpleClass`, `$method`, `$receiver`, `$params` |
| `receiverFormat` | `$name`, `$id`. Renders empty when the frame has no receiver id. |
| `paramsFormat` | `$name`, `$id`, and `...` to mark repetition. Renders empty when the method has no parameter ids. |

- **`paramsFormat`** is split as follows. The text before the first placeholder and the text after `...` wrap the list. The part from the first to the last placeholder is repeated for each parameter. The text between the last placeholder and `...` separates the items. So `[$name: $id; ...]` renders `[orderId: 42; customer: 7]`.
- **In all three templates**, placeholders start with `$` and everything else is literal, braces included; `$$` is a literal `$`. A placeholder name ends at the first character that is not a letter or digit, so `$class$receiver.$method$params` needs no separators. When letters or digits follow directly, write the name in braces: `${method}X` renders `processX`, while `$methodX` is rejected as an unknown placeholder. A brace without a `$` before it is literal. `(` and `)` are not allowed, because IDEs find a frame's file and line by the `(File.java:12)` at its end; ids have theirs replaced by `{` and `}` for the same reason.
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

When an exception leaves such a method, the advice passes `this`, the id arguments and their labels to `Dispatch` (in `stack-augmentor-instrument-bridge`), which hands them to the handler. The handler finds that method's frame in the exception's stack trace and replaces it, so every printer and logger shows the ids. The agent opens `java.lang` to its own classes at startup, so it replaces the one element in the exception's own trace array; build-time instrumentation copies the trace and writes it back with `setStackTrace`. The advice is inlined, so on a normal return it costs one null check: the argument array is only built on the exception path. A test checks that normal calls allocate nothing.

- **Java agent:** the bridge is appended to the bootstrap class loader, so every class loader can see it, and the agent installs the handler at startup.
- **Build time:** the bridge is an ordinary dependency (through `stack-augmentor-runtime`), and `Dispatch` finds the runtime's handler with `ServiceLoader` when the first exception needs it.

## Limitations

- **Only frames the exception passed through get ids.** If an exception is caught and logged in method `m`, then `m` and the frames below it show no ids.
- **Constructors are not instrumented.**
- **An exception instance that is thrown more than once keeps the ids of its first throw.** Its stack trace is recorded once, when it is created, so a preallocated exception that is thrown repeatedly shows the ids of the first time it left each method, and later throws add none.
- **Each instrumented frame an exception leaves costs a few microseconds.** The handler walks the top of the current stack to find the caller, which costs about a microsecond whatever the depth. With build-time instrumentation it also copies the stack trace twice (`getStackTrace`, `setStackTrace`), so the cost grows with the number of instrumented frames times the trace length; the JVM keeps at most 1024 frames in a trace (`-XX:MaxJavaStackTraceDepth`), which bounds it. The agent writes the frame in place and copies nothing. Measured with JDK 25 on a Linux container, agent on vs off: an exception passing 5 instrumented frames in a 100-frame stack took 33 µs instead of 21 µs; one passing a recursion of 1000 instrumented frames took 1.8 ms instead of 0.16 ms (3.8 ms when copying). Normal returns are not affected; avoid instrumenting deeply recursive methods that throw often.
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
| `examples/build-time` | The same demo with build-time instrumentation (the stand-ins are compiled with it), plus a default-method case, with tests that run without an agent |

Build and test with `./gradlew build`; run the agent demo with `run.bat`. This needs JDK 25.
