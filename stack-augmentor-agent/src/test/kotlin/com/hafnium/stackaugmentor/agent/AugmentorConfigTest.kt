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
        assertEquals(Fallback.TO_STRING, AugmentorConfig().fallback)
        assertEquals("{class}{receiver}.{method}{params}", AugmentorConfig().frameFormat)
        assertEquals("[{name}={id}]", AugmentorConfig().receiverFormat)
        assertEquals("[{name}={id}, ...]", AugmentorConfig().paramsFormat)
    }

    @Test
    fun `full configuration`() {
        val config = parse(
            """
            include = ["com.hafnium.**", "com.acme.orders.*"]
            fallback = "identity"
            maxIdLength = 32
            debug = true
            frameFormat = "{class}.{method}{receiver}{params}"
            receiverFormat = "<{id}>"
            paramsFormat = "({name}: {id}; ...)"

            [id]
            "com.thirdparty.Order" = "getOrderNumber()"
            "com.thirdparty.Customer" = "customerId"

            [param]
            "com.thirdparty.OrderService.process" = ["orderId", 2]
            """,
        )
        assertEquals(
            AugmentorConfig(
                include = listOf("com.hafnium.**", "com.acme.orders.*"),
                fallback = Fallback.IDENTITY,
                maxIdLength = 32,
                debug = true,
                frameFormat = "{class}.{method}{receiver}{params}",
                receiverFormat = "<{id}>",
                paramsFormat = "({name}: {id}; ...)",
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
            [id]
            "com.acme.Order" = "orderId"
            [param]
            "com.acme.OrderService.process" = ["order"]
            """,
        )
        val unquoted = parse(
            """
            [id]
            com.acme.Order = "orderId"
            [param]
            com.acme.OrderService.process = ["order"]
            """,
        )
        assertEquals(quoted, unquoted)
    }

    @Test
    fun `include globs`() {
        val config = parse("""include = ["com.hafnium.**", "com.acme.orders.*", "com.acme.Order?"]""")
        assertTrue(config.isIncluded("com.hafnium.ObjectClass"))
        assertTrue(config.isIncluded("com.hafnium.deep.pkg.ObjectClass\$Inner"))
        assertTrue(config.isIncluded("com.acme.orders.Order"))
        assertFalse(config.isIncluded("com.acme.orders.sub.Order"))
        assertTrue(config.isIncluded("com.acme.Orders"))
        assertFalse(config.isIncluded("com.hafniumx.Other"))
        assertFalse(config.isIncluded("comXhafnium.Other"))
    }

    @Test
    fun `syntax errors report their position`() {
        val message = error("include = [\"a\"\nfallback = ")
        assertTrue(message.startsWith("Invalid TOML in test.toml:"), message)
        assertTrue(message.contains("line"), message)
    }

    @Test
    fun `invalid values name the key and its line`() {
        assertEquals(
            "test.toml, line 2: fallback must be \"toString\", \"identity\" or \"none\", was \"hash\"",
            error("include = []\nfallback = \"hash\""),
        )
        assertTrue(error("maxIdLength = \"64\"").contains("'maxIdLength' must be an integer"))
        assertTrue(error("maxIdLength = 1").contains("maxIdLength must be between"))
        assertTrue(error("maxIdLength = 1.5").contains("'maxIdLength' must be an integer"))
        assertTrue(error("include = \"com.acme.**\"").contains("'include' must be an array of strings"))
        assertTrue(error("include = [1, 2]").contains("'include' must be an array of strings"))
        assertTrue(error("debug = \"yes\"").contains("'debug' must be true or false"))
        assertTrue(error("mode = \"registry\"").contains("unknown key 'mode'"))
        assertTrue(error("[format]\nframe = \"{class}.{method}\"").contains("unknown key 'format'"))
        assertTrue(error("id = \"x\"").contains("'id' must be a table"))
    }

    @Test
    fun `invalid id and param entries`() {
        assertEquals(
            "test.toml, line 3: must be a field name (e.g. \"orderId\") or a method (e.g. \"getOrderId()\"), was get-id()",
            error("include = []\n[id]\n\"com.acme.Order\" = \"get-id()\""),
        )
        assertTrue(error("[id]\n\"com.acme.Order\" = 5").contains("must be a field name"))
        assertTrue(error("[param]\n\"com.acme.Order.process\" = \"order\"").contains("must be an array"))
        assertTrue(error("[param]\n\"com.acme.Order.process\" = []").contains("at least one parameter"))
        assertTrue(error("[param]\n\"com.acme.Order.process\" = [\"#1\"]").contains("invalid parameter '#1'"))
        assertTrue(error("[param]\n\"com.acme.Order.process\" = [-1]").contains("invalid parameter '-1'"))
        assertTrue(error("[param]\nOrder = [\"id\"]").contains("must name a class and a method"))
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
