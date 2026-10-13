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

/** The first [augment.receiver] entry of a class, whatever its methods. */
private fun AugmentorConfig.entryOf(type: Class<*>) = classEntries(type).firstOrNull()

class AugmentorConfigTest {

    private fun parse(text: String) = AugmentorConfig.parse(text.trimIndent(), "test.toml")

    private fun error(text: String): String = assertThrows<ConfigException> { parse(text) }.message!!

    @Test
    fun defaults() {
        assertEquals(AugmentorConfig(), parse(""))
        assertEquals("\$class\$receiver.\$method\$params", AugmentorConfig().frameFormat())
        assertEquals("{\$name=\$id, ...}", AugmentorConfig().receiverFormat())
        assertEquals("{\$name=\$id, ...}", AugmentorConfig().paramsFormat())
        assertFalse(AugmentorConfig().hasAugmentEntries())
        assertNull(AugmentorConfig().entryOf("any.pkg.Class"))
        assertFalse(AugmentorConfig().inPlaceModification())
    }

    @Test
    fun `full configuration`() {
        val config = parse(
            """
            debug = true
            inPlaceModification = true

            [augment.receiver]
            "com.hafnium.**" = "@"
            "com.thirdparty.Order" = "getOrderNumber()"
            "com.thirdparty.Customer" = "customerId"

            [augment.params]
            "com.thirdparty.OrderService::process" = "orderId, 2"
            "com.hafnium.Legacy::*" = "@"

            [augment]
            frameFormat = "${'$'}class.${'$'}method${'$'}receiver${'$'}params"
            receiverFormat = "<${'$'}id>"
            paramsFormat = "[${'$'}name: ${'$'}id; ...]"
            maxIdLength = 32
            """,
        )
        assertEquals(
            AugmentorConfig.builder()
                .classes(
                    mapOf(
                        "com.hafnium.**" to IdSpec.Annotations(),
                        "com.thirdparty.Order" to IdSpec.MethodSpec("getOrderNumber"),
                        "com.thirdparty.Customer" to IdSpec.FieldSpec("customerId"),
                    ),
                )
                .methods(
                    mapOf(
                        "com.thirdparty.OrderService::process" to listOf(ParamRef.ByName("orderId"), ParamRef.ByIndex(2)),
                        "com.hafnium.Legacy::*" to listOf(ParamRef.Annotations()),
                    ),
                )
                .frameFormat("\$class.\$method\$receiver\$params")
                .receiverFormat("<\$id>")
                .paramsFormat("[\$name: \$id; ...]")
                .maxIdLength(32)
                .debug(true)
                .inPlaceModification(true)
                .build(),
            config,
        )
        assertEquals(listOf(ParamRef.ByName("orderId"), ParamRef.ByIndex(2)), config.paramRefs("com.thirdparty.OrderService", "process", false))
        assertEquals(listOf(ParamRef.Annotations()), config.paramRefs("com.hafnium.Legacy", "run", false))
        assertTrue(config.hasAugmentEntries())
    }

    @Test
    fun `the system property overrides inPlaceModification of the file`() {
        val property = AugmentorConfig.IN_PLACE_MODIFICATION_PROPERTY
        try {
            System.setProperty(property, "true")
            assertTrue(parse("").inPlaceModification())
            System.setProperty(property, "false")
            assertFalse(parse("inPlaceModification = true").inPlaceModification())
        } finally {
            System.clearProperty(property)
        }
        assertTrue(parse("inPlaceModification = true").inPlaceModification())
    }

    @Test
    fun `dotted keys and inline tables are the same as sections`() {
        val sections = parse(
            """
            [augment.receiver]
            "com.acme.**" = "@"
            [augment]
            maxIdLength = 20
            """,
        )
        assertEquals(sections, parse("augment.receiver.\"com.acme.**\" = \"@\"\naugment.maxIdLength = 20"))
        assertEquals(sections, parse("augment = { receiver = { \"com.acme.**\" = \"@\" }, maxIdLength = 20 }"))
    }

