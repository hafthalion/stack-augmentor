# Overview Specification

## Purpose
Describes Stack Augmentor as a whole: its goal, the words the other specifications use, the ways it runs, and how a frame
is decided. Read it first; the other specifications refine it and do not repeat it. All specifications state what the
system does as seen by its users, not how it is implemented.

## Requirements

### Requirement: Goal
Stack Augmentor SHALL augment the frames of Java stack traces with values that identify what the failing code was working
on: members of the object a frame's method runs on (receiver members) and selected arguments of the method (parameter
values). The augmentation SHALL be part of the stack trace itself, so that every printer and logger shows it without
changes. Frames and exceptions that are not selected SHALL stay as they are, the application SHALL behave as without
Stack Augmentor in every other respect, and methods that do not throw SHALL run at practically unchanged speed.

#### Scenario: Augmented frame
- **GIVEN** `ObjectClass.objectMethod(orderId = 42)` running on an object whose receiver member `objectId` is `object-1`, and the default formats
- **WHEN** an augmented exception leaves `objectMethod` and is printed with `printStackTrace`
- **THEN** its frame reads `com.hafnium.ObjectClass{objectId=object-1}.objectMethod{orderId=42}(ObjectClass.kt:10)`, and the other frames are unchanged

### Requirement: Terminology
The specifications, the documentation and the messages SHALL use these words:
- **frame**: one element of a throwable's stack trace, of the method running at that point;
- **declaring class**: the class that declares a frame's method, as the frame names it, whatever the runtime class of the object;
- **receiver members**: fields or no-argument methods of the object a frame's method runs on, shown with their values;
- **parameter values**: arguments of a frame's method, shown with the parameter names;
- **augmented**: a frame is augmented when it shows receiver members or parameter values; an exception is augmented when
  the configuration selects it, so that its frames can be augmented;
- **entry**: one key and value of a configuration table; the **deciding entry** of a method is the first entry of a table,
  in file order, that matches it;
- **instrumented**: a class or method that Stack Augmentor changed so that it can augment its frames.

They SHALL NOT say that frames or exceptions "get ids": "receiver members", "parameter values" and "augmented" replace it.

#### Scenario: Documentation wording
- **GIVEN** the README, `docs/` and these specifications
- **WHEN** they describe what a frame shows
- **THEN** they speak of receiver members and parameter values, and of augmented frames and exceptions

### Requirement: Ways to run
Stack Augmentor SHALL offer three ways to run, with the same configuration file and the same frame format:
- **Java agent** (see the Java agent specification): instruments the classes that need it when they are loaded,
  third-party classes included, without changes to the application's build; it augments the frames an exception leaves;
- **live-stack mode** (the agent with a native library, experimental, see the live-stack specification): reads the values
  of every configured frame from the stack when an exception records its stack trace, also of the frames that catch it;
- **build-time instrumentation** (see the build-time instrumentation specification): instruments the project's own classes
  when they are built; at runtime only a small runtime library is needed, no agent.

The first and the third are the instrumenting modes. Classes instrumented at build time SHALL also run under the agent, in
either of its modes.

#### Scenario: Same frames in every way
- **GIVEN** the same annotated class and the same configuration
- **WHEN** an exception leaves one of its methods, once with the agent and once with build-time instrumentation
- **THEN** the frame looks the same

### Requirement: What a frame shows
For each frame, the system SHALL decide independently:
1. whether the exception is augmented at all: the deciding `[augment.exceptions]` entry for its class is `true`;
2. the receiver members of the method: its deciding `[augment.receiver]` entry, `"-"` meaning none; without one, the
   `@AugmentedReceiver` members, if `@Augmented` selects the method; otherwise none. Constructors and static methods have none;
3. the parameter values of the method: its deciding `[augment.params]` entry, `"-"` meaning none; without one, its
   `@AugmentedParam` and `@AugmentedParams` parameters under the same condition; otherwise none.

What is instrumented SHALL follow from these rules and SHALL NOT be configured separately.

#### Scenario: Names before annotations
- **GIVEN** `"com.acme.Order::*" = "getId()"` in `[augment.receiver]`, no `[augment.params]` entries, and `com.acme.Order` with `@Augmented`, `@AugmentedReceiver val code` and `fun ship(@AugmentedParam to: String)`
- **WHEN** an augmented exception leaves `ship("Main St")`
- **THEN** the frame shows `Order{getId=…}.ship{to=Main St}`: the entry decides the receiver members, the annotation the parameter values

### Requirement: Specifications
Each specification SHALL own one subject and refer to the owner instead of repeating its rules:

| Specification | Subject |
|---|---|
| functional/overview | goal, terminology, ways to run, what a frame shows |
| functional/configuration | the files, where they come from, the configuration chain of a class, the built-in configuration, tables, key syntax and matching, validation |
| functional/receivers-and-parameters | what entries and annotations select, labels, values as text, hashing |
| functional/stack-trace-augmentation | which frames are augmented, when values are captured, exception selection, causes |
| functional/frame-format | `frameFormat`, `receiverFormat`, `paramsFormat` |
| functional/java-agent | starting the agent, which classes it instruments, build-time classes under the agent |
| functional/live-stack | the experimental live-stack mode |
| functional/build-time-instrumentation | the build plugin and the runtime without an agent |
| functional/exception-format | printing a cause chain root cause first |
| functional/diagnostics | errors, warnings and debug output |
| non-functional/design | architecture constraints between the parts |
| non-functional/compatibility | JDKs, languages, dependencies, output layout |
| non-functional/limitations | accepted limits of the design |
| non-functional/packaging | deliverables and what each way to run needs |
| non-functional/performance | cost on normal returns and on exceptions |
| non-functional/reliability | never changing application behaviour, failing fast |
| non-functional/toolchain | languages, build, tests, benchmark |

The README and `docs/` SHALL describe the same behaviour for users, and MAY go into implementation details that the
specifications leave open; `docs/configuration.md` is the full reference of the file.

#### Scenario: A rule changes
- **GIVEN** a change to how keys match
- **WHEN** the specifications are updated
- **THEN** only the configuration specification states the rule, and the others refer to it
