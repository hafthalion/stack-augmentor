package com.hafnium.stackaugmentor.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertEquals("{class}{receiver}.{method}{params}", AugmentorConfig().frameFormat())
        assertEquals("{\$name=\$id}", AugmentorConfig().receiverFormat())
        assertEquals("{\$name=\$id, ...}", AugmentorConfig().paramsFormat())
    }

    @Test
    fun `full configuration`() {
        val config = parse(
            """
            debug = true

            [instrument]
            annotatedClasses = ["com.hafnium.**", "com.acme.orders.*"]

            [instrument.classIds]
            "com.thirdparty.Order" = "getOrderNumber()"
            "com.thirdparty.Customer" = "customerId"

            [instrument.methodParams]
            "com.thirdparty.OrderService.process" = ["orderId", 2]

            [augment]
            frameFormat = "{class}.{method}{receiver}{params}"
            receiverFormat = "<${'$'}id>"
            paramsFormat = "(${'$'}name: ${'$'}id; ...)"
            maxIdLength = 32
            """,
        )
        assertEquals(
            AugmentorConfig.builder()
                .annotatedClasses(listOf("com.hafnium.**", "com.acme.orders.*"))
                .ids(
                    mapOf(
                        "com.thirdparty.Order" to IdSpec.MethodSpec("getOrderNumber"),
                        "com.thirdparty.Customer" to IdSpec.FieldSpec("customerId"),
                    ),
                )
                .params(mapOf("com.thirdparty.OrderService.process" to listOf(ParamRef.ByName("orderId"), ParamRef.ByIndex(2))))
                .frameFormat("{class}.{method}{receiver}{params}")
                .receiverFormat("<\$id>")
                .paramsFormat("(\$name: \$id; ...)")
                .maxIdLength(32)
                .debug(true)
                .build(),
            config,
        )
        assertEquals(listOf(ParamRef.ByName("orderId"), ParamRef.ByIndex(2)), config.paramRefs("com.thirdparty.OrderService", "process"))
        assertTrue(config.hasParamEntries("com.thirdparty.OrderService"))
    }

    @Test
    fun `dotted keys and inline tables are the same as sections`() {
        val sections = parse(
            """
            [instrument]
            annotatedClasses = ["com.acme.**"]
            [augment]
            maxIdLength = 20
            """,
        )
        assertEquals(sections, parse("instrument.annotatedClasses = [\"com.acme.**\"]\naugment.maxIdLength = 20"))
        assertEquals(sections, parse("instrument = { annotatedClasses = [\"com.acme.**\"] }\naugment = { maxIdLength = 20 }"))
    }

    @Test
    fun `unquoted class names are the same as quoted ones`() {
        val quoted = parse(
            """
            [instrument.classIds]
            "com.acme.Order" = "orderId"
            [instrument.methodParams]
            "com.acme.OrderService.process" = ["order"]
            """,
        )
        val unquoted = parse(
            """
            [instrument.classIds]
            com.acme.Order = "orderId"
            [instrument.methodParams]
            com.acme.OrderService.process = ["order"]
            """,
        )
        assertEquals(quoted, unquoted)
    }

    @Test
    fun `annotatedClasses globs`() {
        val config = parse("""instrument.annotatedClasses = ["com.hafnium.**", "com.acme.orders.*", "com.acme.Order?"]""")
        assertTrue(config.honoursAnnotations("com.hafnium.ObjectClass"))
        assertTrue(config.honoursAnnotations("com.hafnium.deep.pkg.ObjectClass\$Inner"))
        assertTrue(config.honoursAnnotations("com.acme.orders.Order"))
        assertFalse(config.honoursAnnotations("com.acme.orders.sub.Order"))
        assertTrue(config.honoursAnnotations("com.acme.Orders"))
        assertFalse(config.honoursAnnotations("com.hafniumx.Other"))
        assertFalse(config.honoursAnnotations("comXhafnium.Other"))

        // Empty (the default): annotations are honoured in every package.
        assertTrue(AugmentorConfig().honoursAnnotations("any.pkg.Class"))
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
        assertTrue(error("instrument.annotatedClasses = \"com.acme.**\"").contains("'instrument.annotatedClasses' must be an array of strings"))
        assertTrue(error("instrument.annotatedClasses = [1, 2]").contains("'instrument.annotatedClasses' must be an array of strings"))
        assertTrue(error("debug = \"yes\"").contains("'debug' must be true or false"))
        assertTrue(error("instrument = \"x\"").contains("'instrument' must be a table"))
        assertTrue(error("[instrument]\nclassIds = \"x\"").contains("'instrument.classIds' must be a table"))
    }

    @Test
    fun `unknown keys are rejected, in every section`() {
        assertEquals(
            "test.toml, line 2: unknown key 'frame' in [augment]; allowed: frameFormat, receiverFormat, paramsFormat, maxIdLength",
            error("[augment]\nframe = \"{class}.{method}\""),
        )
        assertTrue(error("[instrument]\nclasses = []").contains("unknown key 'classes' in [instrument]"))
        assertTrue(error("[other]\nx = 1").contains("unknown key 'other'; allowed: debug, instrument, augment"))
        // Keys of earlier layouts are rejected, not silently ignored.
        for (old in listOf(
            "augmentAnnotatedClasses = []",
            "include = []",
            "maxIdLength = 64",
            "frameFormat = \"{class}.{method}\"",
            "[augmentClassIds]",
            "[augmentMethodParams]",
            "[format]\nmaxIdLength = 64",
            "fallback = \"toString\"",
        )) {
            assertTrue(error(old).contains("unknown key"), old)
        }
    }

    @Test
    fun `invalid classIds and methodParams entries`() {
        assertEquals(
            "test.toml, line 3: must be a field name (e.g. \"orderId\") or a method (e.g. \"getOrderId()\"), was get-id()",
            error("debug = false\n[instrument.classIds]\n\"com.acme.Order\" = \"get-id()\""),
        )
        assertTrue(error("[instrument.classIds]\n\"com.acme.Order\" = 5").contains("must be a field name"))
        assertTrue(error("[instrument.methodParams]\n\"com.acme.Order.process\" = \"order\"").contains("must be an array"))
        assertTrue(error("[instrument.methodParams]\n\"com.acme.Order.process\" = []").contains("at least one parameter"))
        assertTrue(error("[instrument.methodParams]\n\"com.acme.Order.process\" = [\"#1\"]").contains("invalid parameter '#1'"))
        assertTrue(error("[instrument.methodParams]\n\"com.acme.Order.process\" = [-1]").contains("invalid parameter '-1'"))
        assertTrue(error("[instrument.methodParams]\nOrder = [\"id\"]").contains("[instrument.methodParams] keys must name a class and a method"))
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