    @Test
    fun `unquoted class names are the same as quoted ones`() {
        val quoted = parse(
            """
            [augment.receiver]
            "com.acme.Order" = "orderId"
            [augment.params]
            "com.acme.OrderService::process" = "order"
            """,
        )
        val unquoted = parse(
            """
            [augment.receiver]
            com.acme.Order = "orderId"
            [augment.params]
            "com.acme.OrderService::process" = "order"
            """,
        )
        assertEquals(quoted, unquoted)
        // [augment.params] keys contain "::", so they are quoted: an unquoted key names no method.
        assertTrue(error("[augment.params]\ncom.acme.OrderService.process = \"0\"").contains("keys must name a class and its methods separated by ::"))
    }

    @Test
    fun `a class or method configured twice is rejected with both lines`() {
        assertEquals(
            "test.toml, line 3: 'com.acme.Order' is configured twice; it is already configured on line 2 " +
                "(quoted and unquoted keys name the same class)",
            error("[augment.receiver]\n\"com.acme.Order\" = \"a\"\ncom.acme.Order = \"b\""),
        )
    }

    @Test
    fun `receiver entries, the first matching entry wins`() {
        val config = parse(
            """
            [augment.receiver]
            "com.hafnium.**" = "@"
            "com.acme.Order" = "orderId"
            "com.acme.legacy.*" = "getKey()"
            "com.acme.**" = "@"
            "com.acme.Order?" = "number"
            "com.acme.legacy.Thing" = "never"
            """,
        )
        val annotations = IdSpec.Annotations()
        fun spec(className: String) = config.entryOf(className)?.spec()

        assertEquals(annotations, spec("com.hafnium.ObjectClass"))
        assertEquals(annotations, spec("com.hafnium.deep.pkg.ObjectClass\$Inner"))
        assertNull(spec("com.hafniumx.Other"))
        assertNull(spec("comXhafnium.Other"))
        // '*' stays within one package segment.
        assertEquals(IdSpec.MethodSpec("getKey"), spec("com.acme.legacy.Other"))
        assertEquals(annotations, spec("com.acme.legacy.sub.Thing"))
        // The first entry wins, however specific the later ones are.
        assertEquals(IdSpec.FieldSpec("orderId"), spec("com.acme.Order"))
        assertEquals(annotations, spec("com.acme.Orders"))
        assertEquals(IdSpec.MethodSpec("getKey"), spec("com.acme.legacy.Thing"))
        assertEquals("com.acme.legacy.*", config.entryOf("com.acme.legacy.Thing")!!.key())
        assertTrue(config.entryOf("com.acme.legacy.Thing")!!.isPattern())
        assertFalse(config.entryOf("com.acme.Order")!!.isPattern())

        assertEquals(annotations, parse("augment.receiver.\"**\" = \"@\"").entryOf("any.pkg.Class")?.spec())
    }

    interface Identified

    open class Entity : Identified

    class Customer : Entity()

    @Test
    fun `a receiver class name matches its subclasses, a pattern the class only`() {
        val entity = Entity::class.java.name
        val identified = Identified::class.java.name
        val config = parse(
            """
            [augment.receiver]
            "${entity.dropLast(1)}?" = "-"
            "$entity" = "id"
            "$identified" = "key()"
            """,
        )
        // The pattern matches Entity only; the class name matches Entity's subclasses too.
        assertEquals(IdSpec.Excluded(), config.entryOf(Entity::class.java)!!.spec())
        assertEquals(IdSpec.FieldSpec("id"), config.entryOf(Customer::class.java)!!.spec())
        assertEquals(entity, config.entryOf(Customer::class.java)!!.key())
        // An interface's name matches the interface and the classes implementing it.
        assertEquals(IdSpec.MethodSpec("key"), config.entryOf(Identified::class.java)!!.spec())
        assertEquals(IdSpec.MethodSpec("key"), parse("[augment.receiver]\n\"$identified\" = \"key()\"").entryOf(Customer::class.java)!!.spec())
        assertNull(config.entryOf(String::class.java))
        // By name: the predicate says which supertypes the class has.
        assertEquals(IdSpec.FieldSpec("id"), config.classEntries("com.acme.Other") { it == "com.acme.Other" || it == entity }.first().spec())
        assertTrue(AugmentorConfig.isA(Customer::class.java, identified))
        assertFalse(AugmentorConfig.isA(Entity::class.java, Customer::class.java.name))
    }

