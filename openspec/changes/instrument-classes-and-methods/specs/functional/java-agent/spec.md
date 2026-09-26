# Spec Delta

## MODIFIED Requirements

### Requirement: Third-party classes
The agent SHALL instrument classes whose deciding `[instrument.classes]` entry names a field or method
(including subclasses of the matched classes), and classes with methods selected by `[instrument.methods]`
entries, in any package and without annotations. Both tables SHALL accept class patterns with wildcards.
Classes in the ignored packages SHALL stay uninstrumented, even when a wildcard entry matches them.

#### Scenario: Configured library class
- **GIVEN** `"com.thirdparty.Customer" = "customerId"` in `[instrument.classes]`
- **WHEN** `Customer("c-9").rename()` throws
- **THEN** the frame reads `com.thirdparty.Customer{customerId=c-9}.rename(…)`

#### Scenario: Library classes selected by a wildcard
- **GIVEN** `"com.thirdparty.*Service.*" = "*"` in `[instrument.methods]`
- **WHEN** `InventoryService().reserve("x-1", 2)` throws
- **THEN** the frame reads `com.thirdparty.InventoryService.reserve{sku=x-1, count=2}(…)`

#### Scenario: Library classes with a receiver id selected by a wildcard
- **GIVEN** `"com.thirdparty.*Account" = "number"` in `[instrument.classes]`
- **WHEN** `SavingsAccount("S-1").withdraw()` throws
- **THEN** the frame reads `com.thirdparty.SavingsAccount{number=S-1}.withdraw(…)`

#### Scenario: Wildcard matching JDK classes
- **GIVEN** `"java.**.*" = "*"` in `[instrument.methods]` and `"java.**" = "@"` in `[instrument.classes]`
- **WHEN** an exception is thrown inside a JDK method
- **THEN** the JDK frames are unchanged
