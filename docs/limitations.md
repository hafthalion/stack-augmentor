# Limitations

[Back to the README](../README.md)

- **Only the frames the exception passed through are augmented.** If an exception is caught and logged in method `m`, then `m` and the frames below it are not augmented. Not so when the agent reads frames from the [live stack](live-stack.md).
- **Constructors show parameter values only, and not for exceptions thrown inside the constructor that `super(...)` or `this(...)` calls.** The JVM's verifier allows no exception handler around that call, so the calling constructor's frame stays unchanged; exceptions while computing its arguments are covered. The [live stack](live-stack.md) mode covers it.
- **An exception thrown more than once keeps the values of its first throw.** Its stack trace is recorded once, when it is created.
- **Each instrumented frame an exception leaves costs a few microseconds.** Normal returns cost nothing.
  - The handler walks the top of the stack to find the caller: about a microsecond, whatever the depth.
  - By default (`inPlaceModification = true`) it writes the frame into the exception's own trace and copies nothing. That needs `java.lang` open: the agent opens it; with build-time instrumentation, start the application with `--add-opens java.base/java.lang=ALL-UNNAMED` (or `Add-Opens: java.base/java.lang` in an executable jar's manifest).
  - Where `java.lang` is not open, or with `inPlaceModification = false`, it copies the stack trace instead (`getStackTrace`, `setStackTrace`), so the cost grows with the trace length, up to the JVM's 1024 frames (`-XX:MaxJavaStackTraceDepth`). Open `java.lang` when deeply recursive methods are instrumented at build time.
  - An exception with a cause or suppressed exceptions also gets their traces read for each such frame, to augment the frames they share the same way (copying: one more copy of each of their traces).
  - Measured with JDK 25 on a Linux container, agent on vs off: an exception passing 5 instrumented frames in a 100-frame stack took 33 µs instead of 21 µs; one passing a recursion of 1000 instrumented frames took 1.8 ms in place and 3.8 ms copying, instead of 0.16 ms.
- **A cause created in an earlier call with the very same frames below** (same methods and lines, e.g. a stored exception from the previous loop iteration) is augmented with the values of the call that the wrapping exception leaves in those shared frames.
- **A cause or suppressed exception attached after the wrapper left some frames** (`initCause` or `addSuppressed` called later, e.g. in a caller) keeps those frames as they are. Only the frames the wrapper leaves after that get copied into it.
- **Parameter values are read when the exception leaves the method.** A parameter that was reassigned shows its new value.
- **Class and method names in the `StackTraceElement`s change.** Tools that parse stack traces (IDE links, error grouping) may not recognise the changed frames.
- **With the agent, the JVM prints `Sharing is only supported for boot loader classes because bootstrap classpath has been appended`** at startup. This is expected, because the agent extends the bootstrap class path; add `-Xshare:off` to silence it.
