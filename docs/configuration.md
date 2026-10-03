# Configuration

[Back to the README](../README.md)

The configuration is a TOML file (ending in `.toml`). `[augment]` sets how frames look, `[augment.receiver]` which receivers get ids and `[augment.params]` which parameters:

```toml
# Print diagnostics to stderr: the configuration, which classes and methods get instrumented and why,
# where each id comes from, and config entries or annotations that have no effect.
debug = false

# Write each frame into the exception's own stack trace instead of copying the trace (see limitations.md).
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

## Formats

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
