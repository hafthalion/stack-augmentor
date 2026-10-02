package com.hafnium.stackaugmentor.runtime.ids

import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig
import com.hafnium.stackaugmentor.runtime.config.ConfigException
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
        assertEquals("{\$name=\$id, ...}", AugmentorConfig.DEFAULT_RECEIVER_FORMAT)
        assertEquals("{\$name=\$id, ...}", AugmentorConfig.DEFAULT_PARAMS_FORMAT)
        val result = rewrite().rewrite(element, listOf(receiver), listOf(orderId))
        assertEquals("com.hafnium.ObjectClass{objectId=123}", result.className)
        assertEquals("process{orderId=42}", result.methodName)
        assertEquals("com.hafnium.ObjectClass{objectId=123}.process{orderId=42}(ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `groups without ids render empty`() {
        assertEquals("com.hafnium.ObjectClass.process(ObjectClass.java:13)", rewrite().rewrite(element, emptyList(), emptyList()).toString())
        assertEquals("com.hafnium.ObjectClass.process{orderId=42}(ObjectClass.java:13)", rewrite().rewrite(element, emptyList(), listOf(orderId)).toString())
    }

    @Test
    fun `ids after the method`() {
        val result = rewrite(frameFormat = "\$class.\$method\$receiver\$params").rewrite(element, listOf(receiver), listOf(orderId))
        assertEquals("com.hafnium.ObjectClass.process{objectId=123}{orderId=42}(ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `value only and simple class name`() {
        val result = rewrite(frameFormat = "\$simpleClass\$receiver.\$method", receiverFormat = "<\$id>").rewrite(element, listOf(receiver), listOf(orderId))
        assertEquals("ObjectClass<123>.process(ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `brackets instead of braces`() {
        val result = rewrite(receiverFormat = "[\$name=\$id]", paramsFormat = "[\$name=\$id, ...]").rewrite(element, listOf(receiver), listOf(orderId, customer))
        assertEquals("com.hafnium.ObjectClass[objectId=123].process[orderId=42, customer=7](ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `braces and dollars are literal in the frame format`() {
        val result = rewrite(frameFormat = "{\$\$\$simpleClass}\$receiver.\$method{\$params}").rewrite(element, listOf(receiver), listOf(orderId))
        assertEquals("{\$ObjectClass}{objectId=123}.process{{orderId=42}}(ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `braced placeholders end at the brace`() {
        val result = rewrite(
            frameFormat = "\${simpleClass}Impl\$receiver.\${method}X\$params",
            receiverFormat = "<\${name}s=\$id>",
            paramsFormat = "[\${name}Id=\${id}, ...]",
        ).rewrite(element, listOf(receiver), listOf(orderId, customer))
        assertEquals("ObjectClassImpl<objectIds=123>.processX[orderIdId=42, customerId=7](ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `a brace without a dollar is literal`() {
        val result = rewrite(frameFormat = "{\$class}\$receiver.\$method\$params}").rewrite(element, listOf(receiver), listOf(orderId))
        assertEquals("{com.hafnium.ObjectClass}{objectId=123}.process{orderId=42}}(ObjectClass.java:13)", result.toString())
        // $$ before a brace is a literal dollar followed by a literal brace.
        assertEquals("com.hafnium.ObjectClass\${123}", rewrite(receiverFormat = "\$\${\$id}").rewrite(element, listOf(receiver), emptyList()).className)
    }

    @Test
    fun `params format with repetition marker`() {
        assertEquals(
            "process{orderId=42, customer=7}",
            rewrite().rewrite(element, emptyList(), listOf(orderId, customer)).methodName,
        )
        assertEquals(
            "process[orderId: 42; customer: 7]",
            rewrite(paramsFormat = "[\$name: \$id; ...]").rewrite(element, emptyList(), listOf(orderId, customer)).methodName,
        )
    }

    @Test
    fun `params format without repetition marker repeats the whole template`() {
        val format = rewrite(paramsFormat = "{\$name=\$id}")
        assertEquals("process{orderId=42}", format.rewrite(element, emptyList(), listOf(orderId)).methodName)
        assertEquals("process{orderId=42},{customer=7}", format.rewrite(element, emptyList(), listOf(orderId, customer)).methodName)
    }

    @Test
    fun `all parameter ids are shown`() {
        val ids = (1..6).map { NamedId("p$it", "$it") }
        assertEquals("process{p1=1, p2=2, p3=3, p4=4, p5=5, p6=6}", rewrite().rewrite(element, emptyList(), ids).methodName)
    }

    @Test
    fun `double dollar is a literal dollar`() {
        val result = rewrite(receiverFormat = "\$\$\$id").rewrite(element, listOf(receiver), emptyList())
        assertEquals("com.hafnium.ObjectClass\$123", result.className)
    }

    @Test
    fun `frame format needs dot method`() {
        val error = assertThrows<ConfigException> { rewrite(frameFormat = "\$class#\$method") }
        assertTrue(error.message!!.contains(".\$method"), error.message)
        assertThrows<ConfigException> { rewrite(frameFormat = "\$class.\$method.\$method") }
        assertThrows<ConfigException> { rewrite(frameFormat = "\$method.\$class") }
        // A literal dollar before the dot does not make it '.$method'.
        assertThrows<ConfigException> { rewrite(frameFormat = "\$class.\$\$method") }
        // Braces are literal, so they do not make a placeholder.
        assertThrows<ConfigException> { rewrite(frameFormat = "{class}{receiver}.{method}{params}") }
    }

    @Test
    fun `parentheses are rejected in every template`() {
        // They would confuse IDEs looking for the frame's (File.java:12).
        for ((frame, receiver, params) in listOf(
            Triple("\$class.\$method(\$params)", AugmentorConfig.DEFAULT_RECEIVER_FORMAT, AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, "(\$id)", AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, AugmentorConfig.DEFAULT_RECEIVER_FORMAT, "(\$name: \$id; ...)"),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, AugmentorConfig.DEFAULT_RECEIVER_FORMAT, "{\$name=\$id), ...}"),
        )) {
            val message = assertThrows<ConfigException>("frame=$frame receiver=$receiver params=$params") { rewrite(frame, receiver, params) }.message!!
            assertTrue(message.contains("must not contain '('") || message.contains("must not contain ')'"), message)
        }
        val message = assertThrows<ConfigException> { rewrite(paramsFormat = "(\$name: \$id; ...)") }.message!!
        assertTrue(message.startsWith("paramsFormat must not contain '(' (at position 0)"), message)
    }

    @Test
    fun `invalid templates are rejected`() {
        val invalid = listOf(
            Triple("\$klass.\$method", AugmentorConfig.DEFAULT_RECEIVER_FORMAT, AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple("\$class\$receiver.\$method\$", AugmentorConfig.DEFAULT_RECEIVER_FORMAT, AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            // $file and $line are not placeholders: the JDK always prints the location itself.
            Triple("\$class.\$method\$line", AugmentorConfig.DEFAULT_RECEIVER_FORMAT, AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, "{\$method}", AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, "\$idx", AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, "cost \$", AugmentorConfig.DEFAULT_PARAMS_FORMAT),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, AugmentorConfig.DEFAULT_RECEIVER_FORMAT, "{...}"),
            Triple(AugmentorConfig.DEFAULT_FRAME_FORMAT, AugmentorConfig.DEFAULT_RECEIVER_FORMAT, "{\$name, ...\$id}"),
        )
        for ((frame, receiver, params) in invalid) {
            assertThrows<ConfigException>("frame=$frame receiver=$receiver params=$params") { rewrite(frame, receiver, params) }
        }
        for (receiver in listOf("\${nam}", "\${id", "\${}", "\${ id}")) {
            assertThrows<ConfigException>(receiver) { rewrite(receiverFormat = receiver) }
        }
        assertTrue(assertThrows<ConfigException> { rewrite(receiverFormat = "<\${id>") }.message!!.contains("unclosed '\${'"))
        assertTrue(assertThrows<ConfigException> { rewrite(frameFormat = "\${class}#\${method}") }.message!!.contains(".\$method"))
        val message = assertThrows<ConfigException> { rewrite(receiverFormat = "{\$nam}") }.message!!
        assertTrue(message.contains("unknown placeholder '\$nam'"), message)
    }

    @Test
    fun `several receiver ids repeat like parameters`() {
        val tenant = NamedId("tenant", "acme")
        assertEquals("com.hafnium.ObjectClass{tenant=acme, objectId=123}", rewrite().rewrite(element, listOf(tenant, receiver), emptyList()).className)
        assertEquals("com.hafnium.ObjectClass[acme|123]",
            rewrite(receiverFormat = "[\$id|...]").rewrite(element, listOf(tenant, receiver), emptyList()).className)
        // Without "...", the whole template repeats, separated by commas.
        assertEquals("com.hafnium.ObjectClass<acme>,<123>",
            rewrite(receiverFormat = "<\$id>").rewrite(element, listOf(tenant, receiver), emptyList()).className)
        assertTrue(assertThrows<ConfigException> { rewrite(receiverFormat = "{...}") }.message!!.startsWith("receiverFormat must contain"))
    }

    @Test
    fun `native method location is kept`() {
        val native = StackTraceElement("com.hafnium.ObjectClass", "process", "ObjectClass.java", -2)
        assertEquals("com.hafnium.ObjectClass{objectId=123}.process(Native Method)", rewrite().rewrite(native, listOf(receiver), emptyList()).toString())
    }

    @Test
    fun `class loader and module prefix are kept`() {
        val inModule = StackTraceElement("my-loader", "my.module", "1.0", "com.hafnium.ObjectClass", "process", "ObjectClass.java", 13)
        val result = rewrite().rewrite(inModule, listOf(receiver), emptyList())
        assertEquals("my-loader/my.module@1.0/com.hafnium.ObjectClass{objectId=123}.process(ObjectClass.java:13)", result.toString())
    }

    @Test
    fun `real frames of application classes have no prefix`() {
        val real = Throwable().stackTrace[0]
        val result = rewrite().rewrite(real, listOf(receiver), emptyList())
        assertEquals(real.toString().replace("${real.className}.", "${real.className}{objectId=123}."), result.toString())
    }
}
