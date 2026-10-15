# Receivers and parameters

[Back to the README](../README.md)

Receiver members and parameter values are configured independently: `[augment.receiver]` decides the receiver members, `[augment.params]` the parameter values, by name (or index). Neither table affects the other. A method that no entry of a table matches uses the annotations instead, where `[augment.annotations]` selects its class and `@Augmented` the method (see [Annotations](#annotations) below). Which classes and methods get instrumented is not configured directly: whatever the tables and annotations need is instrumented behind the scenes.

**Keys.** Both tables have the same keys, `"<class>::<methods>"`, which match a frame by the class that declares its method and by the method's name:

- the class is a class name, which matches that class only; with `+` after it, e.g. `"com.acme.Order+::*"`, also its subclasses, and for an interface the classes implementing it, as `instanceof` does; or a pattern with `*` (within a package or name), `**` (across packages) and `?` (one character), which matches class names only, not subclasses elsewhere. `+` goes after class names only;
- the methods are names or patterns separated by commas, e.g. `"com.acme.Order::ship, cancel*"`, or `*` for all of them; the part after `::` is required. Constructors are named `<init>`, in `[augment.params]` only.

For each method, the first entry in the file whose class and methods match decides, as in `[augment.exceptions]`, so put the more specific entries first. The keys contain `::`, so they are quoted. `"@"` is not allowed in these tables, neither as a value nor in a key: they name members and parameters, and `[augment.annotations]` says where the annotations are read.

**Receiver members**: members of the object a frame runs on. They come from the deciding `[augment.receiver]` entry of the frame's method, or without one from its class's `@AugmentedReceiver` members, and are read from the object. The entry names:

- a field, or a no-argument `method()`, looked up in that class and its superclasses (for an interface: in it and the interfaces it extends); a method or getter is also found as a default method of an implemented interface; a name without a field, such as a Kotlin property of an interface, uses the property's getter (`code` reads `getCode()`);
- several of them separated by commas, e.g. `"tenant, lineId()"`: one value each, in this order, e.g. `OrderLine{tenant=acme, lineId=3}.cancel`. A member that is missing is left out, with the same warning as for a single one;
- `"-"`: nothing, so the methods the key names show no receiver members, not even from annotations. Their parameter values are not affected.

`"com.thirdparty.Parcel::ship, cancel*" = "trackingCode"` shows `Parcel{trackingCode=…}.ship` and `Parcel{trackingCode=…}.cancelLater`, while `Parcel.weigh` stays without receiver members (its parameter values are not affected). A class may have several entries, e.g. `"com.thirdparty.Parcel::weigh" = "weight"` after the one above gives `weigh` its own member. Constructors never show receiver members, so `<init>` is not allowed here.

With `"com.acme.**" = true` in `[augment.annotations]`, `"com.acme.Order::*" = "getId()"` gives `Order` its `getId()` instead of its `@AugmentedReceiver` members, and `"com.acme.generated.**::*" = "-"` takes the generated classes out; `"com.acme.Order::export" = "-"` before `"com.acme.Order::*" = "getId()"` takes one method out. An entry after one that already decides a method does not apply to it. A method without a deciding entry and without annotations in use, or whose entry names members its class does not have, shows no receiver members. Each member shows its value as text, from `toString()`.

A frame's method belongs to the class that declares it, so the methods a subclass inherits show the members of the superclass's entry, while its own methods need an entry that matches the subclass: its own, or the superclass's with `+`:

```toml
[augment.receiver]
"com.acme.TrackedOrder::*" = "tracking"   # TrackedOrder extends Order: before Order's entry, which matches it too
"com.acme.Order+::*" = "id"
```

A `TrackedOrder` shows `Order{id=…}.ship` in the frame of `Order.ship()`, and `TrackedOrder{tracking=…}.track` in the frame of its own `track()`. Without its own entry, `track()` would show `TrackedOrder{id=…}.track`, from `Order`'s entry, and without the `+` it would show no receiver members. The same holds for subclasses generated at runtime: a Spring CGLIB proxy `Order$$SpringCGLIB$$0`, a Hibernate proxy `Order$HibernateProxy$…`, a Mockito mock `Order$MockitoMock$…`, an anonymous subclass, or an enum constant with a body show the member of `Order`'s entry in `Order`'s frames, and with `+` also in their own overrides. A pattern such as `"com.acme.*::*"` matches them only by name (it matches `com.acme.Order$$SpringCGLIB$$0`, since `*` stops only at a `.`, but not a subclass in another package).

Default methods of interfaces work the same way: with `"com.acme.Labeled::*" = "label()"`, the frame of `Labeled.relabel()` shows `Labeled{label=…}` whatever class implements it, with `label()` called on the object. The implementing class's entry does not apply to it, and without an entry for `Labeled` the frame is unchanged. With `"com.acme.Labeled+::*"` the entry also matches the implementing classes, so their own methods show `label()` too, unless an earlier entry matches them. Bridge methods are never instrumented, including the one Kotlin compiles into each implementing class to call the default method, so its frame stays as it is.

The label is the real field or method name (`{objectId=…}`, `{getKey=…}`); it cannot be changed.

**Parameter values** are shown after the method name, in declaration order. `[augment.params]` entries select them (see [Configuration](configuration.md)), with the same keys: `"com.acme.Order::ship, cancel*"`, `"com.acme.Order::*"` for all methods of the class, `"com.acme.Order+::process"` for `process` of `Order` and of its subclasses. The entry names the parameters:
- by name or by 0-based index (0 to 255), separated by commas, e.g. `"order, 2"`;
- `"-"`: none, not even from annotations.

A method that no entry matches uses its annotations instead, where `[augment.annotations]` selects its class and `@Augmented` the method: `@AugmentedParam` on a parameter, or `@AugmentedParams` on the method (all its parameters; not of methods that override it). There is no wildcard for the parameters themselves: each one is named, or selected by an annotation.

**Constructors** are named `<init>`, as in stack traces: `"com.acme.Shipment::<init>" = "orderId"`. Where `[augment.annotations]` selects the class, constructors show their annotated parameters like methods (`@AugmentedParams` works on constructors too), and `@Augmented` selects them like methods; an entry such as `"com.acme.Shipment::<init>" = "-"` takes them out. Wildcards in the method name match constructors like any method, so `"com.acme.Shipment::*" = "-"` takes them out as well. A constructor frame shows parameter values only, never receiver members, e.g. `com.acme.Shipment.<init>{orderId=42}`, for exceptions thrown in its body (including Kotlin `init` blocks) and while computing the arguments of its `super(...)` or `this(...)` call, e.g. `super(requireNonNull(code))`. An exception thrown inside the called constructor itself leaves the frame unchanged.

**Hashed parameter values.** A `#` after a name or an index in `[augment.params]` (`"user, email#, 2#"`), or `@AugmentedParam(secret = true)` on the parameter (also under `@AugmentedParams`, which then shows the others as text), shows the value as a short hash instead of its text: `#` and the first 8 hex digits of the SHA-256 of the `toString()`, e.g. `invite{user=ann, email=#71d4f55f}`. The same value always gives the same hash, so a value can still be followed across log lines and incidents without appearing in them. `null` stays `null`. When a parameter is selected twice (by name and by index, or by both annotations), it is hashed if either hashes it. The hash is not salted: values from a small set, e.g. PINs, can be found by trying them all, so leave such parameters out rather than hashing them.

The keys match the class that declares the method, and with `+` its subclasses. When several entries match a method, only the first one in the file decides, as in `[augment.receiver]`. Entries are not combined, so a `"-"` takes a method out of the entries after it:

```toml
[augment.params]
"com.thirdparty.InventoryAudit::log" = "reason"   # log's first parameter ...
"com.thirdparty.InventoryAudit::*" = "-"            # ... but no other method of InventoryAudit ...
"com.thirdparty.Inventory*::*" = "sku, count"   # ... and these parameters of the other Inventory* classes
```

Because the tables are independent, a class can take its receiver members from an explicit entry and still use its parameter annotations, or ignore its receiver and keep its parameters:

```toml
[augment.annotations]
"com.acme.**" = true

[augment.receiver]
"com.acme.Order::*" = "getId()"       # getId() instead of Order's @AugmentedReceiver
"com.acme.generated.**::*" = "-"      # no receiver members
```

Without `[augment.params]` entries for them, the methods of `Order` and of the generated classes still show their annotated parameters, where `@Augmented` selects them.

## Annotations

The annotations of `stack-augmentor-api` are read only in the classes that [`[augment.annotations]`](configuration.md#annotations) selects, by the class that declares the frame's method; apart from `@Augmented` itself, only for the methods and constructors that `@Augmented` selects and that no entry of the table in question matches:

- **`@AugmentedReceiver`** on a field, a no-argument method, or (in Kotlin) a primary-constructor `val`: a receiver member, one value each; fields first, in declaration order, then methods by name, starting with the class itself and then its superclasses, which need no `@Augmented` of their own. Static members and methods with parameters are not used.
- **`@AugmentedParam`** on a parameter: its value; `@AugmentedParam(secret = true)` shows it hashed.
- **`@AugmentedParams`** on a method or constructor: all its parameters; not of methods that override it.
- **`@Augmented`** selects the methods and constructors of the class that the annotations augment. On a method or constructor, `@Augmented` turns them on for it and `@Augmented(false)` off. Otherwise `@Augmented` or `@Augmented(false)` on the class decides. Otherwise they are not augmented: a class without `@Augmented`, on the class or on its methods, is ignored, and its other annotations are not even looked for. Usually it goes on the class, for all its methods and constructors. Only the class that declares the method counts; methods that override it are not affected. A Kotlin top-level function takes it on the function itself, since the class of its file cannot carry it.

```kotlin
class Dispatcher(@AugmentedReceiver val hub: String) {
    @Augmented                                   // only send is augmented: Dispatcher{hub=H-1}.send{order=o-1}
    fun send(@AugmentedParam order: String) { }

    fun track(@AugmentedParam order: String) { } // unchanged: neither it nor the class has @Augmented
}
```

`@Augmented` on the class would augment `track` too, unless `track` had `@Augmented(false)`. Without the `@Augmented` on `send`, the class would be ignored and both frames unchanged; `@Augmented(false)` on the class is the same as none, except that it says so. `[augment.receiver]` and `[augment.params]` entries never read `@Augmented`, so an entry for `track` still applies.

## Labels and values

The label is the parameter name compiled into the class file, so compile Java code with `javac -parameters` (Kotlin: `javaParameters = true`); without it, the label is `arg<N>`. The value is the argument's `toString()` (arrays with their elements), even when its class has an `[augment.receiver]` entry: that table only applies to receivers. Give a class a `toString()` to control how it appears as an argument.

All values become Strings when they are captured. Line breaks are replaced by a space, `(` and `)` by `{` and `}` (so that IDEs still find the frame's `(File.kt:12)`, e.g. after a data class's `Point(x=1)`), the length is capped at `maxValueLength`, and a member that throws shows `?`.
