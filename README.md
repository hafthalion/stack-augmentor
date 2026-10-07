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
| Cost | a few µs per configured frame the exception leaves | exceptions pay a walk of the live stack down to the last configured frame | as the agent |
| Needs at runtime | the agent jar | the agent jar and the native library | `stack-augmentor-runtime` on the classpath |
| Example | `./gradlew :examples:java-agent:run` | `./gradlew :examples:live-agent:run` | `./gradlew :examples:build-time:run` |

An application with classes instrumented at build time can also run with the agent, in either mode.

## Options

| Option | Where | Default | What it does |
|---|---|---|---|
| Configuration file | `-javaagent:...=config=<file>`, `-Dstackaugmentor.config=<file>`, or (build-time) `stack-augmentor.toml` on the classpath | none: nothing is augmented | the TOML file with everything below |
| `[augment.receiver]` | configuration | empty | which classes show a receiver id, and where it comes from |
| `[augment.params]` | configuration | empty | which methods show parameter ids, and which parameters |
| `[augment]` | configuration | see [Formats](docs/configuration.md#formats) | how frames look: `frameFormat`, `receiverFormat`, `paramsFormat`, `maxIdLength` |
| `debug` | configuration | `false` | prints what gets instrumented and where each id comes from |
| `inPlaceModification` | configuration, or `-Dstackaugmentor.inPlaceModification=true` | `false` | writes each frame into the exception's own stack trace instead of copying the trace; faster for deep stacks, needs `java.lang` open (see [Limitations](docs/limitations.md)) |
| Live stack | `-agentpath:<native library>` | off | the agent reads ids from the live stack instead of instrumenting classes (see [Live stack](docs/live-stack.md)) |

See [Configuration](docs/configuration.md) for the file in full.

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

## Limitations in short

- Only frames the exception leaves get ids, except in the live stack mode.
- Constructors show parameter ids only.
- An exception thrown more than once keeps the ids of its first throw.
- Each configured frame an exception leaves costs a few microseconds; normal returns cost nothing.
- Changed class and method names may confuse tools that parse stack traces.

## More documentation

- [Where ids come from](docs/ids.md): receiver and parameter ids, subclasses, interfaces, constructors, hashed values
- [Configuration](docs/configuration.md): the file in full, and the frame formats
- [Causes, root cause first](docs/causes.md): `%rEx` in Logback and Log4j 2, and `ExceptionFormat.rootCauseFirst` in the API
- [Live stack](docs/live-stack.md): the experimental mode with the native library
- [Limitations](docs/limitations.md): the details, with measured costs
- [How it works](docs/how-it-works.md): the instrumentation, the modules and packages

Build and test with `./gradlew build`. `./run.sh [java-agent|live-agent|build-time|benchmark]` (or `run.bat` on Windows) builds the project and runs that example module from `examples`, or the benchmark, and asks which one without an argument: `java-agent` with a simple cause chain printed by `printStackTrace` and root cause first; `live-agent` twice, with the agent alone and in the live-stack mode if a C compiler (`cc`, or MinGW `gcc` on Windows) built the native library; `build-time` without an agent; `benchmark` runs `:stack-augmentor-it-benchmark:benchmark`, which writes `stack-augmentor-it-benchmark/build/reports/benchmark/index.html`. This needs JDK 25.
