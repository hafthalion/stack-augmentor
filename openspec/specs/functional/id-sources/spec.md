# Id Sources Specification

## Purpose
Defines where the ids shown in stack trace frames come from: the id of the object a frame runs on
(receiver id) and the ids of selected method arguments (parameter ids), how they are labelled, and how
their values are turned into text.

## Requirements

### Requirement: Independent receiver and parameter ids
`[augment.receiver]` SHALL decide the receiver ids and `[augment.params]` the parameter ids, independently:
no value in one table (a member, `"@"`, `"*"` or `"-"`) SHALL change what the other selects. What gets
instrumented SHALL NOT be configured directly: a class SHALL be instrumented when either table gives it a
receiver id or a parameter id, and a method when it gets a parameter id or, as an instance method, a
receiver id.

#### Scenario: Receiver ignored, parameters kept
- **GIVEN** `"com.acme.**" = "@"` and `"com.acme.generated.**" = "-"` in `[augment.receiver]`, `"com.acme.**.*" = "@"` in `[augment.params]`, and `fun map(@StackTraceParam id: Int)` in the annotated class `com.acme.generated.Mapper`
- **WHEN** `map(7)` throws
- **THEN** that frame shows `id=7` and no receiver id

#### Scenario: Receiver id only
- **GIVEN** `"com.acme.**" = "@"` as the only entry, and a class `com.acme.Order` with `@StackTraceId val code` and `fun ship(@StackTraceParam to: String)`
- **WHEN** `ship("Main St")` throws
- **THEN** that frame shows the receiver id `code=…` and no parameter ids

### Requirement: Receiver id from an annotated member
The system SHALL use a non-static field or a non-static, no-argument method annotated with
`@StackTraceId` (`com.hafnium.stackaugmentor.StackTraceId`) as the receiver id of the objects of a class
whose deciding `[augment.receiver]` entry is `"@"` (see "Annotations in use"). The
annotated member SHALL be looked up in the class that the deciding entry matched and in its superclasses.
In Kotlin, a property annotated in the primary constructor has the annotation on its field, because
`@StackTraceId` does not target parameters. A member that cannot be made accessible SHALL be skipped with a
warning, and the lookup SHALL continue with the next annotated member. The annotation SHALL be matched by its class name, so that a copy of the API loaded by
another class loader is also recognised.

#### Scenario: Annotated field
- **GIVEN** a class `ObjectClass` with `@StackTraceId val objectId = "object-1"`, matched by an `"@"` entry
- **WHEN** an exception leaves an instance method of `ObjectClass`
- **THEN** that frame shows the receiver id `objectId=object-1`

#### Scenario: Annotated method
- **GIVEN** a class with `@StackTraceId fun key() = "k-1"`, matched by an `"@"` entry
- **WHEN** an exception leaves one of its instance methods
- **THEN** that frame shows the receiver id `key=k-1`

#### Scenario: Inherited annotation
- **GIVEN** a class `Derived`, matched by an `"@"` entry, extending a class that declares `@StackTraceId val baseId = "b1"`
- **WHEN** an exception leaves a method of `Derived`
- **THEN** that frame shows the receiver id `baseId=b1`

#### Scenario: Kotlin primary-constructor property
- **GIVEN** `class Node(@StackTraceId val name: String)`, matched by an `"@"` entry
- **WHEN** an exception leaves a method of `Node("c")`
- **THEN** that frame shows the receiver id `name=c`

