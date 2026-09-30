# Frame Format Specification

## Purpose
Defines how the ids are laid out in a stack trace frame, through the `frameFormat`, `receiverFormat` and
`paramsFormat` templates of the `[augment]` configuration section.

## Requirements

### Requirement: Default layout
By default, the receiver id SHALL follow the class name and the parameter ids SHALL follow the method
name, each in braces. The defaults SHALL be `frameFormat = "$class$receiver.$method$params"`,
`receiverFormat = "{$name=$id}"` and `paramsFormat = "{$name=$id, ...}"`.

#### Scenario: Receiver and parameter ids
- **GIVEN** the default formats
- **WHEN** `ObjectClass.objectMethod` with receiver id `objectId=object-1` and parameter id `orderId=42` throws
- **THEN** the frame is printed as `com.hafnium.ObjectClass{objectId=object-1}.objectMethod{orderId=42}(ObjectClass.kt:13)`

#### Scenario: Missing groups
- **GIVEN** the default formats
- **WHEN** a frame has parameter ids but no receiver id
- **THEN** the receiver group is empty, e.g. `com.thirdparty.OrderService.process{order=Order#4711, quantity=3}(OrderService.kt:14)`

### Requirement: Frame template
`frameFormat` SHALL support the placeholders `$class`, `$simpleClass`, `$method`, `$receiver` and
`$params`. A placeholder name SHALL end at the first character that is not a letter or digit; the braced form,
e.g. `${method}`, SHALL be the same placeholder, with the name ending at the `}`. All other text, including a
brace without a `$` before it, SHALL be literal, and `$$` SHALL be a literal `$`. It SHALL contain `.$method` (or `.${method}`) exactly once,
because the JDK always prints `<class>.<method>(<file>:<line>)`: the part before that `.` becomes the
frame's class, the rest its method.

#### Scenario: Ids after the method
- **GIVEN** `frameFormat = "$class.$method$receiver$params"`
- **WHEN** a frame with both ids is rendered
- **THEN** it reads `com.hafnium.ObjectClass.process{objectId=123}{orderId=42}(ObjectClass.java:13)`

#### Scenario: Braced placeholder followed by letters
- **GIVEN** `frameFormat = "$class$receiver.${method}X$params"`
- **WHEN** a frame without ids is rendered
- **THEN** it reads `com.hafnium.ObjectClass.processX(ObjectClass.java:13)`, while `$methodX` would be rejected as an unknown placeholder

#### Scenario: Missing .$method
- **GIVEN** `frameFormat = "$class#$method"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message that `frameFormat` must contain `.$method` exactly once

### Requirement: Receiver and parameter templates
`receiverFormat` and `paramsFormat` SHALL support the placeholders `$name` and `$id`. All other text,
braces included, SHALL be literal, and `$$` SHALL be a literal `$`; as in `frameFormat`, `${name}` and `${id}` are
the braced forms. In `paramsFormat`, `...` SHALL mark
repetition: the text before the first placeholder and after `...` wraps the list, the text from the
first to the last placeholder is repeated for each parameter, and the text between the last placeholder
and `...` separates the items. Without `...`, the whole template SHALL be repeated and joined with `,`.
A template that renders no ids SHALL render as empty text.

#### Scenario: Custom parameter list
- **GIVEN** `paramsFormat = "[$name: $id; ...]"`
- **WHEN** a frame has the parameter ids `orderId=42` and `customer=7`
- **THEN** the method part reads `process[orderId: 42; customer: 7]`

#### Scenario: Value only
- **GIVEN** `receiverFormat = "<$id>"`
- **WHEN** a frame with the receiver id `objectId=123` is rendered
- **THEN** the class part reads `ObjectClass<123>`

### Requirement: Template validation
The system SHALL reject, when the configuration is loaded, a template with an unknown placeholder (for
example `$klass`, `$line`, `$idx`, `${nam}` or a lone `$`), an unclosed `${`, a
`paramsFormat` without `$name` or `$id`, a `paramsFormat` with placeholders after `...`, and any template
containing `(` or `)`: IDEs find a frame's file and line by the parenthesised `(File.java:12)` that the JDK
appends, which parentheses in the frame's class or method part would confuse.

#### Scenario: Parentheses in a template
- **GIVEN** `paramsFormat = "($name: $id; ...)"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message that `paramsFormat` must not contain `(`

#### Scenario: Unknown placeholder
- **GIVEN** `receiverFormat = "{$nam}"`
- **WHEN** the configuration is loaded
- **THEN** it is rejected with a message naming the unknown placeholder `$nam`
