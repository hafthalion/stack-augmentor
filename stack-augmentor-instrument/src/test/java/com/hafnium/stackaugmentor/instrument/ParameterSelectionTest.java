package com.hafnium.stackaugmentor.instrument;

import com.hafnium.stackaugmentor.Augmented;
import com.hafnium.stackaugmentor.AugmentedReceiver;
import com.hafnium.stackaugmentor.AugmentedParam;
import com.hafnium.stackaugmentor.AugmentedParams;
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

    @Augmented
    static class MethodLevel {
        @AugmentedParams
        void move(@AugmentedParam String item, int count) {
        }

        void plain(String item) {
        }
    }

    @Augmented
    static class Reservations {
        @AugmentedParams
        void reserve(String sku, int count) {
        }

        @AugmentedParams
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

    /** Only ship and the constructor are augmented: they have @Augmented. */
    static class Shipping {
        @AugmentedReceiver
        String code = "s";

        @Augmented
        Shipping(@AugmentedParam String code) {
        }

        @Augmented
        void ship(@AugmentedParam String to, int count) {
        }

        void weigh(@AugmentedParam String unit) {
        }
    }

    /** {@code @Augmented(false)} on the type: only loud, which turns it on again, is augmented. */
    @Augmented(false)
    static class Muted {
        @AugmentedReceiver
        String code = "m";

        void quiet(@AugmentedParam int x) {
        }

        @Augmented
        void loud(@AugmentedParam int x) {
        }
    }

    /** {@code @Augmented} on the type: all methods but skip are augmented. */
    @Augmented
    static class Selective {
        @AugmentedReceiver
        String code = "s";

        void run(@AugmentedParam int x) {
        }

        @Augmented(false)
        void skip(@AugmentedParam int x) {
        }

        @Augmented
        void also(@AugmentedParam int x) {
        }
    }

    /** Without {@code @Augmented}, its annotations are ignored. */
    static class WithoutAugmented {
        @AugmentedReceiver
        String code = "w";

        @AugmentedParams
        void run(String x) {
        }
    }

    static class Login {
        void login(String userName, String email, int attempt) {
        }
    }

    /** An explicit class entry gives its receiver id, a params entry decides for fail. */
    @Augmented
    static class Explicit {
        @AugmentedReceiver
        String code = "c";

        String getId() {
            return "i";
        }

        void fail(@AugmentedParam int x) {
        }

        void failAnnotated(@AugmentedParam int y) {
        }
    }

    /** Its only @AugmentedReceiver method takes an argument, so it can never give an id. */
    @Augmented
    static class UnusableId {
        @AugmentedReceiver
        String idFor(int version) {
            return "v" + version;
        }

        void run() {
        }
    }

    @Augmented
    static class UsableId {
        @AugmentedReceiver
        String id() {
            return "u";
        }

        void run() {
        }
    }

    @Augmented
    static class Built {
        @AugmentedParams
        Built(String code, long size) {
        }

        Built(String code) {
        }

        void run(String code) {
        }
    }

    private static final String HERE = ParameterSelectionTest.class.getPackageName() + ".**";

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

    /** {@code [augment.annotations]} with one entry that is true. */
    private static AugmentorConfig annotations(String classes) {
        return AugmentorConfig.builder().annotations(Map.of(classes, true)).build();
    }

    @Test
    void methodAnnotations() {
        IdParameters parameters = new IdParameters(annotations(HERE));
        assertEquals(List.of("item", "count"), labels(parameters, MethodLevel.class, "move"));
        assertEquals(List.of(), labels(parameters, MethodLevel.class, "plain"));
        assertEquals(List.of("sku", "count"), labels(parameters, Reservations.class, "reserve"));
        assertEquals(List.of("sku"), labels(parameters, Reservations.class, "release"));
        assertEquals(List.of(), labels(parameters, Reservations.class, "none"));
        // Not inherited by overriding methods.
        assertEquals(List.of(), labels(parameters, Derived.class, "reserve"));
    }

    @Test
    void annotationsAreReadOnlyWhereTheAnnotationsTableSelectsTheClass() {
        Map<String, Boolean> offFirst = new LinkedHashMap<>();
        offFirst.put(Reservations.class.getName(), false);
        offFirst.put(MethodLevel.class.getName(), false);
        offFirst.put(HERE, true);
        for (AugmentorConfig config : List.of(new AugmentorConfig(), annotations("com.acme.**"),
                AugmentorConfig.builder().annotations(offFirst).build(),
                AugmentorConfig.builder().annotations(Map.of(HERE, false)).build())) {
            IdParameters parameters = new IdParameters(config);
            assertEquals(List.of(), labels(parameters, Reservations.class, "reserve"));
            assertEquals(List.of(), labels(parameters, MethodLevel.class, "move"));
        }
    }

    @Test
    void aParamsEntryDecidesBeforeTheAnnotations() {
        String explicit = Explicit.class.getName();
        IdParameters parameters = new IdParameters(AugmentorConfig.builder()
                .annotations(Map.of(HERE, true))
                .methods(inOrder(
                        explicit + "::fail", List.of(new ParamRef.Excluded()),
                        Reservations.class.getName() + "::reserve", List.of(new ParamRef.ByName("count"))))
                .build());
        // "-" for fail: no parameter values, the annotations do not apply.
        assertEquals(List.of(), labels(parameters, Explicit.class, "fail"));
        assertEquals(List.of("y"), labels(parameters, Explicit.class, "failAnnotated"));
        // The name entry for reserve replaces its @AugmentedParams; release keeps them.
        assertEquals(List.of("count"), labels(parameters, Reservations.class, "reserve"));
        assertEquals(List.of("sku"), labels(parameters, Reservations.class, "release"));
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
                .methods(Map.of(explicit + "::fail", List.of(new ParamRef.ByIndex(0))))
                .annotations(Map.of(explicit, true))
                .build();
        TypeMatching matching = new TypeMatching(config, new IdParameters(config));

        assertFalse(matching.instrument(type(Reservations.class)));
        assertFalse(matching.instrument(type(MethodLevel.class)));
        assertTrue(matching.instrument(type(Explicit.class)));

        String output = err.toString(StandardCharsets.UTF_8);
        String notRead = ": no [augment.annotations] entry that is true matches it";
        assertTrue(output.contains("ignoring the parameter annotations of release, reserve in " + Reservations.class.getName() + notRead), output);
        assertTrue(output.contains("ignoring the parameter annotations of move in " + MethodLevel.class.getName() + notRead), output);
        assertTrue(output.contains("ignoring @AugmentedReceiver in " + explicit + ": [augment.receiver] entries or @Augmented decide for all its methods"),
                output);
        // failAnnotated uses its annotation; the params entry decides for fail.
        assertTrue(output.contains("ignoring the parameter annotations of fail in " + explicit + ": an [augment.params] entry or @Augmented decides for them"),
                output);
    }

    @Test
    void methodsAfterDoubleColonLimitTheReceiverIds() {
        String explicit = Explicit.class.getName();
        String login = Login.class.getName();
        AugmentorConfig config = AugmentorConfig.builder()
                .classes(firstMatch(explicit + "::fail", new IdSpec.MethodSpec("getId"), login + "::logout", new IdSpec.FieldSpec("id")))
                .methods(Map.of(explicit + "::failAnnotated", List.of(new ParamRef.ByName("y"))))
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
        assertTrue(description.contains("receiver members from [augment.receiver] \"" + explicit + "::fail\", [augment.receiver] \"" + explicit + "::*\""),
                description);
    }

    @Test
    void augmentedOnMethodsSelectsThem() {
        String shipping = Shipping.class.getName();
        AugmentorConfig config = AugmentorConfig.builder()
                .annotations(Map.of(HERE, true))
                .methods(Map.of(shipping + "::weigh", List.of(new ParamRef.ByName("unit"))))
                .build();
        IdParameters parameters = new IdParameters(config);
        TypeMatching matching = new TypeMatching(config, parameters);

        assertEquals(AugmentorConfig.ANNOTATION_ENTRY, matching.receiverEntry(type(Shipping.class), method(Shipping.class, "ship")));
        assertEquals(List.of("to"), labels(parameters, Shipping.class, "ship"));
        // weigh has no @Augmented: no receiver members, and its parameter only by its params entry.
        assertFalse(matching.receiverIds(type(Shipping.class), method(Shipping.class, "weigh")));
        assertEquals(List.of("unit"), labels(parameters, Shipping.class, "weigh"));
        MethodDescription constructor = type(Shipping.class).getDeclaredMethods().filter(isConstructor()).getOnly();
        assertEquals(List.of("code"), parameters.select(type(Shipping.class), constructor).stream().map(IdParameter::label).toList());
        assertTrue(matching.describe(type(Shipping.class), matching.methods(type(Shipping.class))).contains("receiver members from @AugmentedReceiver"));

        // Without [augment.annotations], @Augmented selects nothing.
        AugmentorConfig named = methods(Map.of(shipping + "::weigh", List.of(new ParamRef.ByName("unit"))));
        assertFalse(new TypeMatching(named, new IdParameters(named)).receiverIds(type(Shipping.class), method(Shipping.class, "ship")));
        assertEquals(List.of(), labels(new IdParameters(named), Shipping.class, "ship"));
    }

    @Test
    void augmentedOnTheTypeDecidesForTheMethodsWithoutTheirOwn() {
        AugmentorConfig config = annotations(HERE);
        IdParameters parameters = new IdParameters(config);
        TypeMatching matching = new TypeMatching(config, parameters);

        assertFalse(matching.receiverIds(type(Muted.class), method(Muted.class, "quiet")));
        assertEquals(List.of(), labels(parameters, Muted.class, "quiet"));
        assertTrue(matching.receiverIds(type(Muted.class), method(Muted.class, "loud")));
        assertEquals(List.of("x"), labels(parameters, Muted.class, "loud"));

        // @Augmented on the type augments run, which has none of its own.
        assertTrue(matching.receiverIds(type(Selective.class), method(Selective.class, "run")));
        assertEquals(List.of("x"), labels(parameters, Selective.class, "run"));
        assertTrue(matching.receiverIds(type(Selective.class), method(Selective.class, "also")));
        assertFalse(matching.receiverIds(type(Selective.class), method(Selective.class, "skip")));
        assertEquals(List.of(), labels(parameters, Selective.class, "skip"));
    }

    @Test
    void aClassWithoutAugmentedIsIgnored() {
        AugmentorConfig config = annotations(HERE);
        IdParameters parameters = new IdParameters(config);
        TypeMatching matching = new TypeMatching(config, parameters);

        assertFalse(matching.receiverIds(type(WithoutAugmented.class), method(WithoutAugmented.class, "run")));
        assertEquals(List.of(), labels(parameters, WithoutAugmented.class, "run"));
        assertFalse(matching.instrument(type(WithoutAugmented.class)));
    }

    @Test
    void excludedClassesAndMethodsAreIndependent() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        Log.setDebug(true);
        String service = Service.class.getName();
        String explicit = Explicit.class.getName();
        AugmentorConfig config = AugmentorConfig.builder()
                .classes(Map.of(explicit + "::*", new IdSpec.Excluded()))
                .annotations(Map.of(HERE, true))
                .methods(inOrder(
                        service + "::process", List.of(new ParamRef.ByName("note")),
                        service + "::*", List.of(new ParamRef.Excluded()),
                        MethodLevel.class.getName() + "::*", List.of(new ParamRef.ByIndex(0), new ParamRef.ByIndex(1))))
                .build();
        IdParameters parameters = new IdParameters(config);
        TypeMatching matching = new TypeMatching(config, parameters);

        // The "-" receiver entry drops Explicit's receiver members, not its parameter annotations.
        assertEquals(List.of("x"), labels(parameters, Explicit.class, "fail"));
        assertEquals(List.of("y"), labels(parameters, Explicit.class, "failAnnotated"));
        assertTrue(matching.instrument(type(Explicit.class)));
        assertTrue(matching.describe(type(Explicit.class), matching.methods(type(Explicit.class))).contains("(parameter values only)"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("ignoring @AugmentedReceiver in " + explicit
                + ": [augment.receiver] entries or @Augmented decide for all its methods"), err.toString(StandardCharsets.UTF_8));
        // The params entry labels the parameters by index, not by the annotations.
        assertEquals(List.of("item", "count"), labels(parameters, MethodLevel.class, "move"));
        assertEquals(List.of("item"), labels(parameters, MethodLevel.class, "plain"));
        // A "-" method entry before the pattern wins; the exact entry before the "-" wins.
        assertEquals(List.of("note"), labels(parameters, Service.class, "process"));
    }

    @Test
    void onlyUsableIdMethodsGetAClassInstrumented() {
        AugmentorConfig config = annotations(HERE);
        TypeMatching matching = new TypeMatching(config, new IdParameters(config));

        assertFalse(matching.instrument(type(UnusableId.class)));
        assertTrue(matching.instrument(type(UsableId.class)));
    }

    @Augmented
    static class Secrets {
        void login(@AugmentedParam String userName, @AugmentedParam(secret = true) String email, int attempt) {
        }

        @AugmentedParams
        void update(String name, @AugmentedParam(secret = true) String token) {
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
        IdParameters annotations = new IdParameters(annotations(Secrets.class.getName()));
        assertEquals(List.of("userName", "email#"), encoded(annotations, Secrets.class, "login"));
        // Under @AugmentedParams, @AugmentedParam(secret = true) hashes one parameter.
        assertEquals(List.of("name", "token#"), encoded(annotations, Secrets.class, "update"));
        // A params entry decides alone: the annotations, secret included, do not apply.
        IdParameters named = new IdParameters(AugmentorConfig.builder()
                .annotations(Map.of(Secrets.class.getName(), true))
                .methods(Map.of(Secrets.class.getName() + "::login", List.of(new ParamRef.ByName("email"))))
                .build());
        assertEquals(List.of("email"), encoded(named, Secrets.class, "login"));
        assertEquals(List.of("name", "token#"), encoded(named, Secrets.class, "update"));
    }

    @Test
    void constructorsByWildcardOrInitEntryAndNeverForTheReceiver() {
        TypeDescription built = type(Built.class);
        MethodDescription annotated = built.getDeclaredMethods().filter(isConstructor().and(takesArguments(2))).getOnly();
        MethodDescription plain = built.getDeclaredMethods().filter(isConstructor().and(takesArguments(1))).getOnly();

        // The annotations apply to constructors too.
        AugmentorConfig fromAnnotations = annotations(HERE);
        IdParameters annotationParameters = new IdParameters(fromAnnotations);
        assertEquals(List.of("code", "size"), annotationParameters.select(built, annotated).stream().map(IdParameter::label).toList());
        assertTrue(new TypeMatching(fromAnnotations, annotationParameters).constructors(built).matches(annotated));
        assertFalse(new TypeMatching(fromAnnotations, annotationParameters).constructors(built).matches(plain));

        // Wildcards in the method name match constructors too.
        AugmentorConfig wildcard = methods(Map.of(HERE + "::*", List.of(new ParamRef.ByIndex(0))));
        assertEquals(List.of("code"), new IdParameters(wildcard).select(built, plain).stream().map(IdParameter::label).toList());

        AugmentorConfig init = methods(Map.of(HERE + "::<init>", List.of(new ParamRef.ByIndex(1))));
        IdParameters parameters = new IdParameters(init);
        TypeMatching matching = new TypeMatching(init, parameters);
        assertEquals(List.of("size"), parameters.select(built, annotated).stream().map(IdParameter::label).toList());
        assertTrue(matching.instrument(built));
        assertTrue(matching.constructors(built).matches(annotated));
        assertFalse(matching.constructors(built).matches(plain));
        // Constructors never get the method advice.
        assertFalse(matching.methods(built).matches(annotated));
    }
}