### Requirement: Receiver id from external configuration
The system SHALL use the `[augment.receiver]` configuration table to choose the receiver id source of a
class. A key SHALL be a class name or a class pattern with the globs `*` (within one package segment),
`**` (across segments) and `?` (one character). A value SHALL be a field name, a no-argument method written
with `()`, `"@"` for the class's `@StackTraceId`, or `"-"` for none. An entry SHALL apply only to the
classes whose names it matches, not to their subclasses. The receiver id of a frame SHALL come from the
deciding entry of the class that declares the frame's method, read from the object the method runs on,
whatever the object's runtime class: a subclass, including one generated at runtime (a proxy, a mock, an
anonymous class or an enum constant with a body), SHALL show the id of its superclass's entry in the frames
of the methods it inherits. The methods that a subclass declares SHALL only get a receiver id from an entry
that matches the subclass, which a pattern whose glob matches a generated class's name does. A default method
of an interface SHALL likewise show the id of the interface's deciding entry, read from the object,
whatever entry the implementing class has; without an entry for the interface, its frame SHALL be
unchanged. Bridge methods, including those Kotlin compiles into a class for each default method it
inherits, SHALL NOT be instrumented, so their frames are unchanged. When several entries match a class, the most specific SHALL decide: an entry without wildcards beats any pattern, and among patterns, the one with the
most characters other than `*` and `?` wins, with ties broken by the alphabetical order of the keys. A
configured field or method SHALL be looked up in the matched class and its superclasses, including private
members; for an interface, in the interface and the interfaces it extends, where an abstract method is
called on the object. When no field of a configured name exists, as in an interface or for a Kotlin
property without a backing field, the property's getter SHALL be used (`getName()`, or `isName()` for a
name starting with `is`), with the configured name as the label.

#### Scenario: Configured method of a third-party class
- **GIVEN** `"com.thirdparty.Order" = "getOrderNumber()"` in `[augment.receiver]`
- **WHEN** an exception leaves a method of an `Order` with order number 4711
- **THEN** that frame shows the receiver id `getOrderNumber=4711`

#### Scenario: Configured member does not exist
- **GIVEN** `"com.thirdparty.Customer" = "nope"` in `[augment.receiver]` and no field `nope`
- **WHEN** the id source of `Customer` is first needed
- **THEN** a warning `[augment.receiver] "com.thirdparty.Customer": no field or property nope found` is printed
- **AND** frames of `Customer` show no receiver id

#### Scenario: Kotlin interface property
- **GIVEN** `"com.acme.Tracked" = "trackingCode"`, where the Kotlin interface `Tracked` declares `val trackingCode: String` and has a default method `track()`
- **WHEN** an exception leaves `Tracked.track()`, called on an object whose `trackingCode` is `T-9`
- **THEN** that frame shows the receiver id `trackingCode=T-9`, read through `getTrackingCode()`

#### Scenario: Wildcard entry with a member
- **GIVEN** `"com.thirdparty.*Account" = "number"` in `[augment.receiver]`
- **WHEN** an exception leaves a method of a `com.thirdparty.SavingsAccount` with the field `number = "S-1"`
- **THEN** that frame shows the receiver id `number=S-1`

#### Scenario: Most specific entry wins
- **GIVEN** `"com.acme.**" = "@"` and `"com.acme.Order" = "getId()"` in `[augment.receiver]`, and `com.acme.Order` with `@StackTraceId val code` and `fun getId()`
- **WHEN** an exception leaves a method of an `Order`
- **THEN** that frame shows the receiver id `getId=…`, and `Order`'s `@StackTraceId` is not used

#### Scenario: Subclass without an entry
- **GIVEN** `"com.thirdparty.Order" = "getOrderNumber()"` and a subclass `com.acme.RushOrder` that no entry matches
- **WHEN** an exception leaves `Order.ship()`, called on a `RushOrder`, and separately `RushOrder.expedite()`
- **THEN** the first frame shows the receiver id `getOrderNumber=…`, and the second frame is unchanged

#### Scenario: Subclass with an entry of its own
- **GIVEN** `"com.acme.Order" = "id"` and `"com.acme.TrackedOrder" = "tracking"`, where `TrackedOrder` extends `Order`
- **WHEN** an exception leaves `Order.ship()`, called on a `TrackedOrder`, and separately `TrackedOrder.track()`
- **THEN** the first frame shows `id=…`, and the second `tracking=…`

#### Scenario: Subclass with an entry naming an inherited member
- **GIVEN** `"com.acme.ExpressOrder" = "id"`, where `ExpressOrder` extends `Order`, which declares the field `id`
- **WHEN** an exception leaves a method that `ExpressOrder` declares
- **THEN** that frame shows the receiver id `id=…`

