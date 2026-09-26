package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.StackTraceId;
import com.hafnium.stackaugmentor.StackTraceParam;
import com.hafnium.stackaugmentor.StackTraceParams;
import com.hafnium.stackaugmentor.runtime.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.IdSpec;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.ParamRef;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterSelectionTest {

    static class MethodLevel {
        @StackTraceParams
        void move(@StackTraceParam(name = "sku") String item, int count) {
        }

        void plain(String item) {
        }
    }

    @StackTraceParams
    static class ClassLevel {
        void reserve(String sku, int count) {
        }

        static void release(String sku) {
        }

        void none() {
        }
    }

    static class Derived extends ClassLevel {
        void run(int x) {
        }
    }

    static class Service {
        void process(String order, int quantity, String note) {
        }
    }

    /** An explicit class entry gives its receiver id; an "@" method entry re-enables the parameter annotations. */
    static class Explicit {
        @StackTraceId
        String code = "c";

        String getId() {
            return "i";
        }

        void fail(@StackTraceParam int x) {
        }

        void failAnnotated(@StackTraceParam(name = "why") int y) {
        }
    }

    private static final String HERE = ParameterSelectionTest.class.getPackageName() + ".**";
    private static final IdSpec ANNOTATIONS = new IdSpec.Annotations();

    private final PrintStream originalErr = System.err;

    @AfterEach
    void restore() {
        System.setErr(originalErr);
        Log.setDebug(false);
    }

    private static TypeDescription type(Class<?> type) {
        return TypeDescription.ForLoadedType.of(type);
    }

    private static MethodDescription method(Class<?> type, String name) {
        return type(type).getDeclaredMethods().filter(named(name)).getOnly();
    }

    private static List<String> labels(IdParameters parameters, Class<?> type, String method) {
        return parameters.select(type(type), method(type, method)).stream().map(IdParameter::label).toList();
    }

    private static AugmentorConfig classes(Map<String, IdSpec> classes) {
        return AugmentorConfig.builder().classes(classes).build();
    }

    @Test
    void methodAndClassLevelAnnotations() {
        IdParameters parameters = new IdParameters(classes(Map.of(HERE, ANNOTATIONS)));
        assertEquals(List.of("sku", "count"), labels(parameters, MethodLevel.class, "move"));
        assertEquals(List.of(), labels(parameters, MethodLevel.class, "plain"));
        assertEquals(List.of("sku", "count"), labels(parameters, ClassLevel.class, "reserve"));
        assertEquals(List.of("sku"), labels(parameters, ClassLevel.class, "release"));
        assertEquals(List.of(), labels(parameters, ClassLevel.class, "none"));
        // Not inherited by subclasses.
        assertEquals(List.of(), labels(parameters, Derived.class, "run"));
    }

    @Test
    void annotationsWithoutAnAtEntryAreIgnored() {
        for (AugmentorConfig config : List.of(new AugmentorConfig(), classes(Map.of("com.acme.**", ANNOTATIONS)))) {
            IdParameters parameters = new IdParameters(config);
            assertEquals(List.of(), labels(parameters, ClassLevel.class, "reserve"));
            assertEquals(List.of(), labels(parameters, MethodLevel.class, "move"));
        }
    }

    @Test
    void explicitClassEntryAndAtMethodEntry() {
        String explicit = Explicit.class.getName();
        // The exact explicit entry beats the "@" pattern: no parameter annotations ...
        AugmentorConfig classesOnly = classes(Map.of(HERE, ANNOTATIONS, explicit, new IdSpec.MethodSpec("getId")));
        IdParameters withoutMethods = new IdParameters(classesOnly);
        assertEquals(List.of(), labels(withoutMethods, Explicit.class, "fail"));
        assertEquals(List.of(), labels(withoutMethods, Explicit.class, "failAnnotated"));
        // ... until an "@" method entry re-enables them, for the matched methods only.
        IdParameters withMethods = new IdParameters(AugmentorConfig.builder()
                .classes(classesOnly.classes())
                .methods(Map.of(explicit + ".failAnnot*", List.of(new ParamRef.Annotations())))
                .build());
        assertEquals(List.of(), labels(withMethods, Explicit.class, "fail"));
        assertEquals(List.of("why"), labels(withMethods, Explicit.class, "failAnnotated"));

        // A class-level @StackTraceParams, enabled by method entries alone.
        IdParameters classLevel = new IdParameters(AugmentorConfig.builder()
                .methods(Map.of(ClassLevel.class.getName() + ".reserve", List.of(new ParamRef.Annotations())))
                .build());
        assertEquals(List.of("sku", "count"), labels(classLevel, ClassLevel.class, "reserve"));
        assertEquals(List.of(), labels(classLevel, ClassLevel.class, "release"));
    }

    @Test
    void configuredAllParametersAndOverlaps() {
        String service = Service.class.getName();
        IdParameters parameters = new IdParameters(AugmentorConfig.builder()
                .methods(Map.of(
                        service + ".process", List.of(new ParamRef.ByName("order")),
                        service.replace("Service", "Serv*") + ".*", List.of(new ParamRef.ByIndex(2))))
                .build());
        assertEquals(List.of("order", "note"), labels(parameters, Service.class, "process"));

        IdParameters all = new IdParameters(AugmentorConfig.builder()
                .methods(Map.of(service + ".proc*", List.of(new ParamRef.All())))
                .build());
        assertEquals(List.of("order", "quantity", "note"), labels(all, Service.class, "process"));
    }

    @Test
    void wildcardEntriesProduceNoUnmatchedMessages() {
        String service = Service.class.getName();
        IdParameters parameters = new IdParameters(AugmentorConfig.builder()
                .methods(Map.of(
                        service + ".*", List.of(new ParamRef.ByName("missing"), new ParamRef.ByIndex(9)),
                        service.replace("Service", "Serv?ce") + ".nope*", List.of(new ParamRef.All())))
                .build());
        assertEquals(List.of(), parameters.unmatchedEntries(type(Service.class)));

        IdParameters exact = new IdParameters(AugmentorConfig.builder()
                .methods(Map.of(
                        service + ".process", List.of(new ParamRef.ByName("missing")),
                        service + ".nope", List.of(new ParamRef.All())))
                .build());
        List<String> messages = exact.unmatchedEntries(type(Service.class));
        assertEquals(2, messages.size(), messages.toString());
        assertTrue(messages.stream().anyMatch(it -> it.contains("[instrument.methods]")), messages.toString());
        assertTrue(messages.stream().anyMatch(it -> it.contains("no parameter 'missing' in process(String order, int quantity, String note)")),
                messages.toString());
        assertTrue(messages.stream().anyMatch(it -> it.contains("has no method 'nope'")), messages.toString());
    }

    @Test
    void ignoredAnnotationsAreReported() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        Log.setDebug(true);
        String explicit = Explicit.class.getName();
        AugmentorConfig config = AugmentorConfig.builder()
                .classes(Map.of(explicit, new IdSpec.MethodSpec("getId")))
                .methods(Map.of(explicit + ".failAnnotated", List.of(new ParamRef.Annotations())))
                .build();
        TypeMatching matching = new TypeMatching(config, new IdParameters(config));

        assertFalse(matching.instrument(type(ClassLevel.class)));
        assertFalse(matching.instrument(type(MethodLevel.class)));
        assertTrue(matching.instrument(type(Explicit.class)));

        String output = err.toString(StandardCharsets.UTF_8);
        String reason = ": no \"@\" entry in [instrument.classes] or [instrument.methods] applies";
        assertTrue(output.contains("ignoring the parameter annotations of release, reserve in " + ClassLevel.class.getName() + reason), output);
        assertTrue(output.contains("ignoring the parameter annotations of move in " + MethodLevel.class.getName() + reason), output);
        // failAnnotated is enabled by its method entry.
        assertTrue(output.contains("ignoring @StackTraceId and the parameter annotations of fail in " + explicit + reason), output);
    }
}
