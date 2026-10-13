# Where ids come from

[Back to the README](../README.md)

The two kinds of ids are configured independently: `[augment.receiver]` decides the receiver ids, `[augment.params]` the parameter ids. Neither table affects the other. Which classes and methods get instrumented is not configured directly: whatever either table needs is instrumented behind the scenes.

**Keys.** Both tables have the same keys, `"<class>::<methods>"`, which match a frame by the class that declares its method and by the method's name:

- the class is a class name, which matches that class only; with `+` after it, e.g. `"com.acme.Order+::*"`, also its subclasses, and for an interface the classes implementing it, as `instanceof` does; or a pattern with `*` (within a package or name), `**` (across packages) and `?` (one character), which matches class names only, not subclasses elsewhere. `+` goes after class names only;
- the methods are names or patterns separated by commas, e.g. `"com.acme.Order::ship, cancel*"`, `*` for all of them, and `@` for those annotated with `@StackTraceAugment` (`"com.acme.Order::@"`); the part after `::` is required. Constructors are named `<init>`, in `[augment.params]` only.

For each method, the first entry in the file whose class and methods match decides, as in `[augment.exceptions]`, so put the more specific entries first. The keys contain `::`, so they are quoted.

**Receiver ids**: the object a frame runs on. They come from the deciding `[augment.receiver]` entry of the frame's method, and are read from the object. The entry names:

- a field, or a no-argument `method()`, looked up in that class and its superclasses (for an interface: in it and the interfaces it extends); a method or getter is also found as a default method of an implemented interface; a name without a field, such as a Kotlin property of an interface, uses the property's getter (`code` reads `getCode()`);
- several of them separated by commas, e.g. `"tenant, lineId()"`: one id each, in this order, e.g. `OrderLine{tenant=acme, lineId=3}.cancel`. A member that is missing is left out, with the same warning as for a single one;
- `"@"`: every `@StackTraceId` on a field, a no-argument method, or (in Kotlin) a primary-constructor `val`, one id each: fields first, in declaration order, then methods by name, starting with the class itself and then its superclasses; or
- `"-"`: nothing, so the class gets no receiver id. Its parameter ids are not affected.

`"com.thirdparty.Parcel::ship, cancel*" = "trackingCode"` shows `Parcel{trackingCode=…}.ship` and `Parcel{trackingCode=…}.cancelLater`, while `Parcel.weigh` stays without receiver id (its parameter ids are not affected). `"com.acme.Dispatcher::@" = "hub"` shows the ids in the methods annotated with `@StackTraceAugment` only. A class may have several entries, e.g. `"com.thirdparty.Parcel::weigh" = "weight"` after the one above gives `weigh` its own id. Constructors never show a receiver id, so `<init>` is not allowed here.

`"com.acme.Order::*" = "getId()"` before `"com.acme.**::*" = "@"` gives `Order` its `getId()`, and `"com.acme.generated.**::*" = "-"` before it takes the generated classes out; `"com.acme.Order::export" = "-"` before `"com.acme.Order::*" = "getId()"` takes one method out. An entry after one that already decides a method does not apply to it. A method without a deciding entry, or whose entry names members its class does not have, gets no receiver id. Every id is the member's value as text, from `toString()`.

A frame's method belongs to the class that declares it, so the methods a subclass inherits show the id of the superclass's entry, while its own methods need an entry that matches the subclass: its own, or the superclass's with `+`:

```toml
[augment.receiver]
"com.acme.TrackedOrder::*" = "tracking"   # TrackedOrder extends Order: before Order's entry, which matches it too
"com.acme.Order+::*" = "id"
```

A `TrackedOrder` shows `Order{id=…}.ship` in the frame of `Order.ship()`, and `TrackedOrder{tracking=…}.track` in the frame of its own `track()`. Without its own entry, `track()` would show `TrackedOrder{id=…}.track`, from `Order`'s entry, and without the `+` it would show no receiver id. The same holds for subclasses generated at runtime: a Spring CGLIB proxy `Order$$SpringCGLIB$$0`, a Hibernate proxy `Order$HibernateProxy$…`, a Mockito mock `Order$MockitoMock$…`, an anonymous subclass, or an enum constant with a body show `Order`'s id in `Order`'s frames, and with `+` also in their own overrides. A pattern such as `"com.acme.*::*"` matches them only by name (it matches `com.acme.Order$$SpringCGLIB$$0`, since `*` stops only at a `.`, but not a subclass in another package).

