package com.hafnium.stackaugmentor.runtime.config

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** The first [augment.receiver] entry of a class known only by its name, without supertypes, whatever its methods. */
private fun AugmentorConfig.entryOf(className: String) = classEntries(className) { it == className }.firstOrNull()

/** [AugmentorConfig.paramRefs] for a class known only by its name, without supertypes. */
private fun AugmentorConfig.paramRefs(className: String, methodName: String): List<ParamRef> =
    paramRefs(className, { it == className }, methodName)

/** [AugmentorConfig.hasParamEntries] for a class known only by its name, without supertypes. */
private fun AugmentorConfig.hasParamEntries(className: String) = hasParamEntries(className) { it == className }

/** The first [augment.receiver] entry of a class, whatever its methods. */
private fun AugmentorConfig.entryOf(type: Class<*>) = classEntries(type).firstOrNull()

class AugmentorConfigTest {

    private fun parse(text: String) = AugmentorConfig.parse(text.trimIndent(), "test.toml")

    private fun error(text: String): String = assertThrows<ConfigException> { parse(text) }.message!!

    @Test
    fun defaults() {
        assertEquals(AugmentorConfig(), parse(""))
        assertEquals("\$class\$receiver.\$method\$params", AugmentorConfig().frameFormat())
        assertEquals("{\$name=\$value, ...}", AugmentorConfig().receiverFormat())
        assertEquals("{\$name=\$value, ...}", AugmentorConfig().paramsFormat())
        assertFalse(AugmentorConfig().hasAugmentEntries())
        assertNull(AugmentorConfig().entryOf("any.pkg.Class"))
        assertTrue(AugmentorConfig().inPlaceModification())
        assertFalse(AugmentorConfig().inPlaceModificationRequested())
        assertFalse(parse("settings.inPlaceModification = false").inPlaceModification())
        assertTrue(parse("settings.inPlaceModification = false").inPlaceModificationRequested())
    }

    @Test
    fun `full configuration`() {
        val config = parse(
            """
            [settings]
            debug = true
            inPlaceModification = true

            [augment.annotations]
            "com.hafnium.generated.**" = false
            "com.hafnium.**" = true

            [augment.receiver]
            "com.thirdparty.Order+::*" = "getOrderNumber()"
            "com.thirdparty.Customer::*" = "customerId"

            [augment.params]
            "com.thirdparty.OrderService::process" = "orderId, 2"

            [augment]
            frameFormat = "${'$'}class.${'$'}method${'$'}receiver${'$'}params"
            receiverFormat = "<${'$'}value>"
            paramsFormat = "[${'$'}name: ${'$'}value; ...]"
            maxValueLength = 32
            """,
        )
        assertEquals(
            AugmentorConfig.builder()
                .annotations(mapOf("com.hafnium.generated.**" to false, "com.hafnium.**" to true))
                .classes(
                    mapOf(
                        "com.thirdparty.Order+::*" to ReceiverSpec.MethodSpec("getOrderNumber"),
                        "com.thirdparty.Customer::*" to ReceiverSpec.FieldSpec("customerId"),
                    ),
                )
                .methods(
                    mapOf(
                        "com.thirdparty.OrderService::process" to listOf(ParamRef.ByName("orderId"), ParamRef.ByIndex(2)),
                    ),
                )
                .frameFormat("\$class.\$method\$receiver\$params")
                .receiverFormat("<\$value>")
                .paramsFormat("[\$name: \$value; ...]")
                .maxValueLength(32)
                .debug(true)
                .inPlaceModification(true)
                .build(),
            config,
        )
        assertEquals(listOf(ParamRef.ByName("orderId"), ParamRef.ByIndex(2)), config.paramRefs("com.thirdparty.OrderService", "process"))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.hafnium.Legacy", "run"))
        assertTrue(config.readsAnnotations("com.hafnium.Legacy") { false })
        assertFalse(config.readsAnnotations("com.hafnium.generated.Legacy") { false })
        assertFalse(config.readsAnnotations("com.thirdparty.Order") { false })
        assertEquals("com.hafnium.generated.**=false, com.hafnium.**=true", config.annotationsDescription())
        assertTrue(config.hasAugmentEntries())
    }

    @Test
    fun `the system property overrides inPlaceModification of the file`() {
        val property = AugmentorConfig.IN_PLACE_MODIFICATION_PROPERTY
        try {
            System.setProperty(property, "false")
            assertFalse(parse("").inPlaceModification())
            System.setProperty(property, "true")
            assertTrue(parse("settings.inPlaceModification = false").inPlaceModification())
            System.setProperty(property, "false")
            assertFalse(parse("settings.inPlaceModification = true").inPlaceModification())
        } finally {
            System.clearProperty(property)
        }
        assertTrue(parse("settings.inPlaceModification = true").inPlaceModification())
    }

