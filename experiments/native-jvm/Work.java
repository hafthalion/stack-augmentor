import java.util.*;
import java.util.stream.*;
public class Work {
    public static void main(String[] a) {
        for (int r = 0; r < 5; r++) {
            long t0 = System.nanoTime(); long sink = 0;
            for (int i = 0; i < 200; i++) {
                Map<String, List<Integer>> m = new HashMap<>();
                for (int j = 0; j < 2000; j++) m.computeIfAbsent("k" + (j % 97), k -> new ArrayList<>()).add(j);
                sink += m.values().stream().flatMap(List::stream).filter(x -> x % 3 == 0).mapToLong(x -> x).sum();
                List<String> l = m.keySet().stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList());
                sink += String.join(",", l).hashCode();
            }
            System.out.printf("round %d: %.1f ms%n", r, (System.nanoTime() - t0) / 1e6);
            if (sink == 42) System.out.println();
        }
    }
}
