# Stack Augmentor

Shows **which object** (and optionally **which arguments**) each frame of a stack trace was running on:

```
Exception in thread "main" java.lang.Exception: An error has occurred
	at com.hafnium.ObjectClass{objectId=object-1}.objectMethod{orderId=42}(ObjectClass.kt:10)
	at com.hafnium.Main.main(Main.kt:15)
```

The ids come from annotations on your classes (`@StackTraceId`, `@StackTraceParam`, `@StackTraceParams`), or from a configuration file for classes you cannot change. Classes without either stay as they are. It works for Java and Kotlin; the library itself is Java and does not need the Kotlin runtime.

## Three ways to use it

| | Java agent | Java agent, live stack (experimental) | Build-time instrumentation |
|---|---|---|---|
| Start | `-javaagent:stack-augmentor-agent.jar=config=<file>` | the same, plus `-agentpath:<native library>` | nothing: the ByteBuddy Gradle plugin changes your compiled classes |
| Frames with ids | the frames the exception leaves | every frame on the stack when the exception is created, including the one that catches it | the frames the exception leaves |
| Third-party classes | yes, from the configuration | yes, from the configuration | no, only the classes of the project being built |
| Cost | a few µs per configured frame the exception leaves | every exception pays a stack walk, more with configured frames | as the agent |
| Needs at runtime | the agent jar | the agent jar and the native library | `stack-augmentor-runtime` on the classpath |
| Example | `./gradlew :examples:java-agent:run` | `./gradlew :examples:live-agent:run` | `./gradlew :examples:build-time:run` |

An application with classes instrumented at build time can also run with the agent, in either mode.

## Options

