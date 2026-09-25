package com.hafnium.stackaugmentor.agent

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
        assertEquals("{class}{receiver}.{method}{params}", AugmentorConfig().frameFormat)
        assertEquals("{\$name=\$id}", AugmentorConfig().receiverFormat)
        assertEquals("{\$name=\$id, ...}", AugmentorConfig().paramsFormat)
    }

    @Test
    fun `full configuration`() {
        val config = parse(
            """
            augmentAnnotatedClasses = ["com.hafnium.**", "com.acme.orders.*"]
            maxIdLength = 32
            debug = true
            frameFormat = "{class}.{method}{receiver}{params}"
            receiverFormat = "<${'$'}id>"
            paramsFormat = "(${'$'}name: ${'$'}id; ...)"

            [augmentClassIds]
            "com.thirdparty.Order" = "getOrderNumber()"
            "com.thirdparty.Customer" = "customerId"

            [augmentMethodParams]
            "com.thirdparty.OrderService.process" = ["orderId", 2]
            """,
        )
        assertEquals(
            AugmentorConfig(
                augmentAnnotatedClasses = listOf("com.hafnium.**", "com.acme.orders.*"),
                maxIdLength = 32,
                debug = true,
                frameFormat = "{class}.{method}{receiver}{params}",
                receiverFormat = "<\$id>",
                paramsFormat = "(\$name: \$id; ...)",
                ids = mapOf(
                    "com.thirdparty.Order" to IdSpec.MethodSpec("getOrderNumber"),
                    "com.thirdparty.Customer" to IdSpec.FieldSpec("customerId"),
                ),
                params = mapOf("com.thirdparty.OrderService.process" to listOf(ParamRef.ByName("orderId"), ParamRef.ByIndex(2))),
            ),
            config,
        )
        assertEquals(listOf(ParamRef.ByName("orderId"), ParamRef.ByIndex(2)), config.paramRefs("com.thirdparty.OrderService", "process"))
        assertTrue(config.hasParamEntries("com.thirdparty.OrderService"))
    }

    @Test
    fun `unquoted class names are the same as quoted ones`() {
        val quoted = parse(
            """
            [augmentClassIds]
            "com.acme.Order" = "orderId"
            [augmentMethodParams]
            "com.acme.OrderService.process" = ["order"]
            """,
        )
        val unquoted = parse(
            """
            [augmentClassIds]
            com.acme.Order = "orderId"
            [augmentMethodParams]
            com.acme.OrderService.process = ["order"]
            """,
        )
        assertEquals(quoted, unquoted)
    }

    @Test
    fun `augmentAnnotatedClasses globs`() {
        val config = parse("""augmentAnnotatedClasses = ["com.hafnium.**", "com.acme.orders.*", "com.acme.Order?"]""")
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
        val message = error("augmentAnnotatedClasses = [\"a\"\nmaxIdLength = ")
        assertTrue(message.startsWith("Invalid TOML in test.toml:"), message)
        assertTrue(message.contains("line"), message)
    }

    @Test
    fun `invalid values name the key and its line`() {
        assertEquals(
            "test.toml, line 2: maxIdLength must be between 2 and 10000, was 1",
            error("augmentAnnotatedClasses = []\nmaxIdLength = 1"),
        )
        assertTrue(error("maxIdLength = \"64\"").contains("'maxIdLength' must be an integer"))
        assertTrue(error("maxIdLength = 1").contains("maxIdLength must be between"))
        assertTrue(error("maxIdLength = 1.5").contains("'maxIdLength' must be an integer"))
        assertTrue(error("augmentAnnotatedClasses = \"com.acme.**\"").contains("'augmentAnnotatedClasses' must be an array of strings"))
        assertTrue(error("augmentAnnotatedClasses = [1, 2]").contains("'augmentAnnotatedClasses' must be an array of strings"))
        assertTrue(error("debug = \"yes\"").contains("'debug' must be true or false"))
        assertTrue(error("mode = \"registry\"").contains("unknown key 'mode'"))
        assertTrue(error("[format]\nframe = \"{class}.{method}\"").contains("unknown key 'format'"))
        assertTrue(error("augmentClassIds = \"x\"").contains("'augmentClassIds' must be a table"))
        // Removed and renamed keys are rejected, not silently ignored.
        assertTrue(error("fallback = \"toString\"").contains("unknown key 'fallback'"))
        assertTrue(error("include = [\"com.acme.**\"]").contains("unknown key 'include'"))
        assertTrue(error("[augmentIds]\n\"com.acme.Order\" = \"orderId\"").contains("unknown key 'augmentIds'"))
        assertTrue(error("[augmentParams]\n\"com.acme.Order.process\" = [\"order\"]").contains("unknown key 'augmentParams'"))
    }

    @Test
    fun `invalid id and param entries`() {
        assertEquals(
            "test.toml, line 3: must be a field name (e.g. \"orderId\") or a method (e.g. \"getOrderId()\"), was get-id()",
            error("augmentAnnotatedClasses = []\n[augmentClassIds]\n\"com.acme.Order\" = \"get-id()\""),
        )
        assertTrue(error("[augmentClassIds]\n\"com.acme.Order\" = 5").contains("must be a field name"))
        assertTrue(error("[augmentMethodParams]\n\"com.acme.Order.process\" = \"order\"").contains("must be an array"))
        assertTrue(error("[augmentMethodParams]\n\"com.acme.Order.process\" = []").contains("at least one parameter"))
        assertTrue(error("[augmentMethodParams]\n\"com.acme.Order.process\" = [\"#1\"]").contains("invalid parameter '#1'"))
        assertTrue(error("[augmentMethodParams]\n\"com.acme.Order.process\" = [-1]").contains("invalid parameter '-1'"))
        assertTrue(error("[augmentMethodParams]\nOrder = [\"id\"]").contains("must name a class and a method"))
    }

    @Test
    fun `load reads toml files only`(@TempDir dir: Path) {
        val toml = dir.resolve("agent.toml")
        Files.writeString(toml, "maxIdLength = 10")
        assertEquals(10, AugmentorConfig.load("config=$toml").maxIdLength)
        assertEquals(10, AugmentorConfig.load(toml.toString()).maxIdLength)

        val properties = dir.resolve("agent.properties")
        Files.writeString(properties, "maxIdLength=10")
        val message = assertThrows<ConfigException> { AugmentorConfig.load("config=$properties") }.message!!
        assertTrue(message.contains("must be a TOML file ending in .toml"), message)

        assertTrue(assertThrows<ConfigException> { AugmentorConfig.load("config=${dir.resolve("missing.toml")}") }.message!!.contains("not found"))
        assertEquals(AugmentorConfig(), AugmentorConfig.load(null))
    }
}
