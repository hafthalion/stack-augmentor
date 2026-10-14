# Receivers and Parameters Specification

## Purpose
Defines where the values shown in stack trace frames come from: members of the object a frame runs on
(receiver members) and selected method arguments (parameter values), how they are labelled, and how
they are turned into text.

## Requirements

### Requirement: Independent receiver members and parameter values
`[augment.receiver]` SHALL decide the receiver members and `[augment.params]` the parameter values, independently:
no value in one table (a member, a parameter, `"@"` or `"-"`) SHALL change what the other selects. What gets
instrumented SHALL NOT be configured directly: a class SHALL be instrumented when either table augments it with a
receiver member or a parameter value, and a method when it is augmented with a parameter value or, as an instance method, a
receiver member.

#### Scenario: Receiver ignored, parameters kept
- **GIVEN** `"com.acme.generated.**::*" = "-"` and then `"com.acme.**::*" = "@"` in `[augment.receiver]`, `"com.acme.**::*" = "@"` in `[augment.params]`, and `fun map(@AugmentedParam id: Int)` in the annotated class `com.acme.generated.Mapper`
- **WHEN** `map(7)` throws
- **THEN** that frame shows `id=7` and no receiver members

#### Scenario: Receiver member only
- **GIVEN** `"com.acme.**::*" = "@"` as the only entry, and a class `com.acme.Order` with `@AugmentedReceiver val code` and `fun ship(@AugmentedParam to: String)`
- **WHEN** `ship("Main St")` throws
- **THEN** that frame shows the receiver member `code=…` and no parameter values

### Requirement: Receiver members from annotations
The system SHALL use every non-static field and every non-static, no-argument method annotated with
`@AugmentedReceiver` (`com.hafnium.stackaugmentor.AugmentedReceiver`) as a receiver member of the objects of a class
whose deciding `[augment.receiver]` entry is `"@"` (see "Annotations in use"), one value per member. The
annotated members SHALL be looked up in the class that the deciding entry matched and in its superclasses,
and shown in this order: per class, the class itself first, then its superclasses, nearest first; within a
class, its fields in declaration order, then its methods by name. A member whose label a member shown
before it already has, such as a superclass field hidden by a field of the same name, SHALL be left out.
In Kotlin, a property annotated in the primary constructor has the annotation on its field, because
`@AugmentedReceiver` does not target parameters. A static member, a method with parameters and a synthetic method
SHALL NOT be receiver members, even when annotated, and SHALL NOT make a class count as annotated when the
instrumentation decides which classes to change. A member that cannot be made accessible SHALL be skipped with a
warning, and the others SHALL still be used. The annotation SHALL be matched by its class name, so that a copy of the API loaded by
another class loader is also recognised.

#### Scenario: Annotated method with parameters
- **GIVEN** a class matched by an `"@"` entry whose only `@AugmentedReceiver` member is `String idFor(int version)`
- **WHEN** the instrumentation decides whether to change the class
- **THEN** the class is not instrumented for a receiver member

#### Scenario: Annotated field
- **GIVEN** a class `ObjectClass` with `@AugmentedReceiver val objectId = "object-1"`, matched by an `"@"` entry
- **WHEN** an exception leaves an instance method of `ObjectClass`
- **THEN** that frame shows the receiver member `objectId=object-1`

#### Scenario: Annotated method
- **GIVEN** a class with `@AugmentedReceiver fun key() = "k-1"`, matched by an `"@"` entry
- **WHEN** an exception leaves one of its instance methods
- **THEN** that frame shows the receiver member `key=k-1`

#### Scenario: Several annotated members
- **GIVEN** a class with `@AugmentedReceiver val tenant = "acme"` and `@AugmentedReceiver val orderId = 42`, matched by an `"@"` entry
- **WHEN** an exception leaves one of its instance methods
- **THEN** that frame shows the receiver members `{tenant=acme, orderId=42}`

#### Scenario: Inherited annotation
- **GIVEN** a class `Derived`, matched by an `"@"` entry, extending a class that declares `@AugmentedReceiver val baseId = "b1"`
- **WHEN** an exception leaves a method of `Derived`
- **THEN** that frame shows the receiver member `baseId=b1`

#### Scenario: Kotlin primary-constructor property
- **GIVEN** `class Node(@AugmentedReceiver val name: String)`, matched by an `"@"` entry
- **WHEN** an exception leaves a method of `Node("c")`
- **THEN** that frame shows the receiver member `name=c`

