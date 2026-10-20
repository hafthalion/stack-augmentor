# Overview Specification

## Purpose
Describes Stack Augmentor as a whole: its goal, the words the other specifications use, the ways it runs, how a frame
is decided and rewritten, and which specification owns which rule. Read it first; the other specifications refine it
and do not repeat it.

## Requirements

### Requirement: Goal
Stack Augmentor SHALL augment the frames of Java stack traces with values that identify what the failing code was
working on: members of the object a frame's method runs on (receiver members) and selected arguments of the method
(parameter values). The augmentation SHALL be written into the frames themselves (`StackTraceElement`s), so that every
printer and logger shows it without changes. Frames and exceptions that are not selected SHALL stay as they are, the
application's behaviour SHALL not change otherwise (see the reliability specification), and a method that returns
normally SHALL pay no more than a null check (see the performance specification).

#### Scenario: Augmented frame
- **GIVEN** `ObjectClass.objectMethod(orderId = 42)` running on an object whose receiver member `objectId` is `object-1`, and the default formats
- **WHEN** an exception that `[augment.exceptions]` selects leaves `objectMethod` and is printed with `printStackTrace`
- **THEN** its frame reads `com.hafnium.ObjectClass{objectId=object-1}.objectMethod{orderId=42}(ObjectClass.kt:10)`, and the other frames are unchanged

### Requirement: Terminology
The specifications, the documentation and the messages SHALL use these words:
- **frame**: one `StackTraceElement` of a throwable's stack trace, of the method running at that point;
- **declaring class**: the class that declares a frame's method, as the frame names it, whatever the runtime class of the object;
- **receiver members**: fields or no-argument methods of the object a frame's method runs on, shown with their values;
- **parameter values**: arguments of a frame's method, shown with the parameter names;
- **augmented**: a frame is augmented when it shows receiver members or parameter values; an exception is augmented when
  `[augment.exceptions]` selects it, so that the frames it leaves are augmented;
- **entry**: one key and value of a configuration table; the **deciding entry** of a method is the first entry of a table,
  in file order, that matches it;
- **instrumented**: a class or method to which the agent or the build plugin added code.

They SHALL NOT say that frames or exceptions "get ids": "receiver members", "parameter values" and "augmented" replace it.

#### Scenario: Documentation wording
- **GIVEN** the README, `docs/` and these specifications
- **WHEN** they describe what a frame shows
- **THEN** they speak of receiver members and parameter values, and of augmented frames and exceptions

### Requirement: Ways to run
Stack Augmentor SHALL offer three ways to run, all with the same configuration file and the same frame format:
- **Java agent** (`-javaagent`, see the Java agent specification): instruments the classes that need it when they are
  loaded, third-party classes included, and augments the frames an exception leaves;
- **live-stack mode** (the agent with the native library, experimental, see the live-stack specification): instruments
  only `java.lang.Throwable` and reads the values of every configured frame from the live stack when an exception is
  created, also of the frames that catch it;
- **build-time instrumentation** (the ByteBuddy build plugin, see the build-time instrumentation specification):
  instruments the project's own compiled classes; at runtime only `stack-augmentor-api` and `stack-augmentor-runtime`
  are needed, no agent.

The first and the third are called the instrumenting modes. Classes instrumented at build time SHALL also run under the
agent, in either of its modes (see the Java agent specification).

#### Scenario: Same frames in every way
- **GIVEN** the same annotated class and the same configuration
- **WHEN** an exception leaves one of its methods, once with the agent and once with build-time instrumentation
- **THEN** the frame looks the same

### Requirement: Selection of what a frame shows
For each frame, the system SHALL decide independently:
1. whether the exception is augmented at all: the deciding `[augment.exceptions]` entry for the throwable's class is `true`;
2. the receiver members of the method: its deciding `[augment.receiver]` entry, `"-"` meaning none; without one, the
   `@AugmentedReceiver` members, if the deciding `[augment.annotations]` entry for the declaring class is `true` and
   `@Augmented` selects the method; otherwise none. Constructors and static methods have none;
3. the parameter values of the method: its deciding `[augment.params]` entry, `"-"` meaning none; without one, its
   `@AugmentedParam` and `@AugmentedParams` parameters under the same two conditions; otherwise none.

The configuration specification defines the tables and how keys match, the receivers and parameters specification what
the entries and annotations select, and the stack trace augmentation specification which methods are instrumented and how
frames are rewritten.

#### Scenario: Names before annotations
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, `"com.acme.Order::*" = "getId()"` in `[augment.receiver]`, no `[augment.params]` entries, and `com.acme.Order` with `@Augmented`, `@AugmentedReceiver val code` and `fun ship(@AugmentedParam to: String)`
- **WHEN** an augmented exception leaves `ship("Main St")`
- **THEN** the frame shows `Order{getId=…}.ship{to=Main St}`: the entry decides the receiver members, the annotation the parameter values

### Requirement: Processing
In the instrumenting modes, the system SHALL work in these steps:
1. **Configuration**: the agent at JVM start, the build plugin at build time and the runtime of build-time classes at the
   first exception read and validate the TOML file, or the built-in configuration without one (configuration specification);
2. **Instrumentation**: methods with receiver members or parameter values get inlined exit code that, only when a
   throwable leaves, passes the receiver, the key of the deciding `[augment.receiver]` entry, the selected arguments and
   their labels to `Dispatch` (design specification);
3. **Handling**: the one installed handler checks `[augment.exceptions]`, reads the receiver members and converts every
   value to text (receivers and parameters specification);
4. **Rewriting**: it finds the method's frame in the throwable's stack trace, replaces it with an augmented one laid out
   by the frame format, and writes the same frame into the causes and suppressed exceptions that share it (stack trace
   augmentation and frame format specifications).

The live-stack mode SHALL apply steps 1, 3 and 4 with the same rules, but capture the values from the live stack when the
throwable records its stack trace, instead of step 2.

#### Scenario: Normal return
- **GIVEN** an instrumented method
- **WHEN** it returns normally
- **THEN** no handler is called and nothing is allocated

### Requirement: Specifications
The specifications SHALL each own one subject, and refer to the owner instead of repeating its rules:

| Specification | Subject |
|---|---|
| functional/overview | goal, terminology, ways to run, processing |
| functional/configuration | the file, where it comes from, the built-in configuration, tables, key syntax and matching, validation |
| functional/receivers-and-parameters | what entries and annotations select, labels, values as text, hashing |
| functional/stack-trace-augmentation | instrumented methods, capture, exception selection, rewriting, causes |
| functional/frame-format | `frameFormat`, `receiverFormat`, `paramsFormat` |
| functional/java-agent | starting the agent, instrumenting at class load, build-time classes under the agent |
| functional/live-stack | the experimental live-stack mode |
| functional/build-time-instrumentation | the build plugin and the runtime without an agent |
| functional/exception-format | `ExceptionFormat.rootCauseFirst` |
| functional/diagnostics | errors, warnings and debug messages |
| non-functional/* | compatibility, design, known limitations, packaging, performance, reliability, toolchain |

The README and `docs/` SHALL describe the same behaviour for users; `docs/configuration.md` is the full reference of the
file.

#### Scenario: A rule changes
- **GIVEN** a change to how keys match
- **WHEN** the specifications are updated
- **THEN** only the configuration specification states the rule, and the others refer to it
