# Configuration

[Back to the README](../README.md)

Everything Stack Augmentor does is set in one TOML file. It says which exceptions are augmented, which frames show receiver members and which show parameter values, and how the frames look. Without a file nothing is augmented.

- [The file at a glance](#the-file-at-a-glance)
- [Where the file comes from](#where-the-file-comes-from)
- [Keys: classes and methods](#keys-classes-and-methods)
- [Exceptions](#exceptions): `[augment.exceptions]`
- [Receiver members](#receiver-members): `[augment.receiver]`
- [Parameter values](#parameter-values): `[augment.params]`
- [Formats](#formats): `[augment]`
- [Root options](#root-options): `debug`, `inPlaceModification`
- [Errors and warnings](#errors-and-warnings)
- [Tips](#tips)

[Receivers and parameters](receivers-and-parameters.md) explains what the entries do to a frame in more detail: inherited methods, interfaces, constructors and hashed values.

## The file at a glance

```toml
debug = false                  # print what gets instrumented and where each value comes from
inPlaceModification = false    # write frames into the exception's own stack trace instead of copying it

[augment]                      # how frames look (these are the defaults)
frameFormat = "$class$receiver.$method$params"
receiverFormat = "{$name=$value, ...}"
paramsFormat = "{$name=$value, ...}"
maxValueLength = 64

[augment.exceptions]           # which exceptions are augmented: the first matching entry decides
"java.io.FileNotFoundException+" = false
"java.lang.Exception+" = true

[augment.receiver]             # which frames show receiver members, and which ones
"com.acme.generated.**::*" = "-"
"com.acme.**::*" = "@"
"com.thirdparty.Order+::*" = "getOrderNumber()"
"com.thirdparty.OrderLine::*" = "tenant, lineId()"
"com.thirdparty.Parcel::ship, cancel*" = "trackingCode"

[augment.params]               # which frames show parameter values, and which parameters
"com.acme.**::*" = "@"
"com.thirdparty.OrderService+::process" = "order, 2"
"com.thirdparty.UserService::invite" = "user, email#"
"com.thirdparty.Shipment::<init>" = "orderId"
```

| Key | Value | Default | Section |
|---|---|---|---|
| `debug` | `true` or `false` | `false` | [Root options](#root-options) |
| `inPlaceModification` | `true` or `false` | `false` | [Root options](#root-options) |
| `[augment]` `frameFormat` | template | `"$class$receiver.$method$params"` | [Formats](#formats) |
| `[augment]` `receiverFormat` | template | `"{$name=$value, ...}"` | [Formats](#formats) |
| `[augment]` `paramsFormat` | template | `"{$name=$value, ...}"` | [Formats](#formats) |
| `[augment]` `maxValueLength` | 2 to 10000 | `64` | [Formats](#formats) |
| `[augment.exceptions]` | `"<class>" = true` or `false` | empty: no exception is augmented | [Exceptions](#exceptions) |
| `[augment.receiver]` | `"<class>::<methods>" = "<members>"` | empty | [Receiver members](#receiver-members) |
| `[augment.params]` | `"<class>::<methods>" = "<parameters>"` | empty | [Parameter values](#parameter-values) |

TOML in short: `#` starts a comment, a key with `::`, `*` or `+` in it must be quoted, an unquoted key with `.` is read as nested tables (which works for `[augment.exceptions]` class names only), and dotted keys (`augment.maxValueLength = 32`) work as well as sections. The order of the tables does not matter. The order of the entries within `[augment.exceptions]`, `[augment.receiver]` and `[augment.params]` does: the first entry that matches decides.

## Where the file comes from

The file name must end in `.toml`.

| Who reads it | Where from | What it uses |
|---|---|---|
| Java agent, also in the live-stack mode | `-javaagent:stack-augmentor-agent.jar=config=<file>` (or just `=<file>`), otherwise `-Dstackaugmentor.config=<file>` | everything, at JVM start |
| Build plugin | the plugin's argument in `build.gradle.kts` (see the [README](../README.md#quick-start-build-time-instrumentation)) | `[augment.receiver]` and `[augment.params]`, to decide which classes and methods to instrument and which parameters they pass; `debug`. It validates the whole file and warns if no `[augment.exceptions]` entry is `true` |
| Runtime of classes instrumented at build time | `-Dstackaugmentor.config=<file>`, otherwise `stack-augmentor.toml` on the classpath (in an application server also from the context class loader of the first throwing thread) | `[augment]`, `[augment.exceptions]`, `[augment.receiver]`, `debug`, `inPlaceModification`; the parameters were chosen at build time |

For build-time instrumentation, one file in `src/main/resources` serves both the build and the runtime. A frame's receiver members come from the runtime entry with the same key as the entry that decided its method at build time, so if you change a key, rebuild. Without a runtime file, no exception is augmented and a warning says so.

## Keys: classes and methods

`[augment.receiver]` and `[augment.params]` have the same keys, `"<class>::<methods>"`. They match a frame by the class that declares its method and by the method's name. `[augment.exceptions]` keys are just the `<class>` part.

**The class** is one of:

- **a class name**, e.g. `com.acme.Order`: that class only. Nested classes are written with `$`, as in stack traces: `com.acme.Order$Line`.
- **a class name with `+`**, e.g. `com.acme.Order+`: the class and its subclasses, and for an interface the classes implementing it, as `instanceof` does. The supertypes are known by name, so no class is loaded for it.
- **a pattern**, which matches class names only, never subclasses elsewhere:
  - `*` any characters within one package (or name), e.g. `com.acme.*` or `com.acme.*Repository`;
  - `**` any characters across packages, e.g. `com.acme.**`, or `**` for every class;
  - `?` one character.

  A pattern takes no `+`.

**The methods**, after `::`, are required. They are names or patterns separated by commas:

- a name, e.g. `ship`, or a pattern with `*` and `?`, e.g. `cancel*`;
- `*` for all methods;
- `@` for the methods annotated with `@Augmented` (from `stack-augmentor-api`; it has no attributes);
- `<init>` for the constructors, in `[augment.params]` only, since constructors show no receiver members. Patterns such as `*` match constructors too.

Keys can contain letters, digits, `_`, `$` and the wildcards. Spaces around the commas don't matter.

| Key | Matches |
|---|---|
| `"com.acme.Order::ship"` | `Order.ship` |
| `"com.acme.Order::ship, cancel*"` | `Order.ship` and `Order`'s methods starting with `cancel` |
| `"com.acme.Order::*"` | all methods of `Order` |
| `"com.acme.Order::@"` | `Order`'s methods annotated with `@Augmented` |
| `"com.acme.Order::ship, @"` | `Order.ship` and the annotated ones |
| `"com.acme.Order+::*"` | all methods of `Order`, of its subclasses and, for an interface, of its implementing classes |
| `"com.acme.*::*"` | all methods of the classes in `com.acme`, including nested classes such as `com.acme.Order$Line` |
| `"com.acme.**::*"` | all methods of the classes in `com.acme` and its subpackages |
| `"com.acme.**.*Repository::find*"` | the `find…` methods of every `…Repository` class under `com.acme` |
| `"com.acme.Shipment::<init>"` | the constructors of `Shipment` (`[augment.params]` only) |

**The first match decides.** For each method, the first entry in the file whose class and methods match decides, so put the specific entries before the general ones. One class may have several entries, e.g. one per group of methods. Entries are never combined: the entry that decides gives everything this table shows for that method, and `"-"` gives nothing.

```toml
[augment.receiver]
"com.acme.Order::export" = "-"          # no receiver members in export ...
"com.acme.Order::*" = "getId()"         # ... getId() in Order's other methods ...
"com.acme.generated.**::*" = "-"        # ... nothing in the generated classes ...
"com.acme.**::*" = "@"                  # ... and @AugmentedReceiver in the rest of com.acme
```

A method belongs to the class that declares it: a subclass's own methods need an entry that matches the subclass, its own or the superclass's with `+`, while the methods it inherits use the superclass's entry. [Receivers and parameters](receivers-and-parameters.md) has examples, including proxies, mocks and interface default methods.

## Exceptions

`[augment.exceptions]` says which exceptions are augmented: `true` or `false` per class or pattern. The first entry that matches the class of the thrown exception decides, in the order of the file. An exception that no entry matches is not augmented.

```toml
[augment.exceptions]
"java.io.FileNotFoundException+" = false   # not this one and its subclasses ...
"java.io.IOException+" = true              # ... but the other IOExceptions
"com.acme.flow.**" = false                 # not these classes (their subclasses elsewhere are not matched)
"com.acme.**" = true
```

- **Without this table, or with an empty one, no exception is augmented.** `"java.lang.Exception+" = true` augments all exceptions, `"**" = true` all throwables, errors included. To exclude only some, put their `false` entries before it.
- **The keys are classes as above, without methods**: a class name matches that class only, `+` after it also its subclasses, and a pattern the name of the exception's class only.
- **Unquoted class names work too**, since TOML reads `java.io.IOException = true` as nested tables, which mean the same. Keys with `+` or wildcards must be quoted.
- **It is checked when an exception leaves an instrumented method** (in the live-stack mode: when it is created). Classes are instrumented as the other two tables say, whatever this table contains.
- **Causes:** the frames an exception that is not augmented leaves stay as they are, also where its causes share them. An augmented exception still writes its frames into the causes that share them, whatever their class, so printed traces still collapse into `... N more`, in both modes. See [Causes](causes.md).

## Receiver members

`[augment.receiver]` says which frames show members of the object the method runs on, and which members. The value is a string:

| Value | Receiver members |
|---|---|
| `"orderId"` | the field `orderId`, looked up in the class and its superclasses; without such a field, its getter (`getOrderId()`, as for Kotlin properties of interfaces) |
| `"getOrderId()"` | the result of the no-argument method `getOrderId()`, also a default method of an implemented interface |
| `"tenant, lineId()"` | several of them, one value each, in this order: `OrderLine{tenant=acme, lineId=3}.cancel` |
| `"@"` | every `@AugmentedReceiver` field, no-argument method or (Kotlin) primary-constructor `val` of the class and its superclasses |
| `"-"` | none, also for the entries after it that would match |

- **Each member shows its value as text**, from `toString()`, labelled with the member's real name: `{orderId=42}`, `{getKey=…}`.
- **`@AugmentedReceiver` counts only through an `"@"` entry.** An annotated class without one shows no receiver members.
- **A member the class does not have is left out**, with a warning (see [Errors and warnings](#errors-and-warnings)).
- **The methods go in the key, not in the value**: `"com.acme.Parcel::weigh" = "weight"`.
- **Constructors never show receiver members**, so `<init>` is not allowed in these keys.

## Parameter values

`[augment.params]` says which frames show parameter values, and which parameters. They are shown after the method name, in declaration order. The value is a string:

| Value | Parameter values |
|---|---|
| `"order"` | the parameter named `order` |
| `"2"` | the third parameter (0-based index, 0 to 255) |
| `"order, 2"` | several of them, by name or index |
| `"user, email#, 3#"` | `#` after a name or index shows the value hashed: `email=#71d4f55f` |
| `"@"` | the parameters the annotations select: `@AugmentedParam` on a parameter, `@AugmentedParams` on the method (all its parameters); `@AugmentedParam(secret = true)` hashes |
| `"-"` | none, also for the entries after it that would match |

- **There is no wildcard for parameters.** Each one is named, indexed or selected by an annotation.
- **Labels are the parameter names compiled into the class**, so compile Java with `javac -parameters` (Kotlin: `javaParameters = true`); otherwise they are `arg0`, `arg1`, ….
- **The value is the argument's `toString()`** (arrays with their elements), read when the exception leaves the method.
- **A hash** is `#` and the first 8 hex digits of the SHA-256 of the `toString()`, the same for the same value, so it can be followed across log lines. It is not salted: leave values from a small set, such as PINs, out rather than hashing them. `null` stays `null`.
- **Constructors** are `<init>`: `"com.acme.Shipment::<init>" = "orderId"` gives `com.acme.Shipment.<init>{orderId=42}`.
- **Static methods** work as instance methods do; they just have no receiver members.

`[augment.receiver]` and `[augment.params]` are independent: a class can take its receiver members from explicit names and its parameter values from annotations, or have only one of them.

## Formats

`[augment]` sets how an augmented frame is written. It is read at runtime, in every mode.

| Key | Placeholders |
|---|---|
| `frameFormat` | `$class`, `$simpleClass`, `$method`, `$receiver`, `$params` |
| `receiverFormat` | `$name`, `$value`, and `...` to mark repetition. Renders empty when the frame has no receiver members. |
| `paramsFormat` | `$name`, `$value`, and `...` to mark repetition. Renders empty when the method has no parameter values. |
| `maxValueLength` | longer values are cut to this many characters (2 to 10000, default 64) |

- **`receiverFormat` and `paramsFormat`** are split as follows. The text before the first placeholder and the text after `...` wrap the list. The part from the first to the last placeholder is repeated for each item. The text between the last placeholder and `...` separates the items. So `[$name: $value; ...]` renders `[orderId: 42; customer: 7]`. Without `...`, the whole template is repeated, separated by `,`: `<$value>` renders `<acme>,<3>` for two receiver members.
- **In all three templates**, placeholders start with `$` and everything else is literal, braces included; `$$` is a literal `$`. A placeholder name ends at the first character that is not a letter or digit, so `$class$receiver.$method$params` needs no separators. When letters or digits follow directly, write the name in braces: `${method}X` renders `processX`, while `$methodX` is rejected as an unknown placeholder. A brace without a `$` before it is literal.
- **`(` and `)` are not allowed**, because IDEs find a frame's file and line by the `(File.java:12)` at its end. Values have theirs replaced by `{` and `}` for the same reason, and line breaks by a space.
- **`frameFormat` must contain `.$method` (or `.${method}`) exactly once**, because the JDK always prints `<class>.<method>(<file>:<line>)`.

| Setting | Frame |
|---|---|
| defaults | `com.hafnium.ObjectClass{objectId=1}.process{orderId=42}(ObjectClass.java:13)` |
| `frameFormat = "$class.$method$receiver$params"` | `com.hafnium.ObjectClass.process{objectId=1}{orderId=42}(ObjectClass.java:13)` |
| `frameFormat = "$simpleClass$receiver.$method$params"` | `ObjectClass{objectId=1}.process{orderId=42}(ObjectClass.java:13)` |
| `receiverFormat = "<$value>"` | `com.hafnium.ObjectClass<1>.process{orderId=42}(ObjectClass.java:13)` |
| `receiverFormat = "[$name=$value, ...]"`, `paramsFormat = "[$name=$value, ...]"` | `com.hafnium.ObjectClass[objectId=1].process[orderId=42](ObjectClass.java:13)` |

## Root options

- **`debug = true`** prints diagnostics to stderr: the configuration and where it came from, which classes and methods get instrumented and by which entry, where each value comes from, and annotations or entries that have no effect. Startup messages start with their source: `agent:`, `build plugin:` or `runtime:` (classes instrumented at build time, at runtime); the others name the class or the entry they are about.
- **`inPlaceModification = true`** writes each frame into the exception's own stack trace instead of copying the trace, which is faster for deep stacks (see [Limitations](limitations.md)). It needs `java.lang` open: the agent opens it; with build-time instrumentation, start the application with `--add-opens java.base/java.lang=ALL-UNNAMED`, otherwise the agent stops the JVM at startup, and the runtime of build-time classes prints an error and leaves stack traces unchanged. `-Dstackaugmentor.inPlaceModification=true|false` overrides the file.

## Errors and warnings

**An invalid configuration stops the JVM (with the agent) or the build at startup**, naming the file, the key and its line:

```
[stack-augmentor] ERROR agent: stack-augmentor.toml, line 3: maxValueLength must be between 2 and 10000, was 1
```

With build-time instrumentation, the runtime reads its configuration when the first exception needs it; it then prints such an error and leaves the stack traces unchanged, and the application keeps running. Rejected are:

- invalid TOML, and values of the wrong type, e.g. an array instead of a comma-separated string;
- unknown keys, so typos don't go unnoticed (the message lists the allowed ones);
- invalid keys, e.g. without `::`, with `+` after a pattern, or `<init>` in `[augment.receiver]`;
- invalid values, e.g. an index above 255, an empty item in `"a,,b"`, or a method, receiver member or parameter listed twice (`"email, email#"` too; a name and an index of the same parameter are allowed, and it is shown once);
- the same key twice, also written differently: `"com.acme.Order"` and unquoted `com.acme.Order`, or `"Order::a, b"`, `"Order::a,b"` and `"Order::b, a"`;
- templates with `(`, `)`, an unknown placeholder, or without `.$method`.

**Warnings** don't stop anything:

- no configuration file, or one without `[augment.receiver]` and `[augment.params]` entries: nothing is augmented;
- receiver or parameter entries, but no `[augment.exceptions]` entry that is `true`: no exception would be augmented;
- a receiver member the class does not have, for a key with a class name. For a pattern it is a debug message, since a pattern may match classes that don't have it. So are parameter names that a method does not have.

## Tips

- **Put specific entries first**, general ones (`"com.acme.**::*"`) last.
- **Keep patterns narrow.** `"com.**::*"` makes the agent instrument many classes, which slows down class loading.
- **Prefer annotations in your own code** (`"com.acme.**::*" = "@"` in both tables), and explicit members for classes you cannot change.
- **Use `+` for class hierarchies and proxies**, e.g. `"com.acme.Order+::*"`, so that subclasses' own methods and overrides are augmented as well.
- **Turn on `debug`** when a frame does not show what you expect: it says which entry decided each method.