### Requirement: Receiver members from external configuration
The system SHALL use the `[augment.receiver]` configuration table to choose the receiver members of a
method. Its keys SHALL be those of `[augment.params]`, `"<class>::<methods>"`, matched the same way (see the
configuration spec): a class name with the globs `*` (within one package segment), `**` (across segments) and `?`
(one character), or a class name optionally followed by `+`; then `::` and method names or patterns separated by
commas without duplicates, `*` for all methods and `@` for the methods annotated with `@Augmented`. The
entry SHALL apply only to the frames of the methods whose names match one of them; `<init>` SHALL be rejected, as
constructors show no receiver members. One class MAY have several entries with different methods. A value SHALL be a field name, a no-argument method written
with `()`, several of them in one string separated by commas without duplicates, `"@"` for the class's `@AugmentedReceiver` members, or
`"-"` for none. A list SHALL give one value per member, in the listed order; a member that is not found
SHALL be reported as a single one is and left out, while the others are still shown. Every receiver member SHALL
be shown as its value as text, from `toString()`. A class name SHALL match the class it names; with `+` also its
subclasses, and for an interface the classes implementing it, as `instanceof` does, comparing names so that no
class is loaded; a key with wildcards SHALL match the classes whose names it matches only, not their
subclasses. When several entries match a class and apply to a method, the first in the order of the
file SHALL decide for that method, as in `[augment.exceptions]`, however specific the later ones are; a method that
no matching entry applies to SHALL keep its frames' parameter values only. The receiver members of a frame SHALL come from
the deciding entry of the class that declares the frame's method, read from the object the method runs on,
whatever the object's runtime class: a subclass, including one generated at runtime (a proxy, a mock, an
anonymous class or an enum constant with a body), SHALL show the receiver members of its superclass's entry in the frames
of the methods it inherits. The methods that a subclass declares SHALL take their receiver members from the first
entry that matches the subclass: an entry of its own, an entry with `+` for a superclass or an implemented
interface, or a pattern whose glob matches its name. A default method
of an interface SHALL likewise show the receiver members of the interface's deciding entry, read from the object,
whatever entry the implementing class has; without an entry for the interface, its frame SHALL be
unchanged. Bridge methods, including those Kotlin compiles into a class for each default method it
inherits, SHALL NOT be instrumented, so their frames are unchanged. A
configured field or method SHALL be looked up in the matched class and its superclasses, including private
members; for an interface, in the interface and the interfaces it extends, where an abstract method is
called on the object. A configured method or getter that a class does not declare itself SHALL then be looked up
in the interfaces the class and its superclasses implement, nearest first, so that a Java class finds a default
method it inherits, as a Kotlin class, which compiles it into the class, does. When no field of a configured name exists, as in an interface or for a Kotlin
property without a backing field, the property's getter SHALL be used (`getName()`, or `isName()` for a
name starting with `is`), with the configured name as the label.

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

#### Scenario: Receiver members and parameters of the annotated methods
- **GIVEN** `"com.acme.Dispatcher::@" = "hub"` in `[augment.receiver]`, `"com.acme.Dispatcher::@, track" = "order"` in `[augment.params]`, and a `Dispatcher` with hub `H-1` whose `send(order)` is annotated with `@Augmented` and whose `track(order)` is not
- **WHEN** exceptions leave `send("o-1")` and `track("o-1")`
- **THEN** the frame of `send` shows `Dispatcher{hub=H-1}.send{order=o-1}`, and the frame of `track` shows `Dispatcher.track{order=o-1}`

#### Scenario: Default method of an implemented interface
- **GIVEN** `"com.acme.Order::*" = "getId()"`, where the Java class `Order` implements `Identified`, whose default method `getId()` returns `id-7`
- **WHEN** an exception leaves a method of an `Order`
- **THEN** that frame shows the receiver member `getId=id-7`
- **AND** with `"com.acme.Order::*" = "id"` and no field `id`, the frame shows `id=id-7`, read through the same getter

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

#### Scenario: Kotlin interface property
- **GIVEN** `"com.acme.Tracked::*" = "trackingCode"`, where the Kotlin interface `Tracked` declares `val trackingCode: String` and has a default method `track()`
- **WHEN** an exception leaves `Tracked.track()`, called on an object whose `trackingCode` is `T-9`
- **THEN** that frame shows the receiver member `trackingCode=T-9`, read through `getTrackingCode()`

#### Scenario: Wildcard entry with a member
- **GIVEN** `"com.thirdparty.*Account::*" = "number"` in `[augment.receiver]`
- **WHEN** an exception leaves a method of a `com.thirdparty.SavingsAccount` with the field `number = "S-1"`
- **THEN** that frame shows the receiver member `number=S-1`

