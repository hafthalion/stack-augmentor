# Native JVM capture: prototypes

Throwaway prototypes behind `/mnt/project-files/reports/native-jvm-exploration-2026-10-02.md` (project files).
Not part of the build.

- `agent.c`: JVMTI agent. Default: Exception event, reads `this` and the first parameter of `Proto$Svc.call` /
  `Proto$Child.<init>` frames and hands them to `Proto$Hook`. Options: `capsonly` (only acquire
  `can_access_local_variables`, no events), `L` (no local reads), `J` (no Java callback), `C` (no locals capability).
  `JNI_OnLoad` shows that the capability cannot be added in the live phase; `Hook.count()` is a `GetStackTrace` prefilter.
- `Proto.java`: benchmark and correctness check. Modes: `base`, `agent` (with `-javaagent`), `jvmti`, `live`
  (`LiveStackFrame` walk in the exception constructor; `-Dplain=true` plain walk only, `-Dnative=true` JNI prefilter only),
  `nothrow`.
- `Work.java`: normal-path workload to compare with and without `capsonly`.

```bash
J=$JAVA_HOME
gcc -O2 -shared -fPIC -I$J/include -I$J/include/linux agent.c -o libproto.so
javac -parameters Proto.java Work.java
O="--add-opens java.base/java.lang=ALL-UNNAMED"
java Proto base 10 100000
java -javaagent:stack-augmentor-agent.jar=config=sa.toml Proto agent 10 100000
java $O Proto live 10 100000                                    # receivers lost in JIT-compiled frames
java $O -agentpath:./libproto.so=capsonly Proto live 10 100000  # receivers kept
java -agentpath:./libproto.so Proto jvmti 10 2000                # slow: deopt + VM operation per local
```
