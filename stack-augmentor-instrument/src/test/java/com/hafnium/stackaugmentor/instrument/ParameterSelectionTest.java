package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.StackTraceAugment;
import com.hafnium.stackaugmentor.StackTraceReceiver;
import com.hafnium.stackaugmentor.StackTraceParam;
import com.hafnium.stackaugmentor.StackTraceParams;
import com.hafnium.stackaugmentor.runtime.Log;
import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig;
import com.hafnium.stackaugmentor.runtime.config.IdSpec;
import com.hafnium.stackaugmentor.runtime.config.ParamRef;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
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

    static class Reservations {
        @StackTraceParams
        void reserve(String sku, int count) {
        }

        @StackTraceParams
        static void release(String sku) {
        }

        void none(String sku) {
        }
    }

    static class Derived extends Reservations {
        @Override
        void reserve(String sku, int count) {
        }
    }

    static class Service {
        void process(String order, int quantity, String note) {
        }
    }

    /** Selected by "@" after "::" in both tables: ship and the constructor are annotated with @StackTraceAugment. */
    static class Shipping {
        String code = "s";

        @StackTraceAugment
        Shipping(String code) {
        }

        @StackTraceAugment
        void ship(String to, int count) {
        }

        void weigh(String unit) {
        }
    }

    static class Login {
        void login(String userName, String email, int attempt) {
        }
    }

    /** An explicit class entry gives its receiver id; an "@" method entry re-enables the parameter annotations. */
    static class Explicit {
        @StackTraceReceiver
        String code = "c";

        String getId() {
            return "i";
        }

        void fail(@StackTraceParam int x) {
        }

        void failAnnotated(@StackTraceParam int y) {
        }
    }

    /** Its only @StackTraceReceiver method takes an argument, so it can never give an id. */
    static class UnusableId {
        @StackTraceReceiver
        String idFor(int version) {
            return "v" + version;
        }

        void run() {
        }
    }

    static class UsableId {
        @StackTraceReceiver
        String id() {
            return "u";
        }

        void run() {
        }
    }

    static class Built {
        @StackTraceParams
        Built(String code, long size) {
        }

        Built(String code) {
        }

        void run(String code) {
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

    /** {@code [augment.params]} entries in this order, as keys and values: the first matching one wins. */
    @SuppressWarnings("unchecked")
    private static Map<String, List<ParamRef>> inOrder(Object... keysAndValues) {
        Map<String, List<ParamRef>> methods = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            methods.put((String) keysAndValues[i], (List<ParamRef>) keysAndValues[i + 1]);
        }
        return methods;
    }

    /** Two {@code [augment.receiver]} entries in this order: the first matching one wins. */
    private static Map<String, IdSpec> firstMatch(String key1, IdSpec spec1, String key2, IdSpec spec2) {
        Map<String, IdSpec> classes = new LinkedHashMap<>();
        classes.put(key1, spec1);
        classes.put(key2, spec2);
        return classes;
    }

    private static AugmentorConfig classes(Map<String, IdSpec> classes) {
        return AugmentorConfig.builder().classes(classes).build();
    }

    private static AugmentorConfig methods(Map<String, List<ParamRef>> methods) {
        return AugmentorConfig.builder().methods(methods).build();
    }

    /** All methods of the classes in this package. */
    private static final String HERE_METHODS = HERE + "::*";

    @Test
    void methodAnnotations() {
        IdParameters parameters = new IdParameters(methods(Map.of(HERE_METHODS, List.of(new ParamRef.Annotations()))));
        assertEquals(List.of("item", "count"), labels(parameters, MethodLevel.class, "move"));
        assertEquals(List.of(), labels(parameters, MethodLevel.class, "plain"));
        assertEquals(List.of("sku", "count"), labels(parameters, Reservations.class, "reserve"));
        assertEquals(List.of("sku"), labels(parameters, Reservations.class, "release"));
        assertEquals(List.of(), labels(parameters, Reservations.class, "none"));
        // Not inherited by overriding methods.
        assertEquals(List.of(), labels(parameters, Derived.class, "reserve"));
    }

    @Test
    void annotationsWithoutAnAtEntryAreIgnored() {
        // An "@" class entry is for @StackTraceReceiver only: the tables are independent.
        for (AugmentorConfig config : List.of(new AugmentorConfig(), classes(Map.of(HERE_METHODS, ANNOTATIONS)),
                methods(Map.of("com.acme.**::*", List.of(new ParamRef.Annotations()))))) {
            IdParameters parameters = new IdParameters(config);
            assertEquals(List.of(), labels(parameters, Reservations.class, "reserve"));
            assertEquals(List.of(), labels(parameters, MethodLevel.class, "move"));
        }
    }

    @Test
    void explicitClassEntryAndAtMethodEntry() {
        String explicit = Explicit.class.getName();
        // The explicit entry before the "@" pattern wins: no parameter annotations ...
        AugmentorConfig classesOnly = classes(firstMatch(explicit + "::*", new IdSpec.MethodSpec("getId"), HERE_METHODS, ANNOTATIONS));
        IdParameters withoutMethods = new IdParameters(classesOnly);
        assertEquals(List.of(), labels(withoutMethods, Explicit.class, "fail"));
        assertEquals(List.of(), labels(withoutMethods, Explicit.class, "failAnnotated"));
        // ... until an "@" method entry re-enables them, for the matched methods only.
        IdParameters withMethods = new IdParameters(AugmentorConfig.builder()
                .classes(classesOnly.classes())
                .methods(Map.of(explicit + "::failAnnot*", List.of(new ParamRef.Annotations())))
                .build());
        assertEquals(List.of(), labels(withMethods, Explicit.class, "fail"));
        assertEquals(List.of("y"), labels(withMethods, Explicit.class, "failAnnotated"));

        // @StackTraceParams, enabled by a method entry for that method alone.
        IdParameters classLevel = new IdParameters(AugmentorConfig.builder()
                .methods(Map.of(Reservations.class.getName() + "::reserve", List.of(new ParamRef.Annotations())))
                .build());
        assertEquals(List.of("sku", "count"), labels(classLevel, Reservations.class, "reserve"));
        assertEquals(List.of(), labels(classLevel, Reservations.class, "release"));
    }

    @Test
    void onlyTheFirstMatchingEntryDecides() {
        String service = Service.class.getName();
        IdParameters parameters = new IdParameters(AugmentorConfig.builder()
                .methods(inOrder(
                        service + "::process", List.of(new ParamRef.ByName("order")),
                        service.replace("Service", "Serv*") + "::*", List.of(new ParamRef.ByIndex(2))))
                .build());
        // The first entry decides alone: the pattern adds nothing.
        assertEquals(List.of("order"), labels(parameters, Service.class, "process"));
        // The other way round, the pattern decides, however specific the later entry is.
        IdParameters patternFirst = new IdParameters(AugmentorConfig.builder()
                .methods(inOrder(
                        service.replace("Service", "Serv*") + "::*", List.of(new ParamRef.ByIndex(2)),
                        service + "::process", List.of(new ParamRef.ByName("order"))))
                .build());
        assertEquals(List.of("note"), labels(patternFirst, Service.class, "process"));
    }

    @Test
    void wildcardEntriesProduceNoUnmatchedMessages() {
        String service = Service.class.getName();
        IdParameters parameters = new IdParameters(AugmentorConfig.builder()
                .methods(Map.of(
                        service + "::*", List.of(new ParamRef.ByName("missing"), new ParamRef.ByIndex(9)),
                        service.replace("Service", "Serv?ce") + "::nope*", List.of(new ParamRef.ByIndex(0))))
                .build());
        assertEquals(List.of(), parameters.unmatchedEntries(type(Service.class)));

        IdParameters exact = new IdParameters(AugmentorConfig.builder()
                .methods(Map.of(
                        service + "::process", List.of(new ParamRef.ByName("missing")),
                        service + "::nope", List.of(new ParamRef.ByIndex(0))))
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
                .classes(Map.of(explicit + "::*", new IdSpec.MethodSpec("getId")))
                .methods(Map.of(explicit + "::failAnnotated", List.of(new ParamRef.Annotations())))
                .build();
        TypeMatching matching = new TypeMatching(config, new IdParameters(config));

        assertFalse(matching.instrument(type(Reservations.class)));
        assertFalse(matching.instrument(type(MethodLevel.class)));
        assertTrue(matching.instrument(type(Explicit.class)));

        String output = err.toString(StandardCharsets.UTF_8);
        String reason = ": no \"@\" entry in [augment.params] applies";
        assertTrue(output.contains("ignoring the parameter annotations of release, reserve in " + Reservations.class.getName() + reason), output);
        assertTrue(output.contains("ignoring the parameter annotations of move in " + MethodLevel.class.getName() + reason), output);
        assertTrue(output.contains("ignoring @StackTraceReceiver in " + explicit + ": its [augment.receiver] entry \"" + explicit + "::*\" is not \"@\""),
                output);
        // failAnnotated is enabled by its method entry.
        assertTrue(output.contains("ignoring the parameter annotations of fail in " + explicit + reason), output);
    }

    @Test
    void methodsAfterDoubleColonLimitTheReceiverIds() {
        String explicit = Explicit.class.getName();
        String login = Login.class.getName();
        AugmentorConfig config = AugmentorConfig.builder()
                .classes(firstMatch(explicit + "::fail", new IdSpec.MethodSpec("getId"), login + "::logout", new IdSpec.FieldSpec("id")))
                .methods(Map.of(explicit + "::failAnnotated", List.of(new ParamRef.Annotations())))
                .build();
        TypeMatching matching = new TypeMatching(config, new IdParameters(config));

        // Only fail shows the receiver id; failAnnotated is instrumented for its parameter only, getId not at all.
        assertTrue(matching.receiverIds(type(Explicit.class), method(Explicit.class, "fail")));
        assertFalse(matching.receiverIds(type(Explicit.class), method(Explicit.class, "failAnnotated")));
        var methods = matching.methods(type(Explicit.class));
        assertTrue(methods.matches(method(Explicit.class, "fail")));
        assertTrue(methods.matches(method(Explicit.class, "failAnnotated")));
        assertFalse(methods.matches(method(Explicit.class, "getId")));
        // Login declares no method of its entry, so it is not instrumented.
        assertFalse(matching.instrument(type(Login.class)));
        assertFalse(matching.methods(type(Login.class)).matches(method(Login.class, "login")));
    }

    @Test
    void severalEntriesOfOneClassDecidePerMethod() {
        String explicit = Explicit.class.getName();
        Map<String, IdSpec> classes = new LinkedHashMap<>();
        classes.put(explicit + "::fail", new IdSpec.MethodSpec("getId"));
        classes.put(explicit + "::failAnnotated", new IdSpec.Excluded());
        classes.put(explicit + "::*", new IdSpec.FieldSpec("code"));
        AugmentorConfig config = classes(classes);
        TypeMatching matching = new TypeMatching(config, new IdParameters(config));

        assertEquals(explicit + "::fail", matching.receiverEntry(type(Explicit.class), method(Explicit.class, "fail")).key());
        // "-" for failAnnotated: the later entry does not apply to it.
        assertFalse(matching.receiverIds(type(Explicit.class), method(Explicit.class, "failAnnotated")));
        assertEquals(explicit + "::*", matching.receiverEntry(type(Explicit.class), method(Explicit.class, "getId")).key());
        String description = matching.describe(type(Explicit.class), matching.methods(type(Explicit.class)));
        assertTrue(description.contains("receiver id from [augment.receiver] \"" + explicit + "::fail\", [augment.receiver] \"" + explicit + "::*\""),
                description);
    }

    @Test
    void atAfterDoubleColonSelectsTheAnnotatedMethods() {
        String shipping = Shipping.class.getName();
        AugmentorConfig config = AugmentorConfig.builder()
                .classes(Map.of(shipping + "::@", new IdSpec.FieldSpec("code")))
                .methods(inOrder(shipping + "::@", List.of(new ParamRef.ByIndex(0)), shipping + "::*", List.of(new ParamRef.ByName("unit"))))
                .build();
        IdParameters parameters = new IdParameters(config);
        TypeMatching matching = new TypeMatching(config, parameters);

        assertEquals(shipping + "::@", matching.receiverEntry(type(Shipping.class), method(Shipping.class, "ship")).key());
        assertFalse(matching.receiverIds(type(Shipping.class), method(Shipping.class, "weigh")));
        assertEquals(List.of("to"), labels(parameters, Shipping.class, "ship"));
        assertEquals(List.of("unit"), labels(parameters, Shipping.class, "weigh"));
        MethodDescription constructor = type(Shipping.class).getDeclaredMethods().filter(isConstructor()).getOnly();
        assertEquals(List.of("code"), parameters.select(type(Shipping.class), constructor).stream().map(IdParameter::label).toList());
    }

    @Test
    void excludedClassesAndMethodsAreIndependent() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        Log.setDebug(true);
        String service = Service.class.getName();
        String explicit = Explicit.class.getName();
        AugmentorConfig config = AugmentorConfig.builder()
                .classes(firstMatch(explicit + "::*", new IdSpec.Excluded(), HERE_METHODS, ANNOTATIONS))
                .methods(inOrder(
                        explicit + "::failAnnotated", List.of(new ParamRef.Annotations()),
                        service + "::process", List.of(new ParamRef.ByName("note")),
                        service + "::*", List.of(new ParamRef.Excluded()),
                        HERE_METHODS, List.of(new ParamRef.ByIndex(0), new ParamRef.ByIndex(1))))
                .build();
        IdParameters parameters = new IdParameters(config);
        TypeMatching matching = new TypeMatching(config, parameters);

        // The "-" class entry drops Explicit's receiver id, not its parameters.
        assertEquals(List.of("x"), labels(parameters, Explicit.class, "fail"));
        assertEquals(List.of("y"), labels(parameters, Explicit.class, "failAnnotated"));
        assertTrue(matching.instrument(type(Explicit.class)));
        assertTrue(matching.describe(type(Explicit.class), matching.methods(type(Explicit.class))).contains("(parameter ids only)"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("ignoring @StackTraceReceiver in " + explicit + ": its [augment.receiver] entry \""
                + explicit + "::*\" is not \"@\""), err.toString(StandardCharsets.UTF_8));
        // The "@" class entry uses @StackTraceReceiver only: the method entry labels the parameters by name.
        assertEquals(List.of("item", "count"), labels(parameters, MethodLevel.class, "move"));
        // A "-" method entry before the pattern wins; the exact entry before the "-" wins.
        assertEquals(List.of("note"), labels(parameters, Service.class, "process"));
    }

    @Test
    void onlyUsableIdMethodsGetAClassInstrumented() {
        AugmentorConfig config = classes(Map.of(HERE_METHODS, ANNOTATIONS));
        TypeMatching matching = new TypeMatching(config, new IdParameters(config));

        assertFalse(matching.instrument(type(UnusableId.class)));
        assertTrue(matching.instrument(type(UsableId.class)));
    }

    static class Secrets {
        void login(@StackTraceParam String userName, @StackTraceParam(secret = true) String email, int attempt) {
        }

        @StackTraceParams
        void update(String name, @StackTraceParam(secret = true) String token) {
        }
    }


    @Test
    void hashedParameters() {
        String login = Login.class.getName();
        IdParameters byName = new IdParameters(methods(Map.of(login + "::login",
                List.of(new ParamRef.ByName("email", true), new ParamRef.ByIndex(0, true), new ParamRef.ByIndex(2)))));
        assertEquals(List.of("userName#", "email#", "attempt"), encoded(byName, Login.class, "login"));

        // Selected plainly by index and hashed by name: hashed.
        IdParameters twice = new IdParameters(methods(Map.of(login + "::login",
                List.of(new ParamRef.ByIndex(0), new ParamRef.ByIndex(1), new ParamRef.ByName("email", true)))));
        assertEquals(List.of("userName", "email#"), encoded(twice, Login.class, "login"));
    }

    @Test
    void secretAnnotations() {
        IdParameters annotations = new IdParameters(methods(Map.of(
                Secrets.class.getName() + "::*", List.of(new ParamRef.Annotations()))));
        assertEquals(List.of("userName", "email#"), encoded(annotations, Secrets.class, "login"));
        // Under @StackTraceParams, @StackTraceParam(secret = true) hashes one parameter.
        assertEquals(List.of("name", "token#"), encoded(annotations, Secrets.class, "update"));
        // A name entry before the pattern decides alone: the annotations, secret included, do not apply.
        IdParameters named = new IdParameters(methods(inOrder(
                Secrets.class.getName() + "::login", List.of(new ParamRef.ByName("email")),
                Secrets.class.getName() + "::*", List.of(new ParamRef.Annotations()))));
        assertEquals(List.of("email"), encoded(named, Secrets.class, "login"));
    }

    @Test
    void constructorsByWildcardOrInitEntryAndNeverForTheReceiver() {
        TypeDescription built = type(Built.class);
        MethodDescription annotated = built.getDeclaredMethods().filter(isConstructor().and(takesArguments(2))).getOnly();
        MethodDescription plain = built.getDeclaredMethods().filter(isConstructor().and(takesArguments(1))).getOnly();

        // Wildcards in the method name match constructors too.
        AugmentorConfig wildcard = AugmentorConfig.builder()
                .classes(Map.of(HERE_METHODS, ANNOTATIONS))
                .methods(Map.of(HERE_METHODS, List.of(new ParamRef.Annotations())))
                .build();
        IdParameters wildcardParameters = new IdParameters(wildcard);
        assertEquals(List.of("code", "size"), wildcardParameters.select(built, annotated).stream().map(IdParameter::label).toList());
        assertTrue(new TypeMatching(wildcard, wildcardParameters).constructors(built).matches(annotated));

        AugmentorConfig init = methods(Map.of(HERE + "::<init>", List.of(new ParamRef.Annotations())));
        IdParameters parameters = new IdParameters(init);
        TypeMatching matching = new TypeMatching(init, parameters);
        assertEquals(List.of("code", "size"), parameters.select(built, annotated).stream().map(IdParameter::label).toList());
        assertTrue(matching.instrument(built));
        assertTrue(matching.constructors(built).matches(annotated));
        assertFalse(matching.constructors(built).matches(plain));
        // Constructors never get the method advice.
        assertFalse(matching.methods(built).matches(annotated));
    }
}
