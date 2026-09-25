package com.hafnium.stackaugmentor.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.StringReader
import java.util.Properties

class AugmentorConfigTest {

    private fun parse(text: String) = AugmentorConfig.parse(Properties().apply { load(StringReader(text)) })

    @Test
    fun defaults() {
        val config = parse("")
        assertEquals(Fallback.TO_STRING, config.fallback)
        assertEquals("{class}{receiver}.{method}{params}", config.frameFormat)
        assertEquals("[{name}={id}]", config.receiverFormat)
        assertEquals("[{name}={id}, ...]", config.paramsFormat)
    }

    @Test
    fun `full configuration`() {
        val config = parse(
            """
            include=com.hafnium.**, com.acme.orders.*
            fallback=identity
            maxIdLength=32
            paramsFormat=({name}: {id}; ...)
            id.com.thirdparty.Order=getOrderNumber()
            id.com.thirdparty.Customer=customerId
            param.com.thirdparty.OrderService.process=orderId, #2
            """.trimIndent(),
        )
        assertEquals(Fallback.IDENTITY, config.fallback)
        assertEquals(32, config.maxIdLength)
        assertEquals("({name}: {id}; ...)", config.paramsFormat)
        assertEquals(IdSpec.MethodSpec("getOrderNumber"), config.ids["com.thirdparty.Order"])
        assertEquals(IdSpec.FieldSpec("customerId"), config.ids["com.thirdparty.Customer"])
        assertEquals(
            listOf(ParamRef.ByName("orderId"), ParamRef.ByIndex(2)),
            config.paramRefs("com.thirdparty.OrderService", "process"),
        )
        assertTrue(config.hasParamEntries("com.thirdparty.OrderService"))
    }

    @Test
    fun `include globs`() {
        val config = parse("include=com.hafnium.**,com.acme.orders.*,com.acme.Order?")
        assertTrue(config.isIncluded("com.hafnium.ObjectClass"))
        assertTrue(config.isIncluded("com.hafnium.deep.pkg.ObjectClass\$Inner"))
        assertTrue(config.isIncluded("com.acme.orders.Order"))
        assertFalse(config.isIncluded("com.acme.orders.sub.Order"))
        assertTrue(config.isIncluded("com.acme.Orders"))
        assertFalse(config.isIncluded("com.hafniumx.Other"))
        assertFalse(config.isIncluded("comXhafnium.Other"))
    }

    @Test
    fun `toml is equivalent to properties`() {
        val fromProperties = parse(
            """
            include=com.hafnium.**,com.acme.orders.*
            fallback=identity
            maxIdLength=32
            debug=true
            frameFormat={class}.{method}{receiver}{params}
            paramsFormat=({name}: {id}; ...)
            id.com.thirdparty.Order=getOrderNumber()
            id.com.thirdparty.Customer=customerId
            param.com.thirdparty.OrderService.process=orderId,#2
            """.trimIndent(),
        )
        val fromToml = AugmentorConfig.parseToml(
            """
            include = ["com.hafnium.**", "com.acme.orders.*"]
            fallback = "identity"
            maxIdLength = 32
            debug = true
            frameFormat = "{class}.{method}{receiver}{params}"
            paramsFormat = "({name}: {id}; ...)"

            [id]
            "com.thirdparty.Order" = "getOrderNumber()"
            com.thirdparty.Customer = "customerId"   # unquoted works as well

            [param]
            "com.thirdparty.OrderService.process" = ["orderId", 2]
            """.trimIndent(),
        )
        assertEquals(fromProperties, fromToml)
    }

    @Test
    fun `invalid toml is rejected with its position`() {
        val syntax = assertThrows<ConfigException> { AugmentorConfig.parseToml("include = [\"a\"\nfallback = ", "test.toml") }
        assertTrue(syntax.message!!.startsWith("Invalid TOML in test.toml:"), syntax.message)
        assertTrue(syntax.message!!.contains("line"), syntax.message)

        assertThrows<ConfigException> { AugmentorConfig.parseToml("maxIdLength = 1.5") }
        assertThrows<ConfigException> { AugmentorConfig.parseToml("include = [1, 2]") }
        assertThrows<ConfigException> { AugmentorConfig.parseToml("[format]\nframe = \"{class}.{method}\"") }
    }

    @Test
    fun `invalid values are rejected`() {
        assertThrows<ConfigException> { parse("mode=registry") } // registry mode was removed
        assertThrows<ConfigException> { parse("fallback=hash") }
        assertThrows<ConfigException> { parse("maxIdLength=1") }
        assertThrows<ConfigException> { parse("id.com.acme.Order=get-id()") }
        assertThrows<ConfigException> { parse("param.com.acme.Order.process=#x") }
        assertThrows<ConfigException> { parse("param.Order=id") }
        assertThrows<ConfigException> { parse("frameformat={class}.{method}") }
    }
}