    @Test
    fun `a receiver entry may list several fields and methods`() {
        val config = parse("[augment.receiver]\n\"com.acme.OrderLine\" = \"tenant, lineId()\"")
        assertEquals(
            IdSpec.MemberList(listOf(IdSpec.FieldSpec("tenant"), IdSpec.MethodSpec("lineId"))),
            config.entryOf("com.acme.OrderLine")?.spec(),
        )
        assertEquals("com.acme.OrderLine=[tenant, lineId()]", config.classesDescription())
        assertEquals("test.toml, line 2: must name at least one field or method", error("[augment.receiver]\n\"com.acme.A\" = \" \""))
        assertTrue(error("[augment.receiver]\n\"com.acme.A\" = \"id,,code\"").contains("has an empty item in 'id,,code'"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A\" = [\"id\"]").contains("must be a string"))
        // Spaces around the items do not matter.
        assertEquals(
            IdSpec.MemberList(listOf(IdSpec.FieldSpec("tenant"), IdSpec.MethodSpec("lineId"))),
            parse("[augment.receiver]\n\"com.acme.OrderLine\" = \"tenant,lineId() \"").entryOf("com.acme.OrderLine")?.spec(),
        )
        assertTrue(error("[augment.receiver]\n\"com.acme.A\" = \"id, @\"").contains("invalid receiver id '@'"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A\" = \"id, 1\"").contains("invalid receiver id '1'"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A\" = \"id, id()\"").contains("'id()' is listed twice"))
    }

    @Test
    fun `receiver keys may name methods after double colon, and the first entry that applies to a method decides`() {
        val config = parse(
            """
            [augment.receiver]
            "com.acme.Order::ship,cancel*" = "id, code()"
            "com.acme.Order::@" = "@"
            "com.acme.Order" = "number"
            com.acme.Customer = "customerId"
            """,
        )
        assertEquals(
            listOf("com.acme.Order::ship, cancel*", "com.acme.Order::@", "com.acme.Order", "com.acme.Customer"),
            config.classes().keys.toList(),
        )
        val order = config.classEntry("com.acme.Order::ship, cancel*")!!
        assertEquals(IdSpec.MemberList(listOf(IdSpec.FieldSpec("id"), IdSpec.MethodSpec("code"))), order.spec())
        assertEquals("com.acme.Order", order.className())
        assertEquals(listOf("ship", "cancel*"), order.methods())
        assertTrue(order.appliesTo("ship", false))
        assertTrue(order.appliesTo("cancelAll", false))
        assertFalse(order.appliesTo("process", false))
        // Without "@" after ::, @StackTraceAugment does not count.
        assertFalse(order.appliesTo("process", true))
        val entries = config.classEntries("com.acme.Order") { it == "com.acme.Order" }
        assertEquals(listOf("com.acme.Order::ship, cancel*", "com.acme.Order::@", "com.acme.Order"), entries.map { it.key() })
        assertEquals("com.acme.Order::ship, cancel*", AugmentorConfig.firstApplying(entries, "ship", true)!!.key())
        assertEquals("com.acme.Order::@", AugmentorConfig.firstApplying(entries, "process", true)!!.key())
        assertEquals("com.acme.Order", AugmentorConfig.firstApplying(entries, "process", false)!!.key())
        // Without :: all methods.
        assertEquals(listOf<String>(), config.classEntry("com.acme.Customer")!!.methods())
        assertTrue(config.classEntry("com.acme.Customer")!!.appliesTo("anything", false))
        assertNull(config.classEntry("com.acme.Unknown"))
        assertEquals(
            "com.acme.Order::ship, cancel*=[id, code()], com.acme.Order::@=@, com.acme.Order=number, com.acme.Customer=customerId",
            config.classesDescription(),
        )

        assertTrue(error("[augment.receiver]\n\"com.acme.A::\" = \"id\"").contains("[augment.receiver] keys must name a class or a class pattern"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::a,,b\" = \"id\"").contains("optionally followed by :: and its methods"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::ship()\" = \"id\"").contains("optionally followed by :: and its methods"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::a::b\" = \"id\"").contains("optionally followed by :: and its methods"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::<init>\" = \"id\"").contains("optionally followed by :: and its methods"))
        assertTrue(error("[augment.receiver]\n\"::a\" = \"id\"").contains("optionally followed by :: and its methods"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::a, a\" = \"id\"").contains("'a' is listed twice"))
        assertTrue(error("[augment.receiver]\n\"com.acme.A::a,b\" = \"id\"\n\"com.acme.A::a, b\" = \"x\"").contains("is configured twice"))
        // The methods no longer go in the value.
        assertTrue(error("[augment.receiver]\n\"com.acme.A\" = \"id :: a\"").contains("the methods go in the key"))
    }

