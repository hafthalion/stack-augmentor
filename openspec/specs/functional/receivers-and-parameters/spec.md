# Receivers and Parameters Specification

## Purpose
Defines what a frame shows: which receiver members and parameter values the entries of `[augment.receiver]` and
`[augment.params]` and the annotations select, how they are labelled, and how they are turned into text. The key syntax
and the first-match rule are those of the configuration specification; this specification does not repeat them.

## Requirements

### Requirement: Independent receiver members and parameter values
`[augment.receiver]` SHALL decide the receiver members and `[augment.params]` the parameter values of a method,
independently: no entry of one table, a member, a parameter or `"-"`, SHALL change what the other selects. For a method
that no entry of a table matches, the annotations SHALL take that table's place (see "Annotations in use"), for that table
only. Constructors and static methods SHALL show parameter values only.

#### Scenario: Receiver ignored, parameters kept
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, `"com.acme.generated.**::*" = "-"` in `[augment.receiver]`, no `[augment.params]` entries, and `fun map(@AugmentedParam id: Int)` in `com.acme.generated.Mapper`, which has `@Augmented` and an `@AugmentedReceiver` member
- **WHEN** `map(7)` throws
- **THEN** that frame shows `id=7` and no receiver members

#### Scenario: Receiver member only
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, `"com.acme.**::*" = "-"` in `[augment.params]`, and a class `com.acme.Order` with `@Augmented`, `@AugmentedReceiver val code` and `fun ship(@AugmentedParam to: String)`
- **WHEN** `ship("Main St")` throws
- **THEN** that frame shows the receiver member `code=…` and no parameter values

#### Scenario: Name entry before the annotations
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, `"com.acme.Order::ship" = "getId()"` in `[augment.receiver]`, `"com.acme.Order::cancel" = "-"` in `[augment.params]`, and `com.acme.Order` with `@Augmented`, `@AugmentedReceiver val code`, `fun getId()`, `fun ship(@AugmentedParam to: String)` and `fun cancel(@AugmentedParam reason: String)`
- **WHEN** `ship("Main St")` throws, and separately `cancel("late")` throws
- **THEN** the first frame shows `Order{getId=…}.ship{to=Main St}`: the receiver entry decides the receiver members, and the parameter annotation is used, since no `[augment.params]` entry matches `ship`
- **AND** the second frame shows `Order{code=…}.cancel` without parameter values: no receiver entry matches `cancel`, and the `"-"` entry decides its parameters

### Requirement: Annotations in use
The annotations of `stack-augmentor-api` (`@AugmentedReceiver`, `@AugmentedParam`, `@AugmentedParams` and `@Augmented`)
SHALL be read only in the declaring classes for which the deciding `[augment.annotations]` entry is `true`; a class that no
entry matches, and every class without entries, SHALL NOT read them. Within such a class, they SHALL be used for a method or
constructor only where `@Augmented` selects it (see "Methods selected by @Augmented") and only for the table that has no
entry matching it: the `@AugmentedReceiver` members where no `[augment.receiver]` entry matches the method, the
`@AugmentedParam` and `@AugmentedParams` parameters where no `[augment.params]` entry matches it. A class without
`@Augmented` and without a matching entry SHALL NOT be augmented, even when `[augment.annotations]` selects it and it has
other annotations. The annotations SHALL be recognised by their class names, so that a copy of the API loaded by another
class loader is also recognised (see the design specification).

#### Scenario: Annotated class outside the selected classes
- **GIVEN** `"com.hafnium.it.fixtures.**" = true` as the only `[augment.annotations]` entry, and no `[augment.receiver]` or `[augment.params]` entries
- **WHEN** an exception leaves a method of a class in `com.hafnium.it.outside` with `@Augmented` and an annotated parameter, and no annotated superclass
- **THEN** the frame is unchanged

#### Scenario: Subclass of a selected class
- **GIVEN** `"com.hafnium.it.fixtures.**" = true` in `[augment.annotations]`, and `com.hafnium.it.outside.DerivedOutside`, with `@Augmented`, extending `com.hafnium.it.fixtures.Base`, which declares `@AugmentedReceiver val baseId = "b1"`
- **WHEN** an exception leaves a method of `DerivedOutside`
- **THEN** that frame shows no receiver members: `DerivedOutside` declares `fail()`, and the pattern matches the names of the classes in `com.hafnium.it.fixtures` only, not their subclasses elsewhere
- **AND** with `"com.hafnium.it.fixtures.Base+" = true`, the frame shows `baseId=b1`

