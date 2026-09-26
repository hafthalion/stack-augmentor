# Spec Delta

## MODIFIED Requirements

### Requirement: Packages whose annotations are used
The system SHALL use `@StackTraceId` annotations (on fields and methods), `@StackTraceParam` annotations
(on parameters) and `@StackTraceParams` annotations (on methods and classes) only in classes matched by
`[instrument] annotatedClasses`. The
patterns SHALL be globs over class names, where `*` matches within one package segment, `**` across
segments and `?` one character. An empty or missing list SHALL match all packages. Classes without an
annotation or configuration entry SHALL NOT get ids, even when they are matched.

#### Scenario: Annotated class outside the listed packages
- **GIVEN** `annotatedClasses = ["com.hafnium.it.fixtures.**"]`
- **WHEN** an exception leaves a method of an annotated class in `com.hafnium.it.outside`
- **THEN** the frame is unchanged

#### Scenario: Matched class without annotations
- **GIVEN** a class in a matched package that has neither an annotation nor a configuration entry, but overrides `toString()`
- **WHEN** an exception leaves one of its methods
- **THEN** the frame is unchanged

#### Scenario: Class-level parameter annotation outside the listed packages
- **GIVEN** `annotatedClasses = ["com.hafnium.it.fixtures.**"]` and a class in `com.hafnium.it.outside` annotated with `@StackTraceParams`
- **WHEN** one of its methods throws
- **THEN** the frame shows no parameter ids

### Requirement: Parameter ids
The system SHALL show the value of a method parameter after the method name when the parameter is
annotated with `@StackTraceParam` (`com.hafnium.stackaugmentor.StackTraceParam`), when its method or the
class declaring the method is annotated with `@StackTraceParams`
(`com.hafnium.stackaugmentor.StackTraceParams`, see "Parameter ids from method- and class-level
annotations"), or when an `[instrument.methodParams]` entry selects it (see "Parameter ids of
configured methods"). This SHALL apply to instance and static methods. A parameter selected more than once
SHALL be shown once, and parameter ids SHALL be listed in declaration order. `@StackTraceId` on a
parameter SHALL NOT select it: `@StackTraceId` marks receiver ids only.

#### Scenario: Annotated parameter
- **GIVEN** `fun objectMethod(@StackTraceParam orderId: Int)`
- **WHEN** `objectMethod(42)` throws
- **THEN** that frame shows the parameter id `orderId=42`

#### Scenario: Configured parameters by name and index
- **GIVEN** `"com.thirdparty.OrderService.process" = ["order", 1]` for `process(order: Order, quantity: Int, note: String)`
- **WHEN** `process(Order(4711), 3, "rush")` throws
- **THEN** that frame shows `order=4711, quantity=3`

#### Scenario: Static method
- **GIVEN** a top-level (static) function `staticWithParam(@StackTraceParam code: Int)`
- **WHEN** `staticWithParam(5)` throws
- **THEN** that frame shows the parameter id `code=5` and no receiver id

#### Scenario: Selected by an annotation and by the configuration
- **GIVEN** `fun op(@StackTraceParam x: Int, y: Int)` in class `C`, and `"C.op" = ["x", "y"]`
- **WHEN** `op(1, 2)` throws
- **THEN** that frame shows `x=1, y=2`, each parameter once

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

## ADDED Requirements

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
An `[instrument.methodParams]` entry `"<class pattern>.<method pattern>" = <parameters>` SHALL select
parameters of every method whose class name matches the class pattern and whose name matches the method
pattern, including every overload. The class pattern SHALL use the `annotatedClasses` globs (`*` within
one package segment, `**` across segments, `?` one character). In the method pattern, `*` SHALL match any
sequence of characters and `?` one character. A key without wildcards SHALL match exactly as before.
`<parameters>` SHALL be an array of parameter names and 0-based indexes, or the string `"*"` for all
parameters. Names and indexes that a matched method does not have SHALL be skipped. When several entries
match one method, the parameters they select SHALL be combined.

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
