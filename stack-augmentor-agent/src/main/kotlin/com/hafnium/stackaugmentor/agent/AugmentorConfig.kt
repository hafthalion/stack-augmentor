package com.hafnium.stackaugmentor.agent

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.nio.file.Files
import java.nio.file.Path

/** Where a receiver id comes from, for classes configured externally. */
sealed interface IdSpec {
    val memberName: String

    data class FieldSpec(override val memberName: String) : IdSpec
    data class MethodSpec(override val memberName: String) : IdSpec
}

/** Selects a parameter of a configured method, by name or by position. */
sealed interface ParamRef {
    data class ByName(val name: String) : ParamRef
    data class ByIndex(val index: Int) : ParamRef
}

class ConfigException(message: String) : IllegalArgumentException(message)

/**
 * The agent configuration, read from a TOML file:
 *
 * ```toml
 * augmentAnnotatedClasses = ["com.hafnium.**"]
 * maxIdLength = 64
 * frameFormat = "{class}{receiver}.{method}{params}"
 * receiverFormat = "{$name=$id}"
 * paramsFormat = "{$name=$id, ...}"
 * debug = false
 *
 * [augmentClassIds]
 * "com.thirdparty.Order" = "getOrderNumber()"
 *
 * [augmentMethodParams]
 * "com.thirdparty.OrderService.process" = ["order", 2]
 * ```
 */