#### Scenario: Matched class without annotations
- **GIVEN** a class that `[augment.annotations]` selects, which has no annotations, but overrides `toString()`
- **WHEN** an exception leaves one of its methods
- **THEN** the frame is unchanged

#### Scenario: Method-level parameter annotation outside the selected classes
- **GIVEN** `"com.hafnium.it.fixtures.**" = true` in `[augment.annotations]` and a method of a class with `@Augmented` in `com.hafnium.it.outside` annotated with `@AugmentedParams`
- **WHEN** that method throws
- **THEN** the frame shows no parameter values

#### Scenario: Method-level parameter annotation of one selected class
- **GIVEN** `@AugmentedParams fun run(a: Int, b: String)` in `com.hafnium.it.outside.ClassParamsViaMethods`, which has `@Augmented`, and `"com.hafnium.it.outside.ClassParamsViaMethods" = true` in `[augment.annotations]`
- **WHEN** `run(1, "x")` throws
- **THEN** that frame shows all its parameter values

#### Scenario: No annotations entry
- **GIVEN** a configuration with only `"com.thirdparty.Order::*" = "getOrderNumber()"` in `[augment.receiver]`
- **WHEN** an exception leaves a method of a class with `@Augmented` and other annotations in any other package
- **THEN** the frame is unchanged

#### Scenario: First matching annotations entry
- **GIVEN** `"com.acme.generated.**" = false` and then `"com.acme.**" = true` in `[augment.annotations]`, and the classes `com.acme.generated.Mapper` and `com.acme.Order`, each with `@Augmented`, an `@AugmentedReceiver` field and an `@AugmentedParam` parameter
- **WHEN** a method of each throws
- **THEN** the frame of `Mapper` is unchanged, and the frame of `Order` shows its receiver member and parameter value
- **AND** with the two entries the other way round, `"com.acme.**" = true` decides for `Mapper` too, and its frame shows them as well

### Requirement: Methods selected by @Augmented
`@Augmented` (`com.hafnium.stackaugmentor.Augmented`, targets type, method and constructor, with `boolean value()`
defaulting to `true`) SHALL decide which methods and constructors of a class that `[augment.annotations]` selects the
annotations augment:
- the method's or constructor's own `@Augmented` SHALL decide for it: `@Augmented` on, `@Augmented(false)` off;
- otherwise the `@Augmented` of the declaring type SHALL decide;
- otherwise the method or constructor SHALL NOT be augmented by the annotations.

A class without `@Augmented`, on the type or on any of its methods and constructors, SHALL therefore be ignored: its
`@AugmentedReceiver`, `@AugmentedParam` and `@AugmentedParams` SHALL NOT be looked for. `@Augmented(false)` on a type SHALL
have the effect of no `@Augmented` on it. Only the annotation of the declaring class SHALL count; methods that override
it SHALL NOT be affected. A Kotlin top-level function SHALL take `@Augmented` on the function, since the class of its file
cannot carry it. `[augment.receiver]` and `[augment.params]` entries SHALL NOT read `@Augmented` and SHALL apply without
it, and `@Augmented` SHALL have no effect where `[augment.annotations]` does not select the class.

#### Scenario: @Augmented on a method
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, and `com.acme.Dispatcher`, without `@Augmented` on the class, with `@AugmentedReceiver val hub = "H-1"`, `@Augmented fun send(@AugmentedParam order: String)` and `fun track(@AugmentedParam order: String)`
- **WHEN** `send("o-1")` throws, and separately `track("o-1")` throws
- **THEN** the first frame shows `Dispatcher{hub=H-1}.send{order=o-1}`, and the second frame is unchanged

#### Scenario: @Augmented on the type
- **GIVEN** the `Dispatcher` above, with `@Augmented` on the class instead of on `send`
- **WHEN** `send("o-1")` throws, and separately `track("o-1")` throws
- **THEN** the frames show `Dispatcher{hub=H-1}.send{order=o-1}` and `Dispatcher{hub=H-1}.track{order=o-1}`

#### Scenario: @Augmented(false) on a method
- **GIVEN** the `Dispatcher` above, with `@Augmented` on the class instead of on `send`, and `@Augmented(false)` on `track`
- **WHEN** `send("o-1")` throws, and separately `track("o-1")` throws
- **THEN** the first frame shows `Dispatcher{hub=H-1}.send{order=o-1}`, and the second frame is unchanged: the method's own annotation decides