#### Scenario: Generated subclass
- **GIVEN** `"com.acme.Order" = "id"`, and a Spring CGLIB proxy `com.acme.Order$$SpringCGLIB$$0` that calls the real methods
- **WHEN** an exception leaves `Order.ship()`, called on the proxy
- **THEN** that frame shows `id=…`, and the frame of the proxy's own override is unchanged

#### Scenario: Default method of an interface
- **GIVEN** `"com.acme.Labeled" = "label()"` and `"com.acme.Parcel" = "code"`, where the Kotlin class `Parcel` implements `Labeled` and inherits its default method `relabel()`
- **WHEN** an exception leaves `Labeled.relabel()`, called on a `Parcel` whose `label()` returns `parcel-p1`
- **THEN** the frame of `Labeled.relabel()` shows `label=parcel-p1`, and the frame of the bridge method `Parcel.relabel()` is unchanged
- **AND** the same holds for a class implementing `Labeled` without an entry of its own

#### Scenario: Default method of an interface without an entry
- **GIVEN** an interface `Sealable` that no entry matches, with a default method `seal()`, implemented by a class with an entry
- **WHEN** an exception leaves `Sealable.seal()`
- **THEN** its frame is unchanged

#### Scenario: Interface extending an interface
- **GIVEN** `"com.acme.Tracked" = "label()"`, where `Tracked` extends `Labeled`, which declares `label()`, and has a default method `track()`
- **WHEN** an exception leaves `Tracked.track()`
- **THEN** that frame shows `label=…`, read through `Labeled.label()`

