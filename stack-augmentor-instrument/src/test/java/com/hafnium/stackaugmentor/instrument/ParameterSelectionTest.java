package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.StackTraceParam;
import com.hafnium.stackaugmentor.StackTraceParams;
import com.hafnium.stackaugmentor.runtime.AugmentorConfig;
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

    @Test
    void methodAndClassLevelAnnotations() {
        IdParameters parameters = new IdParameters(new AugmentorConfig());
        assertEquals(List.of("sku", "count"), labels(parameters, MethodLevel.class, "move"));
        assertEquals(List.of(), labels(parameters, MethodLevel.class, "plain"));
        assertEquals(List.of("sku", "count"), labels(parameters, ClassLevel.class, "reserve"));
        assertEquals(List.of("sku"), labels(parameters, ClassLevel.class, "release"));
        assertEquals(List.of(), labels(parameters, ClassLevel.class, "none"));
        // Not inherited by subclasses.
        assertEquals(List.of(), labels(parameters, Derived.class, "run"));
    }

    @Test
    void annotationsOutsideAnnotatedClassesAreIgnored() {
        IdParameters parameters = new IdParameters(AugmentorConfig.builder().annotatedClasses(List.of("com.acme.**")).build());
        assertEquals(List.of(), labels(parameters, ClassLevel.class, "reserve"));
        assertEquals(List.of(), labels(parameters, MethodLevel.class, "move"));
    }

    @Test
    void configuredAllParametersAndOverlaps() {
        String service = Service.class.getName();
        IdParameters parameters = new IdParameters(AugmentorConfig.builder()
                .params(Map.of(
                        service + ".process", List.of(new ParamRef.ByName("order")),
                        service.replace("Service", "Serv*") + ".*", List.of(new ParamRef.ByIndex(2))))
                .build());
        assertEquals(List.of("order", "note"), labels(parameters, Service.class, "process"));

        IdParameters all = new IdParameters(AugmentorConfig.builder()
                .params(Map.of(service + ".proc*", List.of(new ParamRef.All())))
                .build());
        assertEquals(List.of("order", "quantity", "note"), labels(all, Service.class, "process"));
    }

    @Test
    void wildcardEntriesProduceNoUnmatchedMessages() {
        String service = Service.class.getName();
        IdParameters parameters = new IdParameters(AugmentorConfig.builder()
                .params(Map.of(
                        service + ".*", List.of(new ParamRef.ByName("missing"), new ParamRef.ByIndex(9)),
                        service.replace("Service", "Serv?ce") + ".nope*", List.of(new ParamRef.All())))
                .build());
        assertEquals(List.of(), parameters.unmatchedEntries(type(Service.class)));

        IdParameters exact = new IdParameters(AugmentorConfig.builder()
                .params(Map.of(
                        service + ".process", List.of(new ParamRef.ByName("missing")),
                        service + ".nope", List.of(new ParamRef.All())))
                .build());
        List<String> messages = exact.unmatchedEntries(type(Service.class));
        assertEquals(2, messages.size(), messages.toString());
        assertTrue(messages.stream().anyMatch(it -> it.contains("no parameter 'missing' in process(String order, int quantity, String note)")),
                messages.toString());
        assertTrue(messages.stream().anyMatch(it -> it.contains("has no method 'nope'")), messages.toString());
    }

    @Test
    void classLevelAnnotationOutsideAnnotatedClassesIsReported() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        Log.setDebug(true);
        AugmentorConfig config = AugmentorConfig.builder().annotatedClasses(List.of("com.acme.**")).build();
        TypeMatching matching = new TypeMatching(config, new IdParameters(config));

        assertFalse(matching.instrument(type(ClassLevel.class)));
        assertFalse(matching.instrument(type(MethodLevel.class)));

        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("ignoring @StackTraceId, @StackTraceParam and @StackTraceParams in " + ClassLevel.class.getName()), output);
        assertTrue(output.contains("ignoring @StackTraceId, @StackTraceParam and @StackTraceParams in " + MethodLevel.class.getName()), output);
    }
}
