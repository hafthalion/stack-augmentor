# Causes, root cause first

The JDK prints an exception with its causes outermost first: each cause follows as `Caused by:`, and leaves out the frames it shares with the exception it caused as `... N more`. With Stack Augmentor these shared frames show the same ids in every exception of the chain, so they still collapse (see [How it works](how-it-works.md)).

Often the root cause is what matters, and it comes last. Two ways to print it first:

## Logback and Log4j 2: `%rEx`

Both loggers print the root cause first with the `%rEx` (or `%rootException`) conversion word in the pattern. Each exception that wrapped it follows as `Wrapped by:`:

```xml
<!-- Logback -->
<pattern>%d %-5level %logger - %msg%n%rEx</pattern>
```

```xml
<!-- Log4j 2 -->
<PatternLayout pattern="%d %-5level %logger - %msg%n%rEx"/>
```

Both leave out the frames each exception shares with its wrapper: Logback as `... N common frames omitted`, Log4j 2 as `... N more`; Log4j 2's `%rEx{filters(...)}` can also leave out the frames of given packages. Both read the stack traces with `getStackTrace()`, so they show the ids.

`java.util.logging` has no such option; its `SimpleFormatter` prints what `printStackTrace` prints. Use a `Formatter` of your own with `ExceptionFormat.rootCauseFirst` (below).

## `ExceptionFormat.rootCauseFirst` in the API

`com.hafnium.stackaugmentor.ExceptionFormat.rootCauseFirst(throwable)` in `stack-augmentor-api` returns the same order as Logback's `%rEx` as a `String`, for code that formats exceptions itself; `rootCauseFirst(throwable, out)` appends it to an `Appendable`, e.g. a `Writer`, `PrintStream` or `StringBuilder`:

```
java.io.IOException: disk full
	at com.example.Store{name=orders}.write{orderId=42}(Store.java:40)
	... 3 common frames omitted
Wrapped by: java.lang.IllegalStateException: order 42 not saved
	at com.example.Orders.save(Orders.java:12)
	at com.example.Main.main(Main.java:5)
```

Read from the top, the frames follow the stack upwards, each printed once. Suppressed exceptions follow the frames of the exception they were suppressed in, indented and headed `Suppressed:`, with their own causes again root first. A circular cause chain stops at `[CIRCULAR REFERENCE: ...]`, as in the JDK.

`./gradlew :examples:java-agent:run` prints a cause chain both ways.

For `java.util.logging`, e.g.:

```java
public class RootCauseFirstFormatter extends SimpleFormatter {
    @Override
    public String format(LogRecord record) {
        Throwable thrown = record.getThrown();
        if (thrown == null) {
            return super.format(record);
        }
        record.setThrown(null);
        try {
            return super.format(record) + ExceptionFormat.rootCauseFirst(thrown);
        } finally {
            record.setThrown(thrown);
        }
    }
}
```
