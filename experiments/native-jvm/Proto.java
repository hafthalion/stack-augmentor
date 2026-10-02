import java.lang.reflect.Method;
import java.util.*;

public class Proto {
    static String mode;

    static final class Svc {
        final String id; Svc next;
        Svc(String id) { this.id = id; }
        int call(int n, long tag) {
            if (n == 0) { if (mode.equals("nothrow")) return 0; throw mode.equals("live") ? new LiveEx() : new IllegalStateException("boom"); }
            return next.call(n - 1, tag) + 1; // 'this' and 'n' are dead after this call
        }
        public String toString() { return id; }
    }

    static class Base { Base(String s) { if (s.isEmpty()) throw mode.equals("live") ? new LiveEx() : new IllegalStateException("empty"); } }
    static final class Child extends Base {
        final String id;
        Child(String id, int k) { super(id.substring(k)); this.id = id; }
    }

    // ---- Hook used by the JVMTI agent ----
    public static final class Hook {
        static final Object[] self = new Object[512];
        static final Object[] param = new Object[512];
        static int frames;
        static native int count();
        public static void frame(Throwable t, int depth, Object s, int n, Object p) {
            self[depth] = s; param[depth] = p != null ? p : n; frames++;
        }
    }

    // ---- LiveStackFrame walk, emulating a hook in Throwable.fillInStackTrace ----
    static final StackWalker LIVE;
    static final Method GET_LOCALS;
    static {
        StackWalker w = null; Method m = null;
        try {
            Class<?> lsf = Class.forName("java.lang.LiveStackFrame");
            Method gw = lsf.getDeclaredMethod("getStackWalker", Set.class);
            gw.setAccessible(true);
            w = (StackWalker) gw.invoke(null, EnumSet.noneOf(StackWalker.Option.class));
            m = lsf.getDeclaredMethod("getLocals");
            m.setAccessible(true);
        } catch (Throwable e) { /* not opened */ }
        LIVE = w; GET_LOCALS = m;
    }
    static final boolean NATIVE = Boolean.getBoolean("native");
    static final boolean PLAIN = Boolean.getBoolean("plain");
    static final StackWalker PLAIN_WALKER = StackWalker.getInstance();
    static final class LiveEx extends RuntimeException {
        final Object[] self = new Object[512], param = new Object[512];
        LiveEx() {
            super("boom");
            if (NATIVE) { if (Hook.count() < 0) throw new Error(); return; }
            if (PLAIN) { PLAIN_WALKER.forEach(f -> { if (f.getClassName() == null) throw new Error(); }); return; }
            int[] d = {0};
            LIVE.forEach(f -> {
                String c = f.getClassName();
                if (c.equals(LiveEx.class.getName()) ) return; // the constructor frame, skipped like fillInStackTrace does
                int depth = d[0]++;
                if (c.equals(Svc.class.getName()) || c.equals(Child.class.getName())) {
                    try {
                        Object[] locals = (Object[]) GET_LOCALS.invoke(f);
                        self[depth] = locals.length > 0 ? locals[0] : null;
                        param[depth] = locals.length > 1 ? locals[1] : null;
                    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                }
            });
        }
    }

    public static void main(String[] args) {
        mode = args[0];
        if (System.getProperty("loadlib") != null) System.load(System.getProperty("loadlib"));
        int depth = Integer.parseInt(args[1]);
        int iterations = Integer.parseInt(args[2]);
        Svc[] svcs = new Svc[depth + 1];
        for (int i = 0; i <= depth; i++) svcs[i] = new Svc("svc-" + i);
        for (int i = 0; i < depth; i++) svcs[i].next = svcs[i + 1];

        long sink = 0;
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                try { sink += svcs[0].call(depth, i); } catch (RuntimeException e) { sink += e.hashCode() & 1; }
            }
            long t1 = System.nanoTime();
            System.out.printf("%s depth=%d round %d: %.2f us per throw%n", mode, depth, round, (t1 - t0) / 1000.0 / iterations);
        }
        // Correctness after warm-up (JIT-compiled frames)
        try { svcs[0].call(depth, 7); } catch (RuntimeException e) { report(e, depth); }
        try { new Child("abc", 3); } catch (RuntimeException e) { report(e, 2); }
        System.out.println("sink " + sink);
    }

    static void report(RuntimeException e, int n) {
        Object[] self = e instanceof LiveEx l ? l.self : Hook.self;
        Object[] param = e instanceof LiveEx l ? l.param : Hook.param;
        StackTraceElement[] trace = e.getStackTrace();
        int ok = 0, nulls = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(trace.length, n + 1); i++) {
            Object s = self[i];
            if (s == null) nulls++; else ok++;
            if (i < 4 || i > n - 2) sb.append(String.format("    [%d] %s.%s self=%s param=%s%n", i, trace[i].getClassName(), trace[i].getMethodName(), s, param[i]));
        }
        System.out.printf("  %s: %d frames with receiver, %d without%n%s", e.getClass().getSimpleName(), ok, nulls, sb);
        Arrays.fill(self, null); Arrays.fill(param, null);
    }
}