    @Test
    fun `dotted keys and inline tables are the same as sections`() {
        val sections = parse(
            """
            [augment.receiver]
            "com.acme.**::*" = "id"
            [augment.annotations]
            "com.acme.**" = true
            [augment]
            maxValueLength = 20
            """,
        )
        assertEquals(
            sections,
            parse("augment.receiver.\"com.acme.**::*\" = \"id\"\naugment.annotations.\"com.acme.**\" = true\naugment.maxValueLength = 20"),
        )
        assertEquals(
            sections,
            parse("augment = { receiver = { \"com.acme.**::*\" = \"id\" }, annotations = { \"com.acme.**\" = true }, maxValueLength = 20 }"),
        )
    }

    @Test
    fun `receiver and params keys contain double colon, so they are quoted`() {
        // An unquoted key names no method.
        assertTrue(error("[augment.receiver]\ncom.acme.Order = \"orderId\"").contains("keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\ncom.acme.OrderService.process = \"0\"").contains("keys must name a class and its methods separated by ::"))
    }

    @Test
    fun `a class or method configured twice is rejected with both lines`() {
        assertEquals(
            "test.toml, line 3: 'com.acme.Order::a, b' is configured twice; it is already configured on line 2 " +
                "(keys that differ in spaces or in the order of their methods name the same methods)",
            error("[augment.receiver]\n\"com.acme.Order::a,b\" = \"a\"\n\"com.acme.Order::a, b\" = \"b\""),
        )
        assertEquals(
            "test.toml, line 3: 'com.acme.Order' is configured twice; it is already configured on line 2 " +
                "(quoted and unquoted keys name the same class)",
            error("[augment.exceptions]\n\"com.acme.Order\" = true\ncom.acme.Order = false"),
        )
    }

    @Test
    fun `receiver entries, the first matching entry wins`() {
        val config = parse(
            """
            [augment.receiver]
            "com.hafnium.**::*" = "id"
            "com.acme.Order::*" = "orderId"
            "com.acme.legacy.*::*" = "getKey()"
            "com.acme.**::*" = "code"
            "com.acme.Order?::*" = "number"
            "com.acme.legacy.Thing::*" = "never"
            """,
        )
        val code = ReceiverSpec.FieldSpec("code")
        fun spec(className: String) = config.entryOf(className)?.spec()

        assertEquals(ReceiverSpec.FieldSpec("id"), spec("com.hafnium.ObjectClass"))
        assertEquals(ReceiverSpec.FieldSpec("id"), spec("com.hafnium.deep.pkg.ObjectClass\$Inner"))
        assertNull(spec("com.hafniumx.Other"))
        assertNull(spec("comXhafnium.Other"))
        // '*' stays within one package segment.
        assertEquals(ReceiverSpec.MethodSpec("getKey"), spec("com.acme.legacy.Other"))
        assertEquals(code, spec("com.acme.legacy.sub.Thing"))
        // The first entry wins, however specific the later ones are.
        assertEquals(ReceiverSpec.FieldSpec("orderId"), spec("com.acme.Order"))
        assertEquals(code, spec("com.acme.Orders"))
        assertEquals(ReceiverSpec.MethodSpec("getKey"), spec("com.acme.legacy.Thing"))
        assertEquals("com.acme.legacy.*::*", config.entryOf("com.acme.legacy.Thing")!!.key())
        assertTrue(config.entryOf("com.acme.legacy.Thing")!!.isPattern())
        assertFalse(config.entryOf("com.acme.Order")!!.isPattern())

        assertEquals(code, parse("augment.receiver.\"**::*\" = \"code\"").entryOf("any.pkg.Class")?.spec())
    }

    interface Identified

    open class Entity : Identified

    class Customer : Entity()

    @Test
    fun `a class name with plus matches its subclasses, without plus the class only, a pattern the class name only`() {
        val entity = Entity::class.java.name
        val identified = Identified::class.java.name
        val config = parse(
            """
            [augment.receiver]
            "${entity.dropLast(1)}?::*" = "-"
            "$entity+::*" = "id"
            "$identified+::*" = "key()"
            """,
        )
        // The pattern matches Entity only; the class name with + matches Entity's subclasses too.
        assertEquals(ReceiverSpec.Excluded(), config.entryOf(Entity::class.java)!!.spec())
        assertEquals(ReceiverSpec.FieldSpec("id"), config.entryOf(Customer::class.java)!!.spec())
        assertEquals("$entity+::*", config.entryOf(Customer::class.java)!!.key())
        // An interface's name with + matches the interface and the classes implementing it.
        assertEquals(ReceiverSpec.MethodSpec("key"), config.entryOf(Identified::class.java)!!.spec())
        assertEquals(ReceiverSpec.MethodSpec("key"), parse("[augment.receiver]\n\"$identified+::*\" = \"key()\"").entryOf(Customer::class.java)!!.spec())
        assertNull(config.entryOf(String::class.java))
        // Without + the class only.
        val exact = parse("[augment.receiver]\n\"$entity::*\" = \"id\"\n\"$identified::*\" = \"key()\"")
        assertEquals(ReceiverSpec.FieldSpec("id"), exact.entryOf(Entity::class.java)!!.spec())
        assertNull(exact.entryOf(Customer::class.java))
        assertEquals(ReceiverSpec.MethodSpec("key"), exact.entryOf(Identified::class.java)!!.spec())
        // By name: the predicate says which supertypes the class has.
        assertEquals(ReceiverSpec.FieldSpec("id"), config.classEntries("com.acme.Other") { it == "com.acme.Other" || it == entity }.first().spec())
        // + needs a class name: a pattern matches class names only.
        assertTrue(error("[augment.receiver]\n\"com.acme.*+::*\" = \"id\"").contains("+ after the class includes its subclasses, so it needs a class name without wildcards"))
        assertTrue(AugmentorConfig.isA(Customer::class.java, identified))
        assertFalse(AugmentorConfig.isA(Entity::class.java, Customer::class.java.name))
    }