#### Scenario: First matching entry wins
- **GIVEN** `"com.acme.Order::*" = "getId()"` and then `"com.acme.**::*" = "@"` in `[augment.receiver]`, and `com.acme.Order` with `@AugmentedReceiver val code` and `fun getId()`
- **WHEN** an exception leaves a method of an `Order`
- **THEN** that frame shows the receiver member `getId=…`, and `Order`'s `@AugmentedReceiver` is not used
- **AND** with the two entries the other way round, `"com.acme.**::*" = "@"` decides and the frame shows `code=…`

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
The system SHALL show the value of a method parameter after the method name when the parameter is
annotated with `@AugmentedParam` (`com.hafnium.stackaugmentor.AugmentedParam`), when its method is
annotated with `@AugmentedParams` (`com.hafnium.stackaugmentor.AugmentedParams`, see "Parameter values from
method-level annotations"), in both cases only where an `[augment.params]` entry `"@"` applies, or when an
`[augment.params]` entry selects it by name or index (see "Parameter values of configured methods").
This SHALL apply to instance and static methods. A parameter selected more than once SHALL be shown
once, and parameter values SHALL be listed in declaration order. `@AugmentedReceiver` on a parameter SHALL NOT
select it: `@AugmentedReceiver` marks receiver members only.

#### Scenario: Annotated parameter
- **GIVEN** `fun objectMethod(@AugmentedParam orderId: Int)`, matched by an `[augment.params]` `"@"` entry
- **WHEN** `objectMethod(42)` throws
- **THEN** that frame shows the parameter value `orderId=42`

#### Scenario: Configured parameters by name and index
- **GIVEN** `"com.thirdparty.OrderService::process" = "order, 1"` in `[augment.params]` for `process(order: Order, quantity: Int, note: String)`
- **WHEN** `process(Order(4711), 3, "rush")` throws, where `Order.toString()` returns `Order#4711`
- **THEN** that frame shows `order=Order#4711, quantity=3`

#### Scenario: Static method
- **GIVEN** a top-level (static) function `staticWithParam(@AugmentedParam code: Int)`, matched by an `[augment.params]` `"@"` entry
- **WHEN** `staticWithParam(5)` throws
- **THEN** that frame shows the parameter value `code=5` and no receiver members

#### Scenario: Selected by name and by index
- **GIVEN** `fun op(x: Int, y: Int)` in class `C`, and `"C::op" = "x, y, 0"` in `[augment.params]`
- **WHEN** `op(1, 2)` throws
- **THEN** that frame shows `x=1, y=2`, each parameter once

#### Scenario: Annotations enabled by a method entry
- **GIVEN** `fun run(@AugmentedParam code: Int)` in `com.hafnium.it.outside.MethodAnnotationsOutside`, which no `[augment.receiver]` entry matches, and `"com.hafnium.it.outside.MethodAnnotationsOutside::run" = "@"` in `[augment.params]`
- **WHEN** `run(7)` throws
- **THEN** that frame shows `code=7` and no receiver members

### Requirement: Hashed parameter values
A `#` after a parameter name or index in an `[augment.params]` value (`"email#"`, `"2#"`),
or `@AugmentedParam(secret = true)` on a parameter (also one that `@AugmentedParams` selects) SHALL show the
value hashed: `#` followed by the
first 8 lower-case hex digits of the SHA-256 of the value's text (its UTF-8 bytes, before line breaks and
parentheses are replaced and before `maxValueLength` applies). `null` SHALL stay `null`, and a value whose `toString()`
throws SHALL still show `?`. `secret` SHALL default to `false`. A parameter selected twice (by name and by
index, or by both annotations) SHALL be hashed when either hashes it. The label SHALL stay the parameter name.

#### Scenario: Parameters hashed by name and index
- **GIVEN** `"com.thirdparty.UserService::invite" = "user, email#, 2#"` in `[augment.params]` for `invite(user: String, email: String, attempt: Int)`
- **WHEN** `invite("ann", "ann@example.com", 3)` throws
- **THEN** that frame shows `user=ann, email=#71d4f55f, attempt=#4e074085`

#### Scenario: Secret annotations
- **GIVEN** `fun invite(@AugmentedParam user: String, @AugmentedParam(secret = true) email: String)` and `@AugmentedParams fun register(@AugmentedParam(secret = true) email: String, nickname: String)`, matched by an `"@"` entry
- **WHEN** `invite("ann", "ann@example.com")` throws, and separately `register("a@b.c", "annie")` throws
- **THEN** the first frame shows `user=ann, email=#71d4f55f`, and the second `email=#d648b243, nickname=annie`