data class AugmentorConfig(
    /** Packages (globs) where `@StackTraceId` is honoured; empty means all packages. */
    val augmentAnnotatedClasses: List<String> = emptyList(),
    val maxIdLength: Int = 64,
    val frameFormat: String = DEFAULT_FRAME_FORMAT,
    val receiverFormat: String = DEFAULT_RECEIVER_FORMAT,
    val paramsFormat: String = DEFAULT_PARAMS_FORMAT,
    /** Receiver id sources by class name: the `[augmentClassIds]` table. */
    val ids: Map<String, IdSpec> = emptyMap(),
    /** Parameter ids by `className.methodName`: the `[augmentMethodParams]` table. */
    val params: Map<String, List<ParamRef>> = emptyMap(),
    val debug: Boolean = false,
) {
    private val annotatedClassPatterns: List<Regex> = augmentAnnotatedClasses.map(::globToRegex)

    /** Whether `@StackTraceId` annotations on this class are used. */
    fun honoursAnnotations(className: String): Boolean =
        annotatedClassPatterns.isEmpty() || annotatedClassPatterns.any { it.matches(className) }

    fun paramRefs(className: String, methodName: String): List<ParamRef> =
        params["$className.$methodName"].orEmpty()

    fun hasParamEntries(className: String): Boolean = params.keys.any { it.startsWith("$className.") }

    companion object {
        const val DEFAULT_FRAME_FORMAT = "{class}{receiver}.{method}{params}"
        const val DEFAULT_RECEIVER_FORMAT = "{\$name=\$id}"
        const val DEFAULT_PARAMS_FORMAT = "{\$name=\$id, ...}"
        const val CONFIG_PROPERTY = "stackaugmentor.config"

        private val IDENTIFIER = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*")

        /**
         * Loads the TOML file named by the agent arguments (`config=<path>` or just `<path>`),
         * or by the `stackaugmentor.config` system property. Without either, the defaults apply.
         */
        fun load(agentArgs: String?): AugmentorConfig {
            val location = agentArgs?.trim()?.takeIf { it.isNotEmpty() }?.removePrefix("config=")
                ?: System.getProperty(CONFIG_PROPERTY)?.takeIf { it.isNotBlank() }
                ?: return AugmentorConfig()
            val path = Path.of(location)
            if (!Files.isRegularFile(path)) throw ConfigException("Configuration file not found: $path")
            if (!path.fileName.toString().endsWith(".toml", ignoreCase = true)) {
                throw ConfigException("$path: the configuration must be a TOML file ending in .toml")
            }
            return parse(Files.readString(path), path.fileName.toString())
        }

        /** Parses a TOML configuration. [source] names it in error messages. */
        fun parse(text: String, source: String = "configuration"): AugmentorConfig {
            val toml = Toml.parse(text)
            if (toml.hasErrors()) {
                throw ConfigException("Invalid TOML in $source: ${toml.errors().joinToString("; ")}")
            }
            return ConfigReader(toml, source).read()
        }

        /** `*` matches within one package segment, `**` across segments, `?` one character. */
        internal fun globToRegex(glob: String): Regex {
            val regex = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        regex.append(".*")
                        i++
                    }
                    c == '*' -> regex.append("[^.]*")
                    c == '?' -> regex.append("[^.]")
                    else -> regex.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(regex.toString())
        }
    }

    /** Maps the parsed TOML onto [AugmentorConfig]; errors name the key and its line. */
    private class ConfigReader(private val toml: TomlParseResult, private val source: String) {

        fun read(): AugmentorConfig {
            for (key in toml.keySet()) {
                if (key !in KNOWN_KEYS) throw error(listOf(key), "unknown key '$key'; allowed: ${KNOWN_KEYS.joinToString()}")
            }
            val defaults = AugmentorConfig()
            return AugmentorConfig(
                augmentAnnotatedClasses = stringArray("augmentAnnotatedClasses") ?: defaults.augmentAnnotatedClasses,
                maxIdLength = value<Long>("maxIdLength", "an integer")?.let { maxIdLength(it) } ?: defaults.maxIdLength,
                frameFormat = value("frameFormat", "a string") ?: defaults.frameFormat,
                receiverFormat = value("receiverFormat", "a string") ?: defaults.receiverFormat,
                paramsFormat = value("paramsFormat", "a string") ?: defaults.paramsFormat,
                ids = entries(ID_TABLE).associate { (path, value) -> target(path, ID_TABLE) to idSpec(path, value) },
                params = entries(PARAM_TABLE).associate { (path, value) -> target(path, PARAM_TABLE) to paramRefs(path, value) },
                debug = value("debug", "true or false") ?: defaults.debug,
            )
        }

        private inline fun <reified T> value(key: String, expected: String): T? {
            val value = toml.get(listOf(key)) ?: return null
            return value as? T ?: throw error(listOf(key), "'$key' must be $expected")
        }

        private fun stringArray(key: String): List<String>? {
            val array = value<TomlArray>(key, "an array of strings, e.g. [\"com.acme.**\"]") ?: return null
            return array.toList().map { it as? String ?: throw error(listOf(key), "'$key' must be an array of strings") }
        }

        /**
         * The entries of the `[augmentClassIds]` or `[augmentMethodParams]` table, with their full key paths. Key paths make quoted
         * (`"com.acme.Order"`) and unquoted (`com.acme.Order`, i.e. nested tables) class names equivalent.
         */
        private fun entries(table: String): List<Pair<List<String>, Any>> {
            val content = value<TomlTable>(table, "a table, e.g. [$table]") ?: return emptyList()
            return content.keyPathSet().map { path -> (listOf(table) + path) to content.get(path)!! }
        }

        private fun target(path: List<String>, table: String): String {
            val target = path.drop(1).joinToString(".")
            val valid = if (table == ID_TABLE) target.isNotEmpty() else target.lastIndexOf('.') > 0
            if (!valid) {
                val example = if (table == ID_TABLE) "\"com.acme.Order\"" else "\"com.acme.OrderService.process\""
                throw error(path, "[$table] keys must name a ${if (table == ID_TABLE) "class" else "class and a method"}, e.g. $example")
            }
            return target
        }

        private fun idSpec(path: List<String>, value: Any): IdSpec {
            val text = value as? String
            if (text == null || !IDENTIFIER.matches(text.removeSuffix("()"))) {
                throw error(path, "must be a field name (e.g. \"orderId\") or a method (e.g. \"getOrderId()\"), was $value")
            }
            val name = text.removeSuffix("()")
            return if (text.endsWith("()")) IdSpec.MethodSpec(name) else IdSpec.FieldSpec(name)
        }

        private fun paramRefs(path: List<String>, value: Any): List<ParamRef> {
            val array = value as? TomlArray
                ?: throw error(path, "must be an array of parameter names and indexes, e.g. [\"order\", 2]")
            if (array.size() == 0) throw error(path, "must list at least one parameter")
            return array.toList().map { ref ->
                when {
                    ref is String && IDENTIFIER.matches(ref) -> ParamRef.ByName(ref)
                    ref is Long && ref in 0..255 -> ParamRef.ByIndex(ref.toInt())
                    else -> throw error(path, "invalid parameter '$ref': use a parameter name or a 0-based index")
                }
            }
        }

        private fun maxIdLength(value: Long): Int {
            if (value !in 2..10_000) throw error(listOf("maxIdLength"), "maxIdLength must be between 2 and 10000, was $value")
            return value.toInt()
        }

        private fun error(path: List<String>, message: String): ConfigException {
            val position = toml.inputPositionOf(path)?.let { ", line ${it.line()}" } ?: ""
            return ConfigException("$source$position: $message")
        }

        companion object {
            const val ID_TABLE = "augmentClassIds"
            const val PARAM_TABLE = "augmentMethodParams"

            private val KNOWN_KEYS = listOf(
                "augmentAnnotatedClasses", "maxIdLength", "frameFormat", "receiverFormat", "paramsFormat", "debug", ID_TABLE, PARAM_TABLE,
            )
        }
    }
}
