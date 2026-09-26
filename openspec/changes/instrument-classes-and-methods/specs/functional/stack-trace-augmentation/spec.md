# Spec Delta

## MODIFIED Requirements

### Requirement: Instrumented methods
The system SHALL add exit advice to methods that are not abstract, native, bridge or synthetic, in these
cases:
- instance methods of a class that has a receiver id source (a field or method named by its deciding
  `[instrument.classes]` entry, or an annotated member when that entry is `"@"`);
- instance or static methods that have at least one parameter id.

Constructors SHALL NOT be instrumented.

#### Scenario: Static method without parameter ids
- **GIVEN** a static function without id parameters
- **WHEN** it throws
- **THEN** its frame is unchanged

#### Scenario: Class with parameter ids only
- **GIVEN** a third-party class `OrderService` that is only listed in `[instrument.methods]`
- **WHEN** `process` throws
- **THEN** the frame shows the parameter ids after the method name and no receiver id