### Requirement: Labels
The label of a receiver member SHALL be the real name of the field or method that supplies its value, and the label
of a parameter value SHALL be the parameter name compiled into the class file, however the parameter is
selected. Labels SHALL NOT be overridden: `@AugmentedReceiver` has no attributes, `@AugmentedParams` has none, and the only one of `@AugmentedParam` is `secret`. When a class has no parameter names (compiled without
`-parameters`), the label SHALL be `arg<N>`, so Java classes SHOULD be compiled with `javac -parameters` (Kotlin
with `javaParameters = true`).

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
Every receiver member and parameter value SHALL be converted to text when it is captured. `null` SHALL be shown as `null`. Strings, numbers,
booleans, characters and enums SHALL be shown with `toString()`. Arrays SHALL be shown by their elements.
Other arguments SHALL be shown with `toString()`, also when their class has receiver members:
`[augment.receiver]` SHALL only apply to receivers. Line breaks SHALL be replaced by a single space, and `(`
and `)` by `{` and `}`, because IDEs find a frame's file and line by the parenthesised `(File.kt:12)` that
follows the method. Text longer than
`[augment] maxValueLength` SHALL be cut to `maxValueLength - 1` characters followed by `…`. A member or a `toString()` that
throws SHALL be shown as `?`.

#### Scenario: Object argument whose class has receiver members
- **GIVEN** `fun ship(@AugmentedParam order: Order?)`, and `"com.thirdparty.Order::*" = "getOrderNumber()"` in `[augment.receiver]`
- **WHEN** `ship(Order(4711))` throws, where `Order.toString()` returns `Order#4711`
- **THEN** the frame shows `order=Order#4711`, not the value `4711` of its receiver member
- **AND** `ship(null)` shows `order=null`

#### Scenario: Array receiver member
- **GIVEN** `@AugmentedReceiver val codes = intArrayOf(1, 2)` in a class matched by an `"@"` entry
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

### Requirement: Parameter values from method-level annotations
`@AugmentedParams` (on methods and constructors) SHALL select all parameters of that method or constructor. It SHALL NOT apply to
methods that override it, which need their own annotation. It SHALL only be used where an `[augment.params]` entry `"@"` applies. Methods without parameters SHALL show no parameter values. Synthetic,
bridge, abstract and native methods SHALL NOT be instrumented, as for other parameter values; constructors are
instrumented as described in the stack trace augmentation specification.

#### Scenario: Method-level annotation
- **GIVEN** `@AugmentedParams fun transfer(from: String, to: String, amount: Long)`
- **WHEN** `transfer("a", "b", 10)` throws
- **THEN** that frame shows `from=a, to=b, amount=10`

#### Scenario: Override of an annotated method
- **GIVEN** `@AugmentedParams open fun reserve(sku: String, count: Int)` in `open class Inventory`, and `class DerivedInventory : Inventory()` that overrides `reserve` without annotations
- **WHEN** `DerivedInventory().reserve("x-3", 1)` throws
- **THEN** the frame shows no parameter values for `reserve`

### Requirement: Parameter values of configured methods
An `[augment.params]` entry `"<class>::<method pattern>" = <parameters>`, with the keys of the configuration
spec, SHALL select parameters of every method whose declaring class the class part matches and whose name matches
the method pattern, including every overload; with `@` as the method pattern, of every method and constructor of a matching
class that is annotated with `@Augmented`, while its other methods SHALL be left to the later entries.
Several method patterns separated by commas (`"com.acme.Order::ship, cancel*, @"`) SHALL match a method that any
of them matches; whatever the class's `[augment.receiver]` entry. Keys SHALL match the
class that declares the method, and with `+` after a class name (`"com.acme.Order+::process"`) also the methods
its subclasses declare. The class pattern
SHALL use the `[augment.receiver]` globs (`*` within one package segment, `**` across segments, `?` one
character). In the method pattern, `*` SHALL match any sequence of characters and `?` one character. A key
without wildcards SHALL match exactly. `<parameters>` SHALL be a string of parameter names and 0-based
indexes (0 to 255) separated by commas, or the string `"@"` for the parameters selected by the method's
annotations: `@AugmentedParam` on its parameters, and `@AugmentedParams` on the method. There SHALL be no value for all parameters: each is named, or selected by an annotation. Names and indexes that a matched method does not have
SHALL be skipped. When several entries match one method, only the first in the order of the file SHALL
decide, as in `[augment.receiver]`, however specific the later ones are. The later entries SHALL NOT be
combined with it.

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