#### Scenario: @Augmented(false) on the type
- **GIVEN** the `Dispatcher` above, with `@Augmented(false)` on the class and `@Augmented` on `send`
- **WHEN** `send("o-1")` throws, and separately `track("o-1")` throws
- **THEN** the first frame shows `Dispatcher{hub=H-1}.send{order=o-1}`, and the second frame is unchanged
- **AND** without `@Augmented` on `send`, both frames are unchanged

#### Scenario: Class without @Augmented
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, and `com.acme.Plain` with `@AugmentedReceiver val code = "c-1"` and `fun run(@AugmentedParam x: Int)`, but no `@Augmented` on the class or on any of its methods and constructors
- **WHEN** `run(1)` throws
- **THEN** its frame is unchanged: the annotations of `Plain` are not looked for
- **AND** with `@Augmented` on the class, the frame shows `Plain{code=c-1}.run{x=1}`

#### Scenario: Entries do not read @Augmented
- **GIVEN** the `Dispatcher` with `@Augmented` on `send` only, and `"com.acme.Dispatcher::track" = "order"` in `[augment.params]`
- **WHEN** `track("o-1")` throws
- **THEN** that frame shows `Dispatcher.track{order=o-1}`: the entry decides its parameters, and the receiver members stay off, since `@Augmented` does not select `track`

### Requirement: Receiver members from annotations
Where the annotations decide a method's receiver members, every non-static field and every non-static, no-argument,
non-synthetic method annotated with `@AugmentedReceiver` (`com.hafnium.stackaugmentor.AugmentedReceiver`) SHALL be a
receiver member, one value each. They SHALL be looked up in the declaring class and its superclasses, which need no
`@Augmented` or `[augment.annotations]` entry of their own, and shown in this order: the class itself first, then its
superclasses, nearest first; within a class, its fields in declaration order, then its methods by name. A member whose
label a member shown before it already has, such as a superclass field hidden by a field of the same name, SHALL be left
out. In Kotlin, a property annotated in the primary constructor has the annotation on its field, because
`@AugmentedReceiver` does not target parameters. A static member, a method with parameters and a synthetic method SHALL
NOT be receiver members, even when annotated, and SHALL NOT make a class count as annotated when the instrumentation
decides which classes to change. A member that cannot be made accessible SHALL be skipped with a warning, and the others
SHALL still be used.

#### Scenario: Annotated method with parameters
- **GIVEN** a class with `@Augmented` that `[augment.annotations]` selects, whose only `@AugmentedReceiver` member is `String idFor(int version)`
- **WHEN** the instrumentation decides whether to change the class
- **THEN** the class is not instrumented for a receiver member

#### Scenario: Annotated field
- **GIVEN** a class `ObjectClass` with `@Augmented` and `@AugmentedReceiver val objectId = "object-1"`, selected by `[augment.annotations]`
- **WHEN** an exception leaves an instance method of `ObjectClass`
- **THEN** that frame shows the receiver member `objectId=object-1`

#### Scenario: Annotated method
- **GIVEN** a class with `@Augmented` and `@AugmentedReceiver fun key() = "k-1"`, selected by `[augment.annotations]`
- **WHEN** an exception leaves one of its instance methods
- **THEN** that frame shows the receiver member `key=k-1`

#### Scenario: Several annotated members
- **GIVEN** a class with `@Augmented`, `@AugmentedReceiver val tenant = "acme"` and `@AugmentedReceiver val orderId = 42`, selected by `[augment.annotations]`
- **WHEN** an exception leaves one of its instance methods
- **THEN** that frame shows the receiver members `{tenant=acme, orderId=42}`

#### Scenario: Inherited annotation
- **GIVEN** a class `Derived` with `@Augmented`, selected by `[augment.annotations]`, extending a class that declares `@AugmentedReceiver val baseId = "b1"`
- **WHEN** an exception leaves a method of `Derived`
- **THEN** that frame shows the receiver member `baseId=b1`

#### Scenario: Kotlin primary-constructor property
- **GIVEN** `@Augmented class Node(@AugmentedReceiver val name: String)`, selected by `[augment.annotations]`
- **WHEN** an exception leaves a method of `Node("c")`
- **THEN** that frame shows the receiver member `name=c`

