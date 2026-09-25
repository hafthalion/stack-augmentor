# Id Sources Specification

## Purpose
Defines where the ids shown in stack trace frames come from: the id of the object a frame runs on
(receiver id) and the ids of selected method arguments (parameter ids), how they are labelled, and how
their values are turned into text.

## Requirements

### Requirement: Receiver id from an annotated member
The system SHALL use a non-static field or a non-static, no-argument method annotated with
`@StackTraceId` (`com.hafnium.stackaugmentor.StackTraceId`) as the receiver id of the objects of that
class, including subclasses. In Kotlin, a primary-constructor property whose constructor parameter is
annotated SHALL be recognised as an annotated field. The annotation SHALL be matched by its class name,
so that a copy of the API loaded by another class loader is also recognised.

#### Scenario: Annotated field
- **GIVEN** a class `ObjectClass` with `@StackTraceId val objectId = "object-1"`
- **WHEN** an exception leaves an instance method of `ObjectClass`
- **THEN** that frame shows the receiver id `objectId=object-1`

#### Scenario: Annotated method
- **GIVEN** a class with `@StackTraceId fun key() = "k-1"`
- **WHEN** an exception leaves one of its instance methods
- **THEN** that frame shows the receiver id `key=k-1`

#### Scenario: Inherited annotation
- **GIVEN** a class `Derived` extending a class that declares `@StackTraceId val baseId = "b1"`
- **WHEN** an exception leaves a method of `Derived`
- **THEN** that frame shows the receiver id `baseId=b1`

#### Scenario: Kotlin primary-constructor property
- **GIVEN** `class Node(@StackTraceId val name: String)`
- **WHEN** an exception leaves a method of `Node("c")`
- **THEN** that frame shows the receiver id `name=c`

### Requirement: Receiver id from external configuration
The system SHALL use an entry of the `[instrument.classIds]` configuration table, naming a field or a
no-argument method (written with `()`), as the receiver id of that class and its subclasses. A
configured id source SHALL take precedence over annotations. It SHALL be looked up in the class and its
superclasses, including private members.

#### Scenario: Configured method of a third-party class
- **GIVEN** `"com.thirdparty.Order" = "getOrderNumber()"` in `[instrument.classIds]`
- **WHEN** an exception leaves a method of an `Order` with order number 4711
- **THEN** that frame shows the receiver id `getOrderNumber=4711`

#### Scenario: Configured member does not exist
- **GIVEN** `"com.thirdparty.Customer" = "nope"` in `[instrument.classIds]` and no field `nope`
- **WHEN** the id source of `Customer` is first needed
- **THEN** a warning `[instrument.classIds] "com.thirdparty.Customer": no field nope found` is printed
- **AND** frames of `Customer` show no receiver id

### Requirement: Packages whose annotations are used
The system SHALL use `@StackTraceId` annotations (on members and on parameters) only in classes matched
by `[instrument] annotatedClasses`. The patterns SHALL be globs over class names, where `*` matches
within one package segment, `**` across segments and `?` one character. An empty or missing list SHALL
match all packages. Classes without an annotation or configuration entry SHALL NOT get ids, even when
they are matched.

#### Scenario: Annotated class outside the listed packages
- **GIVEN** `annotatedClasses = ["com.hafnium.it.fixtures.**"]`
- **WHEN** an exception leaves a method of an annotated class in `com.hafnium.it.outside`
- **THEN** the frame is unchanged

#### Scenario: Matched class without annotations
- **GIVEN** a class in a matched package that has neither an annotation nor a configuration entry, but overrides `toString()`
- **WHEN** an exception leaves one of its methods
- **THEN** the frame is unchanged

### Requirement: Parameter ids
The system SHALL show the value of a method parameter after the method name when the parameter is
annotated with `@StackTraceId`, or is listed in the `[instrument.methodParams]` table for
`"<class>.<method>"` by name or by 0-based index. This SHALL apply to instance and static methods and to
every overload of the configured method name. Parameter ids SHALL be listed in declaration order.

#### Scenario: Annotated parameter
- **GIVEN** `fun objectMethod(@StackTraceId orderId: Int)`
- **WHEN** `objectMethod(42)` throws
- **THEN** that frame shows the parameter id `orderId=42`

#### Scenario: Configured parameters by name and index
- **GIVEN** `"com.thirdparty.OrderService.process" = ["order", 1]` for `process(order: Order, quantity: Int, note: String)`
- **WHEN** `process(Order(4711), 3, "rush")` throws
- **THEN** that frame shows `order=4711, quantity=3`

#### Scenario: Static method
- **GIVEN** a top-level (static) function `staticWithParam(@StackTraceId code: Int)`
- **WHEN** `staticWithParam(5)` throws
- **THEN** that frame shows the parameter id `code=5` and no receiver id

### Requirement: Labels
The label of a receiver id SHALL be the real name of the field or method that supplies it, and the label
of a parameter id SHALL be the parameter name. `@StackTraceId(name = "…")` SHALL override the label.
When a class has no parameter names (compiled without `-parameters`), the label SHALL be `arg<N>`.

#### Scenario: Name override
- **GIVEN** `@StackTraceId(name = "user") val login = "bob"`
- **WHEN** an exception leaves a method of that class
- **THEN** the frame shows `user=bob`

#### Scenario: No parameter names
- **GIVEN** a Java class compiled without `-parameters` and `run(@StackTraceId int value)`
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
- **GIVEN** `fun ship(@StackTraceId order: Order?)` and `Order` identified by `getOrderNumber()`
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
