# Spec Delta

## MODIFIED Requirements

### Requirement: Modules
The project SHALL be split into these modules:
- `stack-augmentor-api`: the `@StackTraceId`, `@StackTraceParam` and `@StackTraceParams` annotations;
- `stack-augmentor-instrument-bridge`: `Dispatch`, the class the advice calls (Java, no dependencies);
- `stack-augmentor-runtime`: configuration, id lookup, frame formatting and `ThrowHandler`;
- `stack-augmentor-instrument`: type and method matching and the exit advice (ByteBuddy);
- `stack-augmentor-agent`: the Java agent;
- `stack-augmentor-build-plugin`: the ByteBuddy build plugin.

The agent and build-time instrumentation SHALL share the runtime and instrument modules.

#### Scenario: Shared behaviour
- **GIVEN** the same annotated class and the same `[augment]` configuration
- **WHEN** it is instrumented once by the agent and once at build time
- **THEN** its frames look the same
