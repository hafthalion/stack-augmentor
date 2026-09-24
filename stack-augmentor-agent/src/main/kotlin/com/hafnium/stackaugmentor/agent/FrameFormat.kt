package com.hafnium.stackaugmentor.agent

/** An id shown in a frame, with its label: the field, method or parameter it came from. */
data class NamedId(val name: String, val id: String)

/**
 * Renders frames from the `frameFormat`, `receiverFormat` and `paramsFormat` templates.
 * All templates are parsed and validated once, when the format is created.
 */
class FrameFormat private constructor(
    private val mode: Mode,
    private val frame: List<Token>,
    private val declaringClassPart: List<Token>,
    private val methodPart: List<Token>,
    private val receiver: List<Token>,
    private val params: ParamsTemplate,
) {

    /** Rewrite mode: a replacement element whose `toString()` shows the ids. */
    fun rewrite(element: StackTraceElement, receiverId: NamedId?, paramIds: List<NamedId>): StackTraceElement {
        check(mode == Mode.REWRITE) { "rewrite() needs mode=rewrite" }
        val values = values(element, receiverId, paramIds)
        val declaringClass = render(declaringClassPart, values)
        val method = render(methodPart, values)
        val prefix = prefixOf(element)
        val loader = element.classLoaderName?.takeIf { it.isNotEmpty() && prefix.startsWith("$it/") }
        val rest = if (loader != null) prefix.removePrefix("$loader/") else prefix
        val module = element.moduleName?.takeIf { it.isNotEmpty() && rest.startsWith(it) }
        val version = element.moduleVersion?.takeIf { module != null && rest.startsWith("$module@$it") }
        return StackTraceElement(loader, module, version, declaringClass, method, element.fileName, element.lineNumber)
    }

    /** Registry mode: the complete frame text, as printed after `"at "`. */
    fun renderRegistry(element: StackTraceElement, receiverId: NamedId?, paramIds: List<NamedId>): String {
        val text = render(frame, values(element, receiverId, paramIds))
        val usesLocation = frame.any { it is Token.Placeholder && (it.name == "file" || it.name == "line") }
        return prefixOf(element) + if (usesLocation) text else "$text(${location(element)})"
    }

    private fun values(element: StackTraceElement, receiverId: NamedId?, paramIds: List<NamedId>): (String) -> String = { name ->
        when (name) {
            "class" -> element.className
            "simpleClass" -> element.className.substringAfterLast('.')
            "method" -> element.methodName
            "receiver" -> receiverId?.let { id -> render(receiver, id.values()) } ?: ""
            "params" -> params.render(paramIds)
            "file" -> element.fileName ?: "Unknown Source"
            "line" -> element.lineNumber.toString()
            else -> error("unexpected placeholder $name")
        }
    }

    private sealed interface Token {
        data class Literal(val text: String) : Token
        data class Placeholder(val name: String) : Token
    }

    private class ParamsTemplate(
        val prefix: String,
        val item: List<Token>,
        val separator: String,
        val suffix: String,
    ) {
        fun render(ids: List<NamedId>): String =
            if (ids.isEmpty()) "" else ids.joinToString(separator, prefix, suffix) { render(item, it.values()) }
    }

    companion object {
        private val FRAME_PLACEHOLDERS = setOf("class", "simpleClass", "method", "receiver", "params")
        private val REGISTRY_PLACEHOLDERS = FRAME_PLACEHOLDERS + setOf("file", "line")
        private val ID_PLACEHOLDERS = setOf("name", "id")
        private const val REPEAT = "..."
        private const val DEFAULT_SEPARATOR = ", "

        fun create(config: AugmentorConfig): FrameFormat =
            create(config.mode, config.frameFormat, config.receiverFormat, config.paramsFormat)

        fun create(mode: Mode, frameFormat: String, receiverFormat: String, paramsFormat: String): FrameFormat {
            val frame = parse(frameFormat, if (mode == Mode.REGISTRY) REGISTRY_PLACEHOLDERS else FRAME_PLACEHOLDERS, "frameFormat")
            var declaringClassPart = emptyList<Token>()
            var methodPart = emptyList<Token>()
            if (mode == Mode.REWRITE) {
                // The JDK prints declaringClass + "." + methodName, so the template is split at ".{method}".
                val split = frameFormat.indexOf(".{method}")
                if (split < 0 || frameFormat.indexOf("{method}") != split + 1 || frameFormat.lastIndexOf("{method}") != split + 1) {
                    throw ConfigException(
                        "frameFormat must contain '.{method}' exactly once in rewrite mode, " +
                            "because the JDK prints '<class>.<method>(<file>:<line>)'; was '$frameFormat'",
                    )
                }
                declaringClassPart = parse(frameFormat.substring(0, split), FRAME_PLACEHOLDERS, "frameFormat")
                methodPart = parse(frameFormat.substring(split + 1), FRAME_PLACEHOLDERS, "frameFormat")
            }
            val receiver = parse(receiverFormat, ID_PLACEHOLDERS, "receiverFormat")
            return FrameFormat(mode, frame, declaringClassPart, methodPart, receiver, parseParams(paramsFormat))
        }

        private fun parseParams(template: String): ParamsTemplate {
            val repeat = template.lastIndexOf(REPEAT)
            if (repeat < 0) {
                val item = parse(template, ID_PLACEHOLDERS, "paramsFormat")
                requirePlaceholder(item, template)
                return ParamsTemplate("", item, DEFAULT_SEPARATOR, "")
            }
            val head = parse(template.substring(0, repeat), ID_PLACEHOLDERS, "paramsFormat")
            val tail = parse(template.substring(repeat + REPEAT.length), ID_PLACEHOLDERS, "paramsFormat")
            requirePlaceholder(head, template)
            if (tail.any { it is Token.Placeholder }) {
                throw ConfigException("paramsFormat must not have placeholders after '$REPEAT'; was '$template'")
            }
            val first = head.indexOfFirst { it is Token.Placeholder }
            val last = head.indexOfLast { it is Token.Placeholder }
            return ParamsTemplate(
                prefix = literalText(head.subList(0, first)),
                item = head.subList(first, last + 1),
                separator = literalText(head.subList(last + 1, head.size)),
                suffix = literalText(tail),
            )
        }

        private fun requirePlaceholder(tokens: List<Token>, template: String) {
            if (tokens.none { it is Token.Placeholder }) {
                throw ConfigException("paramsFormat must contain {name} or {id}; was '$template'")
            }
        }

        private fun literalText(tokens: List<Token>): String =
            tokens.joinToString("") { (it as Token.Literal).text }

        /** Splits a template into literals and placeholders; `{{` and `}}` are literal braces. */
        private fun parse(template: String, allowed: Set<String>, key: String): List<Token> {
            val tokens = mutableListOf<Token>()
            val literal = StringBuilder()
            fun flush() {
                if (literal.isNotEmpty()) tokens += Token.Literal(literal.toString())
                literal.clear()
            }
            var i = 0
            while (i < template.length) {
                val c = template[i]
                when {
                    c == '{' && template.startsWith("{{", i) -> literal.append('{').also { i++ }
                    c == '}' && template.startsWith("}}", i) -> literal.append('}').also { i++ }
                    c == '{' -> {
                        val end = template.indexOf('}', i)
                        if (end < 0) throw ConfigException("$key has an unclosed '{' at position $i: '$template'")
                        val name = template.substring(i + 1, end)
                        if (name !in allowed) {
                            throw ConfigException("$key uses unknown placeholder {$name}; allowed: ${allowed.joinToString { "{$it}" }}")
                        }
                        flush()
                        tokens += Token.Placeholder(name)
                        i = end
                    }
                    c == '}' -> throw ConfigException("$key has an unmatched '}' at position $i (write '}}' for a literal brace): '$template'")
                    else -> literal.append(c)
                }
                i++
            }
            flush()
            return tokens
        }

        private fun render(tokens: List<Token>, values: (String) -> String): String = buildString {
            for (token in tokens) {
                when (token) {
                    is Token.Literal -> append(token.text)
                    is Token.Placeholder -> append(values(token.name))
                }
            }
        }

        private fun NamedId.values(): (String) -> String = { if (it == "name") name else id }

        /** The class loader / module part the JDK prints before the class name, e.g. `app//` or `java.base/`. */
        private fun prefixOf(element: StackTraceElement): String {
            val text = element.toString()
            val index = text.indexOf("${element.className}.${element.methodName}(")
            return if (index > 0) text.substring(0, index) else ""
        }

        private fun location(element: StackTraceElement): String = when {
            element.isNativeMethod -> "Native Method"
            element.fileName != null && element.lineNumber >= 0 -> "${element.fileName}:${element.lineNumber}"
            element.fileName != null -> element.fileName
            else -> "Unknown Source"
        }
    }
}
