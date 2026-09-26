# Spec Delta

## ADDED Requirements

### Requirement: Maximum number of parameter ids
A frame SHALL show at most `[augment] maxParams` parameter ids: the first ones in declaration order. When
more parameter ids were selected, `…` SHALL be added as a last item of the list, joined with the
`paramsFormat` separator and inside its prefix and suffix. The limit SHALL be applied when the frame is
rendered, so changing it SHALL NOT require re-instrumenting classes. The receiver id SHALL NOT be affected.

#### Scenario: More parameters than the maximum
- **GIVEN** `maxParams = 2` and the default `paramsFormat`
- **WHEN** a frame has the parameter ids `a=1`, `b=2` and `c=3`
- **THEN** the method part reads `process{a=1, b=2, …}`

#### Scenario: Exactly the maximum
- **GIVEN** `maxParams = 2`
- **WHEN** a frame has the parameter ids `a=1` and `b=2`
- **THEN** the method part reads `process{a=1, b=2}`, without `…`

#### Scenario: Custom parameter list
- **GIVEN** `maxParams = 1` and `paramsFormat = "($name: $id; ...)"`
- **WHEN** a frame has the parameter ids `orderId=42` and `customer=7`
- **THEN** the method part reads `process(orderId: 42; …)`
