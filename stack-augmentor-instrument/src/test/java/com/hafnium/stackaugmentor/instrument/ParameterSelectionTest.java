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
        void move(@StackTraceParam String item, int count) {
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

    static class Login {
        void login(String userName, String password, int attempt) {
        }

        @StackTraceParams
        void register(String email, String nickname) {
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

        void failAnnotated(@StackTraceParam int y) {
        }
    }

    /** Its only @StackTraceId method takes an argument, so it can never give an id. */
    static class UnusableId {
        @StackTraceId
        String idFor(int version) {
            return "v" + version;
        }

        void run() {
        }
    }

    static class UsableId {
        @StackTraceId
        String id() {
            return "u";
        }

        void run() {
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

    /** The labels as the runtime receives them: hashed values' end with '#'. */
    private static List<String> encoded(IdParameters parameters, Class<?> type, String method) {
        return parameters.select(type(type), method(type, method)).stream().map(IdParameter::encodedLabel).toList();
    }

    private static AugmentorConfig classes(Map<String, IdSpec> classes) {
        return AugmentorConfig.builder().classes(classes).build();
    }

    private static AugmentorConfig methods(Map<String, List<ParamRef>> methods) {
        return AugmentorConfig.builder().methods(methods).build();
    }

    /** All methods of the classes in this package. */
    private static final String HERE_METHODS = HERE + ".*";

    @Test
    void methodAndClassLevelAnnotations() {
        IdParameters parameters = new IdParameters(methods(Map.of(HERE_METHODS, List.of(new ParamRef.Annotations()))));
        assertEquals(List.of("item", "count"), labels(parameters, MethodLevel.class, "move"));
        assertEquals(List.of(), labels(parameters, MethodLevel.class, "plain"));
        assertEquals(List.of("sku", "count"), labels(parameters, ClassLevel.class, "reserve"));
        assertEquals(List.of("sku"), labels(parameters, ClassLevel.class, "release"));
        assertEquals(List.of(), labels(parameters, ClassLevel.class, "none"));
        // Not inherited by subclasses.
        assertEquals(List.of(), labels(parameters, Derived.class, "run"));
    }

    @Test
    void annotationsWithoutAnAtEntryAreIgnored() {
        // An "@" class entry is for @StackTraceId only: the tables are independent.
        for (AugmentorConfig config : List.of(new AugmentorConfig(), classes(Map.of(HERE, ANNOTATIONS)),
                methods(Map.of("com.acme.**.*", List.of(new ParamRef.Annotations()))))) {
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
        assertEquals(List.of("y"), labels(withMethods, Explicit.class, "failAnnotated"));

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
        assertTrue(messages.stream().anyMatch(it -> it.contains("[augment.params]")), messages.toString());
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
        String reason = ": no \"@\" entry in [augment.params] applies";
        assertTrue(output.contains("ignoring the parameter annotations of release, reserve in " + ClassLevel.class.getName() + reason), output);
        assertTrue(output.contains("ignoring the parameter annotations of move in " + MethodLevel.class.getName() + reason), output);
        assertTrue(output.contains("ignoring @StackTraceId in " + explicit + ": its [augment.receiver] entry \"" + explicit + "\" is not \"@\""),
                output);
        // failAnnotated is enabled by its method entry.
        assertTrue(output.contains("ignoring the parameter annotations of fail in " + explicit + reason), output);
    }

    @Test
    void excludedClassesAndMethodsAreIndependent() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        Log.setDebug(true);
        String service = Service.class.getName();
        String explicit = Explicit.class.getName();
        AugmentorConfig config = AugmentorConfig.builder()
                .classes(Map.of(HERE, ANNOTATIONS, explicit, new IdSpec.Excluded()))
                .methods(Map.of(
                        HERE_METHODS, List.of(new ParamRef.All()),
                        service + ".*", List.of(new ParamRef.Excluded()),
                        service + ".process", List.of(new ParamRef.ByName("note")),
                        explicit + ".failAnnotated", List.of(new ParamRef.Annotations())))
                .build();
        IdParameters parameters = new IdParameters(config);
        TypeMatching matching = new TypeMatching(config, parameters);

        // The "-" class entry drops Explicit's receiver id, not its parameters.
        assertEquals(List.of("x"), labels(parameters, Explicit.class, "fail"));
        assertEquals(List.of("y"), labels(parameters, Explicit.class, "failAnnotated"));
        assertTrue(matching.instrument(type(Explicit.class)));
        assertTrue(matching.describe(type(Explicit.class), matching.methods(type(Explicit.class))).contains("(parameter ids only)"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("ignoring @StackTraceId in " + explicit + ": its [augment.receiver] entry \""
                + explicit + "\" is not \"@\""), err.toString(StandardCharsets.UTF_8));
        // The "@" class entry uses @StackTraceId only: the "*" method entry labels the parameters by name.
        assertEquals(List.of("item", "count"), labels(parameters, MethodLevel.class, "move"));
        // A "-" method entry beats the less specific pattern; the exact entry beats the "-".
        assertEquals(List.of("note"), labels(parameters, Service.class, "process"));
    }

    @Test
    void onlyUsableIdMethodsGetAClassInstrumented() {
        AugmentorConfig config = classes(Map.of(HERE, ANNOTATIONS));
        TypeMatching matching = new TypeMatching(config, new IdParameters(config));

        assertFalse(matching.instrument(type(UnusableId.class)));
        assertTrue(matching.instrument(type(UsableId.class)));
    }

    @Test
    void hashedParameters() {
        String login = Login.class.getName();
        IdParameters byName = new IdParameters(methods(Map.of(login + ".login",
                List.of(new ParamRef.ByName("password", ParamRef.Hashing.HASH), new ParamRef.ByIndex(0, ParamRef.Hashing.HASH),
                        new ParamRef.ByIndex(2)))));
        assertEquals(List.of("userName#", "password#", "attempt"), encoded(byName, Login.class, "login"));

        IdParameters all = new IdParameters(methods(Map.of(login + ".*", List.of(new ParamRef.All(ParamRef.Hashing.HASH)))));
        assertEquals(List.of("userName#", "password#", "attempt#"), encoded(all, Login.class, "login"));

        IdParameters guessed = new IdParameters(methods(Map.of(login + ".*", List.of(new ParamRef.All(ParamRef.Hashing.GUESS)))));
        assertEquals(List.of("userName#", "password#", "attempt"), encoded(guessed, Login.class, "login"));

        IdParameters annotations = new IdParameters(methods(Map.of(login + ".*", List.of(new ParamRef.Annotations(ParamRef.Hashing.GUESS)))));
        assertEquals(List.of(), encoded(annotations, Login.class, "login"));
        assertEquals(List.of("email#", "nickname"), encoded(annotations, Login.class, "register"));

        // Selected plainly and hashed by another entry: hashed.
        IdParameters overlapping = new IdParameters(methods(Map.of(
                login + ".login", List.of(new ParamRef.ByName("password", ParamRef.Hashing.HASH)),
                login + ".*", List.of(new ParamRef.All()))));
        assertEquals(List.of("userName", "password#", "attempt"), encoded(overlapping, Login.class, "login"));

        IdParameters custom = new IdParameters(AugmentorConfig.builder()
                .methods(Map.of(login + ".*", List.of(new ParamRef.All(ParamRef.Hashing.GUESS))))
                .sensitiveParams(List.of("nickname"))
                .build());
        assertEquals(List.of("email", "nickname#"), encoded(custom, Login.class, "register"));
    }
}
