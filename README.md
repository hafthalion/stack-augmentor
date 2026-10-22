# Stack Augmentor

Shows **which object** (and optionally **which arguments**) each frame of a stack trace was running on:

```
Exception in thread "main" java.lang.Exception: An error has occurred
	at com.hafnium.ObjectClass{objectId=object-1}.objectMethod{orderId=42}(ObjectClass.kt:10)
	at com.hafnium.Main.main(Main.kt:15)
```

Stack Augmentor augments the frames of stack traces with receiver members and parameter values, so a logged exception says which order, customer or account the failing code was working on. Every logger and printer shows them, since the frames themselves are changed. Other frames, and exceptions you don't select, stay as they are.

- **Receiver members**: members of the object a frame's method runs on, from `@AugmentedReceiver` fields and methods, or from fields and getters named in the configuration.
- **Parameter values**: selected arguments, from `@AugmentedParam` and `@AugmentedParams`, or by name or index; sensitive values can be shown as a hash.
- **Classes you cannot change**: one TOML file names their members and parameters, selecting classes by name, with their subclasses (`+`) or by pattern, and methods by name or pattern; it also says in which classes the annotations are read, which exceptions are augmented and how frames look. The annotations augment only the classes and methods with `@Augmented`. See [Configuration](docs/configuration.md).
- **Three ways to run**: a Java agent, the agent reading every frame from the live stack (experimental), or build-time instrumentation without an agent.
- **Cause chains**: the frames a cause shares with its wrapper are augmented the same way, so `... N more` still works; `ExceptionFormat.rootCauseFirst` prints the root cause first.
- **Java and Kotlin**, constructors included; the library is Java and does not need the Kotlin runtime.
- **Cheap**: with instrumented classes, a few microseconds per configured frame an exception leaves, nothing on normal returns; `debug = true` in `[settings]` shows what gets instrumented and why.

## Three ways to use it

| | Java agent | Java agent, live stack (experimental) | Build-time instrumentation |
|---|---|---|---|
| Start | `-javaagent:stack-augmentor-agent.jar=config=<file>` | the same, plus `-agentpath:<native library>` | nothing: the ByteBuddy Gradle plugin changes your compiled classes |
| Frames augmented | the frames the exception leaves | every frame on the stack when the exception is created, including the one that catches it | the frames the exception leaves |
| Third-party classes | yes, from the configuration | yes, from the configuration | no, only the classes of the project being built |
| Cost | a few µs per configured frame the exception leaves | every exception that `[augment.exceptions]` selects pays a stack walk, more with configured frames | as the agent |
| Needs at runtime | the agent jar | the agent jar and the native library | `stack-augmentor-runtime` on the classpath |
| Example | `./gradlew :examples:java-agent:run` | `./gradlew :examples:live-agent:run` | `./gradlew :examples:build-time:run` |

An application with classes instrumented at build time can also run with the agent, in either mode.

## Options

