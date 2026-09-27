# Id Sources Specification

## Purpose
Defines where the ids shown in stack trace frames come from: the id of the object a frame runs on
(receiver id) and the ids of selected method arguments (parameter ids), how they are labelled, and how
their values are turned into text.

## Requirements

### Requirement: Receiver id from an annotated member
The system SHALL use a non-static field or a non-static, no-argument method annotated with
`@StackTraceId` (`com.hafnium.stackaugmentor.StackTraceId`) as the receiver id of the objects of a class
whose deciding `[instrument.classes]` entry is `"@"` (see "Classes whose annotations are used"). The
annotated member SHALL be looked up in the class that the deciding entry matched and in its superclasses.
In Kotlin, a primary-constructor property whose constructor parameter is annotated SHALL be recognised as
an annotated field. The annotation SHALL be matched by its class name, so that a copy of the API loaded by
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
The system SHALL use the `[instrument.classes]` configuration table to choose the receiver id source of a
class. A key SHALL be a class name or a class pattern with the globs `*` (within one package segment),
`**` (across segments) and `?` (one character). A value SHALL be a field name, a no-argument method written
with `()`, or `"@"` for the class's annotations. An entry SHALL apply to the classes it matches and to their
subclasses: the deciding entry of a class SHALL be found by checking the class, then its superclasses in
order, and taking the first class that any entry matches. When several entries match that class, the most
specific SHALL decide: an entry without wildcards beats any pattern, and among patterns, the one with the
most characters other than `*` and `?` wins, with ties broken by the alphabetical order of the keys. A
configured field or method SHALL be looked up in the matched class and its superclasses, including private
members.

#### Scenario: Configured method of a third-party class
- **GIVEN** `"com.thirdparty.Order" = "getOrderNumber()"` in `[instrument.classes]`
- **WHEN** an exception leaves a method of an `Order` with order number 4711
- **THEN** that frame shows the receiver id `getOrderNumber=4711`

#### Scenario: Configured member does not exist
- **GIVEN** `"com.thirdparty.Customer" = "nope"` in `[instrument.classes]` and no field `nope`
- **WHEN** the id source of `Customer` is first needed
- **THEN** a warning `[instrument.classes] "com.thirdparty.Customer": no field nope found` is printed
- **AND** frames of `Customer` show no receiver id

#### Scenario: Wildcard entry with a member
- **GIVEN** `"com.thirdparty.*Account" = "number"` in `[instrument.classes]`
- **WHEN** an exception leaves a method of a `com.thirdparty.SavingsAccount` with the field `number = "S-1"`
- **THEN** that frame shows the receiver id `number=S-1`

#### Scenario: Most specific entry wins
- **GIVEN** `"com.acme.**" = "@"` and `"com.acme.Order" = "getId()"` in `[instrument.classes]`, and `com.acme.Order` with `@StackTraceId val code` and `fun getId()`
- **WHEN** an exception leaves a method of an `Order`
- **THEN** that frame shows the receiver id `getId=…`, and `Order`'s `@StackTraceId` is not used

#### Scenario: Entry of a superclass
- **GIVEN** `"com.thirdparty.Order" = "getOrderNumber()"` and a subclass `com.acme.RushOrder` that no entry matches
- **WHEN** an exception leaves a method of a `RushOrder`
- **THEN** that frame shows the receiver id `getOrderNumber=…`