### Requirement: Receiver members by name
The deciding `[augment.receiver]` entry of a method SHALL give its receiver members, in the order of its value, one value
per member, read from the object the method runs on:
- a field name: the field, looked up in the class the entry matched and its superclasses, private members included; for
  an interface, in the interface and the interfaces it extends. When no field of that name exists, as in an interface or for
  a Kotlin property without a backing field, the property's getter SHALL be used (`getName()`, or `isName()` for a name
  starting with `is`), with the configured name as the label;
- `method()`: the result of that no-argument method, looked up the same way;
- a configured method or getter that the class and its superclasses do not declare SHALL then be looked up in the
  interfaces they implement, nearest first, so that a Java class finds a default method it inherits, as a Kotlin class,
  which compiles it into the class, does;
- `"-"`: no receiver members, not even the `@AugmentedReceiver` members.

A member that is not found, or that cannot be made accessible while no accessible field or getter of that name replaces
it, SHALL be left out with the warning of the diagnostics specification, and the others SHALL still be shown. A method that
no entry decides and whose annotations are not in use SHALL show no receiver members.

#### Scenario: Configured method of a third-party class
- **GIVEN** `"com.thirdparty.Order::*" = "getOrderNumber()"` in `[augment.receiver]`
- **WHEN** an exception leaves a method of an `Order` with order number 4711
- **THEN** that frame shows the receiver member `getOrderNumber=4711`

#### Scenario: List of members of a third-party class
- **GIVEN** `"com.thirdparty.OrderLine::*" = "tenant, lineId()"` in `[augment.receiver]`
- **WHEN** an exception leaves a method of an `OrderLine` with tenant `acme` and `lineId()` returning 3
- **THEN** that frame shows the receiver members `{tenant=acme, lineId=3}`

#### Scenario: Receiver members limited to some methods
- **GIVEN** `"com.thirdparty.Parcel::ship, cancel*" = "trackingCode"` and then `"com.thirdparty.Parcel::weigh" = "weight"` in `[augment.receiver]`
- **WHEN** exceptions leave `ship()`, `cancelLater()`, `weigh()` and `inspect()` of a `Parcel` with tracking code `T-1` and weight 2
- **THEN** the frames of `ship` and `cancelLater` show `{trackingCode=T-1}`, the frame of `weigh` shows `{weight=2}`, and the frame of `inspect` shows no receiver members

#### Scenario: First matching entry wins
- **GIVEN** `"com.acme.Order::*" = "getId()"` and then `"com.acme.**::*" = "code"` in `[augment.receiver]`, and `com.acme.Order` with the field `code` and `fun getId()`
- **WHEN** an exception leaves a method of an `Order`
- **THEN** that frame shows the receiver member `getId=…`
- **AND** with the two entries the other way round, `"com.acme.**::*" = "code"` decides and the frame shows `code=…`

#### Scenario: Wildcard entry with a member
- **GIVEN** `"com.thirdparty.*Account::*" = "number"` in `[augment.receiver]`
- **WHEN** an exception leaves a method of a `com.thirdparty.SavingsAccount` with the field `number = "S-1"`
- **THEN** that frame shows the receiver member `number=S-1`

#### Scenario: Default method of an implemented interface
- **GIVEN** `"com.acme.Order::*" = "getId()"`, where the Java class `Order` implements `Identified`, whose default method `getId()` returns `id-7`
- **WHEN** an exception leaves a method of an `Order`
- **THEN** that frame shows the receiver member `getId=id-7`
- **AND** with `"com.acme.Order::*" = "id"` and no field `id`, the frame shows `id=id-7`, read through the same getter

#### Scenario: Kotlin interface property
- **GIVEN** `"com.acme.Tracked::*" = "trackingCode"`, where the Kotlin interface `Tracked` declares `val trackingCode: String` and has a default method `track()`
- **WHEN** an exception leaves `Tracked.track()`, called on an object whose `trackingCode` is `T-9`
- **THEN** that frame shows the receiver member `trackingCode=T-9`, read through `getTrackingCode()`

#### Scenario: Configured member does not exist
- **GIVEN** `"com.thirdparty.Customer::*" = "customerNo"` in `[augment.receiver]`, a misspelling: `Customer` has a field `customerId`, but no field and no getter for `customerNo`
- **WHEN** the receiver members of `Customer` are first needed
- **THEN** a warning `[augment.receiver] "com.thirdparty.Customer::*": no field or property customerNo found` is printed
- **AND** frames of `Customer` show no receiver members