### Requirement: Parameter ids
The system SHALL show the value of a method parameter after the method name when the parameter is
annotated with `@StackTraceParam` (`com.hafnium.stackaugmentor.StackTraceParam`), when its method or the
class declaring the method is annotated with `@StackTraceParams`
(`com.hafnium.stackaugmentor.StackTraceParams`, see "Parameter ids from method- and class-level
annotations"), in both cases only where an `[augment.params]` entry `"@"` applies, or when an
`[augment.params]` entry selects it by name, index or `"*"` (see "Parameter ids of configured methods").
This SHALL apply to instance and static methods. A parameter selected more than once SHALL be shown
once, and parameter ids SHALL be listed in declaration order. `@StackTraceId` on a parameter SHALL NOT
select it: `@StackTraceId` marks receiver ids only.

#### Scenario: Annotated parameter
- **GIVEN** `fun objectMethod(@StackTraceParam orderId: Int)`, matched by an `[augment.params]` `"@"` entry
- **WHEN** `objectMethod(42)` throws
- **THEN** that frame shows the parameter id `orderId=42`

#### Scenario: Configured parameters by name and index
- **GIVEN** `"com.thirdparty.OrderService.process" = ["order", 1]` in `[augment.params]` for `process(order: Order, quantity: Int, note: String)`
- **WHEN** `process(Order(4711), 3, "rush")` throws, where `Order.toString()` returns `Order#4711`
- **THEN** that frame shows `order=Order#4711, quantity=3`

#### Scenario: Static method
- **GIVEN** a top-level (static) function `staticWithParam(@StackTraceParam code: Int)`, matched by an `[augment.params]` `"@"` entry
- **WHEN** `staticWithParam(5)` throws
- **THEN** that frame shows the parameter id `code=5` and no receiver id

#### Scenario: Selected by an annotation and by the configuration
- **GIVEN** `fun op(@StackTraceParam x: Int, y: Int)` in class `C`, and `"C.*" = "@"` and `"C.op" = ["x", "y"]` in `[augment.params]`
- **WHEN** `op(1, 2)` throws
- **THEN** that frame shows `x=1, y=2`, each parameter once

#### Scenario: Annotations enabled by a method entry
- **GIVEN** `fun run(@StackTraceParam code: Int)` in `com.hafnium.it.outside.MethodAnnotationsOutside`, which no `[augment.receiver]` entry matches, and `"com.hafnium.it.outside.MethodAnnotationsOutside.run" = "@"` in `[augment.params]`
- **WHEN** `run(7)` throws
- **THEN** that frame shows `code=7` and no receiver id

### Requirement: Labels
The label of a receiver id SHALL be the real name of the field or method that supplies it, and the label
of a parameter id SHALL be the parameter name. `@StackTraceId(name = "…")` SHALL override the label of a
receiver id, and `@StackTraceParam(name = "…")` SHALL override the label of that parameter id, also when
the parameter is selected by `@StackTraceParams` or by the configuration. Like ids, a label from `name`
SHALL be kept on one line, and its parentheses SHALL be replaced with braces. When a class has no parameter
names (compiled without `-parameters`), the label SHALL be `arg<N>`.

#### Scenario: Name override
- **GIVEN** `@StackTraceId(name = "user") val login = "bob"`
- **WHEN** an exception leaves a method of that class
- **THEN** the frame shows `user=bob`

#### Scenario: Parameter name override
- **GIVEN** `named(@StackTraceParam(name = "count") int value)`
- **WHEN** `named(6)` throws
- **THEN** the frame shows `count=6`

#### Scenario: Name override under a method-level annotation
- **GIVEN** `@StackTraceParams fun move(@StackTraceParam(name = "sku") item: String, count: Int)`
- **WHEN** `move("x-1", 2)` throws
- **THEN** the frame shows `sku=x-1, count=2`

#### Scenario: Parentheses in a name override
- **GIVEN** `@StackTraceId(name = "id(x)") val id = "7"`
- **WHEN** an exception leaves a method of that class
- **THEN** the frame shows `id{x}=7`

#### Scenario: No parameter names
- **GIVEN** a Java class compiled without `-parameters` and `run(@StackTraceParam int value)`
- **WHEN** `run(5)` throws
- **THEN** the frame shows `arg0=5`

### Requirement: Id values as text
Every id SHALL be converted to text when it is captured. `null` SHALL be shown as `null`. Strings, numbers,
booleans, characters and enums SHALL be shown with `toString()`. Arrays SHALL be shown by their elements.
Other arguments SHALL be shown with `toString()`, also when their class has a receiver id source:
`[augment.receiver]` SHALL only apply to receivers. Line breaks SHALL be replaced by a single space, and `(`
and `)` by `{` and `}`, because IDEs find a frame's file and line by the parenthesised `(File.kt:12)` that
follows the method. Text longer than
`[augment] maxIdLength` SHALL be cut to `maxIdLength - 1` characters followed by `…`. An id source that
throws SHALL be shown as `?`.

#### Scenario: Object argument with an id source
- **GIVEN** `fun ship(@StackTraceParam order: Order?)`, and `"com.thirdparty.Order" = "getOrderNumber()"` in `[augment.receiver]`
- **WHEN** `ship(Order(4711))` throws, where `Order.toString()` returns `Order#4711`
- **THEN** the frame shows `order=Order#4711`, not the receiver id `4711`
- **AND** `ship(null)` shows `order=null`

#### Scenario: Array receiver id
- **GIVEN** `@StackTraceId val codes = intArrayOf(1, 2)` in a class matched by an `"@"` entry
- **WHEN** an exception leaves a method of that class
- **THEN** the frame shows `codes=[1, 2]`, as an array argument would, not `[I@…`

#### Scenario: Long and multi-line ids
- **GIVEN** `maxIdLength = 20`
- **WHEN** a receiver id is 50 characters long, or contains `line1\nline2`
- **THEN** the frame shows the first 19 characters followed by `…`, or `line1 line2`

#### Scenario: Parentheses in an id
- **GIVEN** `fun draw(@StackTraceParam point: Point)`, where `Point` is a Kotlin `data class Point(val x: Int)`
- **WHEN** `draw(Point(1))` throws
- **THEN** the frame shows `point=Point{x=1}`, so that the frame still ends with its `(File.kt:12)`

#### Scenario: Failing id source
- **GIVEN** `@StackTraceId fun id(): String` that throws
- **WHEN** an exception leaves a method of that class
- **THEN** the frame shows `id=?` and the original exception is unchanged otherwise

### Requirement: Parameter ids from method- and class-level annotations
`@StackTraceParams` (on methods and classes only, without attributes) on a method SHALL select all
parameters of that method. `@StackTraceParams` on a class SHALL select all parameters of every instance and
static method declared in that class. It SHALL NOT apply to methods of subclasses or nested classes, which
need their own annotation. Both SHALL only be used where an `[augment.params]` entry `"@"` applies. Methods without parameters SHALL get no parameter ids. Constructors, synthetic,
bridge, abstract and native methods SHALL NOT be instrumented, as for other parameter ids.

#### Scenario: Method-level annotation
- **GIVEN** `@StackTraceParams fun transfer(from: String, to: String, amount: Long)`
- **WHEN** `transfer("a", "b", 10)` throws
- **THEN** that frame shows `from=a, to=b, amount=10`

#### Scenario: Class-level annotation
- **GIVEN** `@StackTraceParams class Inventory` with `fun reserve(sku: String, count: Int)` and `fun release(sku: String)`
- **WHEN** `reserve("x-1", 2)` throws, and separately `release("x-2")` throws
- **THEN** the frames show `reserve{sku=x-1, count=2}` and `release{sku=x-2}`

#### Scenario: Subclass of an annotated class
- **GIVEN** `@StackTraceParams open class Base` and `class Derived : Base()` that declares `fun run(x: Int)` without annotations
- **WHEN** `Derived().run(1)` throws
- **THEN** the frame shows no parameter ids for `run`

### Requirement: Parameter ids of configured methods
An `[augment.params]` entry `"<class pattern>.<method pattern>" = <parameters>` SHALL select
parameters of every method whose class name matches the class pattern and whose name matches the method
pattern, including every overload, whatever the class's `[augment.receiver]` entry. Keys SHALL match the
class that declares the method, not its subclasses. The class pattern
SHALL use the `[augment.receiver]` globs (`*` within one package segment, `**` across segments, `?` one
character). In the method pattern, `*` SHALL match any sequence of characters and `?` one character. A key
without wildcards SHALL match exactly. `<parameters>` SHALL be an array of parameter names and 0-based
indexes, the string `"*"` for all parameters, or the string `"@"` for the parameters selected by the method's
annotations: `@StackTraceParam` on its parameters, and `@StackTraceParams` on the method or on the class that
declares it. Names and indexes that a matched method does not have
SHALL be skipped. When several entries match one method, the parameters they select SHALL be combined, up to
a `"-"` entry as described under "Ignoring receivers and parameters".

#### Scenario: All methods and parameters of a class
- **GIVEN** `"com.thirdparty.InventoryService.*" = "*"`
- **WHEN** `InventoryService.reserve("x-1", 2)` throws
- **THEN** that frame shows `sku=x-1, count=2` and no receiver id

#### Scenario: Wildcards across packages
- **GIVEN** `"com.thirdparty.**.*Repository.find*" = [0]`
- **WHEN** `com.thirdparty.db.OrderRepository.findById(7)` throws, and separately `findAll()` throws
- **THEN** the first frame shows `id=7`, and the second frame is unchanged

#### Scenario: Overlapping entries
- **GIVEN** `"com.thirdparty.OrderService.process" = ["order"]` and `"com.thirdparty.OrderService.*" = [2]`
- **WHEN** `process(Order(4711), 3, "rush")` throws, where `Order.toString()` returns `Order#4711`
- **THEN** that frame shows `order=Order#4711, note=rush`

#### Scenario: Exact explicit class entry with annotated parameters
- **GIVEN** `"com.acme.Order" = "getId()"` in `[augment.receiver]` and `"com.acme.Order.*" = "@"` in `[augment.params]`, and `fun ship(@StackTraceParam(name = "to") address: String)` in `Order`
- **WHEN** `ship("Main St")` throws
- **THEN** that frame shows the receiver id `getId=…` and the parameter id `to=Main St`

### Requirement: Ignoring receivers and parameters
The value `"-"` SHALL select nothing, in either table, and SHALL affect its own table only. A class whose
deciding `[augment.receiver]` entry is `"-"` SHALL get no receiver id; the entry decides with the same
most-specific rule as other entries. When several
`[augment.params]` entries match a method, they SHALL be ordered from the most specific on (the entry
without wildcards first, then the patterns with the most characters other than `*` and `?`, ties broken by
key) and combined up to the first `"-"` entry; that entry and the less specific ones SHALL select nothing.
A configuration whose entries are all `"-"` SHALL count as having no entries.

#### Scenario: No receiver ids in a package under an "@" pattern
- **GIVEN** `"com.acme.**" = "@"` and `"com.acme.generated.**" = "-"` in `[augment.receiver]`, and `com.acme.generated.Mapper` with a `@StackTraceId` field
- **WHEN** a method of `Mapper` throws
- **THEN** its frame shows no receiver id

#### Scenario: Ignored methods under a wildcard entry
- **GIVEN** `"com.thirdparty.Inventory*.*" = "*"`, `"com.thirdparty.InventoryAudit.*" = "-"` and `"com.thirdparty.InventoryAudit.log" = ["reason"]` in `[augment.params]`
- **WHEN** `InventoryAudit.purge("x-1")` throws, and separately `InventoryAudit.log("disk full", 2)` throws
- **THEN** the first frame is unchanged, and the second frame shows `reason=disk full`

### Requirement: Annotations in use
The system SHALL use `@StackTraceId` annotations (on fields and methods) only in classes whose deciding
`[augment.receiver]` entry is `"@"` (see "Receiver id from external configuration"), and
`@StackTraceParam` annotations (on parameters) and `@StackTraceParams` annotations (on methods and classes)
only for methods matched by an `[augment.params]` entry with the value `"@"` (see "Parameter ids of
configured methods"). Without an `"@"` entry, no annotations SHALL be used. A class whose deciding entry
names a field or method SHALL get its receiver id from that member: its `@StackTraceId` SHALL NOT be used.
Classes without an annotation or configuration entry SHALL NOT get ids, even when an `"@"` entry matches
them.

#### Scenario: Annotated class outside the "@" entries
- **GIVEN** `"com.hafnium.it.fixtures.**" = "@"` in `[augment.receiver]` and `"com.hafnium.it.fixtures.**.*" = "@"` in `[augment.params]` as the only entries
- **WHEN** an exception leaves a method of a class in `com.hafnium.it.outside` with an annotated parameter, and no annotated superclass
- **THEN** the frame is unchanged

#### Scenario: Subclass of a class with an "@" entry
- **GIVEN** `"com.hafnium.it.fixtures.**" = "@"`, and `com.hafnium.it.outside.DerivedOutside` extending `com.hafnium.it.fixtures.Base`, which declares `@StackTraceId val baseId = "b1"`
- **WHEN** an exception leaves a method of `DerivedOutside`
- **THEN** that frame shows no receiver id: `DerivedOutside` declares `fail()`, and the entry of `Base` does not apply to its subclasses

#### Scenario: Matched class without annotations
- **GIVEN** a class matched by an `"@"` entry that has no annotations, but overrides `toString()`
- **WHEN** an exception leaves one of its methods
- **THEN** the frame is unchanged

#### Scenario: Class-level parameter annotation outside the "@" entries
- **GIVEN** `"com.hafnium.it.fixtures.**.*" = "@"` in `[augment.params]` and a class in `com.hafnium.it.outside` annotated with `@StackTraceParams`
- **WHEN** one of its methods throws
- **THEN** the frame shows no parameter ids

#### Scenario: Class-level parameter annotation enabled by method entries
- **GIVEN** `@StackTraceParams class ClassParamsViaMethods` in `com.hafnium.it.outside`, and `"com.hafnium.it.outside.ClassParamsViaMethods.*" = "@"` in `[augment.params]`
- **WHEN** `run(1, "x")` throws
- **THEN** that frame shows all its parameter ids

#### Scenario: No "@" entry
- **GIVEN** a configuration with only `"com.thirdparty.Order" = "getOrderNumber()"` in `[augment.receiver]`
- **WHEN** an exception leaves a method of an annotated class in any other package
- **THEN** the frame is unchanged