### Requirement: Parameter ids
The system SHALL show the value of a method parameter after the method name when the parameter is
annotated with `@StackTraceParam` (`com.hafnium.stackaugmentor.StackTraceParam`), when its method or the
class declaring the method is annotated with `@StackTraceParams`
(`com.hafnium.stackaugmentor.StackTraceParams`, see "Parameter ids from method- and class-level
annotations"), or when an `[instrument.methods]` entry selects it (see "Parameter ids of configured
methods"). The annotations SHALL only be used in classes whose deciding `[instrument.classes]` entry is
`"@"`, and in methods matched by an `[instrument.methods]` entry with the value `"@"`. This SHALL apply to instance and static methods. A parameter selected more than once SHALL be shown
once, and parameter ids SHALL be listed in declaration order. `@StackTraceId` on a parameter SHALL NOT
select it: `@StackTraceId` marks receiver ids only.

#### Scenario: Annotated parameter
- **GIVEN** `fun objectMethod(@StackTraceParam orderId: Int)` in a class matched by an `"@"` entry
- **WHEN** `objectMethod(42)` throws
- **THEN** that frame shows the parameter id `orderId=42`

#### Scenario: Configured parameters by name and index
- **GIVEN** `"com.thirdparty.OrderService.process" = ["order", 1]` in `[instrument.methods]` for `process(order: Order, quantity: Int, note: String)`
- **WHEN** `process(Order(4711), 3, "rush")` throws
- **THEN** that frame shows `order=4711, quantity=3`

#### Scenario: Static method
- **GIVEN** a top-level (static) function `staticWithParam(@StackTraceParam code: Int)` in a class matched by an `"@"` entry
- **WHEN** `staticWithParam(5)` throws
- **THEN** that frame shows the parameter id `code=5` and no receiver id

#### Scenario: Selected by an annotation and by the configuration
- **GIVEN** `fun op(@StackTraceParam x: Int, y: Int)` in class `C` matched by an `"@"` entry, and `"C.op" = ["x", "y"]` in `[instrument.methods]`
- **WHEN** `op(1, 2)` throws
- **THEN** that frame shows `x=1, y=2`, each parameter once

#### Scenario: Annotations enabled by a method entry
- **GIVEN** `fun run(@StackTraceParam code: Int)` in `com.hafnium.it.outside.MethodAnnotationsOutside`, which no `[instrument.classes]` entry matches, and `"com.hafnium.it.outside.MethodAnnotationsOutside.run" = "@"` in `[instrument.methods]`
- **WHEN** `run(7)` throws
- **THEN** that frame shows `code=7` and no receiver id

### Requirement: Labels
The label of a receiver id SHALL be the real name of the field or method that supplies it, and the label
of a parameter id SHALL be the parameter name. `@StackTraceId(name = "…")` SHALL override the label of a
receiver id, and `@StackTraceParam(name = "…")` SHALL override the label of that parameter id, also when
the parameter is selected by `@StackTraceParams` or by the configuration. When a class has no parameter
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

#### Scenario: No parameter names
- **GIVEN** a Java class compiled without `-parameters` and `run(@StackTraceParam int value)`
- **WHEN** `run(5)` throws
- **THEN** the frame shows `arg0=5`

### Requirement: Id values as text
Every id SHALL be converted to text when it is captured. `null` SHALL be shown as `null`. Strings, numbers,
booleans, characters and enums SHALL be shown with `toString()`. An argument whose class has a receiver
id source SHALL be shown by that id. Arrays SHALL be shown by their elements. Other arguments SHALL be
shown with `toString()`. Line breaks SHALL be replaced by a single space, and text longer than
`[augment] maxIdLength` SHALL be cut to `maxIdLength - 1` characters followed by `…`. An id source that
throws SHALL be shown as `?`.

#### Scenario: Object argument with an id source
- **GIVEN** `fun ship(@StackTraceParam order: Order?)` and `Order` identified by `getOrderNumber()`
- **WHEN** `ship(Order(4711))` throws
- **THEN** the frame shows `order=4711`
- **AND** `ship(null)` shows `order=null`

#### Scenario: Long and multi-line ids
- **GIVEN** `maxIdLength = 20`
- **WHEN** a receiver id is 50 characters long, or contains `line1\nline2`
- **THEN** the frame shows the first 19 characters followed by `…`, or `line1 line2`

#### Scenario: Failing id source
- **GIVEN** `@StackTraceId fun id(): String` that throws
- **WHEN** an exception leaves a method of that class
- **THEN** the frame shows `id=?` and the original exception is unchanged otherwise

### Requirement: Parameter ids from method- and class-level annotations
`@StackTraceParams` (on methods and classes only, without attributes) on a method SHALL select all
parameters of that method. `@StackTraceParams` on a class SHALL select all parameters of every instance and
static method declared in that class. It SHALL NOT apply to methods of subclasses or nested classes, which
need their own annotation. Methods without parameters SHALL get no parameter ids. Constructors, synthetic,
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
An `[instrument.methods]` entry `"<class pattern>.<method pattern>" = <parameters>` SHALL select
parameters of every method whose class name matches the class pattern and whose name matches the method
pattern, including every overload, whatever the class's `[instrument.classes]` entry. The class pattern
SHALL use the `[instrument.classes]` globs (`*` within one package segment, `**` across segments, `?` one
character). In the method pattern, `*` SHALL match any sequence of characters and `?` one character. A key
without wildcards SHALL match exactly. `<parameters>` SHALL be an array of parameter names and 0-based
indexes, the string `"*"` for all parameters, or the string `"@"` for the parameters selected by the method's
annotations: `@StackTraceParam` on its parameters, and `@StackTraceParams` on the method or on the class that
declares it. Names and indexes that a matched method does not have
SHALL be skipped. When several entries match one method, the parameters they select SHALL be combined, up to
a `"-"` entry as described under "Ignoring classes and methods".

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
- **WHEN** `process(Order(4711), 3, "rush")` throws
- **THEN** that frame shows `order=4711, note=rush`

#### Scenario: Exact explicit class entry with annotated parameters
- **GIVEN** `"com.acme.Order" = "getId()"` in `[instrument.classes]` and `"com.acme.Order.*" = "@"` in `[instrument.methods]`, and `fun ship(@StackTraceParam(name = "to") address: String)` in `Order`
- **WHEN** `ship("Main St")` throws
- **THEN** that frame shows the receiver id `getId=…` and the parameter id `to=Main St`

### Requirement: Ignoring classes and methods
The value `"-"` SHALL ignore what its entry matches, in both tables. A class whose deciding
`[instrument.classes]` entry is `"-"` SHALL get no receiver id and SHALL NOT use its annotations; the entry
decides with the same most-specific rule as other entries and applies to subclasses in the same way. When
several `[instrument.methods]` entries match a method, they SHALL be ordered from the most specific on (the
entry without wildcards first, then the patterns with the most characters other than `*` and `?`, ties
broken by key) and combined up to the first `"-"` entry; that entry and the less specific ones SHALL select
nothing. A `"-"` deciding class entry `"<key>" = "-"` SHALL count as the `[instrument.methods]` pattern
`"<key>.*" = "-"`, so only a more specific method entry still selects parameters of the ignored class. A
configuration whose entries are all `"-"` SHALL count as having no entries.

#### Scenario: Ignored package under an "@" pattern
- **GIVEN** `"com.acme.**" = "@"` and `"com.acme.generated.**" = "-"` in `[instrument.classes]`, and an annotated `com.acme.generated.Mapper`
- **WHEN** a method of `Mapper` throws
- **THEN** its frame is unchanged

#### Scenario: Ignored methods under a wildcard entry
- **GIVEN** `"com.thirdparty.Inventory*.*" = "*"`, `"com.thirdparty.InventoryAudit.*" = "-"` and `"com.thirdparty.InventoryAudit.log" = ["reason"]` in `[instrument.methods]`
- **WHEN** `InventoryAudit.purge("x-1")` throws, and separately `InventoryAudit.log("disk full", 2)` throws
- **THEN** the first frame is unchanged, and the second frame shows `reason=disk full`

#### Scenario: Ignored class with a more specific method entry
- **GIVEN** `"com.acme.generated.**" = "-"` in `[instrument.classes]`, and `"com.acme.**.*" = "*"` and `"com.acme.generated.Mapper.map" = [0]` in `[instrument.methods]`
- **WHEN** `Mapper.map(dto)` throws, and separately `Mapper.copy(dto)` throws
- **THEN** the first frame shows the first parameter and no receiver id, and the second frame is unchanged

### Requirement: Classes whose annotations are used
The system SHALL use `@StackTraceId` annotations (on fields and methods), `@StackTraceParam` annotations
(on parameters) and `@StackTraceParams` annotations (on methods and classes) only in classes whose
deciding `[instrument.classes]` entry is `"@"` (see "Receiver id from external configuration"). In addition,
`@StackTraceParam` and `@StackTraceParams` SHALL be used for methods matched by an `[instrument.methods]`
entry with the value `"@"` (see "Parameter ids of configured methods"). Without an `"@"` entry in either
table, no annotations SHALL be used. A class whose deciding entry names a field or method SHALL get its
receiver id from that member: its `@StackTraceId` SHALL NOT be used, and its parameter annotations only for
methods matched by an `"@"` method entry. Classes without an annotation or
configuration entry SHALL NOT get ids, even when an `"@"` entry matches them.

#### Scenario: Annotated class outside the "@" entries
- **GIVEN** `"com.hafnium.it.fixtures.**" = "@"` as the only `[instrument.classes]` entry
- **WHEN** an exception leaves a method of a class in `com.hafnium.it.outside` with an annotated parameter, and no annotated superclass
- **THEN** the frame is unchanged

#### Scenario: Subclass of a class with an "@" entry
- **GIVEN** `"com.hafnium.it.fixtures.**" = "@"`, and `com.hafnium.it.outside.DerivedOutside` extending `com.hafnium.it.fixtures.Base`, which declares `@StackTraceId val baseId = "b1"`
- **WHEN** an exception leaves a method of `DerivedOutside`
- **THEN** that frame shows the receiver id `baseId=b1`, because the entry of `Base` applies to its subclasses

#### Scenario: Matched class without annotations
- **GIVEN** a class matched by an `"@"` entry that has no annotations, but overrides `toString()`
- **WHEN** an exception leaves one of its methods
- **THEN** the frame is unchanged

#### Scenario: Class-level parameter annotation outside the "@" entries
- **GIVEN** `"com.hafnium.it.fixtures.**" = "@"` and a class in `com.hafnium.it.outside` annotated with `@StackTraceParams`
- **WHEN** one of its methods throws
- **THEN** the frame shows no parameter ids

#### Scenario: Class-level parameter annotation enabled by method entries
- **GIVEN** `@StackTraceParams class ClassParamsViaMethods` in `com.hafnium.it.outside`, and `"com.hafnium.it.outside.ClassParamsViaMethods.*" = "@"` in `[instrument.methods]`
- **WHEN** `run(1, "x")` throws
- **THEN** that frame shows all its parameter ids

#### Scenario: No "@" entry
- **GIVEN** a configuration with only `"com.thirdparty.Order" = "getOrderNumber()"` in `[instrument.classes]`
- **WHEN** an exception leaves a method of an annotated class in any other package
- **THEN** the frame is unchanged