#### Scenario: Exact explicit class entry with annotated parameters
- **GIVEN** `"com.acme.Order::*" = "getId()"` in `[augment.receiver]` and `"com.acme.Order::*" = "@"` in `[augment.params]`, and `fun ship(@AugmentedParam to: String)` in `Order`
- **WHEN** `ship("Main St")` throws
- **THEN** that frame shows the receiver member `getId=…` and the parameter value `to=Main St`

### Requirement: Ignoring receivers and parameters
The value `"-"` SHALL select nothing, in either table, and SHALL affect its own table only. A class whose
deciding `[augment.receiver]` entry is `"-"` SHALL show no receiver members; the entry decides with the same
first-match rule as other entries. Likewise, a method whose deciding `[augment.params]` entry is `"-"`
SHALL show no parameter values, even where a later entry would select some.
A configuration whose entries are all `"-"` SHALL count as having no entries.

#### Scenario: No receiver members in a package under an "@" pattern
- **GIVEN** `"com.acme.generated.**::*" = "-"` and then `"com.acme.**::*" = "@"` in `[augment.receiver]`, and `com.acme.generated.Mapper` with a `@AugmentedReceiver` field
- **WHEN** a method of `Mapper` throws
- **THEN** its frame shows no receiver members

#### Scenario: Ignored methods under a wildcard entry
- **GIVEN** `"com.thirdparty.InventoryAudit::log" = "reason"`, `"com.thirdparty.InventoryAudit::*" = "-"` and `"com.thirdparty.Inventory*::*" = "sku, count"` in `[augment.params]`, in this order
- **WHEN** `InventoryAudit.purge("x-1")` throws, and separately `InventoryAudit.log("disk full", 2)` throws
- **THEN** the first frame is unchanged, and the second frame shows `reason=disk full`

### Requirement: Annotations in use
The system SHALL use `@AugmentedReceiver` annotations (on fields and methods) only in classes whose deciding
`[augment.receiver]` entry is `"@"` (see "Receiver members from external configuration"), and
`@AugmentedParam` annotations (on parameters) and `@AugmentedParams` annotations (on methods)
only for methods matched by an `[augment.params]` entry with the value `"@"` (see "Parameter values of
configured methods"). Without an `"@"` entry, no annotations SHALL be used. A class whose deciding entry
names a field or method SHALL take its receiver members from that entry: its `@AugmentedReceiver` SHALL NOT be used.
Classes without an annotation or configuration entry SHALL NOT be augmented, even when an `"@"` entry matches
them.

#### Scenario: Annotated class outside the "@" entries
- **GIVEN** `"com.hafnium.it.fixtures.**::*" = "@"` in `[augment.receiver]` and `"com.hafnium.it.fixtures.**::*" = "@"` in `[augment.params]` as the only entries
- **WHEN** an exception leaves a method of a class in `com.hafnium.it.outside` with an annotated parameter, and no annotated superclass
- **THEN** the frame is unchanged

#### Scenario: Subclass of a class with an "@" entry
- **GIVEN** `"com.hafnium.it.fixtures.**::*" = "@"`, and `com.hafnium.it.outside.DerivedOutside` extending `com.hafnium.it.fixtures.Base`, which declares `@AugmentedReceiver val baseId = "b1"`
- **WHEN** an exception leaves a method of `DerivedOutside`
- **THEN** that frame shows no receiver members: `DerivedOutside` declares `fail()`, and the pattern matches the names of the classes in `com.hafnium.it.fixtures` only, not their subclasses elsewhere

#### Scenario: Matched class without annotations
- **GIVEN** a class matched by an `"@"` entry that has no annotations, but overrides `toString()`
- **WHEN** an exception leaves one of its methods
- **THEN** the frame is unchanged

#### Scenario: Method-level parameter annotation outside the "@" entries
- **GIVEN** `"com.hafnium.it.fixtures.**::*" = "@"` in `[augment.params]` and a method of a class in `com.hafnium.it.outside` annotated with `@AugmentedParams`
- **WHEN** that method throws
- **THEN** the frame shows no parameter values

#### Scenario: Method-level parameter annotation enabled by method entries
- **GIVEN** `@AugmentedParams fun run(a: Int, b: String)` in `com.hafnium.it.outside.ClassParamsViaMethods`, and `"com.hafnium.it.outside.ClassParamsViaMethods::*" = "@"` in `[augment.params]`
- **WHEN** `run(1, "x")` throws
- **THEN** that frame shows all its parameter values

#### Scenario: No "@" entry
- **GIVEN** a configuration with only `"com.thirdparty.Order::*" = "getOrderNumber()"` in `[augment.receiver]`
- **WHEN** an exception leaves a method of an annotated class in any other package
- **THEN** the frame is unchanged