    @Test
    fun `params keys separate class and method with double colon, and '@' matches the annotated methods`() {
        val config = parse(
            """
            [augment.params]
            "com.acme.Order::@" = "0"
            "com.acme.Order::ship" = "to"
            "com.acme.**::@" = "-"
            "com.acme.**::*" = "1"
            """,
        )
        // "@" matches the methods with @StackTraceAugment only; the others fall through to the later entries.
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Order", "ship", true))
        assertEquals(listOf(ParamRef.ByName("to")), config.paramRefs("com.acme.Order", "ship", false))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Order", "<init>", true))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.acme.Invoice", "pay", true))
        assertEquals(listOf(ParamRef.ByIndex(1)), config.paramRefs("com.acme.Invoice", "pay", false))
        assertTrue(config.hasParamEntries("com.acme.Order"))
        assertEquals("com.acme.Order::@[#0], com.acme.Order::ship[to], com.acme.**::@[-], com.acme.**::*[#1]", config.methodsDescription())
        assertTrue(error("[augment.params]\n\"com.acme.Order::@x\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"com.acme.Order.ship\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"::ship\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::a::b\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
    }

    @Test
    fun `a params key may list several methods after double colon`() {
        val config = parse(
            """
            [augment.params]
            "com.acme.Order::ship,cancel* , @" = "0"
            "com.acme.Order::*" = "1"
            """,
        )
        // The key is kept with its methods separated by ", ".
        assertEquals(listOf("com.acme.Order::ship, cancel*, @", "com.acme.Order::*"), config.methods().keys.toList())
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Order", "ship", false))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Order", "cancelAll", false))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Order", "process", true))
        assertEquals(listOf(ParamRef.ByIndex(1)), config.paramRefs("com.acme.Order", "process", false))
        assertEquals(listOf("ship", "cancel*", "@"), AugmentorConfig.methodNames("com.acme.Order::ship, cancel*, @"))
        assertEquals("com.acme.Order", AugmentorConfig.className("com.acme.Order::ship, cancel*, @"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship,,cancel\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship,\" = \"0\"").contains("keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship, ship\" = \"0\"").contains("'ship' is listed twice"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::ship\" = \"0\"\n\"com.acme.Order::ship \" = \"1\"").contains("is configured twice"))
    }

    @Test
    fun `syntax errors report their position`() {
        val message = error("[augment]\nmaxIdLength = ")
        assertTrue(message.startsWith("Invalid TOML in test.toml:"), message)
        assertTrue(message.contains("line"), message)
    }

    @Test
    fun `invalid values name the key and its line`() {
        assertEquals(
            "test.toml, line 3: maxIdLength must be between 2 and 10000, was 1",
            error("debug = false\n[augment]\nmaxIdLength = 1"),
        )
        assertTrue(error("augment.maxIdLength = \"64\"").contains("'augment.maxIdLength' must be an integer"))
        assertTrue(error("augment.maxIdLength = 1.5").contains("'augment.maxIdLength' must be an integer"))
        assertTrue(error("debug = \"yes\"").contains("'debug' must be true or false"))
        assertTrue(error("inPlaceModification = 1").contains("'inPlaceModification' must be true or false"))
        assertTrue(error("augment = \"x\"").contains("'augment' must be a table"))
        assertTrue(error("[augment]\nreceiver = \"x\"").contains("'augment.receiver' must be a table"))
    }

    @Test
    fun `invalid templates are rejected with their line`() {
        assertEquals(
            "test.toml, line 3: paramsFormat must not contain '(' (at position 0), because IDEs find a frame's file by the " +
                "'(File.java:12)' at its end; use e.g. '{' and '}' or '[' and ']'; was '(\$name; ...)'",
            error("[augment]\nmaxIdLength = 32\nparamsFormat = \"(\$name; ...)\""),
        )
        assertTrue(error("[augment]\nframeFormat = \"\$class#\$method\"").startsWith("test.toml, line 2: frameFormat must contain"))
        assertTrue(error("[augment]\n\nreceiverFormat = \"\$nam\"").startsWith("test.toml, line 3: receiverFormat uses unknown placeholder"))
    }

    @Test
    fun `unknown keys are rejected, in every section`() {
        assertEquals(
            "test.toml, line 2: unknown key 'frame' in [augment]; allowed: frameFormat, receiverFormat, paramsFormat, maxIdLength, " +
                "exceptions, receiver, params",
            error("[augment]\nframe = \"\$class.\$method\""),
        )
        assertTrue(error("[other]\nx = 1").contains("unknown key 'other'; allowed: debug, inPlaceModification, augment"))
        // Keys in the wrong place are rejected, not silently ignored.
        assertTrue(error("maxIdLength = 64").contains("unknown key 'maxIdLength'; allowed: debug, inPlaceModification, augment"))
        assertTrue(error("[augment.other]\nx = 1").contains("unknown key 'other' in [augment]"))
    }

    @Test
    fun `invalid classes and methods entries`() {
        assertEquals(
            "test.toml, line 3: invalid receiver id 'get-id()': use a field name (e.g. \"orderId\") or a method (e.g. \"getOrderId()\"), " +
                "several separated by commas",
            error("debug = false\n[augment.receiver]\n\"com.acme.Order\" = \"get-id()\""),
        )
        assertEquals(
            "test.toml, line 2: must be a string: a field name (e.g. \"orderId\"), a method (e.g. \"getOrderId()\"), several of them " +
                "separated by commas (e.g. \"tenant, getOrderId()\"), \"@\" for its @StackTraceId members, or \"-\" for no receiver id, was 5",
            error("[augment.receiver]\n\"com.acme.Order\" = 5"),
        )
        assertTrue(error("[augment.receiver]\n\"com.acme.Order\" = \"@id\"").contains("invalid receiver id '@id'"))
        assertEquals(
            "test.toml, line 2: [augment.receiver] keys must name a class or a class pattern, e.g. \"com.acme.Order\" or \"com.acme.**\"; " +
                "allowed are letters, digits, _, \$ and the wildcards * (within a package or name), ** (across packages) and ?",
            error("[augment.receiver]\n\"com.acme.Ord+er\" = \"@\""),
        )
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = 0").contains("must be a string of parameter names and indexes"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = [\"order\"]").contains("must be a string"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"\"").contains("must name at least one parameter"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"order,\"").contains("has an empty item in 'order,'"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"#1\"").contains("invalid parameter '#1'"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"-1\"").contains("invalid parameter '-1'"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"x, @\"").contains("invalid parameter '@'"))
        assertTrue(error("[augment.params]\nOrder = \"id\"").contains("[augment.params] keys must name a class and its methods separated by ::"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::<clinit>\" = \"0\"").contains("<init> for the constructors"))
        assertTrue(error("[augment.params]\n\"com.acme.Order::<init>x\" = \"0\"").contains("<init> for the constructors"))
    }

    @Test
    fun `constructor entries end in init, and wildcards in the method name match constructors too`() {
        val config = parse(
            """
            [augment.params]
            "com.acme.Order::<init>" = "id"
            "com.acme.*::*" = "0"
            "com.acme.billing.*::<init>" = "@"
            """,
        )
        assertEquals(listOf(ParamRef.ByName("id")), config.paramRefs("com.acme.Order", "<init>", false))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Order", "process", false))
        // '*' matches <init> like any method name.
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.Invoice", "<init>", false))
        // "com.acme.*.*" does not match the classes in com.acme.billing: '*' stays within one package segment.
        assertEquals(listOf(ParamRef.Annotations()), config.paramRefs("com.acme.billing.Invoice", "<init>", false))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.acme.billing.Invoice", "send", false))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.other.Invoice", "<init>", false))
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
    fun `method entries with wildcards and annotations`() {
        val config = parse(
            """
            [augment.params]
            "com.thirdparty.OrderService::process" = "order"
            "com.thirdparty.OrderService::*" = "2"
            "com.thirdparty.Inventory*::*" = "sku, count"
            "com.thirdparty.**.*Repository::find*" = "0"
            "com.acme.Outer${'$'}Inner::ru?" = "x"
            "com.acme.Legacy::*" = "@"
            """,
        )
        val inventory = listOf(ParamRef.ByName("sku"), ParamRef.ByName("count"))
        assertEquals(inventory, config.methods()["com.thirdparty.Inventory*::*"])
        // The exact entry, which comes first, decides alone; the wildcard entry applies to the other methods.
        assertEquals(listOf(ParamRef.ByName("order")), config.paramRefs("com.thirdparty.OrderService", "process", false))
        assertEquals(listOf(ParamRef.ByIndex(2)), config.paramRefs("com.thirdparty.OrderService", "cancel", false))
        assertEquals(inventory, config.paramRefs("com.thirdparty.InventoryService", "reserve", false))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.thirdparty.db.OrderRepository", "findById", false))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.thirdparty.db.sql.OrderRepository", "findAll", false))
        // '.**.' stands for at least one package segment.
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.OrderRepository", "findAll", false))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.db.OrderRepository", "save", false))
        // '*' stays within one package segment in the class part.
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.sub.InventoryService", "reserve", false))
        assertEquals(listOf(ParamRef.ByName("x")), config.paramRefs("com.acme.Outer\$Inner", "run", false))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.acme.Outer\$Inner", "runs", false))
        assertEquals(listOf(ParamRef.Annotations()), config.paramRefs("com.acme.Legacy", "run", false))

        assertTrue(AugmentorConfig.isPattern("com.thirdparty.OrderService.*"))
        assertFalse(AugmentorConfig.isPattern("com.thirdparty.OrderService.process"))
    }

    @Test
    fun `the first matching entry decides in both tables, '-' included`() {
        val config = parse(
            """
            [augment.receiver]
            "com.acme.generated.Keep" = "id"
            "com.acme.generated.**" = "-"
            "com.acme.**" = "@"

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
        assertEquals(IdSpec.Annotations(), config.entryOf("com.acme.Order")!!.spec())
        assertEquals(IdSpec.Excluded(), config.entryOf("com.acme.generated.Gen")!!.spec())
        assertEquals(IdSpec.FieldSpec("id"), config.entryOf("com.acme.generated.Keep")!!.spec())

        // [augment.params]: the first matching entry decides, "-" included; entries are not combined.
        // The exact entry for charge comes after a matching pattern, so it never applies.
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.thirdparty.billing.BillingService", "charge", false))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.billing.BillingService", "refund", false))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.audit.AuditService", "purge", false))
        assertEquals(listOf(ParamRef.ByName("reason")), config.paramRefs("com.thirdparty.audit.AuditService", "log", false))

        // The tables are independent: the "-" class entry of Gen does not affect its parameters.
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.acme.generated.Gen", "stop", false))
        assertEquals(listOf(ParamRef.ByIndex(1)), config.paramRefs("com.acme.generated.Gen", "run", false))

        assertTrue(config.classesDescription().contains("com.acme.generated.**=-"), config.classesDescription())
        assertTrue(config.methodsDescription().contains("com.thirdparty.audit.AuditService::*[-]"), config.methodsDescription())
        assertTrue(config.hasAugmentEntries())
        val onlyExcluded = parse("[augment.receiver]\n\"com.acme.**\" = \"-\"\n[augment.params]\n\"com.acme.A::b\" = \"-\"")
        assertFalse(onlyExcluded.hasAugmentEntries())
    }

    @Test
    fun `invalid method entries`() {
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = true").contains("\"@\" for the method's annotations, or \"-\" for none, was true"))
        // No wildcard for the parameters: they are named, or selected by the annotations.
        assertTrue(error("[augment.params]\n\"com.acme.Order::process\" = \"*\"").contains("invalid parameter '*'"))
        // The JVM allows at most 255 parameters.
        assertEquals(listOf(ParamRef.ByIndex(255)), parse("augment.params.\"com.acme.Order::process\" = \"255\"").methods()["com.acme.Order::process"])
        assertTrue(
            error("[augment.params]\n\"com.acme.Order::process\" = \"256\"")
                .contains("invalid parameter '256': use a parameter name or a 0-based index from 0 to 255"),
        )
        assertEquals(
            "test.toml, line 2: [augment.params] keys must name a class and its methods separated by ::, e.g. " +
                "\"com.acme.OrderService::process\", \"com.acme.OrderService::process, cancel*\" or \"com.acme.OrderService::*\", " +
                "<init> for the constructors, @ for the methods annotated with @StackTraceAugment; " +
                "allowed are letters, digits, _, \$ and the wildcards * (within a package or name), ** (across packages) and ?",
            error("[augment.params]\n\"com.acme.Order+::process\" = \"@\""),
        )
        assertTrue(error("[augment.params]\n\"com.acme.Order::\" = \"@\"").contains("keys must name a class and its methods"))
        assertTrue(error("[augment.params]\n\"com..Order::run\" = \"@\"").contains("keys must name a class and its methods"))
        // maxParams was removed: all selected parameters are shown.
        assertTrue(error("[augment]\nmaxParams = 4").startsWith("test.toml, line 2: unknown key 'maxParams' in [augment]"))
    }

    @Test
    fun `load reads toml files only`(@TempDir dir: Path) {
        val toml = dir.resolve("agent.toml")
        Files.writeString(toml, "augment.maxIdLength = 10")
        assertEquals(10, AugmentorConfig.load("config=$toml").maxIdLength())
        assertEquals(10, AugmentorConfig.load(toml.toString()).maxIdLength())
        assertEquals(10, AugmentorConfig.load(toml).maxIdLength())

        val properties = dir.resolve("agent.properties")
        Files.writeString(properties, "maxIdLength=10")
        val message = assertThrows<ConfigException> { AugmentorConfig.load("config=$properties") }.message!!
        assertTrue(message.contains("must be a TOML file ending in .toml"), message)

        assertTrue(assertThrows<ConfigException> { AugmentorConfig.load("config=${dir.resolve("missing.toml")}") }.message!!.contains("not found"))
        assertEquals(AugmentorConfig(), AugmentorConfig.load(null as String?))
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
            config.paramRefs("com.acme.User", "login", false),
        )
        assertTrue(config.methodsDescription().contains("com.acme.User::login[user, email#, #2, #3#]"), config.methodsDescription())
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
        // Without entries, no exception gets ids: missing and empty tables alike.
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
            "java.io.FileNotFoundException" = false
            "java.io.IOException" = true
            "com.hafnium.stackaugmentor.runtime.config.AugmentorConfigTest${'$'}Ba?e" = true
            "java.util.concurrent.**" = false
            "java.**" = true
            """,
        )
        assertEquals(
            listOf("java.io.FileNotFoundException", "java.io.IOException", Base::class.java.name.dropLast(2) + "?e", "java.util.concurrent.**", "java.**"),
            config.exceptions().keys.toList(),
        )
        assertEquals(
            "java.io.FileNotFoundException=false, java.io.IOException=true, ${Base::class.java.name.dropLast(2)}?e=true, " +
                "java.util.concurrent.**=false, java.**=true",
            config.exceptionsDescription(),
        )
        assertFalse(config.augments(java.io.FileNotFoundException::class.java))
        assertTrue(config.augments(java.io.IOException::class.java))
        // A class name matches its subclasses, as instanceof does.
        assertTrue(config.augments(java.nio.file.NoSuchFileException::class.java))
        assertTrue(config.augments(Base::class.java))
        // A pattern matches the runtime class only, not its superclasses.
        assertFalse(config.augments(Derived::class.java))
        assertFalse(config.augments(java.util.concurrent.TimeoutException::class.java))
        assertTrue(config.augments(IllegalStateException::class.java))
        // No entry matches.
        assertFalse(config.augments(ConfigException::class.java))
        assertNotEquals(all, config)

        val byName = parse("[augment.exceptions]\n\"${Base::class.java.name}\" = true")
        assertTrue(byName.augments(Derived::class.java))
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
        assertTrue(
            error("[augment.exceptions]\n\"a.B\" = true\na.B = false").contains("'a.B' is configured twice; it is already configured on line 2"),
        )
    }
}
