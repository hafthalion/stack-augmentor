package com.hafnium.stackaugmentor.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FrameFormatTest {

    private val element = StackTraceElement("com.hafnium.ObjectClass", "process", "ObjectClass.java", 13)
    private val receiver = NamedId("objectId", "123")
    private val orderId = NamedId("orderId", "42")
    private val customer = NamedId("customer", "7")

    private fun rewrite(
        frameFormat: String = AugmentorConfig.DEFAULT_FRAME_FORMAT,
        receiverFormat: String = AugmentorConfig.DEFAULT_RECEIVER_FORMAT,
        paramsFormat: String = AugmentorConfig.DEFAULT_PARAMS_FORMAT,
    ) = FrameFormat.create(frameFormat, receiverFormat, paramsFormat)

    @Test
    fun `default format`() {
        assertEquals("{\$name=\$id}", AugmentorConfig.DEFAULT_RECEIVER_FORMAT)
        assertEquals("{\$name=\$id, ...}", AugmentorConfig.DEFAULT_PARAMS_FORMAT)
        val result = rewrite().rewrite(element, receiver, listOf(orderId))
        assertEquals("com.hafnium.ObjectClass{objectId=123}", result.className)
        assertEquals("process{orderId=42}", result.methodName)
        assertEquals("com.hafnium.ObjectClass{objectId=123}.process{orderId=42}(ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `groups without ids render empty`() {
        assertEquals("com.hafnium.ObjectClass.process(ObjectClass.java:13)", rewrite().rewrite(element, null, emptyList()).toString())
        assertEquals("com.hafnium.ObjectClass.process{orderId=42}(ObjectClass.java:13)", rewrite().rewrite(element, null, listOf(orderId)).toString())
    }

    @Test
    fun `ids after the method`() {
        val result = rewrite(frameFormat = "{class}.{method}{receiver}{params}").rewrite(element, receiver, listOf(orderId))
        assertEquals("com.hafnium.ObjectClass.process{objectId=123}{orderId=42}(ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `value only and simple class name`() {
        val result = rewrite(frameFormat = "{simpleClass}{receiver}.{method}", receiverFormat = "<\$id>").rewrite(element, receiver, listOf(orderId))
        assertEquals("ObjectClass<123>.process(ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `brackets instead of braces`() {
        val result = rewrite(receiverFormat = "[\$name=\$id]", paramsFormat = "[\$name=\$id, ...]").rewrite(element, receiver, listOf(orderId, customer))
        assertEquals("com.hafnium.ObjectClass[objectId=123].process[orderId=42, customer=7](ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `params format with repetition marker`() {
        assertEquals(
            "process{orderId=42, customer=7}",
            rewrite().rewrite(element, null, listOf(orderId, customer)).methodName,
        )
        assertEquals(
            "process(orderId: 42; customer: 7)",
            rewrite(paramsFormat = "(\$name: \$id; ...)").rewrite(element, null, listOf(orderId, customer)).methodName,
        )
    }

    @Test
    fun `params format without repetition marker repeats the whole template`() {
        val format = rewrite(paramsFormat = "{\$name=\$id}")
        assertEquals("process{orderId=42}", format.rewrite(element, null, listOf(orderId)).methodName)
        assertEquals("process{orderId=42},{customer=7}", format.rewrite(element, null, listOf(orderId, customer)).methodName)
    }

    @Test
    fun `double dollar is a literal dollar`() {
        val result = rewrite(receiverFormat = "\$\$\$id").rewrite(element, receiver, emptyList())
        assertEquals("com.hafnium.ObjectClass\$123", result.className)
    }

    @Test
    fun `frame format needs dot method`() {
        val error = assertThrows<ConfigException> { rewrite(frameFormat = "{class}#{method}") }
        assertEquals(true, error.message!!.contains(".{method}"))
        assertThrows<ConfigException> { rewrite(frameFormat = "{class}.{method}.{method}") }
    }

    @Test
    fun `invalid templates are rejected`() {
        val invalid = listOf(
            Triple("{klass}.{method}", AugmentorConfig.DEFAULT_RECEIVER_FORMAT, AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple("{class}{receiver.{method}", AugmentorConfig.DEFAULT_RECEIVER_FORMAT, AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            // {file} and {line} are not placeholders: the JDK always prints the location itself.
            Triple("{class}.{method}{line}", AugmentorConfig.DEFAULT_RECEIVER_FORMAT, AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, "{\$method}", AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, "\$idx", AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, "cost \$", AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, AugmentorConfig.DEFAULT_RECEIVER_FORMAT, "{...}"),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, AugmentorConfig.DEFAULT_RECEIVER_FORMAT, "{\$name, ...\$id}"),
        )
        for ((frame, receiver, params) in invalid) {
            assertThrows<ConfigException>("frame=$frame receiver=$receiver params=$params") { rewrite(frame, receiver, params) }
        }
        val message = assertThrows<ConfigException> { rewrite(receiverFormat = "{\$nam}") }.message!!
        assertTrue(message.contains("unknown placeholder '\$nam'"), message)
    }

    @Test
    fun `native method location is kept`() {
        val native = StackTraceElement("com.hafnium.ObjectClass", "process", "ObjectClass.java", -2)
        assertEquals("com.hafnium.ObjectClass{objectId=123}.process(Native Method)", rewrite().rewrite(native, receiver, emptyList()).toString())
    }

    @Test
    fun `class loader and module prefix are kept`() {
        val inModule = StackTraceElement("my-loader", "my.module", "1.0", "com.hafnium.ObjectClass", "process", "ObjectClass.java", 13)
        val result = rewrite().rewrite(inModule, receiver, emptyList())
        assertEquals("my-loader/my.module@1.0/com.hafnium.ObjectClass{objectId=123}.process(ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `real frames of application classes have no prefix`() {
        val real = Throwable().stackTrace[0]
        val result = rewrite().rewrite(real, receiver, emptyList())
        assertEquals(real.toString().replace("${real.className}.", "${real.className}{objectId=123}."), result.toString())
    }
}