#### Scenario: Configured member cannot be made accessible
- **GIVEN** `"com.thirdparty.Customer::*" = "customerNo"`, where `Customer`'s private field `customerNo` is in a module package not opened to stack-augmentor
- **WHEN** the receiver members of `Customer` are first needed
- **THEN** a public getter `getCustomerNo()`, if there is one, gives the receiver member `customerNo=…` without a warning
- **AND** without a usable getter, one warning `[augment.receiver] "com.thirdparty.Customer::*": cannot access private … customerNo` is printed, and no warning about a missing member

### Requirement: Subclasses, proxies and interfaces
The receiver members of a frame SHALL come from the deciding entry of the declaring class of the frame's method, read from
the object the method runs on, whatever the object's runtime class. So:
- a subclass, including one generated at runtime (a proxy, a mock, an anonymous class or an enum constant with a body),
  SHALL show the receiver members of its superclass's entry in the frames of the methods it inherits;
- the methods that a subclass declares, overrides included, SHALL take their receiver members from the first entry that
  matches the subclass: an entry of its own, an entry with `+` for a superclass or an implemented interface, or a pattern
  that matches its name;
- a default method of an interface SHALL show the receiver members of the interface's deciding entry, whatever entry the
  implementing class has; without an entry for the interface, its frame SHALL be unchanged;
- bridge methods, including those Kotlin compiles into a class for each default method it inherits, SHALL NOT be
  instrumented, so their frames are unchanged.

#### Scenario: Subclass without an entry
- **GIVEN** `"com.thirdparty.Order+::*" = "getOrderNumber()"` and a subclass `com.acme.RushOrder` that no other entry matches
- **WHEN** an exception leaves `Order.ship()`, called on a `RushOrder`, and separately `RushOrder.expedite()`
- **THEN** the first frame shows `com.thirdparty.Order{getOrderNumber=…}`, and the second `com.acme.RushOrder{getOrderNumber=…}`
- **AND** with `"com.thirdparty.Order::*"`, without `+`, the first frame is the same and the second is unchanged

#### Scenario: Subclass not matched by a pattern
- **GIVEN** `"com.thirdparty.*::*" = "getOrderNumber()"` and a subclass `com.acme.RushOrder` of `com.thirdparty.Order` that no other entry matches
- **WHEN** an exception leaves `Order.ship()`, called on a `RushOrder`, and separately `RushOrder.expedite()`
- **THEN** the first frame shows the receiver member `getOrderNumber=…`, and the second frame is unchanged

#### Scenario: Subclass with an entry of its own
- **GIVEN** `"com.acme.TrackedOrder::*" = "tracking"` and then `"com.acme.Order+::*" = "id"`, where `TrackedOrder` extends `Order`
- **WHEN** an exception leaves `Order.ship()`, called on a `TrackedOrder`, and separately `TrackedOrder.track()`
- **THEN** the first frame shows `id=…`, and the second `tracking=…`
- **AND** with `"com.acme.Order+::*" = "id"` first, the second frame shows `id=…` too: `TrackedOrder`'s entry never decides

#### Scenario: Subclass with an entry naming an inherited member
- **GIVEN** `"com.acme.ExpressOrder::*" = "id"`, where `ExpressOrder` extends `Order`, which declares the field `id`
- **WHEN** an exception leaves a method that `ExpressOrder` declares
- **THEN** that frame shows the receiver member `id=…`

#### Scenario: Generated subclass
- **GIVEN** `"com.acme.Order+::*" = "id"`, and a Spring CGLIB proxy `com.acme.Order$$SpringCGLIB$$0` that calls the real methods
- **WHEN** an exception leaves `Order.ship()`, called on the proxy
- **THEN** that frame shows `id=…`, and so does the frame of the proxy's own override

#### Scenario: Default method of an interface
- **GIVEN** `"com.acme.Labeled::*" = "label()"` and `"com.acme.Parcel::*" = "code"`, where the Kotlin class `Parcel` implements `Labeled` and inherits its default method `relabel()`
- **WHEN** an exception leaves `Labeled.relabel()`, called on a `Parcel` whose `label()` returns `parcel-p1`
- **THEN** the frame of `Labeled.relabel()` shows `label=parcel-p1`, and the frame of the bridge method `Parcel.relabel()` is unchanged
- **AND** the same holds for a class implementing `Labeled` without an entry of its own