    @Test
    fun `a receiver entry may list several fields and methods`() {
        val config = parse("[augment.receiver]\n\"com.acme.OrderLine::*\" = \"tenant, lineId()\"")
        assertEquals(
            ReceiverSpec.MemberList(listOf(ReceiverSpec.FieldSpec("tenant"), ReceiverSpec.MethodSpec("lineId"))),
            config.entryOf("com.acme.OrderLine")?.spec(),
        )
        assertEquals("com.acme.OrderLine::*=[tenant, lineId()]", config.classesDescription())
        assertEquals("test.toml, line 2: must name at least one field or method", error("[augment.receiver]\n\"com.acme.A::*\" = \" \""))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::*\" = \"id,,code\"").contains("has an empty item in 'id,,code'"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::*\" = [\"id\"]").contains("must be a string"))
        // Spaces around the items do not matter.
        assertEquals(
            ReceiverSpec.MemberList(listOf(ReceiverSpec.FieldSpec("tenant"), ReceiverSpec.MethodSpec("lineId"))),
            parse("[augment.receiver]\n\"com.acme.OrderLine::*\" = \"tenant,lineId() \"").entryOf("com.acme.OrderLine")?.spec(),
        )
        assertTrue(error("[augment.receiver]\n\"com.acme.A::*\" = \"id, @\"").contains("invalid receiver member '@'"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::*\" = \"@\"").contains("\"@\" is not allowed here"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::*\" = \"id, 1\"").contains("invalid receiver member '1'"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::*\" = \"id, id()\"").contains("'id()' is listed twice"))
    }

    @Test
    fun `receiver keys may name methods after double colon, and the first entry that applies to a method decides`() {
        val config = parse(
            """
            [augment.receiver]
            "com.acme.Order::ship,cancel*" = "id, code()"
            "com.acme.Order::*" = "number"
            "com.acme.Customer::*" = "customerId"
            """,
        )
        assertEquals(
            listOf("com.acme.Order::ship, cancel*", "com.acme.Order::*", "com.acme.Customer::*"),
            config.classes().keys.toList(),
        )
        val order = config.classEntry("com.acme.Order::ship, cancel*")!!
        assertEquals(ReceiverSpec.MemberList(listOf(ReceiverSpec.FieldSpec("id"), ReceiverSpec.MethodSpec("code"))), order.spec())
        assertEquals("com.acme.Order", order.className())
        assertEquals(listOf("ship", "cancel*"), order.methods())
        assertTrue(order.appliesTo("ship"))
        assertTrue(order.appliesTo("cancelAll"))
        assertFalse(order.appliesTo("process"))
        val entries = config.classEntries("com.acme.Order") { it == "com.acme.Order" }
        assertEquals(listOf("com.acme.Order::ship, cancel*", "com.acme.Order::*"), entries.map { it.key() })
        assertEquals("com.acme.Order::ship, cancel*", AugmentorConfig.firstApplying(entries, "ship")!!.key())
        assertEquals("com.acme.Order::*", AugmentorConfig.firstApplying(entries, "process")!!.key())
        // "*" for all methods.
        assertEquals(listOf("*"), config.classEntry("com.acme.Customer::*")!!.methods())
        assertTrue(config.classEntry("com.acme.Customer::*")!!.appliesTo("anything"))
        assertNull(config.classEntry("com.acme.Customer"))
        assertEquals(
            "com.acme.Order::ship, cancel*=[id, code()], com.acme.Order::*=number, com.acme.Customer::*=customerId",
            config.classesDescription(),
        )
        // "@" names no methods: [augment.annotations] says where the annotations are read.
        assertTrue(error("[augment.receiver]\n\"com.acme.A::@\" = \"id\"").contains("\"@\" is not allowed here"))

        // The methods are mandatory.
        assertTrue(error("[augment.receiver]\n\"com.acme.A\" = \"id\"").contains("[augment.receiver] keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::\" = \"id\"").contains("[augment.receiver] keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::a,,b\" = \"id\"").contains("keys must name a class and its methods"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::ship()\" = \"id\"").contains("keys must name a class and its methods"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::a::b\" = \"id\"").contains("keys must name a class and its methods"))
        // Constructors show no receiver members.
        val constructor = error("[augment.receiver]\n\"com.acme.A::<init>\" = \"id\"")
        assertTrue(constructor.contains("keys must name a class and its methods") && !constructor.contains("<init>"), constructor)
        assertTrue(error("[augment.receiver]\n\"::a\" = \"id\"").contains("keys must name a class and its methods"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::a, a\" = \"id\"").contains("'a' is listed twice"))
        // The methods do not go in the value.
        assertTrue(error("[augment.receiver]\n\"com.acme.A::*\" = \"id :: a\"").contains("the methods go in the key"))
    }

    @Test
    fun `params keys separate class and method with double colon, and the first matching entry decides`() {
        val config = parse(
            """
            [augment.params]
            "com.acme.Order::ship" = "to"
            "com.acme.**::pay" = "-"
            "com.acme.**::*" = "1"
            """,
        )
        assertEquals(listOf(ParamRef.ByName("to")), config.paramRefs("com.acme.Order", "ship"))
        assertEquals(listOf(ParamRef.ByIndex(1)), config.paramRefs("com.acme.Order", "<init>"))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.acme.Invoice", "pay"))
        assertEquals(listOf(ParamRef.ByIndex(1)), config.paramRefs("com.acme.Invoice", "print"))
        // "-" is an entry: the annotations are not used; without a matching entry they may be.
        assertEquals(listOf(ParamRef.Excluded()), config.paramsEntry("com.acme.Invoice", { false }, "pay"))
        assertNull(config.paramsEntry("com.other.Invoice", { false }, "pay"))
        assertTrue(config.hasParamEntries("com.acme.Order"))
        assertEquals("com.acme.Order::ship[to], com.acme.**::pay[-], com.acme.**::*[1]", config.methodsDescription())
        assertTrue(error("[augment.params]\n\"com.acme.Order::@x\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::@\" = \"0\"").contains("\"@\" is not allowed here"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::*\" = \"@\"").contains("\"@\" is not allowed here"))
        assertTrue(error("[augment.params]\n\"com.acme.Order.ship\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"::ship\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::a::b\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
    }

    @Test
    fun `a params key may list several methods after double colon`() {
        val config = parse(
            """
            [augment.params]
            "com.acme.Order::ship,cancel* , pay" = "0"
            "com.acme.Order::*" = "1"
            """,
        )
        // The key is kept with its methods separated by ", ".
        assertEquals(listOf("com.acme.Order::ship, cancel*, pay", "com.acme.Order::*"), config.methods().keys.toList())
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Order", "ship"))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Order", "cancelAll"))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Order", "pay"))
        assertEquals(listOf(ParamRef.ByIndex(1)), config.paramRefs("com.acme.Order", "process"))
        val key = AugmentorConfig.EntryKey("com.acme.Order+::ship, cancel*, pay")
        assertEquals(listOf("ship", "cancel*", "pay"), key.methods())
        assertEquals("com.acme.Order", key.className())
        assertTrue(key.subclasses())
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship,,cancel\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship,\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship, ship\" = \"0\"").contains("'ship' is listed twice"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship\" = \"0\"\n\"com.acme.Order::ship \" = \"1\"").contains("is configured twice"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship, cancel\" = \"0\"\n\"com.acme.Order::cancel,ship\" = \"1\"")
            .contains("'com.acme.Order::cancel, ship' is configured twice; it is already configured on line 2"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship\" = \"order, order\"").contains("'order' is listed twice"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship\" = \"email, email#\"").contains("'email' is listed twice"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship\" = \"1, 1#\"").contains("'1' is listed twice"))
        // A name and an index of the same parameter cannot be told apart here: shown once
        AugmentorConfig.parse("[augment.params]\n\"com.acme.Order::ship\" = \"order, 0\"")
    }

    @Test
    fun `syntax errors report their position`() {
        val message = error("[augment]\nmaxValueLength = ")
        assertTrue(message.startsWith("Invalid TOML in test.toml:"), message)
        assertTrue(message.contains("line"), message)
    }

    @Test
    fun `invalid values name the key and its line`() {
        assertEquals(
            "test.toml, line 3: maxValueLength must be between 2 and 10000, was 1",
            error("settings.debug = false\n[augment]\nmaxValueLength = 1"),
        )
        assertTrue(error("augment.maxValueLength = \"64\"").contains("'augment.maxValueLength' must be an integer"))
        assertTrue(error("augment.maxValueLength = 1.5").contains("'augment.maxValueLength' must be an integer"))
        assertTrue(error("[settings]\ndebug = \"yes\"").contains("'settings.debug' must be true or false"))
        assertTrue(error("[settings]\ninPlaceModification = 1").contains("'settings.inPlaceModification' must be true or false"))
        assertTrue(error("settings = 1").contains("'settings' must be a table"))
        assertTrue(error("[settings]\nmaxValueLength = 10").contains("unknown key 'maxValueLength' in [settings]"))
        // Before the [settings] table, they were keys of their own.
        assertEquals("test.toml, line 1: 'debug' belongs in the [settings] table", error("debug = true"))
        assertEquals("test.toml, line 1: 'inPlaceModification' belongs in the [settings] table", error("inPlaceModification = false"))
        assertTrue(error("augment = \"x\"").contains("'augment' must be a table"))
        assertTrue(error("[augment]\nreceiver = \"x\"").contains("'augment.receiver' must be a table"))
    }

    @Test
    fun `invalid templates are rejected with their line`() {
        assertEquals(
            "test.toml, line 3: paramsFormat must not contain '(' (at position 0), because IDEs find a frame's file by the " +
                "'(File.java:12)' at its end; use e.g. '{' and '}' or '[' and ']'; was '(\$name; ...)'",
            error("[augment]\nmaxValueLength = 32\nparamsFormat = \"(\$name; ...)\""),
        )
        assertTrue(error("[augment]\nframeFormat = \"\$class#\$method\"").startsWith("test.toml, line 2: frameFormat must contain"))
        assertTrue(error("[augment]\n\nreceiverFormat = \"\$nam\"").startsWith("test.toml, line 3: receiverFormat uses unknown placeholder"))
    }

    @Test
    fun `unknown keys are rejected, in every section`() {
        assertEquals(
            "test.toml, line 2: unknown key 'frame' in [augment]; allowed: frameFormat, receiverFormat, paramsFormat, maxValueLength, " +
                "exceptions, annotations, receiver, params",
            error("[augment]\nframe = \"\$class.\$method\""),
        )
        assertTrue(error("[other]\nx = 1").contains("unknown key 'other'; allowed: settings, augment"))
        // Keys in the wrong place are rejected, not silently ignored.
        assertTrue(error("maxValueLength = 64").contains("unknown key 'maxValueLength'; allowed: settings, augment"))
        assertTrue(error("[augment.other]\nx = 1").contains("unknown key 'other' in [augment]"))
    }

    @Test
    fun `invalid classes and methods entries`() {
        assertEquals(
            "test.toml, line 3: invalid receiver member 'get-id()': use a field name (e.g. \"orderId\") or a method (e.g. \"getOrderId()\"), " +
                "several separated by commas",
            error("settings.debug = false\n[augment.receiver]\n\"com.acme.Order::*\" = \"get-id()\""),
        )
        assertEquals(
            "test.toml, line 2: must be a string: a field name (e.g. \"orderId\"), a method (e.g. \"getOrderId()\"), several of them " +
                "separated by commas (e.g. \"tenant, getOrderId()\"), or \"-\" for no receiver members, was 5",
            error("[augment.receiver]\n\"com.acme.Order::*\" = 5"),
        )
        assertTrue(error("[augment.receiver]\n\"com.acme.Order::*\" = \"@id\"").contains("invalid receiver member '@id'"))
        assertEquals(
            "test.toml, line 2: [augment.receiver] keys must name a class and its methods separated by ::, e.g. " +
                "\"com.acme.Order::ship, cancel*\" or \"com.acme.Order::*\"; + after the class name includes its subclasses " +
                "(\"com.acme.Order+::*\"), a class pattern matches class names only (\"com.acme.**::*\"); " +
                "allowed are letters, digits, _, \$ and the wildcards * (within a package or name), ** (across packages) and ?",
            error("[augment.receiver]\n\"com.acme.Ord+er::*\" = \"id\""),
        )
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = 0").contains("must be a string of parameter names and indexes"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = [\"order\"]").contains("must be a string"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"\"").contains("must name at least one parameter"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"order,\"").contains("has an empty item in 'order,'"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"#1\"").contains("invalid parameter '#1'"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"-1\"").contains("invalid parameter '-1'"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"x, @\"").contains("invalid parameter '@'"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"@\"").contains("\"@\" is not allowed here"))
        assertTrue(error("[augment.params]\nOrder = \"id\"").contains("[augment.params] keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::<clinit>\" = \"0\"").contains("<init> stands for the constructors"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::<init>x\" = \"0\"").contains("<init> stands for the constructors"))
    }

    @Test
    fun `constructor entries end in init, and wildcards in the method name match constructors too`() {
        val config = parse(
            """
            [augment.params]
            "com.acme.Order::<init>" = "id"
            "com.acme.*::*" = "0"
            "com.acme.billing.*::<init>" = "-"
            """,
        )
        assertEquals(listOf(ParamRef.ByName("id")), config.paramRefs("com.acme.Order", "<init>"))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Order", "process"))
        // '*' matches <init> like any method name.
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Invoice", "<init>"))
        // "com.acme.*.*" does not match the classes in com.acme.billing: '*' stays within one package segment.
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.acme.billing.Invoice", "<init>"))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.acme.billing.Invoice", "send"))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.other.Invoice", "<init>"))
    }

    @Test
    fun `whether params entries match a class at all`() {
        val config = parse(
            """
            [augment.params]
            "com.acme.Order::process" = "0"
            "com.acme.Order::cancel" = "-"
            "com.acme.billing.*::send" = "to"
            "com.acme.legacy.**::*" = "-"
            "com.acme.Shipment::ship" = "-"
            """,
        )
        assertTrue(config.hasParamEntries("com.acme.Order"))
        assertTrue(config.hasParamEntries("com.acme.billing.Invoice"))
        // Not a prefix of the class name, a nested class or a "-" entry.
        assertFalse(config.hasParamEntries("com.acme.Ord"))
        assertFalse(config.hasParamEntries("com.acme.Order.Line"))
        assertFalse(config.hasParamEntries("com.acme.billing.sub.Invoice"))
        assertFalse(config.hasParamEntries("com.acme.legacy.Old"))
        assertFalse(config.hasParamEntries("com.acme.Shipment"))
    }

    @Test
    fun `a params class name matches the declaring class only, with plus its subclasses too`() {
        val entity = Entity::class.java.name
        val customer = Customer::class.java.name
        val config = parse(
            """
            [augment.params]
            "$entity::save" = "0"
            "$entity+::load" = "1"
            """,
        )
        val isEntity = { name: String -> AugmentorConfig.isA(Entity::class.java, name) }
        val isCustomer = { name: String -> AugmentorConfig.isA(Customer::class.java, name) }
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs(entity, isEntity, "save"))
        assertEquals(emptyList<ParamRef>(), config.paramRefs(customer, isCustomer, "save"))
        assertEquals(listOf(ParamRef.ByIndex(1)), config.paramRefs(customer, isCustomer, "load"))
        assertTrue(config.hasParamEntries(customer, isCustomer))
        assertFalse(parse("[augment.params]\n\"$entity::save\" = \"0\"").hasParamEntries(customer, isCustomer))
    }

    @Test
    fun `method entries with wildcards`() {
        val config = parse(
            """
            [augment.params]
            "com.thirdparty.OrderService::process" = "order"
            "com.thirdparty.OrderService::*" = "2"
            "com.thirdparty.Inventory*::*" = "sku, count"
            "com.thirdparty.**.*Repository::find*" = "0"
            "com.acme.Outer${'$'}Inner::ru?" = "x"
            """,
        )
        val inventory = listOf(ParamRef.ByName("sku"), ParamRef.ByName("count"))
        assertEquals(inventory, config.methods()["com.thirdparty.Inventory*::*"])
        // The exact entry, which comes first, decides alone; the wildcard entry applies to the other methods.
        assertEquals(listOf(ParamRef.ByName("order")), config.paramRefs("com.thirdparty.OrderService", "process"))
        assertEquals(listOf(ParamRef.ByIndex(2)), config.paramRefs("com.thirdparty.OrderService", "cancel"))
        assertEquals(inventory, config.paramRefs("com.thirdparty.InventoryService", "reserve"))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.thirdparty.db.OrderRepository", "findById"))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.thirdparty.db.sql.OrderRepository", "findAll"))
        // '.**.' stands for at least one package segment.
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.OrderRepository", "findAll"))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.db.OrderRepository", "save"))
        // '*' stays within one package segment in the class part.
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.sub.InventoryService", "reserve"))
        assertEquals(listOf(ParamRef.ByName("x")), config.paramRefs("com.acme.Outer\$Inner", "run"))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.acme.Outer\$Inner", "runs"))

        assertTrue(AugmentorConfig.isPattern("com.thirdparty.OrderService.*"))
        assertFalse(AugmentorConfig.isPattern("com.thirdparty.OrderService.process"))
    }

    @Test
    fun `the first matching entry decides in both tables, '-' included`() {
        val config = parse(
            """
            [augment.receiver]
            "com.acme.generated.Keep::*" = "id"
            "com.acme.generated.**::*" = "-"
            "com.acme.**::*" = "code"

            [augment.params]
            "com.thirdparty.audit.AuditService::log" = "reason"
            "com.thirdparty.audit.AuditService::*" = "-"
            "com.thirdparty.billing.BillingService::refund" = "-"
            "com.thirdparty.**.*Service::*" = "0"
            "com.thirdparty.billing.BillingService::charge" = "1"
            "com.acme.generated.Gen::run" = "1"
            "com.acme.**::*" = "0"
            """,
        )
        // [augment.receiver]: the first matching entry decides, "-" included.
        assertEquals(ReceiverSpec.FieldSpec("code"), config.entryOf("com.acme.Order")!!.spec())
        assertEquals(ReceiverSpec.Excluded(), config.entryOf("com.acme.generated.Gen")!!.spec())
        assertEquals(ReceiverSpec.FieldSpec("id"), config.entryOf("com.acme.generated.Keep")!!.spec())

        // [augment.params]: the first matching entry decides, "-" included; entries are not combined.
        // The exact entry for charge comes after a matching pattern, so it never applies.
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.thirdparty.billing.BillingService", "charge"))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.billing.BillingService", "refund"))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.audit.AuditService", "purge"))
        assertEquals(listOf(ParamRef.ByName("reason")), config.paramRefs("com.thirdparty.audit.AuditService", "log"))

        // The tables are independent: the "-" class entry of Gen does not affect its parameters.
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.generated.Gen", "stop"))
        assertEquals(listOf(ParamRef.ByIndex(1)), config.paramRefs("com.acme.generated.Gen", "run"))

        assertTrue(config.classesDescription().contains("com.acme.generated.**::*=-"), config.classesDescription())
        assertTrue(config.methodsDescription().contains("com.thirdparty.audit.AuditService::*[-]"), config.methodsDescription())
        assertTrue(config.hasAugmentEntries())
        val onlyExcluded = parse("[augment.receiver]\n\"com.acme.**::*\" = \"-\"\n[augment.params]\n\"com.acme.A::b\" = \"-\"")
        assertFalse(onlyExcluded.hasAugmentEntries())
    }

    @Test
    fun `invalid annotations entries`() {
        assertTrue(
            error("[augment.annotations]\n\"com.acme.**\" = \"@\"")
                .contains("must be true to read the annotations of this class or class pattern, or false not to, was @"),
        )
        assertTrue(error("[augment.annotations]\n\"com.acme.**::*\" = true").contains("[augment.annotations]"))
        // Like [augment.exceptions]: the first matching entry decides, + includes the subclasses.
        val config = parse("[augment.annotations]\n\"com.acme.Old\" = false\n\"com.acme.*\" = true\n\"java.lang.Runnable+\" = true")
        assertFalse(config.readsAnnotations("com.acme.Old") { false })
        assertTrue(config.readsAnnotations("com.acme.New") { false })
        assertTrue(config.readsAnnotations(Thread::class.java))
        assertFalse(config.readsAnnotations("com.other.Task") { false })
    }

    @Test
    fun `invalid method entries`() {
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = true").contains("(# hashes the value), or \"-\" for none, was true"))
        // No wildcard for the parameters: they are named, or selected by the annotations.
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"*\"").contains("invalid parameter '*'"))
        // The JVM allows at most 255 parameters, so the last index is 254.
        assertEquals(listOf(ParamRef.ByIndex(254)), parse("augment.params.\"com.acme.Order::process\" = \"254\"").methods()["com.acme.Order::process"])
        assertTrue(
            error("[augment.params]\n\"com.acme.Order::process\" = \"255\"")
                .contains("invalid parameter '255': use a parameter name or a 0-based index from 0 to 254"),
        )
        assertEquals(
            "test.toml, line 2: [augment.params] keys must name a class and its methods separated by ::, e.g. " +
                "\"com.acme.Order::ship, cancel*\" or \"com.acme.Order::*\"; + after the class name includes its subclasses " +
                "(\"com.acme.Order+::*\"), a class pattern matches class names only (\"com.acme.**::*\"), <init> stands for the " +
                "constructors; " +
                "allowed are letters, digits, _, \$ and the wildcards * (within a package or name), ** (across packages) and ?",
            error("[augment.params]\n\"com.acme.Ord+er::process\" = \"0\""),
        )
        assertTrue(error("[augment.params]\n\"com.acme.Order::\" = \"0\"").contains("keys must name a class and its methods"))
        assertTrue(error("[augment.params]\n\"com..Order::run\" = \"0\"").contains("keys must name a class and its methods"))
        // maxParams was removed: all selected parameters are shown.
        assertTrue(error("[augment]\nmaxParams = 4").startsWith("test.toml, line 2: unknown key 'maxParams' in [augment]"))
    }

    @Test
    fun `load reads toml files only`(@TempDir dir: Path) {
        val toml = dir.resolve("agent.toml")
        Files.writeString(toml, "augment.maxValueLength = 10")
        assertEquals(10, AugmentorConfig.global("config=$toml").maxValueLength())
        assertEquals(10, AugmentorConfig.global(toml.toString()).maxValueLength())
        assertEquals(10, AugmentorConfig.load(toml).maxValueLength())

        val properties = dir.resolve("agent.properties")
        Files.writeString(properties, "maxValueLength=10")
        val message = assertThrows<ConfigException> { AugmentorConfig.global("config=$properties") }.message!!
        assertTrue(message.contains("must be a TOML file ending in .toml"), message)

        assertTrue(assertThrows<ConfigException> { AugmentorConfig.global("config=${dir.resolve("missing.toml")}") }.message!!.contains("not found"))
        assertNull(AugmentorConfig.global(null))
    }

    @Test
    fun `without a file the built-in configuration augments all exceptions and reads the annotations of all classes`() {
        val config = AugmentorConfig.builtIn()
        // It lists every other key with its default value.
        assertEquals(
            AugmentorConfig.builder().exceptions(mapOf("java.lang.Exception+" to true)).annotations(mapOf("**" to true)).build(),
            config,
        )
        assertTrue(config.hasAugmentEntries())
        assertTrue(config.augments(IllegalStateException::class.java))
        assertFalse(config.augments(Error::class.java))
        assertEquals(mapOf("**" to true), config.annotations())
        assertTrue(config.readsAnnotations("com.acme.Order") { false })
        assertEquals("none", config.classesDescription())
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.acme.Order", "process"))
    }

    @Test
    fun `a # after a name or an index hashes the value`() {
        val config = parse(
            """
            [augment.params]
            "com.acme.User::login" = "user, email#, 2, 3#"
            """,
        )
        assertEquals(
            listOf(ParamRef.ByName("user"), ParamRef.ByName("email", true), ParamRef.ByIndex(2), ParamRef.ByIndex(3, true)),
            config.paramRefs("com.acme.User", "login"),
        )
        assertTrue(config.methodsDescription().contains("com.acme.User::login[user, email#, 2, 3#]"), config.methodsDescription())
    }

    @Test
    fun `invalid hashed entries`() {
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"order#?\"").contains("invalid parameter 'order#?'"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"256#\"").contains("invalid parameter '256#'"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"#\"").contains("invalid parameter '#'"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"@#\"").contains("invalid parameter '@#'"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"*#\"").contains("invalid parameter '*#'"))
        assertEquals(listOf(ParamRef.ByName("order", true)), parse("[augment.params]\n\"com.acme.Order::process\" = \"order#\"").methods()["com.acme.Order::process"])
    }

    open class Base : RuntimeException()

    class Derived : Base()

    @Test
    fun `exceptions entries, the first matching entry wins`() {
        // Without entries, no exception is augmented: missing and empty tables alike.
        for (none in listOf(AugmentorConfig(), parse(""), parse("[augment.exceptions]"), parse("augment.exceptions = {}"))) {
            assertTrue(none.exceptions().isEmpty())
            assertFalse(none.augments(Error::class.java))
            assertFalse(none.augments(RuntimeException::class.java))
            assertEquals("none", none.exceptionsDescription())
        }
        val all = parse("[augment.exceptions]\n\"**\" = true")
        assertTrue(all.augments(Error::class.java))
        assertTrue(all.augments(Derived::class.java))

        val config = parse(
            """
            [augment.exceptions]
            "java.io.FileNotFoundException+" = false
            "java.io.IOException+" = true
            "com.hafnium.stackaugmentor.runtime.config.AugmentorConfigTest${'$'}Ba?e" = true
            "java.util.concurrent.**" = false
            "java.**" = true
            """,
        )
        assertEquals(
            listOf("java.io.FileNotFoundException+", "java.io.IOException+", Base::class.java.name.dropLast(2) + "?e", "java.util.concurrent.**", "java.**"),
            config.exceptions().keys.toList(),
        )
        assertEquals(
            "java.io.FileNotFoundException+=false, java.io.IOException+=true, ${Base::class.java.name.dropLast(2)}?e=true, " +
                "java.util.concurrent.**=false, java.**=true",
            config.exceptionsDescription(),
        )
        assertFalse(config.augments(java.io.FileNotFoundException::class.java))
        assertTrue(config.augments(java.io.IOException::class.java))
        // A class name with + matches its subclasses, as instanceof does.
        assertTrue(config.augments(java.nio.file.NoSuchFileException::class.java))
        assertTrue(config.augments(Base::class.java))
        // A pattern matches the runtime class only, not its superclasses.
        assertFalse(config.augments(Derived::class.java))
        assertFalse(config.augments(java.util.concurrent.TimeoutException::class.java))
        assertTrue(config.augments(IllegalStateException::class.java))
        // No entry matches.
        assertFalse(config.augments(ConfigException::class.java))
        assertNotEquals(all, config)

        val withSubclasses = parse("[augment.exceptions]\n\"${Base::class.java.name}+\" = true")
        assertTrue(withSubclasses.augments(Derived::class.java))
        // Without + the class only.
        val exact = parse("[augment.exceptions]\n\"${Base::class.java.name}\" = true")
        assertTrue(exact.augments(Base::class.java))
        assertFalse(exact.augments(Derived::class.java))
    }

    @Test
    fun `invalid exceptions entries`() {
        assertTrue(error("[augment]\nexceptions = \"java.io.IOException\"").contains("'augment.exceptions' must be a table"))
        assertEquals(
            "test.toml, line 2: must be true to augment the exceptions of this class or class pattern, or false not to, was @",
            error("[augment.exceptions]\n\"java.io.IOException\" = \"@\""),
        )
        assertTrue(
            error("[augment.exceptions]\n\"java.io.IOException()\" = true")
                .contains("[augment.exceptions] keys must name a class or a class pattern"),
        )
        assertTrue(error("[augment.exceptions]\n\"java.io.*+\" = true").contains("+ after the class includes its subclasses, so it needs a class name"))
        assertTrue(error("[augment.exceptions]\n\"java.io.IOException::*\" = true").contains("[augment.exceptions] keys must name a class or a class pattern"))
        assertTrue(
            error("[augment.exceptions]\n\"a.B\" = true\na.B = false").contains("'a.B' is configured twice; it is already configured on line 2"),
        )
    }
}
