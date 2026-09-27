package com.hafnium.stackaugmentor.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AugmentorConfigTest {

    private fun parse(text: String) = AugmentorConfig.parse(text.trimIndent(), "test.toml")

    private fun error(text: String): String = assertThrows<ConfigException> { parse(text) }.message!!

    @Test
    fun defaults() {
        assertEquals(AugmentorConfig(), parse(""))
        assertEquals("\$class\$receiver.\$method\$params", AugmentorConfig().frameFormat())
        assertEquals("{\$name=\$id}", AugmentorConfig().receiverFormat())
        assertEquals("{\$name=\$id, ...}", AugmentorConfig().paramsFormat())
        assertFalse(AugmentorConfig().hasInstrumentEntries())
        assertNull(AugmentorConfig().classEntry("any.pkg.Class"))
    }

    @Test
    fun `full configuration`() {
        val config = parse(
            """
            debug = true

            [instrument.classes]
            "com.hafnium.**" = "@"
            "com.thirdparty.Order" = "getOrderNumber()"
            "com.thirdparty.Customer" = "customerId"

            [instrument.methods]
            "com.thirdparty.OrderService.process" = ["orderId", 2]
            "com.hafnium.Legacy.*" = "@"

            [augment]
            frameFormat = "${'$'}class.${'$'}method${'$'}receiver${'$'}params"
            receiverFormat = "<${'$'}id>"
            paramsFormat = "(${'$'}name: ${'$'}id; ...)"
            maxIdLength = 32
            maxParams = 3
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
                        "com.thirdparty.OrderService.process" to listOf(ParamRef.ByName("orderId"), ParamRef.ByIndex(2)),
                        "com.hafnium.Legacy.*" to listOf(ParamRef.Annotations()),
                    ),
                )
                .frameFormat("\$class.\$method\$receiver\$params")
                .receiverFormat("<\$id>")
                .paramsFormat("(\$name: \$id; ...)")
                .maxIdLength(32)
                .maxParams(3)
                .debug(true)
                .build(),
            config,
        )
        assertEquals(listOf(ParamRef.ByName("orderId"), ParamRef.ByIndex(2)), config.paramRefs("com.thirdparty.OrderService", "process"))
        assertEquals(listOf(ParamRef.Annotations()), config.paramRefs("com.hafnium.Legacy", "run"))
        assertTrue(config.hasInstrumentEntries())
    }

    @Test
    fun `dotted keys and inline tables are the same as sections`() {
        val sections = parse(
            """
            [instrument.classes]
            "com.acme.**" = "@"
            [augment]
            maxIdLength = 20
            """,
        )
        assertEquals(sections, parse("instrument.classes.\"com.acme.**\" = \"@\"\naugment.maxIdLength = 20"))
        assertEquals(sections, parse("instrument = { classes = { \"com.acme.**\" = \"@\" } }\naugment = { maxIdLength = 20 }"))
    }

    @Test
    fun `unquoted class names are the same as quoted ones`() {
        val quoted = parse(
            """
            [instrument.classes]
            "com.acme.Order" = "orderId"
            [instrument.methods]
            "com.acme.OrderService.process" = ["order"]
            """,
        )
        val unquoted = parse(
            """
            [instrument.classes]
            com.acme.Order = "orderId"
            [instrument.methods]
            com.acme.OrderService.process = ["order"]
            """,
        )
        assertEquals(quoted, unquoted)
    }

    @Test
    fun `class entries and their specificity`() {
        val config = parse(
            """
            [instrument.classes]
            "com.hafnium.**" = "@"
            "com.acme.**" = "@"
            "com.acme.legacy.*" = "getKey()"
            "com.acme.Order" = "orderId"
            "com.acme.Order?" = "number"
            "com.tie.a*" = "first"
            "com.tie.*b" = "second"
            """,
        )
        val annotations = IdSpec.Annotations()
        fun spec(className: String) = config.classEntry(className)?.spec()

        assertEquals(annotations, spec("com.hafnium.ObjectClass"))
        assertEquals(annotations, spec("com.hafnium.deep.pkg.ObjectClass\$Inner"))
        assertNull(spec("com.hafniumx.Other"))
        assertNull(spec("comXhafnium.Other"))
        // Longer patterns are more specific: '*' stays within one package segment.
        assertEquals(IdSpec.MethodSpec("getKey"), spec("com.acme.legacy.Thing"))
        assertEquals(annotations, spec("com.acme.legacy.sub.Thing"))
        // An exact name beats every pattern.
        assertEquals(IdSpec.FieldSpec("orderId"), spec("com.acme.Order"))
        assertEquals(IdSpec.FieldSpec("number"), spec("com.acme.Orders"))
        // Equally specific patterns: the alphabetically first key.
        assertEquals("com.tie.*b", config.classEntry("com.tie.ab")!!.key())
        assertTrue(config.classEntry("com.tie.ab")!!.isPattern())
        assertFalse(config.classEntry("com.acme.Order")!!.isPattern())

        assertEquals(annotations, parse("instrument.classes.\"**\" = \"@\"").classEntry("any.pkg.Class")?.spec())
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
        assertTrue(error("instrument = \"x\"").contains("'instrument' must be a table"))
        assertTrue(error("[instrument]\nclasses = \"x\"").contains("'instrument.classes' must be a table"))
    }

    @Test
    fun `unknown keys are rejected, in every section`() {
        assertEquals(
            "test.toml, line 2: unknown key 'frame' in [augment]; allowed: frameFormat, receiverFormat, paramsFormat, maxIdLength, maxParams",
            error("[augment]\nframe = \"\$class.\$method\""),
        )
        assertTrue(error("[other]\nx = 1").contains("unknown key 'other'; allowed: debug, instrument, augment"))
        // Keys of earlier layouts are rejected, not silently ignored.
        for (old in listOf(
            "augmentAnnotatedClasses = []",
            "include = []",
            "maxIdLength = 64",
            "frameFormat = \"\$class.\$method\"",
            "[augmentClassIds]",
            "[augmentMethodParams]",
            "[format]\nmaxIdLength = 64",
            "fallback = \"toString\"",
        )) {
            assertTrue(error(old).contains("unknown key"), old)
        }
    }

    @Test
    fun `keys of the previous instrument layout are rejected`() {
        assertEquals(
            "test.toml, line 2: unknown key 'annotatedClasses' in [instrument]; allowed: classes, methods",
            error("[instrument]\nannotatedClasses = [\"com.acme.**\"]"),
        )
        assertTrue(error("[instrument.classIds]\n\"com.acme.Order\" = \"id\"").contains("unknown key 'classIds' in [instrument]; allowed: classes, methods"))
        assertTrue(error("[instrument.methodParams]\n\"com.acme.Order.run\" = \"*\"").contains("unknown key 'methodParams' in [instrument]; allowed: classes, methods"))
    }

    @Test
    fun `invalid classes and methods entries`() {
        assertEquals(
            "test.toml, line 3: must be a field name (e.g. \"orderId\"), a method (e.g. \"getOrderId()\") or \"@\" for the class's annotations, was get-id()",
            error("debug = false\n[instrument.classes]\n\"com.acme.Order\" = \"get-id()\""),
        )
        assertTrue(error("[instrument.classes]\n\"com.acme.Order\" = 5").contains("must be a field name"))
        assertTrue(error("[instrument.classes]\n\"com.acme.Order\" = \"@id\"").contains("must be a field name"))
        assertEquals(
            "test.toml, line 2: [instrument.classes] keys must name a class or a class pattern, e.g. \"com.acme.Order\" or \"com.acme.**\"; " +
                "allowed are letters, digits, _, \$ and the wildcards * (within a package or name), ** (across packages) and ?",
            error("[instrument.classes]\n\"com.acme.Ord+er\" = \"@\""),
        )
        assertTrue(error("[instrument.methods]\n\"com.acme.Order.process\" = \"order\"").contains("must be an array"))
        assertTrue(error("[instrument.methods]\n\"com.acme.Order.process\" = []").contains("at least one parameter"))
        assertTrue(error("[instrument.methods]\n\"com.acme.Order.process\" = [\"#1\"]").contains("invalid parameter '#1'"))
        assertTrue(error("[instrument.methods]\n\"com.acme.Order.process\" = [-1]").contains("invalid parameter '-1'"))
        assertTrue(error("[instrument.methods]\n\"com.acme.Order.process\" = [\"@\"]").contains("invalid parameter '@'"))
        assertTrue(error("[instrument.methods]\nOrder = [\"id\"]").contains("[instrument.methods] keys must name a class and a method"))
    }

    @Test
    fun `method entries with wildcards, all parameters and annotations`() {
        val config = parse(
            """
            [instrument.methods]
            "com.thirdparty.OrderService.process" = ["order"]
            "com.thirdparty.OrderService.*" = [2]
            "com.thirdparty.Inventory*.*" = "*"
            "com.thirdparty.**.*Repository.find*" = [0]
            "com.acme.Outer${'$'}Inner.ru?" = ["x"]
            "com.acme.Legacy.*" = "@"
            """,
        )
        val all = listOf(ParamRef.All())
        assertEquals(all, config.methods()["com.thirdparty.Inventory*.*"])
        // The exact entry first, then the matching wildcard entries.
        assertEquals(listOf(ParamRef.ByName("order"), ParamRef.ByIndex(2)), config.paramRefs("com.thirdparty.OrderService", "process"))
        assertEquals(listOf(ParamRef.ByIndex(2)), config.paramRefs("com.thirdparty.OrderService", "cancel"))
        assertEquals(all, config.paramRefs("com.thirdparty.InventoryService", "reserve"))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.thirdparty.db.OrderRepository", "findById"))
        assertEquals(listOf(ParamRef.ByIndex(0)), config.paramRefs("com.thirdparty.db.sql.OrderRepository", "findAll"))
        // '.**.' stands for at least one package segment.
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.OrderRepository", "findAll"))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.db.OrderRepository", "save"))
        // '*' stays within one package segment in the class part.
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.thirdparty.sub.InventoryService", "reserve"))
        assertEquals(listOf(ParamRef.ByName("x")), config.paramRefs("com.acme.Outer\$Inner", "run"))
        assertEquals(emptyList<ParamRef>(), config.paramRefs("com.acme.Outer\$Inner", "runs"))
        assertEquals(listOf(ParamRef.Annotations()), config.paramRefs("com.acme.Legacy", "run"))

        assertTrue(AugmentorConfig.isPattern("com.thirdparty.OrderService.*"))
        assertFalse(AugmentorConfig.isPattern("com.thirdparty.OrderService.process"))
    }

    @Test
    fun `invalid method entries and maxParams`() {
        assertTrue(error("[instrument.methods]\n\"com.acme.Order.process\" = \"all\"").contains("\"*\" for all parameters, or \"@\" for the method's annotations, was all"))
        assertTrue(error("[instrument.methods]\n\"com.acme.Order.process\" = [\"*\"]").contains("invalid parameter '*'"))
        assertEquals(
            "test.toml, line 2: [instrument.methods] keys must name a class and a method, e.g. \"com.acme.OrderService.process\"; " +
                "allowed are letters, digits, _, \$ and the wildcards * (within a package or name), ** (across packages) and ?",
            error("[instrument.methods]\n\"com.acme.Order+.process\" = \"*\""),
        )
        assertTrue(error("[instrument.methods]\n\"com.acme.Order.\" = \"*\"").contains("keys must name a class and a method"))
        assertTrue(error("[instrument.methods]\n\"com..Order.run\" = \"*\"").contains("keys must name a class and a method"))
        assertEquals("test.toml, line 2: maxParams must be between 1 and 255, was 0", error("[augment]\nmaxParams = 0"))
        assertTrue(error("augment.maxParams = 256").contains("maxParams must be between 1 and 255, was 256"))
        assertEquals(4, AugmentorConfig().maxParams())
        assertEquals(1, parse("augment.maxParams = 1").maxParams())
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
}