#### Scenario: Default method of an interface without an entry
- **GIVEN** an interface `Sealable` that no entry matches, with a default method `seal()`, implemented by a class with an entry
- **WHEN** an exception leaves `Sealable.seal()`
- **THEN** its frame is unchanged

#### Scenario: Interface extending an interface
- **GIVEN** `"com.acme.Tracked::*" = "label()"`, where `Tracked` extends `Labeled`, which declares `label()`, and has a default method `track()`
- **WHEN** an exception leaves `Tracked.track()`
- **THEN** that frame shows `label=…`, read through `Labeled.label()`

### Requirement: Parameter values
A method or constructor SHALL show the value of a parameter after its name when its deciding `[augment.params]` entry
selects the parameter by name or index, or, without such an entry and where the annotations are in use, when the parameter
has `@AugmentedParam` (`com.hafnium.stackaugmentor.AugmentedParam`) or the method `@AugmentedParams`
(`com.hafnium.stackaugmentor.AugmentedParams`). This SHALL apply to instance methods, static methods and constructors. A
parameter selected more than once SHALL be shown once, and parameter values SHALL be listed in declaration order.
`@AugmentedReceiver` on a parameter SHALL NOT select it.

#### Scenario: Annotated parameter
- **GIVEN** `fun objectMethod(@AugmentedParam orderId: Int)` in a class with `@Augmented` that `[augment.annotations]` selects, and no `[augment.params]` entry for it
- **WHEN** `objectMethod(42)` throws
- **THEN** that frame shows the parameter value `orderId=42`

#### Scenario: Configured parameters by name and index
- **GIVEN** `"com.thirdparty.OrderService::process" = "order, 1"` in `[augment.params]` for `process(order: Order, quantity: Int, note: String)`
- **WHEN** `process(Order(4711), 3, "rush")` throws, where `Order.toString()` returns `Order#4711`
- **THEN** that frame shows `order=Order#4711, quantity=3`

#### Scenario: Static method
- **GIVEN** a top-level (static) function `@Augmented fun staticWithParam(@AugmentedParam code: Int)`, in a file whose class `[augment.annotations]` selects
- **WHEN** `staticWithParam(5)` throws
- **THEN** that frame shows the parameter value `code=5` and no receiver members

#### Scenario: Selected by name and by index
- **GIVEN** `fun op(x: Int, y: Int)` in class `C`, and `"C::op" = "x, y, 0"` in `[augment.params]`
- **WHEN** `op(1, 2)` throws
- **THEN** that frame shows `x=1, y=2`, each parameter once

#### Scenario: Annotations of one class
- **GIVEN** `fun run(@AugmentedParam code: Int)` in `com.hafnium.it.outside.MethodAnnotationsOutside`, which has `@Augmented` and no `@AugmentedReceiver` member and which no `[augment.receiver]` or `[augment.params]` entry matches, and `"com.hafnium.it.outside.MethodAnnotationsOutside" = true` in `[augment.annotations]`
- **WHEN** `run(7)` throws
- **THEN** that frame shows `code=7` and no receiver members

### Requirement: Parameter values from method-level annotations
`@AugmentedParams` (on methods and constructors) SHALL select all parameters of that method or constructor. It SHALL NOT
apply to methods that override it, which need their own annotation. Methods without parameters SHALL show no parameter
values.

#### Scenario: Method-level annotation
- **GIVEN** `@AugmentedParams fun transfer(from: String, to: String, amount: Long)` in a class with `@Augmented` that `[augment.annotations]` selects
- **WHEN** `transfer("a", "b", 10)` throws
- **THEN** that frame shows `from=a, to=b, amount=10`

#### Scenario: Override of an annotated method
- **GIVEN** `@AugmentedParams open fun reserve(sku: String, count: Int)` in `@Augmented open class Inventory`, and `class DerivedInventory : Inventory()` that overrides `reserve` without annotations
- **WHEN** `DerivedInventory().reserve("x-3", 1)` throws
- **THEN** the frame shows no parameter values for `reserve`

### Requirement: Parameter values of configured methods
The deciding `[augment.params]` entry of a method SHALL select the parameters its value names, by name or 0-based index,
for every overload the key matches. Names and indexes that a matched method does not have SHALL be skipped (with a debug
message for keys without wildcards or `+`, see the diagnostics specification). A method that an entry decides SHALL NOT
use its parameter annotations.

