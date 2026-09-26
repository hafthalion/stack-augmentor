# Spec Delta

## MODIFIED Requirements

### Requirement: Matching by name
The agent, build plugin and runtime SHALL recognise `@StackTraceId`, `@StackTraceParam` and
`@StackTraceParams` by their class names (`com.hafnium.stackaugmentor.StackTraceId`,
`com.hafnium.stackaugmentor.StackTraceParam`, `com.hafnium.stackaugmentor.StackTraceParams`), without
depending on the API module, and read their `name` reflectively where they have one.

#### Scenario: API loaded by an application class loader
- **GIVEN** the application loads its own copy of `stack-augmentor-api`
- **WHEN** its annotated classes are instrumented
- **THEN** the annotations are recognised