| Option | Where | Default | What it does |
|---|---|---|---|
| Configuration files | global: `-javaagent:...=config=<file>` or `-Dstackaugmentor.config=<file>`, and `stack-augmentor-global.toml` on the classpath of a class loader; local: `stack-augmentor.toml` in a jar or directory, for its own classes | none: the [built-in configuration](docs/configuration.md#the-built-in-configuration), which also applies after the files, augments all exceptions with the annotated members and parameters of the classes with `@Augmented` | TOML files with everything below ([where they come from](docs/configuration.md#where-the-files-come-from)) |
| `[augment.exceptions]` | configuration | empty: the next configuration decides | which exceptions are augmented, e.g. `"java.lang.Exception+" = true` ([Exceptions](docs/configuration.md#exceptions)) |
| `[augment.annotations]` | configuration | empty: the next configuration decides | in which classes `@AugmentedReceiver`, `@AugmentedParam`, `@AugmentedParams` and `@Augmented` are read, e.g. `"com.acme.**" = true` ([Annotations](docs/configuration.md#annotations)) |
| `[augment.receiver]` | configuration | empty | which frames show receiver members by name, and which ones, e.g. `"com.thirdparty.Order+::*" = "getOrderNumber()"` ([Receiver members](docs/configuration.md#receiver-members)) |
| `[augment.params]` | configuration | empty | which frames show parameter values by name or index, and which parameters, e.g. `"com.acme.Order::ship, cancel*" = "orderId"` ([Parameter values](docs/configuration.md#parameter-values)) |
| `[augment]` | configuration | see [Formats](docs/configuration.md#formats) | how frames look: `frameFormat`, `receiverFormat`, `paramsFormat`, `maxValueLength` |
| `debug` | `[settings]` of the agent's file or `-Dstackaugmentor.config` | `false` | prints what gets instrumented and where each value comes from |
| `inPlaceModification` | `[settings]` of the agent's file or `-Dstackaugmentor.config`, or `-Dstackaugmentor.inPlaceModification=false` | `true` | writes each frame into the exception's own stack trace instead of copying the trace; faster for deep stacks, needs `java.lang` open, otherwise frames are copied (see [Limitations](docs/limitations.md)) |
| Live stack | `-agentpath:<native library>` | off | the agent reads the values from the live stack instead of instrumenting classes (see [Live stack](docs/live-stack.md)) |

In all four tables the first matching entry decides, and classes are written the same way: a class name matches that class only, `+` after it also its subclasses, and a pattern (`*`, `**`, `?`) matches class names. The tables differ in their keys and values:

- `[augment.receiver]` and `[augment.params]` keys name a class and its methods, `"<class>::<methods>"`, and their values name what to show: `"com.acme.Order::ship, cancel*" = "orderId"`.
- `[augment.exceptions]` and `[augment.annotations]` keys name only a class, and their values are `true` or `false`: `"java.io.IOException+" = true` augments that exception and its subclasses, `"com.acme.**" = true` reads the annotations of the classes under `com.acme`.
- For each method, an `[augment.receiver]` or `[augment.params]` entry that matches it decides; only without one are the annotations used, where `[augment.annotations]` selects the class.

[Configuration](docs/configuration.md) describes the file in full: the syntax, every table and option, and the errors and warnings.

## Quick start: Java agent

```bash
./gradlew :examples:java-agent:run
```

Use it in your own application:

1. Add `stack-augmentor-api` to your dependencies, and annotate the class with `@Augmented` and its members and parameters:

   ```java
   @Augmented   // all its methods and constructors use the annotations
   public class ObjectClass {
       @AugmentedReceiver
       private final String objectId = "object-1";

       public void objectMethod(@AugmentedParam int orderId) { }

       @AugmentedParams   // all parameters
       public void transfer(String from, String to, long amount) { }

       public void invite(@AugmentedParam String user, @AugmentedParam(secret = true) String email) { }
   }
   ```

   or in Kotlin:

   ```kotlin
   @Augmented   // all its methods and constructors use the annotations
   class ObjectClass {
       @AugmentedReceiver
       val objectId = "object-1"

       fun objectMethod(@AugmentedParam orderId: Int) { }

       @AugmentedParams   // all parameters
       fun transfer(from: String, to: String, amount: Long) { }

       fun invite(@AugmentedParam user: String, @AugmentedParam(secret = true) email: String) { }
   }
   ```

2. Say which exceptions are augmented and in which classes the annotations are read, in `stack-augmentor.toml`:

   ```toml
   [augment.exceptions]    # the first matching entry decides; the built-in configuration after it augments all exceptions
   "com.acme.FlowException+" = false

   [augment.annotations]   # where @AugmentedReceiver, @AugmentedParam(s) and @Augmented are read
   "com.acme.**" = true
   "**" = false            # nowhere else, as the built-in configuration would
   ```

   Classes you cannot annotate get `[augment.receiver]` and `[augment.params]` entries that name their members and parameters (see [Configuration](docs/configuration.md)).

3. Start the JVM with the agent (`./gradlew :stack-augmentor-agent:shadowJar` builds it):

   ```
   java -javaagent:stack-augmentor-agent-<version>.jar=config=stack-augmentor.toml -jar app.jar
   ```

   Without a configuration, the [built-in one](docs/configuration.md#the-built-in-configuration) applies, the same as this example for all classes with `@Augmented`; with one, it applies after it. Applications and jars can bring their own files too ([the configuration chain](docs/configuration.md#the-configuration-chain)).

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

val stackAugmentorConfig = layout.projectDirectory.file("src/main/resources/stack-augmentor-global.toml")

byteBuddy {
    entryPoint = EntryPoint.Default.DECORATE   // only add advice, keep the methods as they are
    transformation {
        pluginName = "com.hafnium.stackaugmentor.build.ByteBuddyPlugin"
        argument { value = stackAugmentorConfig.asFile.absolutePath }   // its [augment.*] tables; without it, the built-in configuration
        // optional: the module's local stack-augmentor.toml, after the global file, for this module's classes only
        // argument { index = 1; value = layout.projectDirectory.file("src/main/resources/stack-augmentor.toml").asFile.absolutePath }
        // optional: the build's own file, like the agent's, before the others; only it may have [settings], e.g. debug
        // argument { index = 2; value = layout.projectDirectory.file("stack-augmentor-build.toml").asFile.absolutePath }
    }
}

// byteBuddy transforms the Java classes, byteBuddyKotlin the Kotlin classes
tasks.matching { it.name == "byteBuddy" || it.name == "byteBuddyKotlin" }.configureEach {
    inputs.file(stackAugmentorConfig)   // re-instrument when the configuration changes
}
```

- **At build time**, the plugin instruments the classes and methods that `[augment.annotations]`, `[augment.receiver]` and `[augment.params]` need. Without the configuration argument, the built-in configuration applies: the methods that `@Augmented` selects are instrumented, where their class has annotated members or they have annotated parameters.
- **At runtime**, the application needs only `stack-augmentor-api` and `stack-augmentor-runtime` (no agent, no ByteBuddy, no Kotlin). The runtime reads the file in `-Dstackaugmentor.config` and those on the classpath, `stack-augmentor-global.toml` of the class loader and `stack-augmentor.toml` of the class's jar or directory, for `[augment]`, `[augment.exceptions]` and `[augment.receiver]` (receiver members), and `[settings]` from `-Dstackaugmentor.config` only ([the configuration chain](docs/configuration.md#the-configuration-chain)).
- **One file serves both** when it is `src/main/resources/stack-augmentor-global.toml`. A module's local `src/main/resources/stack-augmentor.toml`, for its own classes only, serves both when it is the plugin's second argument: the plugin marks the classes it instruments with it, so they find it at runtime also when classes and resources are in different build directories ([where the files come from](docs/configuration.md#where-the-files-come-from)).
- A frame's receiver members come from the runtime `[augment.receiver]` entry with the same key as the entry that decided its method at build time; without that key they are left out. Where the annotations decided at build time, the `@AugmentedReceiver` members are shown, like the annotated parameters: the runtime's `[augment.annotations]` is not checked again.
- Without a runtime configuration, a warning says so and the built-in configuration applies; it shows the `@AugmentedReceiver` members, but not the receiver members of `[augment.receiver]` entries. An invalid one is printed as an error, and stack traces stay unchanged.

## Limitations in short

- Only the frames the exception leaves are augmented, except in the live stack mode.
- Constructors show parameter values only.
- An exception thrown more than once keeps the values of its first throw.
- Each configured frame an exception leaves costs a few microseconds; normal returns cost nothing.
- Changed class and method names may confuse tools that parse stack traces.

## More documentation

- [Receivers and parameters](docs/receivers-and-parameters.md): receiver members and parameter values, subclasses, interfaces, constructors, hashed values
- [Configuration](docs/configuration.md): the file in full: keys, tables, formats, options, errors and warnings
- [Causes, root cause first](docs/causes.md): `%rEx` in Logback and Log4j 2, and `ExceptionFormat.rootCauseFirst` in the API
- [Live stack](docs/live-stack.md): the experimental mode with the native library
- [Limitations](docs/limitations.md): the details, with measured costs
- [How it works](docs/how-it-works.md): the instrumentation, the modules and packages

Build and test with `./gradlew build`. `./run.sh [java-agent|live-agent|build-time|benchmark]` (or `run.bat` on Windows) builds the project and runs that example module from `examples`, or the benchmark, and asks which one without an argument: `java-agent` with a simple cause chain printed by `printStackTrace` and root cause first; `live-agent` twice, with the agent alone and in the live-stack mode if a C compiler (`cc`, or MinGW `gcc` on Windows) built the native library; `build-time` without an agent; `benchmark` runs `:stack-augmentor-it:benchmark:benchmark`, which writes `stack-augmentor-it/benchmark/build/reports/benchmark/index.html` and copies it to `docs/benchmark/index.html` (for a quick, rough run, pass shorter settings such as `-Pbenchmark.batchMillis=50 -Pbenchmark.batches=5`, see `stack-augmentor-it/benchmark/build.gradle.kts`); committed to master, it is published on [GitHub Pages](https://hafthalion.github.io/stack-augmentor/). This needs JDK 25.