#### Scenario: All methods of a class
- **GIVEN** `"com.thirdparty.InventoryService::*" = "sku, count"`
- **WHEN** `InventoryService.reserve("x-1", 2)` throws
- **THEN** that frame shows `sku=x-1, count=2` and no receiver members

#### Scenario: Wildcards across packages
- **GIVEN** `"com.thirdparty.**.*Repository::find*" = "0"`
- **WHEN** `com.thirdparty.db.OrderRepository.findById(7)` throws, and separately `findAll()` throws
- **THEN** the first frame shows `id=7`, and the second frame is unchanged

#### Scenario: Methods of subclasses
- **GIVEN** `"com.thirdparty.SavingsAccount+::transfer" = "amount"`, and `PremiumAccount`, which extends `SavingsAccount` and declares `transfer(amount: Int)`
- **WHEN** `PremiumAccount("P-1").transfer(5)` throws
- **THEN** that frame shows `transfer{amount=5}`
- **AND** with `"com.thirdparty.SavingsAccount::transfer"`, without `+`, the frame is unchanged

#### Scenario: Overlapping entries
- **GIVEN** `"com.thirdparty.OrderService::process" = "order"` and then `"com.thirdparty.OrderService::*" = "2"`
- **WHEN** `process(Order(4711), 3, "rush")` throws, where `Order.toString()` returns `Order#4711`
- **THEN** that frame shows only `order=Order#4711`: the first entry decides, and the pattern adds nothing
- **AND** with the two entries the other way round, the pattern decides and the frame shows only the third parameter

#### Scenario: Receiver entry with annotated parameters
- **GIVEN** `"com.acme.Order" = true` in `[augment.annotations]`, `"com.acme.Order::*" = "getId()"` in `[augment.receiver]`, no `[augment.params]` entries, and `fun ship(@AugmentedParam to: String)` in `Order`, which has `@Augmented`
- **WHEN** `ship("Main St")` throws
- **THEN** that frame shows the receiver member `getId=…` and the parameter value `to=Main St`

### Requirement: Ignoring receivers and parameters
The value `"-"` SHALL select nothing, in either table, and SHALL affect its own table only. A method whose deciding
`[augment.receiver]` entry is `"-"` SHALL show no receiver members, not even its class's `@AugmentedReceiver` members; a
method whose deciding `[augment.params]` entry is `"-"` SHALL show no parameter values, even where a later entry or its
annotations would select some. A configuration whose `[augment.receiver]` and `[augment.params]` entries are all `"-"` and
that has no `[augment.annotations]` entry that is `true` SHALL count as having no entries.

#### Scenario: No receiver members in a package whose annotations are read
- **GIVEN** `"com.acme.**" = true` in `[augment.annotations]`, `"com.acme.generated.**::*" = "-"` in `[augment.receiver]`, and `com.acme.generated.Mapper` with `@Augmented` and a `@AugmentedReceiver` field
- **WHEN** a method of `Mapper` throws
- **THEN** its frame shows no receiver members

#### Scenario: Ignored methods under a wildcard entry
- **GIVEN** `"com.thirdparty.InventoryAudit::log" = "reason"`, `"com.thirdparty.InventoryAudit::*" = "-"` and `"com.thirdparty.Inventory*::*" = "sku, count"` in `[augment.params]`, in this order
- **WHEN** `InventoryAudit.purge("x-1")` throws, and separately `InventoryAudit.log("disk full", 2)` throws
- **THEN** the first frame is unchanged, and the second frame shows `reason=disk full`

### Requirement: Hashed parameter values
A `#` after a parameter name or index in an `[augment.params]` value (`"email#"`, `"2#"`), or
`@AugmentedParam(secret = true)` on a parameter (also one that `@AugmentedParams` selects), SHALL show the value hashed:
`#` followed by the first 8 lower-case hex digits of the SHA-256 of the value's text (its UTF-8 bytes, before line breaks
and parentheses are replaced and before `maxValueLength` applies). The hash SHALL NOT be salted, so the same value gives
the same hash in every log line. `null` SHALL stay `null`, and a value whose `toString()` throws SHALL still show `?`.
`secret` SHALL default to `false`. A parameter selected twice (by name and by index, or by both annotations) SHALL be
hashed when either hashes it. The label SHALL stay the parameter name.

#### Scenario: Parameters hashed by name and index
- **GIVEN** `"com.thirdparty.UserService::invite" = "user, email#, 2#"` in `[augment.params]` for `invite(user: String, email: String, attempt: Int)`
- **WHEN** `invite("ann", "ann@example.com", 3)` throws
- **THEN** that frame shows `user=ann, email=#71d4f55f, attempt=#4e074085`