Default methods of interfaces work the same way: with `"com.acme.Labeled::*" = "label()"`, the frame of `Labeled.relabel()` shows `Labeled{label=…}` whatever class implements it, with `label()` called on the object. The implementing class's entry does not apply to it, and without an entry for `Labeled` the frame is unchanged. With `"com.acme.Labeled+::*"` the entry also matches the implementing classes, so their own methods show `label()` too, unless an earlier entry matches them. Bridge methods are never instrumented, including the one Kotlin compiles into each implementing class to call the default method, so its frame stays as it is.

The label is the real field or method name (`{objectId=…}`, `{getKey=…}`); it cannot be changed.

**Parameter ids** are shown after the method name, in declaration order. `[augment.params]` entries select them (see [Configuration](configuration.md)), with the same keys: `"com.acme.Order::ship, cancel*, @"`, `"com.acme.Order::*"` for all methods of the class, `"com.acme.Order+::process"` for `process` of `Order` and of its subclasses. `@` matches the methods and constructors annotated with `@StackTraceAugment` (the others are left to the entries after it). The entry names the parameters:
- by name or by 0-based index (0 to 255), separated by commas, e.g. `"order, 2"`;
- `"@"`: the parameters the method's annotations select: `@StackTraceParam` on a parameter, or `@StackTraceParams` on the method (all its parameters; not of methods that override it);
- `"-"`: none.

There is no wildcard for the parameters themselves: each one is named, or selected by an annotation.

**Constructors** are named `<init>`, as in stack traces: `"com.acme.Shipment::<init>" = "orderId"`, or `"com.acme.**::<init>" = "@"` for the annotations of constructors only (`@StackTraceParams` works on constructors too). Wildcards in the method name match them like any method, so `"com.acme.**::*" = "@"` covers the constructors' annotations as well; an entry before it such as `"com.acme.Shipment::<init>" = "-"` takes them out. A constructor frame shows parameter ids only, never a receiver id, e.g. `com.acme.Shipment.<init>{orderId=42}`, for exceptions thrown in its body (including Kotlin `init` blocks) and while computing the arguments of its `super(...)` or `this(...)` call, e.g. `super(requireNonNull(code))`. An exception thrown inside the called constructor itself leaves the frame unchanged.

**Hashed parameter ids.** A `#` after a name or an index in `[augment.params]` (`"user, email#, 2#"`), or `@StackTraceParam(secret = true)` on the parameter (also under `@StackTraceParams`, which then shows the others as text), shows the value as a short hash instead of its text: `#` and the first 8 hex digits of the SHA-256 of the `toString()`, e.g. `invite{user=ann, email=#71d4f55f}`. The same value always gives the same hash, so a value can still be followed across log lines and incidents without appearing in them. `null` stays `null`. When a parameter is selected twice (by name and by index, or by both annotations), it is hashed if either hashes it. The hash is not salted: values from a small set, e.g. PINs, can be found by trying them all, so leave such parameters out rather than hashing them.

The keys match the class that declares the method, and with `+` its subclasses. When several entries match a method, only the first one in the file decides, as in `[augment.receiver]`. Entries are not combined, so a `"-"` takes a method out of the entries after it:

```toml
[augment.params]
"com.thirdparty.InventoryAudit::log" = "reason"   # log's first parameter ...
"com.thirdparty.InventoryAudit::*" = "-"            # ... but no other method of InventoryAudit ...
"com.thirdparty.Inventory*::*" = "sku, count"   # ... and these parameters of the other Inventory* classes
```

Because the tables are independent, a class can take its receiver id from an explicit entry and still use its parameter annotations, or ignore its receiver and keep its parameters:

```toml
[augment.receiver]
"com.acme.Order::*" = "getId()"
"com.acme.generated.**::*" = "-"      # no receiver ids ...

[augment.params]
"com.acme.Order::*" = "@"
"com.acme.**::*" = "@"              # ... but their parameter annotations still count
```

The label is the parameter name compiled into the class file, so compile Java code with `javac -parameters` (Kotlin: `javaParameters = true`); without it, the label is `arg<N>`. The value is the argument's `toString()` (arrays with their elements), even when its class has an `[augment.receiver]` entry: that table only applies to receivers. Give a class a `toString()` to control how it appears as an argument.

All ids become Strings when they are captured. Line breaks are replaced by a space, `(` and `)` by `{` and `}` (so that IDEs still find the frame's `(File.kt:12)`, e.g. after a data class's `Point(x=1)`), the length is capped at `maxIdLength`, and an id source that throws shows `?`.
