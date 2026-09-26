# Spec Delta

## MODIFIED Requirements

### Requirement: Third-party classes
The agent SHALL instrument classes named in `[instrument.classIds]` (including their subclasses) and
classes with methods selected by `[instrument.methodParams]` entries, including entries with wildcards, in
any package and without annotations. Classes in the ignored packages SHALL stay uninstrumented, even when a
wildcard entry matches them.

#### Scenario: Configured library class
- **GIVEN** `"com.thirdparty.Customer" = "customerId"` in `[instrument.classIds]`
- **WHEN** `Customer("c-9").rename()` throws
- **THEN** the frame reads `com.thirdparty.Customer{customerId=c-9}.rename(…)`

#### Scenario: Library classes selected by a wildcard
- **GIVEN** `"com.thirdparty.*Service.*" = "*"` in `[instrument.methodParams]`
- **WHEN** `InventoryService().reserve("x-1", 2)` throws
- **THEN** the frame reads `com.thirdparty.InventoryService.reserve{sku=x-1, count=2}(…)`

#### Scenario: Wildcard matching JDK classes
- **GIVEN** `"java.**.*" = "*"` in `[instrument.methodParams]`
- **WHEN** an exception is thrown inside a JDK method
- **THEN** the JDK frames are unchanged