#### Scenario: Secret annotations
- **GIVEN** `fun invite(@AugmentedParam user: String, @AugmentedParam(secret = true) email: String)` and `@AugmentedParams fun register(@AugmentedParam(secret = true) email: String, nickname: String)`, in a class with `@Augmented` that `[augment.annotations]` selects
- **WHEN** `invite("ann", "ann@example.com")` throws, and separately `register("a@b.c", "annie")` throws
- **THEN** the first frame shows `user=ann, email=#71d4f55f`, and the second `email=#d648b243, nickname=annie`

### Requirement: Labels
The label of a receiver member SHALL be the real name of the field or method that supplies its value (the configured name
for a getter found for a field name), and the label of a parameter value SHALL be the parameter name compiled into the
class file, however the parameter is selected. Labels SHALL NOT be overridden: `@AugmentedReceiver` and `@AugmentedParams`
have no attributes, the only one of `@AugmentedParam` is `secret`, and the only one of `@Augmented` is its on/off `value`.
When a class has no parameter names (compiled without `-parameters`), the label SHALL be `arg<N>`, so Java classes SHOULD
be compiled with `javac -parameters` (Kotlin with `javaParameters = true`).

#### Scenario: Receiver label
- **GIVEN** `@AugmentedReceiver val login = "bob"`
- **WHEN** an exception leaves a method of that class
- **THEN** the frame shows `login=bob`

#### Scenario: Parameter label
- **GIVEN** `@AugmentedParams fun move(@AugmentedParam item: String, count: Int)`, compiled with parameter names
- **WHEN** `move("x-1", 2)` throws
- **THEN** the frame shows `item=x-1, count=2`, each parameter once

#### Scenario: No parameter names
- **GIVEN** a Java class compiled without `-parameters` and `run(@AugmentedParam int value)`
- **WHEN** `run(5)` throws
- **THEN** the frame shows `arg0=5`

### Requirement: Values as text
Every receiver member and parameter value SHALL be converted to text when it is captured. `null` SHALL be shown as `null`.
Strings, numbers, booleans, characters and enums SHALL be shown with `toString()`, arrays by their elements, and other
objects with `toString()`, also when their class has receiver members: `[augment.receiver]` SHALL only apply to receivers.
Line breaks SHALL be replaced by a single space, and `(` and `)` by `{` and `}`, because IDEs find a frame's file and line
by the parenthesised `(File.kt:12)` that follows the method. Text longer than `[augment] maxValueLength` SHALL be cut to
`maxValueLength - 1` characters followed by `…`. A member or a `toString()` that throws SHALL be shown as `?`.

#### Scenario: Object argument whose class has receiver members
- **GIVEN** `fun ship(@AugmentedParam order: Order?)`, and `"com.thirdparty.Order::*" = "getOrderNumber()"` in `[augment.receiver]`
- **WHEN** `ship(Order(4711))` throws, where `Order.toString()` returns `Order#4711`
- **THEN** the frame shows `order=Order#4711`, not the value `4711` of its receiver member
- **AND** `ship(null)` shows `order=null`

#### Scenario: Array receiver member
- **GIVEN** `@AugmentedReceiver val codes = intArrayOf(1, 2)` in a class with `@Augmented` that `[augment.annotations]` selects
- **WHEN** an exception leaves a method of that class
- **THEN** the frame shows `codes=[1, 2]`, as an array argument would, not `[I@…`

#### Scenario: Long and multi-line values
- **GIVEN** `maxValueLength = 20`
- **WHEN** the value of a receiver member is 50 characters long, or contains `line1\nline2`
- **THEN** the frame shows the first 19 characters followed by `…`, or `line1 line2`

#### Scenario: Parentheses in a value
- **GIVEN** `fun draw(@AugmentedParam point: Point)`, where `Point` is a Kotlin `data class Point(val x: Int)`
- **WHEN** `draw(Point(1))` throws
- **THEN** the frame shows `point=Point{x=1}`, so that the frame still ends with its `(File.kt:12)`

#### Scenario: Failing receiver member
- **GIVEN** `@AugmentedReceiver fun id(): String` that throws
- **WHEN** an exception leaves a method of that class
- **THEN** the frame shows `id=?` and the original exception is unchanged otherwise
