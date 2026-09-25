package com.hafnium.stackaugmentor.runtime

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
 * The configuration, read from a TOML file:
 *
 * ```toml
 * debug = false
 *
 * [instrument]                  # what gets instrumented: agent at class load, build plugin at build time
 * annotatedClasses = ["com.hafnium.**"]
 *
 * [instrument.classIds]
 * "com.thirdparty.Order" = "getOrderNumber()"
 *
 * [instrument.methodParams]
 * "com.thirdparty.OrderService.process" = ["order", 2]
 *
 * [augment]                      # how frames look: at runtime, in both modes
 * frameFormat = "{class}{receiver}.{method}{params}"
 * receiverFormat = "{$name=$id}"
 * paramsFormat = "{$name=$id, ...}"
 * maxIdLength = 64
 * ```
 */
data class AugmentorConfig(
    /** Packages (globs) where `@StackTraceId` is honoured; empty means all packages. */
    val annotatedClasses: List<String> = emptyList(),
    /** Receiver id sources by class name: the `[instrument.classIds]` table. */
    val ids: Map<String, IdSpec> = emptyMap(),
    /** Parameter ids by `className.methodName`: the `[instrument.methodParams]` table. */
    val params: Map<String, List<ParamRef>> = emptyMap(),
    val frameFormat: String = DEFAULT_FRAME_FORMAT,
    val receiverFormat: String = DEFAULT_RECEIVER_FORMAT,
    val paramsFormat: String = DEFAULT_PARAMS_FORMAT,
    val maxIdLength: Int = 64,
    val debug: Boolean = false,
) {
    private val annotatedClassPatterns: List<Regex> = annotatedClasses.map(::globToRegex)

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
        fun load(agentArgs: String?): AugmentorConfig = location(agentArgs)?.let { load(Path.of(it)) } ?: AugmentorConfig()

        /** Loads a TOML configuration file. */
        fun load(path: Path): AugmentorConfig {
            if (!Files.isRegularFile(path)) throw ConfigException("Configuration file not found: $path")
            if (!path.fileName.toString().endsWith(".toml", ignoreCase = true)) {
                throw ConfigException("$path: the configuration must be a TOML file ending in .toml")
            }
            return parse(Files.readString(path), path.fileName.toString())
        }

        /** The configuration file named by the agent arguments or the system property, if any. */
        fun location(agentArgs: String?): String? =
            agentArgs?.trim()?.takeIf { it.isNotEmpty() }?.removePrefix("config=")
                ?: System.getProperty(CONFIG_PROPERTY)?.takeIf { it.isNotBlank() }

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
            checkKeys(emptyList(), toml, ROOT_KEYS)
            table(INSTRUMENT)?.let { checkKeys(INSTRUMENT, it, INSTRUMENT_KEYS) }
            table(AUGMENT)?.let { checkKeys(AUGMENT, it, AUGMENT_KEYS) }
            val defaults = AugmentorConfig()
            return AugmentorConfig(
                annotatedClasses = stringArray(INSTRUMENT + "annotatedClasses") ?: defaults.annotatedClasses,
                ids = entries(CLASS_IDS).associate { (path, value) -> target(path, CLASS_IDS) to idSpec(path, value) },
                params = entries(METHOD_PARAMS).associate { (path, value) -> target(path, METHOD_PARAMS) to paramRefs(path, value) },
                frameFormat = value(AUGMENT + "frameFormat", "a string") ?: defaults.frameFormat,
                receiverFormat = value(AUGMENT + "receiverFormat", "a string") ?: defaults.receiverFormat,
                paramsFormat = value(AUGMENT + "paramsFormat", "a string") ?: defaults.paramsFormat,
                maxIdLength = value<Long>(AUGMENT + "maxIdLength", "an integer")?.let { maxIdLength(it) } ?: defaults.maxIdLength,
                debug = value(listOf("debug"), "true or false") ?: defaults.debug,
            )
        }

        private fun checkKeys(path: List<String>, table: TomlTable, allowed: List<String>) {
            for (key in table.keySet()) {
                if (key !in allowed) {
                    val where = if (path.isEmpty()) "" else " in [${name(path)}]"
                    throw error(path + key, "unknown key '$key'$where; allowed: ${allowed.joinToString()}")
                }
            }
        }

        private fun table(path: List<String>): TomlTable? = value(path, "a table, e.g. [${name(path)}]")

        private inline fun <reified T> value(path: List<String>, expected: String): T? {
            val value = toml.get(path) ?: return null
            return value as? T ?: throw error(path, "'${name(path)}' must be $expected")
        }

        private fun stringArray(path: List<String>): List<String>? {
            val array = value<TomlArray>(path, "an array of strings, e.g. [\"com.acme.**\"]") ?: return null
            return array.toList().map { it as? String ?: throw error(path, "'${name(path)}' must be an array of strings") }
        }

        /**
         * The entries of the `[instrument.classIds]` or `[instrument.methodParams]` table, with their full key paths.
         * Key paths make quoted (`"com.acme.Order"`) and unquoted (`com.acme.Order`, i.e. nested tables) class names equivalent.
         */
        private fun entries(table: List<String>): List<Pair<List<String>, Any>> {
            val content = table(table) ?: return emptyList()
            return content.keyPathSet().map { path -> (table + path) to content.get(path)!! }
        }

        private fun target(path: List<String>, table: List<String>): String {
            val target = path.drop(table.size).joinToString(".")
            val classIds = table == CLASS_IDS
            val valid = if (classIds) target.isNotEmpty() else target.lastIndexOf('.') > 0
            if (!valid) {
                val example = if (classIds) "\"com.acme.Order\"" else "\"com.acme.OrderService.process\""
                throw error(path, "[${name(table)}] keys must name a ${if (classIds) "class" else "class and a method"}, e.g. $example")
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
            if (value !in 2..10_000) throw error(AUGMENT + "maxIdLength", "maxIdLength must be between 2 and 10000, was $value")
            return value.toInt()
        }

        private fun name(path: List<String>) = path.joinToString(".")

        private fun error(path: List<String>, message: String): ConfigException {
            val position = toml.inputPositionOf(path)?.let { ", line ${it.line()}" } ?: ""
            return ConfigException("$source$position: $message")
        }

        companion object {
            val INSTRUMENT = listOf("instrument")
            val AUGMENT = listOf("augment")
            val CLASS_IDS = INSTRUMENT + "classIds"
            val METHOD_PARAMS = INSTRUMENT + "methodParams"

            private val ROOT_KEYS = listOf("debug", "instrument", "augment")
            private val INSTRUMENT_KEYS = listOf("annotatedClasses", "classIds", "methodParams")
            private val AUGMENT_KEYS = listOf("frameFormat", "receiverFormat", "paramsFormat", "maxIdLength")
        }
    }
}