| Option | Where | Default | What it does |
|---|---|---|---|
| Configuration file | `-javaagent:...=config=<file>`, `-Dstackaugmentor.config=<file>`, or (build-time) `stack-augmentor.toml` on the classpath | none: nothing is augmented | the TOML file with everything below |
| `[augment.receiver]` | configuration | empty | which classes show a receiver id, and where it comes from |
| `[augment.params]` | configuration | empty | which methods show parameter ids, and which parameters |
| `[augment]` | configuration | see [Formats](#formats) | how frames look: `frameFormat`, `receiverFormat`, `paramsFormat`, `maxIdLength` |
| `debug` | configuration | `false` | prints what gets instrumented and where each id comes from |
| `inPlaceModification` | configuration, or `-Dstackaugmentor.inPlaceModification=true` | `false` | writes each frame into the exception's own stack trace instead of copying the trace; faster for deep stacks, needs `java.lang` open (see [Limitations](#limitations)) |
| Live stack | `-agentpath:<native library>` | off | the agent reads ids from the live stack instead of instrumenting classes (see [below](#experimental-reading-frames-from-the-live-stack)) |

See [Configuration](#configuration) for the file in full.

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

2. Say which classes use their annotations, in `stack-augmentor.toml`. Receiver ids and parameter ids are configured separately:

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

   Without a configuration, or with empty `[augment.receiver]` and `[augment.params]`, nothing is augmented and the agent prints a warning.

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
        pluginName = "com.hafnium.stackaugmentor.build.ByteBuddyPlugin"
        argument { value = stackAugmentorConfig.asFile.absolutePath }   // required: its [augment.*] tables
    }
}

// byteBuddy transforms the Java classes, byteBuddyKotlin the Kotlin classes
tasks.matching { it.name == "byteBuddy" || it.name == "byteBuddyKotlin" }.configureEach {
    inputs.file(stackAugmentorConfig)   // re-instrument when the configuration changes
}
```

- **At build time**, the plugin instruments the classes and methods that `[augment.receiver]` and `[augment.params]` need. Without the configuration argument, nothing is instrumented and the build prints a warning.
- **At runtime**, the application needs only `stack-augmentor-api` and `stack-augmentor-runtime` (no agent, no ByteBuddy, no Kotlin). The runtime reads `stack-augmentor.toml` from the classpath, or the file in `-Dstackaugmentor.config`, for `[augment]`, `[augment.receiver]` (receiver ids), `debug` and `inPlaceModification`. In an application server it also asks the context class loader of the first throwing thread.
- **One file serves both** when it is in `src/main/resources`.
- Without a runtime configuration, a warning says so and frames show only the parameter ids chosen at build time. An invalid one is printed as an error, and stack traces stay unchanged.

## Where ids come from

The two kinds of ids are configured independently: `[augment.receiver]` decides the receiver ids, `[augment.params]` the parameter ids. Neither table affects the other. Which classes and methods get instrumented is not configured directly: whatever either table needs is instrumented behind the scenes.

**Receiver ids**: the object a frame runs on. They come from the deciding `[augment.receiver]` entry of the class that declares the frame's method, and are read from the object. An entry applies only to the classes whose names it matches, not to their subclasses. The entry names:

- a field, or a no-argument `method()`, looked up in that class and its superclasses (for an interface: in it and the interfaces it extends); a method or getter is also found as a default method of an implemented interface; a name without a field, such as a Kotlin property of an interface, uses the property's getter (`code` reads `getCode()`);
- a list of them, e.g. `["tenant", "lineId()"]`: one id each, in this order, e.g. `OrderLine{tenant=acme, lineId=3}.cancel`. A member that is missing is left out, with the same warning as for a single one;
- `"@"`: every `@StackTraceId` on a field, a no-argument method, or (in Kotlin) a primary-constructor `val`, one id each: fields first, in declaration order, then methods by name, starting with the class itself and then its superclasses; or
- `"-"`: nothing, so the class gets no receiver id. Its parameter ids are not affected.

When several entries match a class, the most specific one decides: an exact class name beats any pattern, and among patterns the one with the most characters other than `*` and `?` wins. So `"com.acme.Order" = "getId()"` overrides `"com.acme.**" = "@"` for `Order`, and `"com.acme.generated.**" = "-"` takes the generated classes out of `"com.acme.**" = "@"`. A class without a deciding entry, or without the members it names, gets no receiver id. Every id is the member's value as text, from `toString()`.

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

**Constructors** are named `<init>`, as in stack traces: `"com.acme.Shipment.<init>" = ["orderId"]`, or `"com.acme.**.<init>" = "@"` for the annotations of constructors only (`@StackTraceParams` works on constructors too). Wildcards in the method name match them like any method, so `"com.acme.**.*" = "@"` covers the constructors' annotations as well; a more specific entry such as `"com.acme.Shipment.<init>" = "-"` takes them out. A constructor frame shows parameter ids only, never a receiver id, e.g. `com.acme.Shipment.<init>{orderId=42}`, for exceptions thrown in its body (including Kotlin `init` blocks) and while computing the arguments of its `super(...)` or `this(...)` call, e.g. `super(requireNonNull(code))`. An exception thrown inside the called constructor itself leaves the frame unchanged.

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

# Write each frame into the exception's own stack trace instead of copying the trace (see Limitations).
# -Dstackaugmentor.inPlaceModification=true|false overrides it.
inPlaceModification = false

# How frames look: read at runtime, in both modes (these are the defaults).
[augment]
frameFormat = "$class$receiver.$method$params"
receiverFormat = "{$name=$id, ...}"
paramsFormat = "{$name=$id, ...}"
maxIdLength = 64

# Which receivers and parameters get ids: two independent tables. Read by the agent when classes load, or by
# the build plugin at build time, which instrument whatever classes and methods they need.
# Keys may use wildcards: '*' within one package (or name), '**' across packages, '?' one character.

# Receiver ids: "@" for the class's @StackTraceId (without an "@" entry, it is not used), or, for classes you
# cannot annotate, a field or a no-argument method ending in "()", or a list of them; "-" for none. An entry applies to the
# classes it matches, not to their subclasses; the most specific entry wins.
[augment.receiver]
"com.hafnium.**" = "@"
"com.hafnium.generated.**" = "-"                     # except these
"com.acme.orders.*" = "@"
"com.thirdparty.Order" = "getOrderNumber()"
"com.thirdparty.Customer" = "customerId"
"com.thirdparty.OrderLine" = ["tenant", "lineId()"]  # several ids
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
"com.thirdparty.Shipment.<init>" = ["orderId"]       # constructors: <init>
```

- **Quote class names.** Without quotes TOML reads each `.` as a nested table; that works too, but keys with wildcards must be quoted. Dotted keys (`augment.maxIdLength = 32`) work as well as sections.
- **Keep wildcards narrow.** `"com.**.*" = "@"` makes the agent instrument many classes, which slows down class loading.
- **`debug = true`** lists the configuration, every instrumented class and the annotations that have no effect. Messages start with their source: `agent:`, `build plugin:` or `runtime:` (build-time instrumentation at runtime).
- **Missing members** are a warning for exact class names, and a debug message for patterns.
- **An invalid configuration stops the JVM (or the build) at startup**, naming the key and its line, e.g. `stack-augmentor.toml, line 3: maxIdLength must be between 2 and 10000, was 1`. Unknown keys are rejected, so typos don't go unnoticed.

### Formats

| Template | Placeholders |
|---|---|
| `frameFormat` | `$class`, `$simpleClass`, `$method`, `$receiver`, `$params` |
| `receiverFormat` | `$name`, `$id`, and `...` to mark repetition. Renders empty when the frame has no receiver id. |
| `paramsFormat` | `$name`, `$id`, and `...` to mark repetition. Renders empty when the method has no parameter ids. |

- **`receiverFormat` and `paramsFormat`** are split as follows. The text before the first placeholder and the text after `...` wrap the list. The part from the first to the last placeholder is repeated for each id. The text between the last placeholder and `...` separates the items. So `[$name: $id; ...]` renders `[orderId: 42; customer: 7]`. Without `...`, the whole template is repeated, separated by `,`: `<$id>` renders `<acme>,<3>` for two receiver ids.
- **In all three templates**, placeholders start with `$` and everything else is literal, braces included; `$$` is a literal `$`. A placeholder name ends at the first character that is not a letter or digit, so `$class$receiver.$method$params` needs no separators. When letters or digits follow directly, write the name in braces: `${method}X` renders `processX`, while `$methodX` is rejected as an unknown placeholder. A brace without a `$` before it is literal. `(` and `)` are not allowed, because IDEs find a frame's file and line by the `(File.java:12)` at its end; ids have theirs replaced by `{` and `}` for the same reason.
- **`frameFormat` must contain `.$method` (or `.${method}`) exactly once**, because the JDK always prints `<class>.<method>(<file>:<line>)`.

Examples:

| Setting | Frame |
|---|---|
| defaults | `com.hafnium.ObjectClass{objectId=1}.process{orderId=42}(ObjectClass.java:13)` |
| `frameFormat = "$class.$method$receiver$params"` | `com.hafnium.ObjectClass.process{objectId=1}{orderId=42}(ObjectClass.java:13)` |
| `receiverFormat = "<$id>"` | `com.hafnium.ObjectClass<1>.process{orderId=42}(ObjectClass.java:13)` |
| `receiverFormat = "[$name=$id, ...]"`, `paramsFormat = "[$name=$id, ...]"` | `com.hafnium.ObjectClass[objectId=1].process[orderId=42](ObjectClass.java:13)` |

## How it works

The agent (ByteBuddy, shaded), or the ByteBuddy build plugin, adds exit advice to instrumented methods:
- instance methods of matched classes;
- static methods that have id parameters;
- constructors that have id parameters. ByteBuddy's advice cannot catch exceptions in constructors, so these get handlers of stack-augmentor's own (`ConstructorExit`): one around the code before the `super(...)` or `this(...)` call and one around the code after it, each passing the id arguments without a receiver and rethrowing the exception. On a normal return they run no code at all.

When an exception leaves such a method, the advice passes `this`, the id arguments and their labels to `Dispatch` (in `stack-augmentor-instrument-bridge`), which hands them to the handler. The handler finds the method's frame in the exception's stack trace and replaces it, so every printer and logger shows the ids. By default it copies the trace and writes it back with `setStackTrace`; with `inPlaceModification = true` it replaces the one element in the exception's own array. On a normal return the inlined advice costs one null check and allocates nothing (a test checks that).

- **Java agent:** the bridge is appended to the bootstrap class loader, so every class loader can see it, and the agent installs the handler at startup.
- **Build time:** the bridge is an ordinary dependency (through `stack-augmentor-runtime`), and `Dispatch` finds the runtime's handler with `ServiceLoader` when the first exception needs it.
- **Both:** an application with classes instrumented at build time can also run with the agent. The agent leaves those classes as they are (it recognizes them by their reference to `Dispatch`), and their advice reaches the agent's handler, because `Dispatch` is then the one in the bootstrap class loader. Which of their methods have ids is decided at build time; the agent's `[augment.receiver]` entries decide the receiver ids at runtime.

## Experimental: reading frames from the live stack

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

## Limitations

- **Only frames the exception passed through get ids.** If an exception is caught and logged in method `m`, then `m` and the frames below it show no ids. Not so when the agent reads frames from the live stack (see above).
- **Constructors get parameter ids only, and not for exceptions from inside `super(...)` or `this(...)`.** The JVM's verifier allows no exception handler around that call, so the calling constructor's frame stays unchanged. The live stack mode covers it.
- **An exception thrown more than once keeps the ids of its first throw.** Its stack trace is recorded once, when it is created.
- **Each instrumented frame an exception leaves costs a few microseconds.** Normal returns cost nothing.
  - The handler walks the top of the stack to find the caller: about a microsecond, whatever the depth.
  - By default it also copies the stack trace (`getStackTrace`, `setStackTrace`), so the cost grows with the trace length, up to the JVM's 1024 frames (`-XX:MaxJavaStackTraceDepth`).
  - `inPlaceModification = true` writes the frame into the exception's own trace and copies nothing; use it when deeply recursive methods are instrumented. It needs `java.lang` open: the agent opens it; with build-time instrumentation, start the application with `--add-opens java.base/java.lang=ALL-UNNAMED` (or `Add-Opens: java.base/java.lang` in an executable jar's manifest). Otherwise startup fails with an error.
  - Measured with JDK 25 on a Linux container, agent on vs off: an exception passing 5 instrumented frames in a 100-frame stack took 33 µs instead of 21 µs; one passing a recursion of 1000 instrumented frames took 1.8 ms in place and 3.8 ms copying, instead of 0.16 ms.
- **Parameter values are read when the exception leaves the method.** A parameter that was reassigned shows its new value.
- **Class and method names in the `StackTraceElement`s change.** Tools that parse stack traces (IDE links, error grouping) may not recognise the changed frames.
- **With the agent, the JVM prints `Sharing is only supported for boot loader classes because bootstrap classpath has been appended`** at startup. This is expected, because the agent extends the bootstrap class path; add `-Xshare:off` to silence it.

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
| `examples/java-agent` | Demo with the agent: the example above, plus third-party stand-ins configured in `stack-augmentor.toml` |
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

Build and test with `./gradlew build`. On Windows, `run.bat` builds the project and runs the live-stack demo (`examples/live-agent`) twice: with the agent alone, and in the live-stack mode if MinGW `gcc` built the native library. This needs JDK 25.
